"""Server-side routing across optional providers with quotas, timeouts and a kill switch."""

from __future__ import annotations

import asyncio
import hashlib
import hmac
import logging
import secrets
from dataclasses import dataclass
from datetime import datetime, timezone

from sqlalchemy import delete, or_, select
from sqlalchemy.orm import Session

from ..config import Settings
from ..models import ProviderUsage
from .base import ClipInfo, ProviderAdapter, ProviderError, ProviderMatch
from .reconcile import Reconciled, reconcile

log = logging.getLogger("nanotune.enhanced")


@dataclass
class EnhancedOutcome:
    outcome: str  # match | no_match | unavailable | error
    reconciled: Reconciled | None
    matches: list[ProviderMatch]
    queried: list[str]
    message: str | None = None


class EnhancedRouter:
    #: Scope prefixes of anonymous counters; they are only needed for the current UTC day.
    ANONYMOUS_SCOPES = ("install", "ip")

    def __init__(self, settings: Settings, providers: list[ProviderAdapter], shazamkit_available: bool):
        self.settings = settings
        self.providers = providers
        self.shazamkit_available = shazamkit_available
        # Anonymous quotas are keyed by install ID and client IP. They are stored only as keyed hashes,
        # so the database never holds raw IP addresses or install IDs. Without an account secret the key
        # is random per process (counters then restart with the server; provider limits are unaffected).
        secret = settings.auth_jwt_secret
        self._scope_key = (
            hmac.new(secret.encode(), b"nanotune-usage-scope-v1", hashlib.sha256).digest()
            if len(secret) >= 32
            else secrets.token_bytes(32)
        )
        self._purged_day: str | None = None

    # ---------------------------------------------------------------- availability
    def usable_providers(self) -> list[ProviderAdapter]:
        if self.settings.recognition_kill_switch:
            return []
        return [p for p in self.providers if p.daily_limit > 0]

    def available(self) -> bool:
        """Enhanced mode needs at least two usable engines overall, at least one server-side."""
        server = len(self.usable_providers())
        total = server + (1 if self.shazamkit_available else 0)
        return self.settings.accounts_available and server >= 1 and total >= 2

    def server_available(self) -> bool:
        """Server engines can be used directly (e.g. as the only engine) by someone."""
        anyone = self.settings.accounts_available or self.settings.server_recognition_anonymous
        return bool(self.usable_providers()) and anyone

    def server_unavailable_message(self) -> str:
        if self.settings.recognition_kill_switch:
            return "Server-side recognition is switched off on this server."
        if not self.usable_providers():
            return "No recognition engines are configured on this server."
        return "Server engines need an account, and accounts are disabled on this server."

    def unavailable_message(self) -> str:
        if self.settings.recognition_kill_switch:
            return "Enhanced recognition is switched off on this server."
        if not self.usable_providers():
            return "Cloud sync is available; additional recognition engines are not configured on this server."
        if not self.settings.accounts_available:
            return "Enhanced recognition requires accounts, which are disabled on this server."
        return "Enhanced recognition needs at least two configured engines."

    # ---------------------------------------------------------------- quotas
    @staticmethod
    def _today() -> str:
        return datetime.now(timezone.utc).strftime("%Y-%m-%d")

    def _usage(self, db: Session, scope: str) -> ProviderUsage:
        day = self._today()
        row = db.scalar(select(ProviderUsage).where(ProviderUsage.scope == scope, ProviderUsage.day == day))
        if row is None:
            row = ProviderUsage(scope=scope, day=day, count=0)
            db.add(row)
            db.flush()
        return row

    def user_quota_left(self, db: Session, user_id: str) -> int:
        return self.settings.enhanced_per_user_daily_limit - self._usage(db, f"user:{user_id}").count

    def anonymous_scope(self, kind: str, value: str) -> str:
        """Quota scope for anonymous use, e.g. ``ip:<keyed hash>``; the raw value is never stored."""
        if kind not in self.ANONYMOUS_SCOPES:
            raise ValueError(f"unknown anonymous scope {kind!r}")
        digest = hmac.new(self._scope_key, value.encode(), hashlib.sha256).hexdigest()[:32]
        return f"{kind}:{digest}"

    def _purge_old_anonymous_counters(self, db: Session) -> None:
        day = self._today()
        if self._purged_day == day:
            return
        db.execute(
            delete(ProviderUsage).where(
                or_(*(ProviderUsage.scope.startswith(f"{kind}:") for kind in self.ANONYMOUS_SCOPES)),
                ProviderUsage.day != day,
            )
        )
        self._purged_day = day

    # ---------------------------------------------------------------- routing
    async def identify(self, db: Session, quota_scopes: list[tuple[str, int]], clip: ClipInfo) -> EnhancedOutcome:
        """Runs the clip through the server engines.

        ``quota_scopes`` are (scope, daily limit) pairs that must all have capacity, e.g. the user,
        or the install plus the client IP for anonymous use. Each attempt counts against all of them.
        """
        providers = self.usable_providers()
        if not providers:
            return EnhancedOutcome("unavailable", None, [], [], self.server_unavailable_message())
        self._purge_old_anonymous_counters(db)
        usages = [(self._usage(db, scope), limit) for scope, limit in quota_scopes]
        if any(usage.count >= limit for usage, limit in usages):
            return EnhancedOutcome("unavailable", None, [], [], "Daily recognition limit reached for this device. Try again tomorrow.")
        for usage, _limit in usages:
            usage.count += 1
        db.commit()

        matches: list[ProviderMatch] = []
        queried: list[str] = []
        errors: list[str] = []
        answered = 0
        for provider in providers:
            usage = self._usage(db, f"provider:{provider.id}")
            if usage.count >= provider.daily_limit:
                errors.append(f"{provider.name}: daily limit reached")
                continue
            usage.count += 1
            db.commit()
            queried.append(provider.id)
            try:
                match = await asyncio.wait_for(provider.identify(clip), timeout=self.settings.enhanced_provider_timeout_seconds)
            except asyncio.TimeoutError:
                errors.append(f"{provider.name}: timed out")
                continue
            except ProviderError as exc:
                log.warning("provider %s failed (retryable=%s)", provider.id, exc.retryable)
                errors.append(f"{provider.name}: {'rate limited' if exc.rate_limited else 'unavailable'}")
                continue
            except Exception:  # noqa: BLE001 - a broken adapter must not break the endpoint
                log.exception("provider %s crashed", provider.id)
                errors.append(f"{provider.name}: failed")
                continue
            answered += 1
            if match is not None:
                matches.append(match)
                if self.settings.enhanced_mode == "fallback":
                    break

        reconciled = reconcile(matches, [p.id for p in providers])
        if reconciled is not None:
            return EnhancedOutcome("match", reconciled, matches, queried)
        if answered > 0:
            return EnhancedOutcome("no_match", None, matches, queried)
        if not queried:
            return EnhancedOutcome("unavailable", None, [], [], "; ".join(errors) or self.unavailable_message())
        return EnhancedOutcome("error", None, [], queried, "; ".join(errors))
