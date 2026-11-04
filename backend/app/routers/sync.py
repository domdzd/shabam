"""History / favourites / settings synchronisation with last-writer-wins and tombstones.

Every accepted change receives the user's next sequence number. Clients push their changes, then
pull everything with ``seq > cursor``. The same LWW rule runs on the phone
(android/.../data/sync/SyncMerge.kt), so copies converge to the most recently updated version.
"""

from __future__ import annotations

from fastapi import APIRouter, Depends, Query, Request
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from ..config import Settings
from ..deps import current_user, enforce, get_db, get_limiter, require_accounts
from ..errors import ApiError
from ..models import SyncFavourite, SyncRecognition, SyncSettings, User
from ..ratelimit import RateLimiter
from ..schemas import SyncFavourite as SyncFavouriteIn
from ..schemas import SyncPull, SyncPush, SyncPushResult
from ..schemas import SyncRecognition as SyncRecognitionIn
from ..schemas import SyncSettings as SyncSettingsIn

router = APIRouter()

PAGE_SIZE = 500


def incoming_wins(stored_updated_at: int | None, incoming_updated_at: int) -> bool:
    return stored_updated_at is None or incoming_updated_at > stored_updated_at


def _next_seq(user: User) -> int:
    user.sync_seq = (user.sync_seq or 0) + 1
    return user.sync_seq


@router.put("/sync", response_model=SyncPushResult)
def push(
    body: SyncPush,
    user: User = Depends(current_user),
    settings: Settings = Depends(require_accounts),
    db: Session = Depends(get_db),
    limiter: RateLimiter = Depends(get_limiter),
) -> SyncPushResult:
    enforce(limiter, f"sync:{user.id}", settings.sync_rate_per_user_per_minute, 60)
    if len(body.recognitions) > settings.sync_max_batch or len(body.favourites) > settings.sync_max_batch:
        raise ApiError(413, "batch_too_large", f"Send at most {settings.sync_max_batch} records per request.")

    existing_count = db.scalar(select(func.count()).select_from(SyncRecognition).where(SyncRecognition.user_id == user.id)) or 0
    accepted = ignored = 0

    for record in body.recognitions:
        stored = db.scalar(
            select(SyncRecognition).where(SyncRecognition.user_id == user.id, SyncRecognition.record_id == record.id)
        )
        if not incoming_wins(stored.updated_at if stored else None, record.updated_at):
            ignored += 1
            continue
        payload = record.model_dump()
        if stored is None:
            if existing_count >= settings.sync_max_records_per_user:
                raise ApiError(413, "quota_exceeded", "Cloud history is full for this account.")
            existing_count += 1
            db.add(SyncRecognition(user_id=user.id, record_id=record.id, payload=payload, updated_at=record.updated_at, deleted=record.deleted, seq=_next_seq(user)))
        else:
            stored.payload, stored.updated_at, stored.deleted, stored.seq = payload, record.updated_at, record.deleted, _next_seq(user)
        accepted += 1

    for fav in body.favourites:
        stored_fav = db.scalar(select(SyncFavourite).where(SyncFavourite.user_id == user.id, SyncFavourite.track_key == fav.track_key))
        if not incoming_wins(stored_fav.updated_at if stored_fav else None, fav.updated_at):
            ignored += 1
            continue
        payload = fav.model_dump()
        if stored_fav is None:
            db.add(SyncFavourite(user_id=user.id, track_key=fav.track_key, payload=payload, updated_at=fav.updated_at, deleted=fav.deleted, seq=_next_seq(user)))
        else:
            stored_fav.payload, stored_fav.updated_at, stored_fav.deleted, stored_fav.seq = payload, fav.updated_at, fav.deleted, _next_seq(user)
        accepted += 1

    if body.settings is not None:
        stored_settings = db.get(SyncSettings, user.id)
        if incoming_wins(stored_settings.updated_at if stored_settings else None, body.settings.updated_at):
            if stored_settings is None:
                db.add(SyncSettings(user_id=user.id, data=body.settings.data, updated_at=body.settings.updated_at, seq=_next_seq(user)))
            else:
                stored_settings.data, stored_settings.updated_at, stored_settings.seq = body.settings.data, body.settings.updated_at, _next_seq(user)
            accepted += 1
        else:
            ignored += 1

    db.commit()
    return SyncPushResult(cursor=user.sync_seq or 0, accepted=accepted, ignored=ignored)


@router.get("/sync", response_model=SyncPull)
def pull(
    since: int = Query(default=0, ge=0),
    user: User = Depends(current_user),
    settings: Settings = Depends(require_accounts),
    db: Session = Depends(get_db),
    limiter: RateLimiter = Depends(get_limiter),
) -> SyncPull:
    enforce(limiter, f"sync:{user.id}", settings.sync_rate_per_user_per_minute, 60)
    recognitions = db.scalars(
        select(SyncRecognition).where(SyncRecognition.user_id == user.id, SyncRecognition.seq > since).order_by(SyncRecognition.seq).limit(PAGE_SIZE + 1)
    ).all()
    favourites = db.scalars(
        select(SyncFavourite).where(SyncFavourite.user_id == user.id, SyncFavourite.seq > since).order_by(SyncFavourite.seq).limit(PAGE_SIZE + 1)
    ).all()
    stored_settings = db.get(SyncSettings, user.id)

    # Merge both streams by seq and cut one page, so the cursor never skips a record.
    merged = sorted([("r", r.seq, r) for r in recognitions] + [("f", f.seq, f) for f in favourites], key=lambda item: item[1])
    page = merged[:PAGE_SIZE]
    has_more = len(merged) > PAGE_SIZE
    cursor = page[-1][1] if has_more else (user.sync_seq or 0)
    page_settings = None
    if stored_settings is not None and since < stored_settings.seq <= cursor:
        page_settings = SyncSettingsIn(data=stored_settings.data, updated_at=stored_settings.updated_at)
    return SyncPull(
        cursor=cursor,
        recognitions=[SyncRecognitionIn.model_validate(item.payload) for kind, _seq, item in page if kind == "r"],
        favourites=[SyncFavouriteIn.model_validate(item.payload) for kind, _seq, item in page if kind == "f"],
        settings=page_settings,
        has_more=has_more,
    )
