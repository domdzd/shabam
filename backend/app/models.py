"""ORM models. Only what accounts and sync need is stored; never audio or fingerprints."""

from __future__ import annotations

import uuid
from datetime import datetime, timezone

from sqlalchemy import JSON, BigInteger, Boolean, DateTime, ForeignKey, Integer, String, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column, relationship

from .db import Base


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


def new_id() -> str:
    return str(uuid.uuid4())


class User(Base):
    __tablename__ = "users"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    email: Mapped[str] = mapped_column(String(320), unique=True, index=True)
    password_hash: Mapped[str] = mapped_column(String(255))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    #: Monotonic change counter used as the sync cursor for this user.
    sync_seq: Mapped[int] = mapped_column(BigInteger, default=0)

    refresh_tokens: Mapped[list[RefreshToken]] = relationship(back_populates="user", cascade="all, delete-orphan", passive_deletes=True)


class RefreshToken(Base):
    """Opaque refresh tokens are stored only as SHA-256 hashes, grouped in rotation families."""

    __tablename__ = "refresh_tokens"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    token_hash: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    family_id: Mapped[str] = mapped_column(String(36), index=True)
    device_name: Mapped[str | None] = mapped_column(String(64), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    revoked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    replaced_by: Mapped[str | None] = mapped_column(String(36), nullable=True)

    user: Mapped[User] = relationship(back_populates="refresh_tokens")


class SyncRecognition(Base):
    __tablename__ = "sync_recognitions"
    __table_args__ = (UniqueConstraint("user_id", "record_id", name="uq_sync_recognition"),)

    pk: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    record_id: Mapped[str] = mapped_column(String(64))
    payload: Mapped[dict] = mapped_column(JSON)
    updated_at: Mapped[int] = mapped_column(BigInteger)
    deleted: Mapped[bool] = mapped_column(Boolean, default=False)
    seq: Mapped[int] = mapped_column(BigInteger, index=True)


class SyncFavourite(Base):
    __tablename__ = "sync_favourites"
    __table_args__ = (UniqueConstraint("user_id", "track_key", name="uq_sync_favourite"),)

    pk: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    track_key: Mapped[str] = mapped_column(String(300))
    payload: Mapped[dict] = mapped_column(JSON)
    updated_at: Mapped[int] = mapped_column(BigInteger)
    deleted: Mapped[bool] = mapped_column(Boolean, default=False)
    seq: Mapped[int] = mapped_column(BigInteger, index=True)


class SyncSettings(Base):
    __tablename__ = "sync_settings"

    user_id: Mapped[str] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), primary_key=True)
    data: Mapped[dict] = mapped_column(JSON)
    updated_at: Mapped[int] = mapped_column(BigInteger)
    seq: Mapped[int] = mapped_column(BigInteger, index=True)


class ProviderUsage(Base):
    """Daily call counters for server-side recognition providers (cost control)."""

    __tablename__ = "provider_usage"
    __table_args__ = (UniqueConstraint("scope", "day", name="uq_provider_usage"),)

    pk: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    #: ``provider:<id>``, ``user:<user id>``, or ``install:``/``ip:`` + keyed hash (anonymous, current day only)
    scope: Mapped[str] = mapped_column(String(120), index=True)
    day: Mapped[str] = mapped_column(String(10))
    count: Mapped[int] = mapped_column(Integer, default=0)
