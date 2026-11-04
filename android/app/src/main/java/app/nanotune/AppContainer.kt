package app.nanotune

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import app.nanotune.core.audio.AudioCapture
import app.nanotune.core.network.Connectivity
import app.nanotune.data.local.NanoTuneDatabase
import app.nanotune.data.remote.BackendClient
import app.nanotune.data.remote.MusicBrainzClient
import app.nanotune.data.repository.AccountRepository
import app.nanotune.data.repository.HistoryRepository
import app.nanotune.data.repository.ServerRepository
import app.nanotune.data.repository.SyncRepository
import app.nanotune.data.settings.CredentialStore
import app.nanotune.data.settings.SettingsRepository
import app.nanotune.recognition.EnhancedGate
import app.nanotune.recognition.MetadataEnricher
import app.nanotune.recognition.RecognitionEngine
import app.nanotune.recognition.StreamingRecognitionProvider
import app.nanotune.recognition.enhanced.EnhancedRecognitionClient
import app.nanotune.recognition.shazamkit.ShazamKitBridge
import app.nanotune.recognition.shazamkit.ShazamTokenSource
import app.nanotune.service.SessionStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Hand-written dependency graph; small enough that a DI framework would only add weight. */
class AppContainer(private val app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        coerceInputValues = true
    }

    val userAgent = "NanoTune/${BuildConfig.VERSION_NAME} (Android)"

    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request()
            chain.proceed(
                if (request.header("User-Agent") == null) request.newBuilder().header("User-Agent", userAgent).build() else request,
            )
        }
        .build()

    val settingsRepository = SettingsRepository(app)
    val credentialStore = CredentialStore(app)
    val connectivity = Connectivity(app)
    val database: NanoTuneDatabase by lazy { NanoTuneDatabase.create(app) }

    val backendClient = BackendClient(okHttp, json, settingsRepository)
    val serverRepository = ServerRepository(backendClient)
    val accountRepository = AccountRepository(backendClient, credentialStore)

    val historyRepository: HistoryRepository by lazy {
        HistoryRepository(database, json, syncActive = { syncRepository.isActive() })
    }
    val syncRepository: SyncRepository by lazy {
        SyncRepository(accountRepository, historyRepository, settingsRepository, credentialStore, appScope)
    }

    val tokenSource = ShazamTokenSource(backendClient, credentialStore, installId = { installId() })

    /** Null when this APK was built without Apple's ShazamKit AAR. */
    val primaryProvider: StreamingRecognitionProvider? = ShazamKitBridge.createProvider(tokenSource)

    val audioCapture = AudioCapture(app)

    val musicBrainz = MusicBrainzClient(
        okHttp,
        json,
        userAgent = "NanoTune/${BuildConfig.VERSION_NAME} ( ${BuildConfig.MUSICBRAINZ_CONTACT} )",
    )

    val metadataEnricher: MetadataEnricher by lazy {
        MetadataEnricher(historyRepository, musicBrainz, settingsRepository, connectivity, appScope)
    }

    val enhancedGate = EnhancedGate(serverRepository, settingsRepository, credentialStore)

    val recognitionEngine: RecognitionEngine by lazy {
        RecognitionEngine(
            primary = primaryProvider,
            tokenSource = tokenSource,
            enhanced = EnhancedRecognitionClient(accountRepository),
            enhancedGate = enhancedGate,
            audio = audioCapture,
            settings = settingsRepository,
            history = historyRepository,
            enricher = metadataEnricher,
            sync = syncRepository,
        )
    }

    val sessionState = SessionStateHolder()

    /**
     * Random per-install identifier sent with anonymous token requests so the server can rate
     * limit fairly behind shared mobile IPs. It is not linked to any account or device ID.
     */
    fun installId(): String {
        val prefs = app.getSharedPreferences("install", Context.MODE_PRIVATE)
        prefs.getString("install_id", null)?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit { putString("install_id", id) }
        return id
    }

    fun resetInstallId() {
        app.getSharedPreferences("install", Context.MODE_PRIVATE).edit { clear() }
    }
}
