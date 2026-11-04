from __future__ import annotations

import jwt
import pytest
from sqlalchemy import create_engine, inspect

from app.migrate import upgrade_to_head
from app.ratelimit import RateLimiter
from app.security import create_access_token, decode_access_token, hash_password, verify_password
from app.shazamkit import DeveloperTokenSigner, SigningNotConfigured


class FakeClock:
    def __init__(self, now: float = 1_000.0):
        self.now = now

    def __call__(self) -> float:
        return self.now


def test_rate_limiter_sliding_window():
    clock = FakeClock()
    limiter = RateLimiter(clock=clock)
    assert limiter.hit("k", 2, 60) is None
    assert limiter.hit("k", 2, 60) is None
    wait = limiter.hit("k", 2, 60)
    assert wait == pytest.approx(60)
    clock.now += 61
    assert limiter.hit("k", 2, 60) is None


def test_signer_refreshes_after_half_life(apple_key):
    path, key = apple_key
    clock = FakeClock(10_000)
    signer = DeveloperTokenSigner("TEAMID1234", "KEYID56789", path.read_text(), ttl_seconds=3600, clock=clock)
    first = signer.current()
    clock.now += 1_000
    assert signer.current() == first
    clock.now += 1_000  # now 2000 s old: less than half the lifetime remains
    second = signer.current()
    assert second.token != first.token
    claims = jwt.decode(second.token, key.public_key(), algorithms=["ES256"], options={"verify_exp": False})
    assert claims == {"iss": "TEAMID1234", "iat": 12_000, "exp": 15_600}


def test_signer_rejects_non_p256_keys():
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import ec

    p384 = ec.generate_private_key(ec.SECP384R1()).private_bytes(
        serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()
    ).decode()
    with pytest.raises(SigningNotConfigured):
        DeveloperTokenSigner("TEAM", "KEY", p384, ttl_seconds=3600)
    with pytest.raises(SigningNotConfigured):
        DeveloperTokenSigner("", "KEY", p384, ttl_seconds=3600)


def test_password_and_access_token_helpers():
    hashed = hash_password("hunter2hunter2")
    assert verify_password(hashed, "hunter2hunter2")
    assert not verify_password(hashed, "wrong")
    assert not verify_password(None, "anything")
    token = create_access_token("user-1", "s" * 48, ttl_seconds=60, now=0)
    assert decode_access_token(token, "s" * 48) is None  # long expired
    fresh = create_access_token("user-1", "s" * 48, ttl_seconds=60)
    assert decode_access_token(fresh, "s" * 48) == "user-1"


def test_migrations_create_schema(tmp_path):
    url = f"sqlite:///{tmp_path / 'migrated.db'}"
    upgrade_to_head(url)
    tables = set(inspect(create_engine(url)).get_table_names())
    assert {"users", "refresh_tokens", "sync_recognitions", "sync_favourites", "sync_settings", "provider_usage", "alembic_version"} <= tables
