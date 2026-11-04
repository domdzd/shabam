"""Shared FastAPI dependencies: database session, client identity, auth, rate limiting."""

from __future__ import annotations

import re
from collections.abc import Iterator

from fastapi import Depends, Header, Request
from sqlalchemy.orm import Session

from .config import Settings
from .errors import ApiError
from .models import User
from .ratelimit import RateLimiter, retry_after_header
from .security import decode_access_token

_INSTALL_ID = re.compile(r"^[A-Za-z0-9-]{8,64}$")


def get_settings_dep(request: Request) -> Settings:
    return request.app.state.settings


def get_db(request: Request) -> Iterator[Session]:
    yield from request.app.state.database.session()


def get_limiter(request: Request) -> RateLimiter:
    return request.app.state.limiter


def client_ip(request: Request) -> str:
    # request.client reflects X-Forwarded-For only when Uvicorn trusts the proxy
    # (--proxy-headers / FORWARDED_ALLOW_IPS), so this cannot be spoofed by clients.
    return request.client.host if request.client else "unknown"


def install_id(x_nanotune_install: str | None = Header(default=None)) -> str | None:
    if x_nanotune_install and _INSTALL_ID.match(x_nanotune_install):
        return x_nanotune_install
    return None


def enforce(limiter: RateLimiter, key: str, limit: int, window_seconds: float) -> None:
    wait = limiter.hit(key, limit, window_seconds)
    if wait is not None:
        raise ApiError(429, "rate_limited", "Too many requests. Please wait a moment and try again.", headers=retry_after_header(wait))


def require_accounts(settings: Settings = Depends(get_settings_dep)) -> Settings:
    if not settings.accounts_available:
        raise ApiError(503, "accounts_disabled", "Accounts are not enabled on this server.")
    return settings


def current_user(
    request: Request,
    authorization: str | None = Header(default=None),
    settings: Settings = Depends(require_accounts),
    db: Session = Depends(get_db),
) -> User:
    if not authorization or not authorization.lower().startswith("bearer "):
        raise ApiError(401, "unauthorized", "Sign in to use this feature.")
    user_id = decode_access_token(authorization[7:].strip(), settings.auth_jwt_secret)
    user = db.get(User, user_id) if user_id else None
    if user is None:
        raise ApiError(401, "unauthorized", "Your session has expired. Please sign in again.")
    return user
