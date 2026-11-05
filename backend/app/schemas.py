"""Wire format of the public API. Mirrors android/.../data/remote/dto/BackendDtos.kt."""

from __future__ import annotations

from pydantic import BaseModel, ConfigDict, Field


class ApiModel(BaseModel):
    model_config = ConfigDict(extra="ignore")


class Health(ApiModel):
    status: str
    version: str


class ShazamKitConfig(ApiModel):
    available: bool
    token_ttl_seconds: int | None = None


class AccountsConfig(ApiModel):
    enabled: bool
    registration_open: bool
    email_verification: bool
    password_reset: bool


class ProviderInfo(ApiModel):
    id: str
    name: str
    paid: bool
    uploads_audio: bool = True
    disclosure: str | None = None


class EnhancedConfig(ApiModel):
    available: bool
    requires_account: bool = True
    max_upload_seconds: int
    providers: list[ProviderInfo]
    message: str | None = None


class ServerEnginesConfig(ApiModel):
    """Server-side engines usable directly (e.g. as the only engine, or to retry saved clips)."""

    available: bool
    anonymous: bool
    requires_account: bool
    max_upload_seconds: int
    providers: list[ProviderInfo]
    message: str | None = None


class ServerConfig(ApiModel):
    api_version: int = 1
    server_name: str
    shazamkit: ShazamKitConfig
    accounts: AccountsConfig
    enhanced: EnhancedConfig
    server_engines: ServerEnginesConfig


class ShazamToken(ApiModel):
    token: str
    expires_at: int
    expires_in: int


class Credentials(ApiModel):
    email: str = Field(min_length=3, max_length=320)
    password: str = Field(min_length=1, max_length=256)
    device_name: str | None = Field(default=None, max_length=64)


class RefreshRequest(ApiModel):
    refresh_token: str = Field(min_length=10, max_length=200)


class DeleteAccountRequest(ApiModel):
    password: str = Field(min_length=1, max_length=256)


class UserOut(ApiModel):
    id: str
    email: str
    created_at: str
    email_verified: bool = False


class EmailRequest(ApiModel):
    email: str = Field(min_length=3, max_length=320)


class CodeRequest(ApiModel):
    code: str = Field(min_length=6, max_length=12)


class PasswordResetConfirm(ApiModel):
    email: str = Field(min_length=3, max_length=320)
    code: str = Field(min_length=6, max_length=12)
    new_password: str = Field(min_length=1, max_length=256)


class TokenPair(ApiModel):
    access_token: str
    refresh_token: str
    token_type: str = "bearer"
    expires_in: int
    user: UserOut


class SyncRecognition(ApiModel):
    id: str = Field(min_length=1, max_length=64)
    track_key: str = Field(min_length=1, max_length=300)
    title: str = Field(max_length=500)
    artist: str = Field(max_length=500)
    album: str | None = Field(default=None, max_length=500)
    release_date: str | None = Field(default=None, max_length=32)
    artwork_url: str | None = Field(default=None, max_length=2048)
    isrc: str | None = Field(default=None, max_length=32)
    provider_track_id: str | None = Field(default=None, max_length=200)
    genres: list[str] = Field(default_factory=list, max_length=20)
    external_urls: dict[str, str] = Field(default_factory=dict, max_length=20)
    provider_id: str = Field(max_length=100)
    recognized_at: int
    mode: str = Field(max_length=16)
    session_id: str | None = Field(default=None, max_length=64)
    confidence: float | None = None
    updated_at: int
    deleted: bool = False


class SyncFavourite(ApiModel):
    track_key: str = Field(min_length=1, max_length=300)
    title: str = Field(max_length=500)
    artist: str = Field(max_length=500)
    artwork_url: str | None = Field(default=None, max_length=2048)
    provider_track_id: str | None = Field(default=None, max_length=200)
    created_at: int
    updated_at: int
    deleted: bool = False


class SyncSettings(ApiModel):
    data: dict[str, str] = Field(max_length=50)
    updated_at: int


class SyncPush(ApiModel):
    recognitions: list[SyncRecognition] = Field(default_factory=list)
    favourites: list[SyncFavourite] = Field(default_factory=list)
    settings: SyncSettings | None = None


class SyncPushResult(ApiModel):
    cursor: int
    accepted: int
    ignored: int


class SyncPull(ApiModel):
    cursor: int
    recognitions: list[SyncRecognition]
    favourites: list[SyncFavourite]
    settings: SyncSettings | None = None
    has_more: bool = False


class EnhancedTrack(ApiModel):
    title: str
    artist: str
    album: str | None = None
    release_date: str | None = None
    artwork_url: str | None = None
    isrc: str | None = None
    provider_track_id: str | None = None
    external_urls: dict[str, str] = Field(default_factory=dict)
    providers: list[str] = Field(default_factory=list)
    score: float | None = None


class EnhancedProviderMatch(ApiModel):
    provider: str
    title: str
    artist: str
    score: float | None = None


class EnhancedResult(ApiModel):
    outcome: str
    best: EnhancedTrack | None = None
    agreement: str | None = None
    matches: list[EnhancedProviderMatch] = Field(default_factory=list)
    providers_queried: list[str] = Field(default_factory=list)
    message: str | None = None
