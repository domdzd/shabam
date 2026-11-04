"""Builds the list of server-side engines from configuration.

Nothing is enabled by default. An engine is only used when its ``*_ENABLED`` flag is true, its
credentials are present and its ``*_DAILY_LIMIT`` is positive (a mandatory cost control).
AcoustID is intentionally not offered: it cannot match microphone snippets.
"""

from __future__ import annotations

import logging

import httpx

from ..config import Settings
from .base import ProviderAdapter
from .providers.acrcloud import ACRCloudProvider
from .providers.audd import AudDProvider

log = logging.getLogger("nanotune.providers")


def build_providers(settings: Settings, transport: httpx.AsyncBaseTransport | None = None) -> list[ProviderAdapter]:
    available: dict[str, ProviderAdapter] = {}
    timeout = settings.enhanced_provider_timeout_seconds

    if settings.audd_enabled:
        if not settings.audd_api_token or settings.audd_daily_limit <= 0:
            log.warning("AUDD_ENABLED is set but AUDD_API_TOKEN or a positive AUDD_DAILY_LIMIT is missing; AudD stays off.")
        else:
            available["audd"] = AudDProvider(
                settings.audd_api_token,
                settings.audd_daily_limit,
                settings.audd_return,
                transport=transport,
                timeout_seconds=timeout,
            )

    if settings.acrcloud_enabled:
        if not (settings.acrcloud_host and settings.acrcloud_access_key and settings.acrcloud_access_secret) or settings.acrcloud_daily_limit <= 0:
            log.warning("ACRCLOUD_ENABLED is set but host/keys or a positive ACRCLOUD_DAILY_LIMIT are missing; ACRCloud stays off.")
        else:
            try:
                available["acrcloud"] = ACRCloudProvider(
                    settings.acrcloud_host,
                    settings.acrcloud_access_key,
                    settings.acrcloud_access_secret,
                    settings.acrcloud_daily_limit,
                    transport=transport,
                    timeout_seconds=timeout,
                )
            except ValueError:
                log.warning("ACRCLOUD_HOST is not a valid host name; ACRCloud stays off.")

    order = [name.strip() for name in settings.provider_order.split(",") if name.strip()]
    ordered = [available[name] for name in order if name in available]
    ordered += [provider for name, provider in available.items() if name not in order]
    return ordered
