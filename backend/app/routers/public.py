"""Health, server capabilities and anonymous ShazamKit developer tokens."""

from __future__ import annotations

import time

from fastapi import APIRouter, Depends, Request

from .. import __version__
from ..config import Settings
from ..deps import client_ip, enforce, get_limiter, get_settings_dep, install_id
from ..errors import ApiError
from ..ratelimit import RateLimiter
from ..schemas import AccountsConfig, EnhancedConfig, Health, ProviderInfo, ServerConfig, ShazamKitConfig, ShazamToken

router = APIRouter()


@router.get("/health", response_model=Health)
def health() -> Health:
    return Health(status="ok", version=__version__)


@router.get("/config", response_model=ServerConfig)
def config(request: Request, settings: Settings = Depends(get_settings_dep)) -> ServerConfig:
    enhanced = request.app.state.enhanced
    signer = request.app.state.signer
    return ServerConfig(
        server_name=settings.server_name,
        shazamkit=ShazamKitConfig(available=signer is not None, token_ttl_seconds=settings.shazamkit_token_ttl_seconds if signer else None),
        accounts=AccountsConfig(
            enabled=settings.accounts_available,
            registration_open=settings.accounts_available and settings.registration_open,
            email_verification=False,
            password_reset=False,
        ),
        enhanced=EnhancedConfig(
            available=enhanced.available(),
            max_upload_seconds=settings.enhanced_max_upload_seconds,
            providers=[
                ProviderInfo(id=p.id, name=p.name, paid=p.paid, disclosure=p.disclosure)
                for p in enhanced.usable_providers()
            ],
            message=None if enhanced.available() else enhanced.unavailable_message(),
        ),
    )


@router.post("/shazamkit/token", response_model=ShazamToken)
def shazamkit_token(
    request: Request,
    settings: Settings = Depends(get_settings_dep),
    limiter: RateLimiter = Depends(get_limiter),
    install: str | None = Depends(install_id),
) -> ShazamToken:
    """Anonymous on purpose: identifying music never requires an account."""
    enforce(limiter, f"token-ip:{client_ip(request)}", settings.token_rate_per_ip_per_hour, 3600)
    if install:
        enforce(limiter, f"token-install:{install}", settings.token_rate_per_install_per_hour, 3600)
    signer = request.app.state.signer
    if signer is None:
        raise ApiError(503, "shazamkit_not_configured", "This NanoTune server has no Apple ShazamKit key configured.")
    token = signer.current()
    now = int(time.time())
    return ShazamToken(token=token.token, expires_at=token.expires_at, expires_in=max(0, token.expires_at - now))
