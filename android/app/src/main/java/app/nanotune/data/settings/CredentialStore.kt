package app.nanotune.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.nanotune.core.security.KeystoreCipher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

/** The signed-in account as known locally. Tokens are kept encrypted at rest. */
data class AccountSession(
    val userId: String,
    val email: String,
    val accessToken: String,
    val accessExpiresAt: Long,
    val refreshToken: String,
)

data class SyncStatus(
    val cursor: Long = 0,
    val lastSyncAt: Long? = null,
    val lastError: String? = null,
    val settingsPushedAt: Long = 0,
)

data class CachedDeveloperToken(val token: String, val expiresAtEpochSeconds: Long)

private val Context.credentialStore: DataStore<Preferences> by preferencesDataStore(name = "credentials")

/**
 * App-private storage for secrets and sync bookkeeping. Excluded from backups and device
 * transfer (see res/xml/data_extraction_rules.xml); values are AES-GCM encrypted with a
 * Keystore key, so a copied file is useless on another device.
 */
class CredentialStore(private val context: Context, private val cipher: KeystoreCipher = KeystoreCipher()) {

    private val data: Flow<Preferences> = context.credentialStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }

    val account: Flow<AccountSession?> = data.map { it.toAccount() }.distinctUntilChanged()

    val syncStatus: Flow<SyncStatus> = data.map { prefs ->
        SyncStatus(
            cursor = prefs[SYNC_CURSOR] ?: 0,
            lastSyncAt = prefs[LAST_SYNC_AT],
            lastError = prefs[LAST_SYNC_ERROR],
            settingsPushedAt = prefs[SETTINGS_PUSHED_AT] ?: 0,
        )
    }.distinctUntilChanged()

    suspend fun currentAccount(): AccountSession? = account.first()

    suspend fun saveAccount(session: AccountSession) {
        context.credentialStore.edit { prefs ->
            prefs[USER_ID] = session.userId
            prefs[EMAIL] = session.email
            prefs[ACCESS_TOKEN] = cipher.encrypt(session.accessToken)
            prefs[ACCESS_EXPIRES_AT] = session.accessExpiresAt
            prefs[REFRESH_TOKEN] = cipher.encrypt(session.refreshToken)
        }
    }

    /** Signs out locally and forgets sync bookkeeping (the next account starts fresh). */
    suspend fun clearAccount() {
        context.credentialStore.edit { prefs ->
            listOf(USER_ID, EMAIL, ACCESS_TOKEN, REFRESH_TOKEN, LAST_SYNC_ERROR).forEach { prefs.remove(it) }
            listOf(ACCESS_EXPIRES_AT, SYNC_CURSOR, LAST_SYNC_AT, SETTINGS_PUSHED_AT).forEach { prefs.remove(it) }
        }
    }

    suspend fun currentSyncStatus(): SyncStatus = syncStatus.first()

    suspend fun recordSync(cursor: Long, at: Long, settingsPushedAt: Long?) {
        context.credentialStore.edit { prefs ->
            prefs[SYNC_CURSOR] = cursor
            prefs[LAST_SYNC_AT] = at
            prefs.remove(LAST_SYNC_ERROR)
            if (settingsPushedAt != null) prefs[SETTINGS_PUSHED_AT] = settingsPushedAt
        }
    }

    suspend fun recordSyncError(message: String) {
        context.credentialStore.edit { it[LAST_SYNC_ERROR] = message }
    }

    suspend fun developerToken(): CachedDeveloperToken? {
        val prefs = data.first()
        val token = prefs[DEV_TOKEN]?.let(cipher::decrypt) ?: return null
        val expiresAt = prefs[DEV_TOKEN_EXPIRES_AT] ?: return null
        return CachedDeveloperToken(token, expiresAt)
    }

    suspend fun saveDeveloperToken(token: CachedDeveloperToken?) {
        context.credentialStore.edit { prefs ->
            if (token == null) {
                prefs.remove(DEV_TOKEN)
                prefs.remove(DEV_TOKEN_EXPIRES_AT)
            } else {
                prefs[DEV_TOKEN] = cipher.encrypt(token.token)
                prefs[DEV_TOKEN_EXPIRES_AT] = token.expiresAtEpochSeconds
            }
        }
    }

    suspend fun clearAll() {
        context.credentialStore.edit { it.clear() }
    }

    private fun Preferences.toAccount(): AccountSession? {
        val userId = this[USER_ID] ?: return null
        val email = this[EMAIL] ?: return null
        val access = this[ACCESS_TOKEN]?.let(cipher::decrypt) ?: return null
        val refresh = this[REFRESH_TOKEN]?.let(cipher::decrypt) ?: return null
        return AccountSession(userId, email, access, this[ACCESS_EXPIRES_AT] ?: 0, refresh)
    }

    private companion object {
        val USER_ID = stringPreferencesKey("user_id")
        val EMAIL = stringPreferencesKey("email")
        val ACCESS_TOKEN = stringPreferencesKey("access_token")
        val ACCESS_EXPIRES_AT = longPreferencesKey("access_expires_at")
        val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        val SYNC_CURSOR = longPreferencesKey("sync_cursor")
        val LAST_SYNC_AT = longPreferencesKey("last_sync_at")
        val LAST_SYNC_ERROR = stringPreferencesKey("last_sync_error")
        val SETTINGS_PUSHED_AT = longPreferencesKey("settings_pushed_at")
        val DEV_TOKEN = stringPreferencesKey("shazamkit_token")
        val DEV_TOKEN_EXPIRES_AT = longPreferencesKey("shazamkit_token_expires_at")
    }
}
