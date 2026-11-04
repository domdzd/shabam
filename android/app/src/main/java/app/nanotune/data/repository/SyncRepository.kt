package app.nanotune.data.repository

import app.nanotune.data.remote.ApiResult
import app.nanotune.data.remote.dto.SyncPushDto
import app.nanotune.data.remote.dto.SyncSettingsDto
import app.nanotune.data.settings.CredentialStore
import app.nanotune.data.settings.SettingsRepository
import app.nanotune.data.settings.SyncStatus
import app.nanotune.data.sync.SyncMerge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface SyncOutcome {
    data object Done : SyncOutcome
    data object Skipped : SyncOutcome
    data class Failed(val message: String) : SyncOutcome
}

/**
 * Two-way history/favourites/settings sync for signed-in users: push local changes, then pull
 * everything newer than the stored cursor. Both sides resolve conflicts last-writer-wins.
 */
class SyncRepository(
    private val account: AccountRepository,
    private val history: HistoryRepository,
    private val settings: SettingsRepository,
    private val credentials: CredentialStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val status: Flow<SyncStatus> = credentials.syncStatus

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val mutex = Mutex()
    private var pending: Job? = null

    /** Debounced background sync, e.g. after a new recognition. */
    fun requestSync(delayMs: Long = 5_000) {
        pending?.cancel()
        pending = scope.launch {
            delay(delayMs)
            syncNow()
        }
    }

    suspend fun isActive(): Boolean = credentials.currentAccount() != null && settings.current().syncEnabled

    suspend fun syncNow(): SyncOutcome = mutex.withLock {
        if (!isActive()) return SyncOutcome.Skipped
        _running.value = true
        try {
            push()?.let { return fail(it) }
            pull()?.let { return fail(it) }
            history.purgeTombstones()
            SyncOutcome.Done
        } finally {
            _running.value = false
        }
    }

    /** Returns an error message, or null on success. */
    private suspend fun push(): String? {
        val current = settings.current()
        val status = credentials.currentSyncStatus()
        var settingsToPush: SyncSettingsDto? = if (current.syncedSettingsUpdatedAt > status.settingsPushedAt) {
            SyncSettingsDto(current.syncedValues(), current.syncedSettingsUpdatedAt)
        } else {
            null
        }
        // Loop until nothing is pending (bounded, in case records keep changing underneath us).
        repeat(MAX_PUSH_ROUNDS) {
            val recognitions = history.pendingRecognitions(SyncMerge.MAX_BATCH)
            val favourites = history.pendingFavourites(SyncMerge.MAX_BATCH)
            if (recognitions.isEmpty() && favourites.isEmpty() && settingsToPush == null) return null
            val body = SyncPushDto(recognitions = recognitions, favourites = favourites, settings = settingsToPush)
            when (val result = account.authorized { api, auth -> api.push(auth, body) }) {
                is ApiResult.Ok -> {
                    recognitions.forEach { history.markRecognitionSynced(it.id, it.updatedAt) }
                    favourites.forEach { history.markFavouriteSynced(it.trackKey, it.updatedAt) }
                    settingsToPush?.let { pushed ->
                        credentials.recordSync(status.cursor, clock(), settingsPushedAt = pushed.updatedAt)
                    }
                    settingsToPush = null
                }
                is ApiResult.HttpError -> return result.message
                is ApiResult.NetworkError -> return result.message
                ApiResult.NotConfigured -> return "No NanoTune server is configured."
            }
        }
        return null
    }

    private suspend fun pull(): String? {
        var cursor = credentials.currentSyncStatus().cursor
        repeat(MAX_PULL_PAGES) {
            val since = cursor
            when (val result = account.authorized { api, auth -> api.pull(auth, since) }) {
                is ApiResult.Ok -> {
                    val page = result.value
                    page.recognitions.forEach { history.applyRemoteRecognition(it) }
                    page.favourites.forEach { history.applyRemoteFavourite(it) }
                    var appliedRemoteSettingsAt: Long? = null
                    page.settings?.let { remote ->
                        if (remote.updatedAt > settings.current().syncedSettingsUpdatedAt) {
                            settings.applyRemote(remote.data, remote.updatedAt)
                            // These values came from the server, so there is nothing to push back.
                            appliedRemoteSettingsAt = remote.updatedAt
                        }
                    }
                    cursor = page.cursor
                    credentials.recordSync(cursor, clock(), settingsPushedAt = appliedRemoteSettingsAt)
                    if (!page.hasMore) return null
                }
                is ApiResult.HttpError -> return result.message
                is ApiResult.NetworkError -> return result.message
                ApiResult.NotConfigured -> return "No NanoTune server is configured."
            }
        }
        return null
    }

    private suspend fun fail(message: String): SyncOutcome {
        credentials.recordSyncError(message)
        return SyncOutcome.Failed(message)
    }

    private companion object {
        const val MAX_PUSH_ROUNDS = 20
        const val MAX_PULL_PAGES = 50
    }
}
