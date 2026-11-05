"""Optional accounts: register, login, refresh-token rotation, logout, profile and deletion."""

from __future__ import annotations

import hashlib
import hmac
import re
import secrets
from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, BackgroundTasks, Depends, Request, Response
from sqlalchemy import delete, select, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from ..config import Settings
from ..deps import client_ip, current_user, enforce, get_db, get_limiter, require_accounts
from ..errors import ApiError
from ..mailer import Mailer, deliver
from ..models import EmailCode, ProviderUsage, RefreshToken, User, new_id, utcnow
from ..ratelimit import RateLimiter
from ..schemas import (
    CodeRequest,
    Credentials,
    DeleteAccountRequest,
    EmailRequest,
    PasswordResetConfirm,
    RefreshRequest,
    TokenPair,
    UserOut,
)
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
    return UserOut(
        id=user.id,
        email=user.email,
        created_at=_as_aware(user.created_at).isoformat(),
        email_verified=user.email_verified_at is not None,
    )


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


# ------------------------------------------------------------------ password reset / verification

_INVALID_CODE = "The code is wrong or has expired. Request a new one."


def _mailer(request: Request) -> Mailer:
    mailer = getattr(request.app.state, "mailer", None)
    if mailer is None:
        raise ApiError(503, "email_unavailable", "This server can't send email, so codes are not available.")
    return mailer


def _code_hash(settings: Settings, user_id: str, purpose: str, code: str) -> str:
    # Six digits are guessable offline, so the stored hash is keyed with the server secret.
    message = f"{purpose}:{user_id}:{code}".encode()
    return hmac.new(settings.auth_jwt_secret.encode(), message, hashlib.sha256).hexdigest()


def _new_code(db: Session, settings: Settings, user: User, purpose: str) -> str:
    code = f"{secrets.randbelow(1_000_000):06d}"
    db.execute(delete(EmailCode).where(EmailCode.user_id == user.id, EmailCode.purpose == purpose))
    db.add(
        EmailCode(
            user_id=user.id,
            purpose=purpose,
            code_hash=_code_hash(settings, user.id, purpose, code),
            expires_at=utcnow() + timedelta(minutes=settings.email_code_ttl_minutes),
        )
    )
    db.commit()
    return code


def _code_matches(db: Session, settings: Settings, user: User, purpose: str, code: str) -> bool:
    row = db.scalar(select(EmailCode).where(EmailCode.user_id == user.id, EmailCode.purpose == purpose))
    if row is None or _as_aware(row.expires_at) <= utcnow() or row.attempts >= settings.email_code_max_attempts:
        return False
    if not hmac.compare_digest(row.code_hash, _code_hash(settings, user.id, purpose, code.strip())):
        row.attempts += 1
        db.commit()
        return False
    return True


def _code_mail(settings: Settings, code: str, what: str) -> str:
    server = settings.server_name or "your NanoTune server"
    return (
        f"Your NanoTune code is {code}\n\n"
        f"Enter it in the app to {what}. It expires in {settings.email_code_ttl_minutes} minutes.\n\n"
        f"If you didn't ask for this, ignore this email; nothing changes. Sent by {server}.\n"
    )


@router.post("/auth/password-reset/request", status_code=202)
def request_password_reset(
    body: EmailRequest,
    request: Request,
    background: BackgroundTasks,
    settings: Settings = Depends(require_accounts),
    db: Session = Depends(get_db),
    limiter: RateLimiter = Depends(get_limiter),
) -> Response:
    """Emails a reset code. Always 202, so it never reveals whether an address has an account."""
    mailer = _mailer(request)
    enforce(limiter, f"email-ip:{client_ip(request)}", settings.email_rate_per_ip_per_hour, 3600)
    email = _normalize_email(body.email)
    enforce(limiter, f"email-address:{email}", settings.email_rate_per_address_per_hour, 3600)
    user = db.scalar(select(User).where(User.email == email))
    if user is not None:
        code = _new_code(db, settings, user, "reset")
        background.add_task(deliver, mailer, email, "Your NanoTune password reset code", _code_mail(settings, code, "choose a new password"))
    return Response(status_code=202)


@router.post("/auth/password-reset/confirm", status_code=204)
def confirm_password_reset(
    body: PasswordResetConfirm,
    request: Request,
    settings: Settings = Depends(require_accounts),
    db: Session = Depends(get_db),
    limiter: RateLimiter = Depends(get_limiter),
) -> Response:
    """Sets a new password with a valid code and signs out every device."""
    _mailer(request)
    enforce(limiter, f"reset-confirm:{client_ip(request)}", settings.login_rate_per_ip_per_minute, 60)
    if len(body.new_password) < settings.password_min_length:
        raise ApiError(422, "weak_password", f"Use a password with at least {settings.password_min_length} characters.")
    user = db.scalar(select(User).where(User.email == body.email.strip().lower()))
    if user is None or not _code_matches(db, settings, user, "reset", body.code):
        raise ApiError(400, "invalid_code", _INVALID_CODE)
    now = utcnow()
    user.password_hash = hash_password(body.new_password)
    if user.email_verified_at is None:
        user.email_verified_at = now  # the code proves access to the mailbox
    db.execute(update(RefreshToken).where(RefreshToken.user_id == user.id, RefreshToken.revoked_at.is_(None)).values(revoked_at=now))
    db.execute(delete(EmailCode).where(EmailCode.user_id == user.id))
    db.commit()
    return Response(status_code=204)


@router.post("/me/email-verification/request", status_code=202)
def request_email_verification(
    request: Request,
    background: BackgroundTasks,
    user: User = Depends(current_user),
    settings: Settings = Depends(require_accounts),
    db: Session = Depends(get_db),
    limiter: RateLimiter = Depends(get_limiter),
) -> Response:
    mailer = _mailer(request)
    enforce(limiter, f"email-address:{user.email}", settings.email_rate_per_address_per_hour, 3600)
    if user.email_verified_at is None:
        code = _new_code(db, settings, user, "verify")
        background.add_task(deliver, mailer, user.email, "Confirm your NanoTune email address", _code_mail(settings, code, "confirm this email address"))
    return Response(status_code=202)


@router.post("/me/email-verification/confirm", response_model=UserOut)
def confirm_email_verification(
    body: CodeRequest,
    request: Request,
    user: User = Depends(current_user),
    settings: Settings = Depends(require_accounts),
    db: Session = Depends(get_db),
    limiter: RateLimiter = Depends(get_limiter),
) -> UserOut:
    _mailer(request)
    enforce(limiter, f"verify-confirm:{user.id}", settings.login_rate_per_ip_per_minute, 60)
    if user.email_verified_at is None:
        if not _code_matches(db, settings, user, "verify", body.code):
            raise ApiError(400, "invalid_code", _INVALID_CODE)
        user.email_verified_at = utcnow()
        db.execute(delete(EmailCode).where(EmailCode.user_id == user.id, EmailCode.purpose == "verify"))
        db.commit()
    return _user_out(user)
