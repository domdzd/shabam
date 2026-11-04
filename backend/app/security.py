"""Password hashing and account tokens. Uses well-maintained libraries only (no custom crypto)."""

from __future__ import annotations

import hashlib
import secrets
import time
import uuid

import jwt
from argon2 import PasswordHasher
from argon2.exceptions import InvalidHashError, VerificationError, VerifyMismatchError

# argon2-cffi's defaults are Argon2id with RFC 9106 "low memory" parameters.
_hasher = PasswordHasher()
# Used to keep login timing similar when the email does not exist.
_DUMMY_HASH = _hasher.hash("nanotune-dummy-password-for-timing")

ACCESS_TOKEN_AUDIENCE = "nanotune-app"


def hash_password(password: str) -> str:
    return _hasher.hash(password)


def verify_password(password_hash: str | None, password: str) -> bool:
    try:
        return _hasher.verify(password_hash or _DUMMY_HASH, password) and password_hash is not None
    except (VerifyMismatchError, VerificationError, InvalidHashError):
        return False


def needs_rehash(password_hash: str) -> bool:
    return _hasher.check_needs_rehash(password_hash)


def create_access_token(user_id: str, secret: str, ttl_seconds: int, now: int | None = None) -> str:
    issued = int(now if now is not None else time.time())
    payload = {
        "sub": user_id,
        "iat": issued,
        "exp": issued + ttl_seconds,
        "aud": ACCESS_TOKEN_AUDIENCE,
        "typ": "access",
        "jti": uuid.uuid4().hex,
    }
    return jwt.encode(payload, secret, algorithm="HS256")


def decode_access_token(token: str, secret: str) -> str | None:
    """Returns the user id, or None if the token is invalid or expired."""
    try:
        payload = jwt.decode(token, secret, algorithms=["HS256"], audience=ACCESS_TOKEN_AUDIENCE, options={"require": ["exp", "sub"]})
    except jwt.PyJWTError:
        return None
    if payload.get("typ") != "access":
        return None
    sub = payload.get("sub")
    return sub if isinstance(sub, str) else None


def new_refresh_token() -> str:
    return secrets.token_urlsafe(32)


def hash_refresh_token(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()
