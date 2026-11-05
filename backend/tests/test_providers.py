"""AudD and ACRCloud adapters against mocked HTTP (never live keys)."""

from __future__ import annotations

import asyncio
import base64
import hashlib
import hmac
import io
import json

import httpx
import pytest
from sqlalchemy import select

from app.config import Settings
from app.models import ProviderUsage
from app.recognition.base import ClipInfo, ProviderError
from app.recognition.providers import acrcloud, audd
from app.recognition.providers.acrcloud import ACRCloudProvider
from app.recognition.providers.audd import AudDProvider
from app.recognition.registry import build_providers
from tests.conftest import auth, register
from tests.test_enhanced import wav

CLIP = ClipInfo(wav_bytes=wav(seconds=3), sample_rate=16_000, duration_seconds=3.0)


def run(coro):
    return asyncio.run(coro)


def multipart_fields(request: httpx.Request) -> dict[str, str]:
    """Tiny multipart parser for assertions: returns form fields and file field names."""
    boundary = request.headers["content-type"].split("boundary=")[1].encode()
    fields: dict[str, str] = {}
    for part in request.content.split(b"--" + boundary):
        if b"Content-Disposition" not in part:
            continue
        header, _, body = part.partition(b"\r\n\r\n")
        name = header.split(b'name="')[1].split(b'"')[0].decode()
        if b"filename=" in header:
            fields[name] = "<file>"
        else:
            fields[name] = body.rstrip(b"\r\n").decode()
    return fields


# --------------------------------------------------------------------------------- AudD

AUDD_MATCH = {
    "status": "success",
    "result": {
        "artist": "Imagine Dragons",
        "title": "Warriors",
        "album": "Warriors",
        "release_date": "2014-09-18",
        "label": "Universal Music",
        "timecode": "00:40",
        "song_link": "https://lis.tn/Warriors",
        "apple_music": {"url": "https://music.apple.com/us/album/warriors/1440831203?i=1440831624", "isrc": "USUM71414145",
                        "artwork": {"url": "https://is1-ssl.mzstatic.com/image/thumb/x/{w}x{h}bb.jpg"}},
        "spotify": {"id": "1lgN0A2Vki2FTON5PYq42m", "name": "Warriors"},
    },
}


def audd_with(response_json=None, status_code=200, capture=None, raw=None):
    def handler(request: httpx.Request) -> httpx.Response:
        if capture is not None:
            capture.append(request)
        if raw is not None:
            return httpx.Response(status_code, content=raw)
        return httpx.Response(status_code, json=response_json)

    return AudDProvider("secret-token", daily_limit=10, transport=httpx.MockTransport(handler))


def test_audd_request_format_and_match_parsing():
    seen: list[httpx.Request] = []
    match = run(audd_with(AUDD_MATCH, capture=seen).identify(CLIP))
    request = seen[0]
    assert str(request.url) == "https://api.audd.io/"
    assert request.method == "POST"
    fields = multipart_fields(request)
    assert fields["api_token"] == "secret-token"
    assert fields["return"] == "apple_music,spotify"
    assert fields["file"] == "<file>"
    assert match.title == "Warriors" and match.artist == "Imagine Dragons"
    assert match.album == "Warriors" and match.release_date == "2014-09-18"
    assert match.isrc == "USUM71414145"
    assert match.provider_track_id == "isrc:USUM71414145"
    assert match.artwork_url == "https://is1-ssl.mzstatic.com/image/thumb/x/600x600bb.jpg"
    assert match.external_urls["spotify"] == "https://open.spotify.com/track/1lgN0A2Vki2FTON5PYq42m"
    assert match.external_urls["apple_music"].startswith("https://music.apple.com/")
    assert match.score is None


def test_audd_lis_tn_thumbnail_fallback_and_no_match():
    result = {"status": "success", "result": {"artist": "A", "title": "T", "song_link": "https://lis.tn/abc"}}
    match = run(audd_with(result).identify(CLIP))
    assert match.artwork_url == "https://lis.tn/abc?thumb"
    assert run(audd_with({"status": "success", "result": None}).identify(CLIP)) is None


@pytest.mark.parametrize(
    "code, retryable, rate_limited",
    [(900, False, False), (902, False, True), (611, True, True), (300, False, False), (999, True, False)],
)
def test_audd_error_codes(code, retryable, rate_limited):
    body = {"status": "error", "error": {"error_code": code, "error_message": "x"}}
    with pytest.raises(ProviderError) as info:
        run(audd_with(body).identify(CLIP))
    assert info.value.retryable is retryable
    assert info.value.rate_limited is rate_limited
    assert "secret-token" not in str(info.value)


def test_audd_deprecated_params_with_result_is_success():
    body = {"status": "error", "error": {"error_code": 51, "error_message": "deprecated"}, "result": AUDD_MATCH["result"]}
    assert run(audd_with(body).identify(CLIP)).title == "Warriors"


