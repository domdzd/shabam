package app.nanotune.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Wire format of the NanoTune backend (backend/app/schemas.py). Keep both sides in sync.

@Serializable
data class HealthDto(val status: String, val version: String? = null)

@Serializable
data class ServerConfigDto(
    @SerialName("api_version") val apiVersion: Int = 1,
    @SerialName("server_name") val serverName: String? = null,
    val shazamkit: ShazamKitConfigDto = ShazamKitConfigDto(),
    val accounts: AccountsConfigDto = AccountsConfigDto(),
    val enhanced: EnhancedConfigDto = EnhancedConfigDto(),
)

@Serializable
data class ShazamKitConfigDto(
    val available: Boolean = false,
    @SerialName("token_ttl_seconds") val tokenTtlSeconds: Long? = null,
)

@Serializable
data class AccountsConfigDto(
    val enabled: Boolean = false,
    @SerialName("registration_open") val registrationOpen: Boolean = false,
    @SerialName("email_verification") val emailVerification: Boolean = false,
    @SerialName("password_reset") val passwordReset: Boolean = false,
)

@Serializable
data class EnhancedConfigDto(
    val available: Boolean = false,
    @SerialName("requires_account") val requiresAccount: Boolean = true,
    @SerialName("max_upload_seconds") val maxUploadSeconds: Int = 12,
    val providers: List<ProviderInfoDto> = emptyList(),
    val message: String? = null,
)

@Serializable
data class ProviderInfoDto(
    val id: String,
    val name: String,
    val paid: Boolean,
    @SerialName("uploads_audio") val uploadsAudio: Boolean = true,
    val disclosure: String? = null,
)

@Serializable
data class ShazamTokenDto(
    val token: String,
    @SerialName("expires_at") val expiresAt: Long,
    @SerialName("expires_in") val expiresIn: Long,
)

@Serializable
data class CredentialsDto(
    val email: String,
    val password: String,
    @SerialName("device_name") val deviceName: String? = null,
)

@Serializable
data class RefreshRequestDto(@SerialName("refresh_token") val refreshToken: String)

@Serializable
data class DeleteAccountRequestDto(val password: String)

@Serializable
data class UserDto(
    val id: String,
    val email: String,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class TokenPairDto(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("token_type") val tokenType: String = "bearer",
    @SerialName("expires_in") val expiresIn: Long,
    val user: UserDto,
)

@Serializable
data class SyncRecognitionDto(
    val id: String,
    @SerialName("track_key") val trackKey: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("artwork_url") val artworkUrl: String? = null,
    val isrc: String? = null,
    @SerialName("provider_track_id") val providerTrackId: String? = null,
    val genres: List<String> = emptyList(),
    @SerialName("external_urls") val externalUrls: Map<String, String> = emptyMap(),
    @SerialName("provider_id") val providerId: String,
    @SerialName("recognized_at") val recognizedAt: Long,
    val mode: String,
    @SerialName("session_id") val sessionId: String? = null,
    val confidence: Float? = null,
    @SerialName("updated_at") val updatedAt: Long,
    val deleted: Boolean = false,
)

@Serializable
data class SyncFavouriteDto(
    @SerialName("track_key") val trackKey: String,
    val title: String,
    val artist: String,
    @SerialName("artwork_url") val artworkUrl: String? = null,
    @SerialName("provider_track_id") val providerTrackId: String? = null,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    val deleted: Boolean = false,
)

@Serializable
data class SyncSettingsDto(
    val data: Map<String, String>,
    @SerialName("updated_at") val updatedAt: Long,
)

@Serializable
data class SyncPushDto(
    val recognitions: List<SyncRecognitionDto> = emptyList(),
    val favourites: List<SyncFavouriteDto> = emptyList(),
    val settings: SyncSettingsDto? = null,
)

@Serializable
data class SyncPushResultDto(
    val cursor: Long,
    val accepted: Int,
    val ignored: Int,
)

@Serializable
data class SyncPullDto(
    val cursor: Long,
    val recognitions: List<SyncRecognitionDto> = emptyList(),
    val favourites: List<SyncFavouriteDto> = emptyList(),
    val settings: SyncSettingsDto? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
data class EnhancedTrackDto(
    val title: String,
    val artist: String,
    val album: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("artwork_url") val artworkUrl: String? = null,
    val isrc: String? = null,
    @SerialName("provider_track_id") val providerTrackId: String? = null,
    @SerialName("external_urls") val externalUrls: Map<String, String> = emptyMap(),
    val providers: List<String> = emptyList(),
    val score: Float? = null,
)

@Serializable
data class EnhancedProviderMatchDto(
    val provider: String,
    val title: String,
    val artist: String,
    val score: Float? = null,
)

@Serializable
data class EnhancedResultDto(
    /** One of `match`, `no_match`, `unavailable`, `error`. */
    val outcome: String,
    val best: EnhancedTrackDto? = null,
    /** One of `single`, `agree`, `conflict` when [outcome] is `match`. */
    val agreement: String? = null,
    val matches: List<EnhancedProviderMatchDto> = emptyList(),
    @SerialName("providers_queried") val providersQueried: List<String> = emptyList(),
    val message: String? = null,
)

@Serializable
data class ApiErrorBodyDto(val code: String, val message: String)

@Serializable
data class ApiErrorEnvelopeDto(val error: ApiErrorBodyDto? = null)
