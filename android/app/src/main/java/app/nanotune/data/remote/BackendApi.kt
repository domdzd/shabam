package app.nanotune.data.remote

import app.nanotune.data.remote.dto.CredentialsDto
import app.nanotune.data.remote.dto.DeleteAccountRequestDto
import app.nanotune.data.remote.dto.PasswordResetConfirmDto
import app.nanotune.data.remote.dto.CodeRequestDto
import app.nanotune.data.remote.dto.EmailRequestDto
import app.nanotune.data.remote.dto.EnhancedResultDto
import app.nanotune.data.remote.dto.HealthDto
import app.nanotune.data.remote.dto.RefreshRequestDto
import app.nanotune.data.remote.dto.ServerConfigDto
import app.nanotune.data.remote.dto.ShazamTokenDto
import app.nanotune.data.remote.dto.SyncPullDto
import app.nanotune.data.remote.dto.SyncPushDto
import app.nanotune.data.remote.dto.SyncPushResultDto
import app.nanotune.data.remote.dto.TokenPairDto
import app.nanotune.data.remote.dto.UserDto
import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Query

/** NanoTune backend, relative to `<server>/api/v1/`. */
interface BackendApi {
    @GET("health")
    suspend fun health(): Response<HealthDto>

    @GET("config")
    suspend fun config(): Response<ServerConfigDto>

    /** Anonymous by design: identifying music never requires an account. */
    @POST("shazamkit/token")
    suspend fun shazamKitToken(@Header("X-NanoTune-Install") installId: String): Response<ShazamTokenDto>

    @POST("auth/register")
    suspend fun register(@Body body: CredentialsDto): Response<TokenPairDto>

    @POST("auth/login")
    suspend fun login(@Body body: CredentialsDto): Response<TokenPairDto>

    @POST("auth/refresh")
    suspend fun refresh(@Body body: RefreshRequestDto): Response<TokenPairDto>

    @POST("auth/logout")
    suspend fun logout(@Body body: RefreshRequestDto): Response<Unit>

    @GET("me")
    suspend fun me(@Header("Authorization") authorization: String): Response<UserDto>

    @HTTP(method = "DELETE", path = "me", hasBody = true)
    suspend fun deleteMe(@Header("Authorization") authorization: String, @Body body: DeleteAccountRequestDto): Response<Unit>

    /** Emails a reset code (always 202, whether or not the address has an account). */
    @POST("auth/password-reset/request")
    suspend fun requestPasswordReset(@Body body: EmailRequestDto): Response<Unit>

    @POST("auth/password-reset/confirm")
    suspend fun confirmPasswordReset(@Body body: PasswordResetConfirmDto): Response<Unit>

    @POST("me/email-verification/request")
    suspend fun requestEmailVerification(@Header("Authorization") authorization: String): Response<Unit>

    @POST("me/email-verification/confirm")
    suspend fun confirmEmailVerification(@Header("Authorization") authorization: String, @Body body: CodeRequestDto): Response<UserDto>

    @GET("sync")
    suspend fun pull(@Header("Authorization") authorization: String, @Query("since") since: Long): Response<SyncPullDto>

    @PUT("sync")
    suspend fun push(@Header("Authorization") authorization: String, @Body body: SyncPushDto): Response<SyncPushResultDto>

    @Multipart
    @POST("recognition/enhanced")
    suspend fun enhanced(@Header("Authorization") authorization: String, @Part audio: MultipartBody.Part): Response<EnhancedResultDto>

    /** Server engines; [authorization] is null for anonymous use (only if the server allows it). */
    @Multipart
    @POST("recognition/identify")
    suspend fun identifyClip(
        @Header("Authorization") authorization: String?,
        @Header("X-NanoTune-Install") installId: String,
        @Part audio: MultipartBody.Part,
    ): Response<EnhancedResultDto>
}
