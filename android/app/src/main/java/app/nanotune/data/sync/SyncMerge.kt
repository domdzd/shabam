package app.nanotune.data.sync

/**
 * Last-writer-wins rules shared by push and pull. The backend applies the same rule
 * (backend/app/sync.py), so a record converges to the most recently updated copy everywhere.
 */
object SyncMerge {
    /** True when an incoming copy updated at [incomingUpdatedAt] should replace the local copy. */
    fun incomingWins(localUpdatedAt: Long?, incomingUpdatedAt: Long): Boolean =
        localUpdatedAt == null || incomingUpdatedAt > localUpdatedAt

    /** Splits [items] into batches the server accepts in one request. */
    fun <T> batches(items: List<T>, maxBatch: Int = MAX_BATCH): List<List<T>> {
        require(maxBatch > 0)
        return items.chunked(maxBatch)
    }

    const val MAX_BATCH = 500

    /** Settings keys that are synchronised; device-specific ones (server URL, auth) never are. */
    val SYNCED_SETTING_KEYS = setOf(
        "theme",
        "manual_listen_seconds",
        "session_mode",
        "session_interval_seconds",
        "duplicate_cooldown_minutes",
        "enrichment_enabled",
        "enrichment_wifi_only",
    )
}
