"""Optional enhanced recognition: a signed-in user uploads one short clip after opting in."""

from __future__ import annotations

from fastapi import APIRouter, Depends, File, Request, UploadFile
from sqlalchemy.orm import Session

from ..config import Settings
from ..deps import current_user, get_db, get_settings_dep
from ..errors import ApiError
from ..models import User
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
    engine: EnhancedRouter = request.app.state.enhanced
    if not engine.available():
        raise ApiError(503, "enhanced_unavailable", engine.unavailable_message())
    # Read at most one byte more than allowed; the clip stays in memory only.
    data = await audio.read(settings.enhanced_max_upload_bytes + 1)
    if len(data) > settings.enhanced_max_upload_bytes:
        raise ApiError(413, "clip_too_large", "The audio clip is too large.")
    try:
        clip = parse_wav(data, max_seconds=settings.enhanced_max_upload_seconds)
    except InvalidClip as exc:
        raise ApiError(422, "invalid_clip", str(exc)) from exc

    result = await engine.identify(db, user.id, clip)
    del data, clip  # nothing is persisted

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
