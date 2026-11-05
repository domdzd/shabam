"""Optional enhanced recognition: a signed-in user uploads one short clip after opting in."""

from __future__ import annotations

from fastapi import APIRouter, Depends, File, Header, Request, UploadFile
from sqlalchemy.orm import Session

from ..config import Settings
from ..deps import client_ip, current_user, get_db, get_settings_dep, install_id
from ..errors import ApiError
from ..models import User
from ..security import decode_access_token
from ..recognition.audio import InvalidClip, parse_wav
from ..recognition.router import EnhancedRouter
from ..schemas import EnhancedProviderMatch, EnhancedResult, EnhancedTrack

router = APIRouter()


@router.post("/recognition/enhanced", response_model=EnhancedResult)
async def enhanced(
    request: Request,
    audio: UploadFile = File(...),
    user: User = Depends(current_user),
    settings: Settings = Depends(get_settings_dep),
    db: Session = Depends(get_db),
) -> EnhancedResult:
    """Fallback after ShazamKit found nothing (signed-in users, >= 2 engines). Kept for 0.1.0 apps."""
    engine: EnhancedRouter = request.app.state.enhanced
    if not engine.available():
        raise ApiError(503, "enhanced_unavailable", engine.unavailable_message())
    clip = await _read_clip(audio, settings)
    return _to_result(await engine.identify(db, [(f"user:{user.id}", settings.enhanced_per_user_daily_limit)], clip))


@router.post("/recognition/identify", response_model=EnhancedResult)
async def identify(
    request: Request,
    audio: UploadFile = File(...),
    authorization: str | None = Header(default=None),
    install: str | None = Depends(install_id),
    settings: Settings = Depends(get_settings_dep),
    db: Session = Depends(get_db),
) -> EnhancedResult:
    """Runs a clip through the server's engines.

    Signed-in users use their daily allowance. Without an account this works only when the owner
    set SERVER_RECOGNITION_ANONYMOUS=true, with per-install and per-IP daily caps.
    """
    engine: EnhancedRouter = request.app.state.enhanced
    if not engine.server_available():
        raise ApiError(503, "server_engines_unavailable", engine.server_unavailable_message())
    user = _optional_user(authorization, settings, db)
    if user is not None:
        scopes = [(f"user:{user.id}", settings.enhanced_per_user_daily_limit)]
    elif settings.server_recognition_anonymous:
        if install is None:
            raise ApiError(400, "install_id_required", "This app version did not identify itself.")
        scopes = [
            (engine.anonymous_scope("install", install), settings.server_recognition_per_install_daily_limit),
            (engine.anonymous_scope("ip", client_ip(request)), settings.server_recognition_per_ip_daily_limit),
        ]
    else:
        raise ApiError(401, "unauthorized", "Sign in to use this server's recognition engines.")
    clip = await _read_clip(audio, settings)
    return _to_result(await engine.identify(db, scopes, clip))


def _optional_user(authorization: str | None, settings: Settings, db: Session) -> User | None:
    if not authorization or not settings.accounts_available or not authorization.lower().startswith("bearer "):
        return None
    user_id = decode_access_token(authorization[7:].strip(), settings.auth_jwt_secret)
    user = db.get(User, user_id) if user_id else None
    if user is None:
        raise ApiError(401, "unauthorized", "Your session has expired. Please sign in again.")
    return user


async def _read_clip(audio: UploadFile, settings: Settings):
    # Read at most one byte more than allowed; the clip stays in memory only and is never stored.
    data = await audio.read(settings.enhanced_max_upload_bytes + 1)
    if len(data) > settings.enhanced_max_upload_bytes:
        raise ApiError(413, "clip_too_large", "The audio clip is too large.")
    try:
        return parse_wav(data, max_seconds=settings.enhanced_max_upload_seconds)
    except InvalidClip as exc:
        raise ApiError(422, "invalid_clip", str(exc)) from exc


def _to_result(result) -> EnhancedResult:
    best = None
    if result.reconciled is not None:
        r = result.reconciled
        best = EnhancedTrack(
            title=r.best.title,
            artist=r.best.artist,
            album=r.best.album,
            release_date=r.best.release_date,
            artwork_url=r.best.artwork_url,
            isrc=r.best.isrc,
            provider_track_id=r.best.provider_track_id,
            external_urls=r.best.external_urls,
            providers=r.providers,
            score=r.score,
        )
    return EnhancedResult(
        outcome=result.outcome,
        best=best,
        agreement=result.reconciled.agreement if result.reconciled else None,
        matches=[EnhancedProviderMatch(provider=m.provider, title=m.title, artist=m.artist, score=m.score) for m in result.matches],
        providers_queried=result.queried,
        message=result.message,
    )
