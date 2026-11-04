package app.nanotune.domain.dedup

import app.nanotune.domain.model.RecognizedTrack
import app.nanotune.domain.model.TrackKeys

/**
 * Suppresses repeated matches of the same song during a listening session.
 *
 * ShazamKit's streaming session reports the same match many times while a song plays, so the
 * window is *sliding*: every sighting refreshes it. A song that keeps playing is therefore stored
 * once, and the same song is stored again only after it has not been heard for [cooldownMs].
 *
 * Times must come from a monotonic clock (e.g. `SystemClock.elapsedRealtime()`).
 * Not thread-safe; confine to one coroutine.
 */
class DuplicateFilter(cooldownMs: Long) {
    var cooldownMs: Long = cooldownMs
        set(value) {
            require(value >= 0) { "cooldown must be >= 0" }
            field = value
        }

    private val lastSeen = HashMap<String, Long>()

    /**
     * Registers a sighting of [track] at [nowMs].
     * @return true when the track should be recorded, false when it is a duplicate.
     */
    fun register(track: RecognizedTrack, nowMs: Long): Boolean {
        prune(nowMs)
        val keys = keysOf(track)
        val duplicate = keys.any { key ->
            val seen = lastSeen[key]
            seen != null && nowMs - seen in 0 until cooldownMs
        }
        keys.forEach { lastSeen[it] = nowMs }
        return !duplicate
    }

    fun reset() = lastSeen.clear()

    private fun keysOf(track: RecognizedTrack): List<String> =
        listOf(TrackKeys.identityKey(track), TrackKeys.similarityKey(track)).distinct()

    private fun prune(nowMs: Long) {
        val iterator = lastSeen.entries.iterator()
        while (iterator.hasNext()) {
            val age = nowMs - iterator.next().value
            // Negative ages mean the clock moved backwards; drop those entries too.
            if (age >= cooldownMs || age < 0) iterator.remove()
        }
    }

    companion object {
        const val DEFAULT_COOLDOWN_MS: Long = 4 * 60_000L

        /**
         * In interval mode a long song can be heard again on the next listening window, so the
         * cooldown must at least span one full interval plus the listening window.
         */
        fun effectiveCooldownMs(configuredMs: Long, intervalMs: Long?, windowMs: Long): Long =
            if (intervalMs == null) configuredMs else maxOf(configuredMs, intervalMs + windowMs * 2)
    }
}
