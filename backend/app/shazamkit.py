"""Signs ShazamKit developer tokens with the Apple Media Services private key.

Token format (Apple, "Generating Developer Tokens"): a JWT signed with ES256 whose header has
``alg`` and the 10-character key identifier ``kid``, and whose claims are ``iss`` (10-character
Team ID), ``iat`` and ``exp``. ``exp`` must not be more than 15,777,000 seconds (6 months) after
``iat``. The private key never leaves the server.
"""

from __future__ import annotations

import threading
import time
from collections.abc import Callable
from dataclasses import dataclass

import jwt
from cryptography.hazmat.primitives.asymmetric.ec import EllipticCurvePrivateKey
from cryptography.hazmat.primitives.serialization import load_pem_private_key

from .config import APPLE_MAX_TOKEN_TTL_SECONDS


class SigningNotConfigured(RuntimeError):
    pass


@dataclass(frozen=True)
class DeveloperToken:
    token: str
    issued_at: int
    expires_at: int


class DeveloperTokenSigner:
    """Caches one token and re-signs it once less than half of its lifetime remains."""

    def __init__(self, team_id: str, key_id: str, private_key_pem: str, ttl_seconds: int, clock: Callable[[], float] = time.time):
        if not team_id or not key_id or not private_key_pem:
            raise SigningNotConfigured("Apple team id, key id and private key are required")
        if not 60 <= ttl_seconds <= APPLE_MAX_TOKEN_TTL_SECONDS:
            raise ValueError("token TTL outside Apple's limit")
        key = load_pem_private_key(private_key_pem.encode("utf-8"), password=None)
        if not isinstance(key, EllipticCurvePrivateKey) or key.curve.name != "secp256r1":
            raise SigningNotConfigured("Apple Media Services keys must be P-256 (ES256) private keys")
        self._key = key
        self._team_id = team_id
        self._key_id = key_id
        self._ttl = ttl_seconds
        self._clock = clock
        self._cached: DeveloperToken | None = None
        self._lock = threading.Lock()

    def current(self) -> DeveloperToken:
        now = int(self._clock())
        with self._lock:
            cached = self._cached
            if cached is None or cached.expires_at - now < self._ttl // 2:
                cached = self._sign(now)
                self._cached = cached
            return cached

    def _sign(self, now: int) -> DeveloperToken:
        expires = now + self._ttl
        token = jwt.encode(
            {"iss": self._team_id, "iat": now, "exp": expires},
            self._key,
            algorithm="ES256",
            headers={"kid": self._key_id},
        )
        return DeveloperToken(token=token, issued_at=now, expires_at=expires)
