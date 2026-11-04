"""Enhanced recognition routing, using fake in-process providers only (never live APIs)."""

from __future__ import annotations

import asyncio
import io
import struct
from dataclasses import dataclass, field

import pytest

from app.recognition.audio import InvalidClip, parse_wav
from app.recognition.base import ClipInfo, ProviderError, ProviderMatch
from app.recognition.reconcile import normalize, reconcile
from tests.conftest import auth, register


def wav(seconds: float = 4.0, rate: int = 16_000, channels: int = 1, bits: int = 16) -> bytes:
    frames = int(seconds * rate)
    data = b"\x00\x00" * frames * channels
    byte_rate = rate * channels * bits // 8
    header = b"RIFF" + struct.pack("<I", 36 + len(data)) + b"WAVE"
    header += b"fmt " + struct.pack("<IHHIIHH", 16, 1, channels, rate, byte_rate, channels * bits // 8, bits)
    header += b"data" + struct.pack("<I", len(data))
    return header + data


@dataclass
class FakeProvider:
    id: str
    name: str = "Fake"
    paid: bool = False
    disclosure: str = "Test provider"
    daily_limit: int = 100
    result: ProviderMatch | None = None
    error: Exception | None = None
    delay: float = 0.0
    calls: list[ClipInfo] = field(default_factory=list)

    async def identify(self, clip: ClipInfo) -> ProviderMatch | None:
        self.calls.append(clip)
        if self.delay:
            await asyncio.sleep(self.delay)
        if self.error:
            raise self.error
        return self.result


def match(provider: str, title: str = "Song", artist: str = "Artist", **extra) -> ProviderMatch:
    return ProviderMatch(provider=provider, title=title, artist=artist, **extra)


def upload(client, tokens, data: bytes | None = None):
    return client.post(
        "/api/v1/recognition/enhanced",
        headers=auth(tokens),
        files={"audio": ("clip.wav", io.BytesIO(data if data is not None else wav()), "audio/wav")},
    )


# ------------------------------------------------------------------ availability rules

def test_not_available_with_only_shazamkit(client):
    tokens = register(client)
    response = upload(client, tokens)
    assert response.status_code == 503
    assert response.json()["error"]["code"] == "enhanced_unavailable"


def test_one_server_provider_plus_shazamkit_is_enough(make_client):
    provider = FakeProvider("fake", result=match("fake"))
    client = make_client(providers=[provider])
    config = client.get("/api/v1/config").json()["enhanced"]
    assert config["available"] is True
    assert config["providers"][0]["id"] == "fake"


def test_one_server_provider_without_shazamkit_is_not_enhanced(make_client):
    client = make_client(with_apple_key=False, providers=[FakeProvider("fake")])
    assert client.get("/api/v1/config").json()["enhanced"]["available"] is False


def test_kill_switch_disables_everything(make_client):
    provider = FakeProvider("fake", result=match("fake"))
    client = make_client(providers=[provider], recognition_kill_switch=True)
    config = client.get("/api/v1/config").json()["enhanced"]
    assert config["available"] is False
    assert "switched off" in config["message"]
    tokens = register(client)
    assert upload(client, tokens).status_code == 503
    assert provider.calls == []


def test_requires_sign_in(make_client):
    client = make_client(providers=[FakeProvider("fake")])
    response = client.post("/api/v1/recognition/enhanced", files={"audio": ("clip.wav", io.BytesIO(wav()), "audio/wav")})
    assert response.status_code == 401


# ------------------------------------------------------------------ routing behaviour

def test_fallback_stops_at_first_match(make_client):
    first = FakeProvider("first", result=match("first", isrc="GBAYE0601498"))
    second = FakeProvider("second", result=match("second"))
    client = make_client(providers=[first, second])
    tokens = register(client)
    body = upload(client, tokens).json()
    assert body["outcome"] == "match"
    assert body["best"]["providers"] == ["first"]
    assert body["agreement"] == "single"
    assert body["providers_queried"] == ["first"]
    assert len(second.calls) == 0


def test_consensus_reports_agreement_and_conflict(make_client):
    agreeing = [FakeProvider("a", result=match("a", album="Album A")), FakeProvider("b", result=match("b", title="SONG (Remastered 2011)", artwork_url="https://img"))]
    client = make_client(providers=agreeing, enhanced_mode="consensus")
    tokens = register(client)
    body = upload(client, tokens).json()
    assert body["agreement"] == "agree"
    assert body["best"]["providers"] == ["a", "b"]
    assert body["best"]["album"] == "Album A"
    assert body["best"]["artwork_url"] == "https://img"


def test_conflicting_providers_are_flagged(make_client):
    providers = [FakeProvider("a", result=match("a", title="One")), FakeProvider("b", result=match("b", title="Two"))]
    client = make_client(providers=providers, enhanced_mode="consensus")
    tokens = register(client)
    body = upload(client, tokens).json()
    assert body["outcome"] == "match"
    assert body["agreement"] == "conflict"
    assert {m["title"] for m in body["matches"]} == {"One", "Two"}


def test_errors_timeouts_and_no_match(make_client):
    providers = [
        FakeProvider("broken", error=ProviderError("down")),
        FakeProvider("slow", delay=2.0, result=match("slow")),
        FakeProvider("empty", result=None),
    ]
    client = make_client(providers=providers, enhanced_provider_timeout_seconds=0.2)
    tokens = register(client)
    body = upload(client, tokens).json()
    assert body["outcome"] == "no_match"
    assert body["providers_queried"] == ["broken", "slow", "empty"]


def test_all_providers_failing_is_an_error(make_client):
    providers = [FakeProvider("a", error=ProviderError("down")), FakeProvider("b", error=RuntimeError("bug"))]
    client = make_client(providers=providers)
    tokens = register(client)
    body = upload(client, tokens).json()
    assert body["outcome"] == "error"


def test_provider_daily_limit_and_user_limit(make_client):
    limited = FakeProvider("limited", daily_limit=1, result=None)
    client = make_client(providers=[limited], enhanced_per_user_daily_limit=2)
    tokens = register(client)
    assert upload(client, tokens).json()["outcome"] == "no_match"
    # Provider exhausted: nothing is called, users are told it's unavailable.
    second = upload(client, tokens).json()
    assert second["outcome"] == "unavailable"
    assert len(limited.calls) == 1
    # Per-user cap.
    third = upload(client, tokens).json()
    assert third["outcome"] == "unavailable"
    assert "Daily" in third["message"]


def test_rejects_bad_audio(make_client):
    client = make_client(providers=[FakeProvider("fake")])
    tokens = register(client)
    assert upload(client, tokens, b"not a wav").status_code == 422
    assert upload(client, tokens, wav(channels=2)).status_code == 422
    assert upload(client, tokens, wav(seconds=30)).status_code in (413, 422)


# ------------------------------------------------------------------ pure helpers

def test_parse_wav_validation():
    clip = parse_wav(wav(seconds=3), max_seconds=12)
    assert clip.sample_rate == 16_000
    assert clip.duration_seconds == pytest.approx(3.0)
    with pytest.raises(InvalidClip):
        parse_wav(wav(seconds=0.5), max_seconds=12)
    with pytest.raises(InvalidClip):
        parse_wav(wav(bits=8), max_seconds=12)


def test_normalize_and_reconcile():
    assert normalize("Beyoncé – Halo (Remastered 2009)") == "beyonce halo"
    assert normalize("Song feat. Someone") == "song"
    assert reconcile([], ["a"]) is None
    result = reconcile([match("b", isrc="X1"), match("a", title="Different", isrc="X1")], ["a", "b"])
    assert result.agreement == "agree"
    assert result.best.provider == "a"  # priority order decides the primary copy
