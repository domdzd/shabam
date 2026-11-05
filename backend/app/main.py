"""FastAPI application factory for the NanoTune backend."""

from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from .config import Settings, get_settings
from .db import Database
from .errors import install_error_handlers
from .mailer import Mailer, SmtpMailer
from .ratelimit import RateLimiter
from .recognition.base import ProviderAdapter
from .recognition.registry import build_providers
from .recognition.router import EnhancedRouter
from .routers import auth, public, recognition, sync
from .shazamkit import DeveloperTokenSigner, SigningNotConfigured

log = logging.getLogger("nanotune")

#: Requests larger than this are rejected before the body is read (uploads are capped separately).
MAX_REQUEST_BYTES = 4_000_000


def _make_signer(settings: Settings) -> DeveloperTokenSigner | None:
    if not settings.shazamkit_configured:
        return None
    try:
        return DeveloperTokenSigner(
            team_id=settings.apple_team_id,
            key_id=settings.apple_key_id,
            private_key_pem=settings.read_apple_private_key(),
            ttl_seconds=settings.shazamkit_token_ttl_seconds,
        )
    except (SigningNotConfigured, ValueError, OSError) as exc:
        # Never include key material in logs.
        log.error("ShazamKit signing disabled: %s", type(exc).__name__)
        return None


def create_app(
    settings: Settings | None = None,
    providers: list[ProviderAdapter] | None = None,
    mailer: Mailer | None = None,
) -> FastAPI:
    settings = settings or get_settings()

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        if settings.run_migrations_on_start:
            from .migrate import upgrade_to_head

            upgrade_to_head(settings.database_url)
        yield
        app.state.database.engine.dispose()

    app = FastAPI(
        title="NanoTune API",
        version=__import__("app").__version__,
        docs_url="/api/docs" if settings.app_env != "production" else None,
        redoc_url=None,
        openapi_url="/api/openapi.json" if settings.app_env != "production" else None,
        lifespan=lifespan,
    )
    app.state.settings = settings
    app.state.database = Database(settings.database_url)
    app.state.limiter = RateLimiter()
    app.state.signer = _make_signer(settings)
    # Codes by email only when accounts and SMTP are configured (tests inject a fake mailer).
    app.state.mailer = (mailer or SmtpMailer(settings)) if settings.email_available else None
    app.state.enhanced = EnhancedRouter(
        settings,
        providers if providers is not None else build_providers(settings),
        shazamkit_available=app.state.signer is not None,
    )

    @app.middleware("http")
    async def limit_body_size(request: Request, call_next):
        length = request.headers.get("content-length")
        if length is not None and length.isdigit() and int(length) > MAX_REQUEST_BYTES:
            return JSONResponse({"error": {"code": "too_large", "message": "Request is too large."}}, status_code=413)
        response = await call_next(request)
        response.headers.setdefault("X-Content-Type-Options", "nosniff")
        response.headers.setdefault("Cache-Control", "no-store")
        return response

    install_error_handlers(app)
    for module in (public, auth, sync, recognition):
        app.include_router(module.router, prefix="/api/v1")
    return app
