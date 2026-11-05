package app.nanotune.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.nanotune.BuildConfig
import app.nanotune.domain.model.RecordingsPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

enum class ThemeMode { SYSTEM, DARK, LIGHT }

enum class SessionMode { CONTINUOUS, INTERVAL }

/** User preferences. Device-specific values (server URL, consent) are never synchronised. */
data class AppSettings(
    val theme: ThemeMode = ThemeMode.DARK,
    /** Upper bound for one "tap to identify"; recognition stops earlier on the first match. */
    val manualListenSeconds: Int = DEFAULT_LISTEN_SECONDS,
    val sessionMode: SessionMode = SessionMode.CONTINUOUS,
    val sessionIntervalSeconds: Int = 60,
    val duplicateCooldownMinutes: Int = 4,
    val sessionsOnMobileData: Boolean = true,
    val enrichmentEnabled: Boolean = true,
    val enrichmentWifiOnly: Boolean = false,
    val serverUrl: String = BuildConfig.DEFAULT_API_BASE_URL,
    val syncEnabled: Boolean = true,
    /** "Use my server's recognition engines" (main engine without ShazamKit, or fallback). */
    val enhancedEnabled: Boolean = false,
    val enhancedConsentAt: Long? = null,
    /** Sorted, comma-separated engine ids the user agreed to; a new engine asks again. */
    val enhancedConsentProviders: String = "",
    /**
     * Keep what the Identify button records on this phone (on by default since 0.3, at the owner's
     * request; see docs/PRIVACY.md). Every tap then records and stores a clip, even without an engine.
     */
    val saveRecordings: Boolean = true,
    /** When the user last turned "Keep recordings" on themselves (informational). */
    val recordingsConsentAt: Long? = null,
    val recordingsPolicy: RecordingsPolicy = RecordingsPolicy.ALL,
    /** 0 = keep until deleted. */
    val recordingsRetentionDays: Int = 30,
    val retryRecordingsOnline: Boolean = true,
    val privacyNoticeSeen: Boolean = false,
    /** When a synchronised preference last changed (epoch ms); 0 = never. */
    val syncedSettingsUpdatedAt: Long = 0,
) {
    /** The subset of settings synchronised with a signed-in account. */
    fun syncedValues(): Map<String, String> = mapOf(
        "theme" to theme.name,
        "manual_listen_seconds" to manualListenSeconds.toString(),
        "session_mode" to sessionMode.name,
        "session_interval_seconds" to sessionIntervalSeconds.toString(),
        "duplicate_cooldown_minutes" to duplicateCooldownMinutes.toString(),
        "enrichment_enabled" to enrichmentEnabled.toString(),
        "enrichment_wifi_only" to enrichmentWifiOnly.toString(),
    )

    fun withSyncedValues(values: Map<String, String>): AppSettings = copy(
        theme = values["theme"]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: theme,
        manualListenSeconds = values["manual_listen_seconds"]?.toIntOrNull()?.coerceIn(MIN_LISTEN_SECONDS, MAX_LISTEN_SECONDS) ?: manualListenSeconds,
        sessionMode = values["session_mode"]?.let { runCatching { SessionMode.valueOf(it) }.getOrNull() } ?: sessionMode,
        sessionIntervalSeconds = values["session_interval_seconds"]?.toIntOrNull()?.coerceIn(30, 3600) ?: sessionIntervalSeconds,
        duplicateCooldownMinutes = values["duplicate_cooldown_minutes"]?.toIntOrNull()?.coerceIn(1, 60) ?: duplicateCooldownMinutes,
        enrichmentEnabled = values["enrichment_enabled"]?.toBooleanStrictOrNull() ?: enrichmentEnabled,
        enrichmentWifiOnly = values["enrichment_wifi_only"]?.toBooleanStrictOrNull() ?: enrichmentWifiOnly,
    )

    companion object {
        const val MIN_LISTEN_SECONDS = 5
        const val MAX_LISTEN_SECONDS = 20
        const val DEFAULT_LISTEN_SECONDS = 12
    }
}

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context, private val clock: () -> Long = System::currentTimeMillis) {

    val settings: Flow<AppSettings> = context.settingsStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { it.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    /** Applies [transform]; bumps [AppSettings.syncedSettingsUpdatedAt] when a synced value changed. */
    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsStore.edit { prefs ->
            val before = prefs.toSettings()
            var after = transform(before)
            if (after.syncedValues() != before.syncedValues() && after.syncedSettingsUpdatedAt == before.syncedSettingsUpdatedAt) {
                after = after.copy(syncedSettingsUpdatedAt = clock())
            }
            prefs.write(after)
        }
    }

    /** Applies settings received from the server without marking them as locally changed. */
    suspend fun applyRemote(values: Map<String, String>, remoteUpdatedAt: Long) {
        context.settingsStore.edit { prefs ->
            val merged = prefs.toSettings().withSyncedValues(values).copy(syncedSettingsUpdatedAt = remoteUpdatedAt)
            prefs.write(merged)
        }
    }

    suspend fun clear() {
        context.settingsStore.edit { it.clear() }
    }

    private fun Preferences.toSettings(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            theme = this[THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: defaults.theme,
            manualListenSeconds = (this[LISTEN_SECONDS] ?: defaults.manualListenSeconds)
                .coerceIn(AppSettings.MIN_LISTEN_SECONDS, AppSettings.MAX_LISTEN_SECONDS),
            sessionMode = this[SESSION_MODE]?.let { runCatching { SessionMode.valueOf(it) }.getOrNull() } ?: defaults.sessionMode,
            sessionIntervalSeconds = this[SESSION_INTERVAL] ?: defaults.sessionIntervalSeconds,
            duplicateCooldownMinutes = this[COOLDOWN_MINUTES] ?: defaults.duplicateCooldownMinutes,
            sessionsOnMobileData = this[SESSIONS_ON_MOBILE] ?: defaults.sessionsOnMobileData,
            enrichmentEnabled = this[ENRICHMENT] ?: defaults.enrichmentEnabled,
            enrichmentWifiOnly = this[ENRICHMENT_WIFI_ONLY] ?: defaults.enrichmentWifiOnly,
            serverUrl = this[SERVER_URL] ?: defaults.serverUrl,
            syncEnabled = this[SYNC_ENABLED] ?: defaults.syncEnabled,
            enhancedEnabled = this[ENHANCED_ENABLED] ?: defaults.enhancedEnabled,
            enhancedConsentAt = this[ENHANCED_CONSENT_AT],
            enhancedConsentProviders = this[ENHANCED_CONSENT_PROVIDERS] ?: "",
            saveRecordings = this[KEEP_RECORDINGS] ?: defaults.saveRecordings,
            recordingsConsentAt = this[RECORDINGS_CONSENT_AT],
            recordingsPolicy = this[RECORDINGS_POLICY]?.let { runCatching { RecordingsPolicy.valueOf(it) }.getOrNull() } ?: defaults.recordingsPolicy,
            recordingsRetentionDays = this[RECORDINGS_RETENTION_DAYS] ?: defaults.recordingsRetentionDays,
            retryRecordingsOnline = this[RETRY_RECORDINGS_ONLINE] ?: defaults.retryRecordingsOnline,
            privacyNoticeSeen = this[PRIVACY_SEEN] ?: defaults.privacyNoticeSeen,
            syncedSettingsUpdatedAt = this[SYNCED_UPDATED_AT] ?: 0L,
        )
    }

    private fun MutablePreferences.write(settings: AppSettings) {
        this[THEME] = settings.theme.name
        this[LISTEN_SECONDS] = settings.manualListenSeconds
        this[SESSION_MODE] = settings.sessionMode.name
        this[SESSION_INTERVAL] = settings.sessionIntervalSeconds
        this[COOLDOWN_MINUTES] = settings.duplicateCooldownMinutes
        this[SESSIONS_ON_MOBILE] = settings.sessionsOnMobileData
        this[ENRICHMENT] = settings.enrichmentEnabled
        this[ENRICHMENT_WIFI_ONLY] = settings.enrichmentWifiOnly
        this[SERVER_URL] = settings.serverUrl
        this[SYNC_ENABLED] = settings.syncEnabled
        this[ENHANCED_ENABLED] = settings.enhancedEnabled
        val consent = settings.enhancedConsentAt
        if (consent == null) remove(ENHANCED_CONSENT_AT) else this[ENHANCED_CONSENT_AT] = consent
        this[ENHANCED_CONSENT_PROVIDERS] = settings.enhancedConsentProviders
        this[KEEP_RECORDINGS] = settings.saveRecordings
        val recordingsConsent = settings.recordingsConsentAt
        if (recordingsConsent == null) remove(RECORDINGS_CONSENT_AT) else this[RECORDINGS_CONSENT_AT] = recordingsConsent
        this[RECORDINGS_POLICY] = settings.recordingsPolicy.name
        this[RECORDINGS_RETENTION_DAYS] = settings.recordingsRetentionDays
        this[RETRY_RECORDINGS_ONLINE] = settings.retryRecordingsOnline
        this[PRIVACY_SEEN] = settings.privacyNoticeSeen
        this[SYNCED_UPDATED_AT] = settings.syncedSettingsUpdatedAt
    }

    private companion object {
        val THEME = stringPreferencesKey("theme")
        val LISTEN_SECONDS = intPreferencesKey("manual_listen_seconds")
        val SESSION_MODE = stringPreferencesKey("session_mode")
        val SESSION_INTERVAL = intPreferencesKey("session_interval_seconds")
        val COOLDOWN_MINUTES = intPreferencesKey("duplicate_cooldown_minutes")
        val SESSIONS_ON_MOBILE = booleanPreferencesKey("sessions_on_mobile_data")
        val ENRICHMENT = booleanPreferencesKey("enrichment_enabled")
        val ENRICHMENT_WIFI_ONLY = booleanPreferencesKey("enrichment_wifi_only")
        val SERVER_URL = stringPreferencesKey("server_url")
        val SYNC_ENABLED = booleanPreferencesKey("sync_enabled")
        val ENHANCED_ENABLED = booleanPreferencesKey("enhanced_enabled")
        val ENHANCED_CONSENT_AT = longPreferencesKey("enhanced_consent_at")
        val ENHANCED_CONSENT_PROVIDERS = stringPreferencesKey("enhanced_consent_providers")
        // 0.3 keys: the 0.2 keys ("save_recordings", "recordings_policy") stored "off" for everyone,
        // so new keys let the new defaults (keep every recording) apply after the update.
        val KEEP_RECORDINGS = booleanPreferencesKey("keep_recordings_v3")
        val RECORDINGS_CONSENT_AT = longPreferencesKey("recordings_consent_at")
        val RECORDINGS_POLICY = stringPreferencesKey("recordings_keep_v3")
        val RECORDINGS_RETENTION_DAYS = intPreferencesKey("recordings_retention_days")
        val RETRY_RECORDINGS_ONLINE = booleanPreferencesKey("retry_recordings_online")
        val PRIVACY_SEEN = booleanPreferencesKey("privacy_notice_seen")
        val SYNCED_UPDATED_AT = longPreferencesKey("synced_settings_updated_at")
    }
}
