"""Runtime configuration, read from environment variables (and an optional .env file).

Secrets (Apple private key, JWT secret, provider keys) are only ever read from the
environment or mounted files; they are never logged or returned by the API.
"""

from __future__ import annotations

import logging
import os
import stat
from functools import lru_cache
from pathlib import Path

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

log = logging.getLogger("nanotune.config")

#: Apple: a developer token's ``exp`` must not be more than 15,777,000 s (6 months) after now.
APPLE_MAX_TOKEN_TTL_SECONDS = 15_777_000


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    app_env: str = "production"
    app_base_url: str = "https://api.example.com"
    server_name: str = "NanoTune"
    database_url: str = "sqlite:////data/nanotune.db"
    run_migrations_on_start: bool = True

    # --- Apple Media Services / ShazamKit developer-token signing -----------------------
    apple_team_id: str = ""
    apple_key_id: str = ""
    #: Informational only: the Media ID the key is associated with. Not part of the JWT.
    apple_media_id: str = ""
    apple_private_key_path: str = "/run/secrets/apple_media_services.p8"
    shazamkit_token_ttl_seconds: int = 3600

    # --- Accounts ---------------------------------------------------------------------
    accounts_enabled: bool = True
    registration_open: bool = True
    #: HS256 secret for access tokens. Generate with: python -c "import secrets;print(secrets.token_urlsafe(48))"
    auth_jwt_secret: str = ""
    access_token_ttl_seconds: int = 900
    refresh_token_ttl_days: int = 30
    password_min_length: int = 10

    # --- Rate limits (per process; run a single worker) --------------------------------
    token_rate_per_install_per_hour: int = 60
    token_rate_per_ip_per_hour: int = 600
    login_rate_per_ip_per_minute: int = 10
    register_rate_per_ip_per_hour: int = 5
    refresh_rate_per_ip_per_minute: int = 30
    sync_rate_per_user_per_minute: int = 30

    # --- Sync limits ---------------------------------------------------------------------
    sync_max_batch: int = 500
    sync_max_records_per_user: int = 100_000

    # --- Optional SMTP (email verification / password reset are not implemented yet) -----
    smtp_host: str = ""
    smtp_port: int = 587
    smtp_username: str = ""
    smtp_password: str = ""
    smtp_from: str = ""

    # --- Enhanced recognition (server-side providers; all disabled by default) -------------
    #: Global kill switch: when true no server-side provider is ever called.
    recognition_kill_switch: bool = False
    #: ``fallback`` = stop at the first provider that matches; ``consensus`` = ask all enabled.
    enhanced_mode: str = "fallback"
    enhanced_max_upload_seconds: int = 12
    enhanced_max_upload_bytes: int = 2_000_000
    enhanced_per_user_daily_limit: int = 25
    enhanced_provider_timeout_seconds: float = 10.0

    @field_validator("shazamkit_token_ttl_seconds")
    @classmethod
    def _ttl_within_apple_limit(cls, value: int) -> int:
        if not 60 <= value <= APPLE_MAX_TOKEN_TTL_SECONDS:
            raise ValueError(f"SHAZAMKIT_TOKEN_TTL_SECONDS must be between 60 and {APPLE_MAX_TOKEN_TTL_SECONDS}")
        return value

    @field_validator("enhanced_mode")
    @classmethod
    def _mode(cls, value: str) -> str:
        if value not in {"fallback", "consensus"}:
            raise ValueError("ENHANCED_MODE must be 'fallback' or 'consensus'")
        return value

    # ---------------------------------------------------------------------------------
    @property
    def shazamkit_configured(self) -> bool:
        return bool(self.apple_team_id and self.apple_key_id and Path(self.apple_private_key_path).is_file())

    @property
    def accounts_available(self) -> bool:
        return self.accounts_enabled and len(self.auth_jwt_secret) >= 32

    @property
    def smtp_configured(self) -> bool:
        return bool(self.smtp_host and self.smtp_from)

    def read_apple_private_key(self) -> str:
        path = Path(self.apple_private_key_path)
        try:
            mode = path.stat().st_mode
            if mode & (stat.S_IRWXG | stat.S_IRWXO):
                log.warning("Apple private key file is readable by group/others; run: chmod 600 %s", path)
        except OSError:
            pass
        return path.read_text(encoding="utf-8")


@lru_cache
def get_settings() -> Settings:
    settings = Settings()
    if settings.accounts_enabled and not settings.accounts_available:
        log.warning("Accounts are disabled: AUTH_JWT_SECRET is missing or shorter than 32 characters.")
    if not settings.shazamkit_configured:
        log.warning("ShazamKit token signing is not configured (APPLE_TEAM_ID, APPLE_KEY_ID, APPLE_PRIVATE_KEY_PATH).")
    if os.environ.get("APP_ENV", settings.app_env) == "production" and settings.app_base_url.startswith("http://"):
        log.warning("APP_BASE_URL uses plain http; public endpoints must be served over HTTPS.")
    return settings
