"""Optional accounts: register, login, refresh-token rotation, logout, profile and deletion."""

from __future__ import annotations

import re
from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends, Request, Response
from sqlalchemy import delete, select, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from ..config import Settings
from ..deps import client_ip, current_user, enforce, get_db, get_limiter, require_accounts
from ..errors import ApiError
from ..models import ProviderUsage, RefreshToken, User, new_id, utcnow
from ..ratelimit import RateLimiter
from ..schemas import Credentials, DeleteAccountRequest, RefreshRequest, TokenPair, UserOut
from ..security import (
    create_access_token,
    hash_password,
    hash_refresh_token,
    needs_rehash,
    new_refresh_token,
    verify_password,
)

router = APIRouter()

_EMAIL = re.compile(r"^[^@\s]{1,64}@[^@\s]+\.[^@\s]+$")
_GENERIC_LOGIN_ERROR = "Email or password is incorrect."


def _normalize_email(email: str) -> str:
    value = email.strip().lower()
    if not _EMAIL.match(value) or len(value) > 254:
        raise ApiError(422, "invalid_email", "Enter a valid email address.")
    return value


def _as_aware(value: datetime) -> datetime:
    # SQLite returns naive datetimes; treat them as UTC.
    return value if value.tzinfo else value.replace(tzinfo=timezone.utc)


def _user_out(user: User) -> UserOut:
    return UserOut(id=user.id, email=user.email, created_at=_as_aware(user.created_at).isoformat())


def _issue(db: Session, settings: Settings, user: User, family_id: str | None, device_name: str | None) -> tuple[TokenPair, str]:
    """Creates a refresh token (stored hashed) plus an access token; returns them and the row id."""
    raw = new_refresh_token()
    token = RefreshToken(
        id=new_id(),
        user_id=user.id,
        token_hash=hash_refresh_token(raw),
        family_id=family_id or new_id(),
        device_name=(device_name or None),
        expires_at=utcnow() + timedelta(days=settings.refresh_token_ttl_days),
    )
    db.add(token)
    db.flush()
    access = create_access_token(user.id, settings.auth_jwt_secret, settings.access_token_ttl_seconds)
    pair = TokenPair(access_token=access, refresh_token=raw, expires_in=settings.access_token_ttl_seconds, user=_user_out(user))
    token_id = token.id
    db.commit()
    return pair, token_id


@router.post("/auth/register", response_model=TokenPair, status_code=201)
def register(
    body: Credentials,
    request: Request,
    settings: Settings = Depends(require_accounts),
    db: Session = Depends(get_db),
    limiter: RateLimiter = Depends(get_limiter),
) -> TokenPair:
    enforce(limiter, f"register:{client_ip(request)}", settings.register_rate_per_ip_per_hour, 3600)
    if not settings.registration_open:
        raise ApiError(403, "registration_closed", "Registration is closed on this server.")
    email = _normalize_email(body.email)
    if len(body.password) < settings.password_min_length:
        raise ApiError(422, "weak_password", f"Use a password with at least {settings.password_min_length} characters.")
    user = User(email=email, password_hash=hash_password(body.password))
    db.add(user)
    try:
        db.flush()
    except IntegrityError:
        db.rollback()
        # Deliberately vague: do not confirm which addresses have accounts.
        raise ApiError(409, "registration_failed", "Could not create an account with these details. If you already have one, sign in instead.")
    return _issue(db, settings, user, None, body.device_name)[0]


@router.post("/auth/login", response_model=TokenPair)
def login(
    body: Credentials,
    request: Request,
    settings: Settings = Depends(require_accounts),
    db: Session = Depends(get_db),
    limiter: RateLimiter = Depends(get_limiter),
) -> TokenPair:
    enforce(limiter, f"login:{client_ip(request)}", settings.login_rate_per_ip_per_minute, 60)
    email = body.email.strip().lower()
    enforce(limiter, f"login-email:{email}", settings.login_rate_per_ip_per_minute, 60)
    user = db.scalar(select(User).where(User.email == email))
    if not verify_password(user.password_hash if user else None, body.password) or user is None:
        raise ApiError(401, "invalid_credentials", _GENERIC_LOGIN_ERROR)
    if needs_rehash(user.password_hash):
        user.password_hash = hash_password(body.password)
    return _issue(db, settings, user, None, body.device_name)[0]


@router.post("/auth/refresh", response_model=TokenPair)
def refresh(
    body: RefreshRequest,
    request: Request,
    settings: Settings = Depends(require_accounts),
    db: Session = Depends(get_db),
    limiter: RateLimiter = Depends(get_limiter),
) -> TokenPair:
    enforce(limiter, f"refresh:{client_ip(request)}", settings.refresh_rate_per_ip_per_minute, 60)
    token = db.scalar(select(RefreshToken).where(RefreshToken.token_hash == hash_refresh_token(body.refresh_token)))
    if token is None:
        raise ApiError(401, "invalid_refresh_token", "Please sign in again.")
    now = utcnow()
    if token.revoked_at is not None:
        # A rotated token was presented again: assume theft and end the whole session family.
        db.execute(
            update(RefreshToken)
            .where(RefreshToken.family_id == token.family_id, RefreshToken.revoked_at.is_(None))
            .values(revoked_at=now)
        )
        db.commit()
        raise ApiError(401, "refresh_token_reused", "Please sign in again.")
    if _as_aware(token.expires_at) <= now:
        raise ApiError(401, "refresh_token_expired", "Please sign in again.")
    user = db.get(User, token.user_id)
    if user is None:
        raise ApiError(401, "invalid_refresh_token", "Please sign in again.")
    token.revoked_at = now
    pair, new_token_id = _issue(db, settings, user, token.family_id, token.device_name)
    token.replaced_by = new_token_id
    db.commit()
    return pair


@router.post("/auth/logout", status_code=204)
def logout(body: RefreshRequest, settings: Settings = Depends(require_accounts), db: Session = Depends(get_db)) -> Response:
    token = db.scalar(select(RefreshToken).where(RefreshToken.token_hash == hash_refresh_token(body.refresh_token)))
    if token is not None:
        db.execute(
            update(RefreshToken)
            .where(RefreshToken.family_id == token.family_id, RefreshToken.revoked_at.is_(None))
            .values(revoked_at=utcnow())
        )
        db.commit()
    # Always 204: do not reveal whether the token existed.
    return Response(status_code=204)


@router.get("/me", response_model=UserOut)
def me(user: User = Depends(current_user)) -> UserOut:
    return _user_out(user)


@router.delete("/me", status_code=204)
def delete_me(
    body: DeleteAccountRequest,
    request: Request,
    user: User = Depends(current_user),
    settings: Settings = Depends(require_accounts),
    db: Session = Depends(get_db),
    limiter: RateLimiter = Depends(get_limiter),
) -> Response:
    """Deletes the account and all of its cloud data (tokens, history, favourites, settings)."""
    enforce(limiter, f"login:{client_ip(request)}", settings.login_rate_per_ip_per_minute, 60)
    if not verify_password(user.password_hash, body.password):
        raise ApiError(403, "invalid_password", "Password is incorrect.")
    db.execute(delete(ProviderUsage).where(ProviderUsage.scope == f"user:{user.id}"))
    db.delete(user)
    db.commit()
    return Response(status_code=204)