def test_audd_transport_failures():
    with pytest.raises(ProviderError) as info:
        run(audd_with(raw=b"<html>bad gateway</html>", status_code=502).identify(CLIP))
    assert info.value.retryable is True

    def boom(request):
        raise httpx.ConnectError("down")

    provider = AudDProvider("t", daily_limit=1, transport=httpx.MockTransport(boom))
    with pytest.raises(ProviderError) as info:
        run(provider.identify(CLIP))
    assert info.value.retryable is True


# ------------------------------------------------------------------------------ ACRCloud

# Trimmed from the sample response in github.com/acrcloud/acrcloud_scan_files_python.
ACR_MATCH = json.loads(
    '{"status": {"msg": "Success", "code": 0, "version": "1.0"}, "cost_time": 1.26, "result_type": 0,'
    ' "metadata": {"timestamp_utc": "2018-08-02 14:44:39", "music": [{"album": {"name": "Solino"},'
    ' "play_offset_ms": 85200, "title": "La Bambola", "release_date": "2002-10-28", "genres": [{"name": "Pop"}],'
    ' "label": "Amiga", "score": 82, "artists": [{"name": "Patty Pravo"}], "duration_ms": 182200,'
    ' "external_ids": {"isrc": "ITB006870616", "upc": "743219711328"}, "acrid": "27fef80da4dabc33591a2c08a08edaf0",'
    ' "external_metadata": {"spotify": {"track": {"name": "La Bambola", "id": "5YT3WdXo5gBwZ0TlJiB0TE"}},'
    ' "youtube": {"vid": "UHCgZY-HX6U"}}}]}}'
)


def acr_with(response_json, capture=None):
    def handler(request):
        if capture is not None:
            capture.append(request)
        return httpx.Response(200, json=response_json)

    return ACRCloudProvider(
        "identify-eu-west-1.acrcloud.com", "my-key", "my-secret", daily_limit=10,
        transport=httpx.MockTransport(handler), clock=lambda: 1_700_000_000.9,
    )


def test_acrcloud_signature_matches_official_formula():
    expected = base64.b64encode(
        hmac.new(b"my-secret", b"POST\n/v1/identify\nmy-key\naudio\n1\n1700000000", hashlib.sha1).digest()
    ).decode()
    assert acrcloud.sign("my-key", "my-secret", "1700000000") == expected


def test_acrcloud_request_format_and_parsing():
    seen: list[httpx.Request] = []
    match = run(acr_with(ACR_MATCH, capture=seen).identify(CLIP))
    request = seen[0]
    assert str(request.url) == "https://identify-eu-west-1.acrcloud.com/v1/identify"
    fields = multipart_fields(request)
    assert fields["access_key"] == "my-key"
    assert fields["data_type"] == "audio"
    assert fields["signature_version"] == "1"
    assert fields["timestamp"] == "1700000000"
    assert fields["sample_bytes"] == str(len(CLIP.wav_bytes))
    assert fields["signature"] == acrcloud.sign("my-key", "my-secret", "1700000000")
    assert fields["sample"] == "<file>"
    assert "my-secret" not in request.content.decode("latin-1")
    assert (match.title, match.artist, match.album) == ("La Bambola", "Patty Pravo", "Solino")
    assert match.isrc == "ITB006870616"
    assert match.score == pytest.approx(0.82)
    assert match.external_urls == {
        "spotify": "https://open.spotify.com/track/5YT3WdXo5gBwZ0TlJiB0TE",
        "video": "https://www.youtube.com/watch?v=UHCgZY-HX6U",
    }


def test_acrcloud_no_result_errors_and_isrc_list():
    assert run(acr_with({"status": {"msg": "No result", "code": 1001}}).identify(CLIP)) is None
    with pytest.raises(ProviderError) as info:
        run(acr_with({"status": {"msg": "invalid access key", "code": 3001}}).identify(CLIP))
    assert info.value.retryable is False
    variant = json.loads(json.dumps(ACR_MATCH))
    variant["metadata"]["music"][0]["external_ids"]["isrc"] = ["ITB006870616", "OTHER"]
    assert run(acr_with(variant).identify(CLIP)).isrc == "ITB006870616"


def test_acrcloud_rejects_bad_host():
    with pytest.raises(ValueError):
        ACRCloudProvider("evil.com/path?x", "k", "s", daily_limit=1)


# ------------------------------------------------------------------------------ registry

def test_registry_requires_flag_credentials_and_limit():
    assert build_providers(Settings(_env_file=None)) == []
    assert build_providers(Settings(_env_file=None, audd_enabled=True, audd_api_token="t")) == []  # no daily limit
    providers = build_providers(
        Settings(
            _env_file=None,
            audd_enabled=True, audd_api_token="t", audd_daily_limit=5,
            acrcloud_enabled=True, acrcloud_host="identify-eu-west-1.acrcloud.com",
            acrcloud_access_key="k", acrcloud_access_secret="s", acrcloud_daily_limit=5,
            provider_order="acrcloud,audd",
        )
    )
    assert [p.id for p in providers] == ["acrcloud", "audd"]


# ---------------------------------------------------------------- /recognition/identify route

def upload(client, headers=None):
    return client.post(
        "/api/v1/recognition/identify",
        headers=headers or {},
        files={"audio": ("clip.wav", io.BytesIO(wav()), "audio/wav")},
    )


INSTALL = {"X-NanoTune-Install": "install-1111-2222"}


def test_server_engines_without_shazamkit_and_without_account(make_client):
    provider = audd_with(AUDD_MATCH)
    client = make_client(with_apple_key=False, providers=[provider], server_recognition_anonymous=True)
    config = client.get("/api/v1/config").json()
    assert config["shazamkit"]["available"] is False
    assert config["enhanced"]["available"] is False  # only one engine: never called "enhanced"
    assert config["server_engines"] == {
        "available": True, "anonymous": True, "requires_account": False, "max_upload_seconds": 12,
        "providers": [{"id": "audd", "name": "AudD", "paid": True, "uploads_audio": True, "disclosure": AudDProvider.disclosure}],
        "message": None,
    }
    body = upload(client, INSTALL).json()
    assert body["outcome"] == "match"
    assert body["best"]["title"] == "Warriors"
    assert body["best"]["providers"] == ["audd"]


def test_anonymous_use_needs_owner_opt_in_and_install_id(make_client):
    client = make_client(providers=[audd_with(AUDD_MATCH)])
    assert client.get("/api/v1/config").json()["server_engines"]["requires_account"] is True
    assert upload(client, INSTALL).status_code == 401
    tokens = register(client)
    assert upload(client, auth(tokens)).json()["outcome"] == "match"

    open_client = make_client(providers=[audd_with(AUDD_MATCH)], server_recognition_anonymous=True)
    assert upload(open_client).status_code == 400


def test_anonymous_daily_caps_per_install_and_ip(make_client):
    client = make_client(
        providers=[audd_with(AUDD_MATCH)],
        server_recognition_anonymous=True,
        server_recognition_per_install_daily_limit=2,
        server_recognition_per_ip_daily_limit=3,
    )
    assert upload(client, INSTALL).json()["outcome"] == "match"
    assert upload(client, INSTALL).json()["outcome"] == "match"
    capped = upload(client, INSTALL).json()
    assert capped["outcome"] == "unavailable" and "limit" in capped["message"]
    # A new install from the same IP gets one more before the per-IP cap.
    other = {"X-NanoTune-Install": "install-3333-4444"}
    assert upload(client, other).json()["outcome"] == "match"
    assert upload(client, other).json()["outcome"] == "unavailable"


def test_identify_unavailable_without_engines(client):
    response = upload(client, INSTALL)
    assert response.status_code == 503
    assert response.json()["error"]["code"] == "server_engines_unavailable"


# ------------------------------------------------------------------ privacy of usage counters

def usage_rows(client) -> list[tuple[str, str, int]]:
    with client.app.state.database.session_factory() as db:
        return [(row.scope, row.day, row.count) for row in db.scalars(select(ProviderUsage))]


def test_anonymous_counters_store_no_raw_install_id_or_ip(make_client):
    client = make_client(providers=[audd_with(AUDD_MATCH)], server_recognition_anonymous=True)
    assert upload(client, INSTALL).json()["outcome"] == "match"
    scopes = [scope for scope, _day, _count in usage_rows(client)]
    assert "provider:audd" in scopes
    assert len([s for s in scopes if s.startswith(("install:", "ip:"))]) == 2
    for scope in scopes:
        assert "install-1111-2222" not in scope and "testclient" not in scope
    # The same install maps to the same (hashed) counter, so the cap still works.
    assert upload(client, INSTALL).json()["outcome"] == "match"
    assert len([s for s, _d, _c in usage_rows(client) if s.startswith("install:")]) == 1


def test_old_anonymous_counters_are_purged_but_provider_totals_kept(make_client):
    client = make_client(providers=[audd_with(AUDD_MATCH)], server_recognition_anonymous=True)
    with client.app.state.database.session_factory() as db:
        db.add_all([
            ProviderUsage(scope="ip:0123456789abcdef", day="2000-01-01", count=3),
            ProviderUsage(scope="install:0123456789abcdef", day="2000-01-01", count=2),
            ProviderUsage(scope="provider:audd", day="2000-01-01", count=7),
        ])
        db.commit()
    assert upload(client, INSTALL).json()["outcome"] == "match"
    old = [row for row in usage_rows(client) if row[1] == "2000-01-01"]
    assert old == [("provider:audd", "2000-01-01", 7)]


def test_account_deletion_removes_the_users_usage_counters(make_client):
    client = make_client(providers=[audd_with(AUDD_MATCH)])
    tokens = register(client)
    assert upload(client, auth(tokens)).json()["outcome"] == "match"
    assert any(scope.startswith("user:") for scope, _d, _c in usage_rows(client))
    deleted = client.request("DELETE", "/api/v1/me", headers=auth(tokens), json={"password": "correct horse battery"})
    assert deleted.status_code == 204
    assert not any(scope.startswith("user:") for scope, _d, _c in usage_rows(client))
