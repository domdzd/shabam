package app.nanotune.data.repository

import androidx.room.withTransaction
import app.nanotune.data.local.FavouriteEntity
import app.nanotune.data.local.FavouriteRecord
import app.nanotune.data.local.HistoryRecord
import app.nanotune.data.local.ListeningSessionEntity
import app.nanotune.data.local.NanoTuneDatabase
import app.nanotune.data.local.RecognitionEntity
import app.nanotune.data.local.TrackEntity
import app.nanotune.data.remote.dto.SyncFavouriteDto
import app.nanotune.data.remote.dto.SyncRecognitionDto
import app.nanotune.data.sync.SyncMerge
import app.nanotune.domain.model.DateFilter
import app.nanotune.domain.model.HistoryEntry
import app.nanotune.domain.model.ListeningSessionSummary
import app.nanotune.domain.model.RecognitionMode
import app.nanotune.domain.model.RecognizedTrack
import app.nanotune.domain.model.TrackKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.UUID

data class SavedRecognition(val recognitionId: String, val trackId: String, val newTrack: Boolean)

data class TrackDetails(
    val trackId: String,
    val track: RecognizedTrack,
    val favourite: Boolean,
    val enrichmentSource: String?,
    val recognitions: List<HistoryEntry>,
)

/** Metadata added after recognition, with its source recorded for provenance. */
data class Enrichment(
    val album: String?,
    val releaseDate: String?,
    val durationMs: Long?,
    val artworkUrl: String?,
    val source: String,
)

class HistoryRepository(
    private val db: NanoTuneDatabase,
    private val json: Json,
    private val clock: () -> Long = System::currentTimeMillis,
    /** True while a signed-in account synchronises; deletions then leave tombstones. */
    private val syncActive: suspend () -> Boolean,
) {
    private val genresSerializer = ListSerializer(String.serializer())
    private val linksSerializer = MapSerializer(String.serializer(), String.serializer())

    fun observeHistory(query: String, filter: DateFilter, favouritesOnly: Boolean): Flow<List<HistoryEntry>> {
        val trimmed = query.trim()
        val since = filter.days?.let { clock() - it * 86_400_000L } ?: 0L
        return db.recognitions().observeHistory(trimmed, likePattern(trimmed), since, favouritesOnly)
            .map { rows -> rows.map { it.toEntry() } }
    }

    fun observeSessionEntries(sessionId: String): Flow<List<HistoryEntry>> =
        db.recognitions().observeSession(sessionId).map { rows -> rows.map { it.toEntry() } }

    fun observeCount(): Flow<Int> = db.recognitions().observeCount()

    fun observeTrack(trackId: String): Flow<TrackDetails?> = combine(
        db.tracks().observe(trackId),
        db.favourites().observeIsFavourite(trackId),
        db.recognitions().observeForTrack(trackId),
    ) { track, favourite, recognitions ->
        track?.let {
            TrackDetails(
                trackId = it.id,
                track = it.toDomain(),
                favourite = favourite,
                enrichmentSource = it.enrichmentSource,
                recognitions = recognitions.map { row -> row.toEntry() },
            )
        }
    }

    suspend fun track(trackId: String): TrackEntity? = db.tracks().byId(trackId)

    suspend fun saveRecognition(
        track: RecognizedTrack,
        providerId: String,
        mode: RecognitionMode,
        sessionId: String?,
        confidence: Float?,
        /** When the music was heard, if earlier than now (e.g. a saved recording identified later). */
        recognizedAt: Long? = null,
    ): SavedRecognition = db.withTransaction {
        val now = clock()
        val (trackId, isNew) = upsertTrack(track, now)
        val recognitionId = UUID.randomUUID().toString()
        db.recognitions().insert(
            RecognitionEntity(
                id = recognitionId,
                trackId = trackId,
                recognizedAt = recognizedAt ?: now,
                providerId = providerId,
                sessionId = sessionId,
                matchConfidence = confidence,
                recognitionMode = mode.name,
                updatedAt = now,
                deletedAt = null,
                pendingSync = true,
            ),
        )
        if (sessionId != null) db.sessions().incrementTrackCount(sessionId)
        SavedRecognition(recognitionId, trackId, isNew)
    }

    suspend fun setFavourite(trackId: String, favourite: Boolean) {
        val now = clock()
        val existing = db.favourites().byTrack(trackId)
        when {
            favourite -> db.favourites().upsert(
                FavouriteEntity(
                    trackId = trackId,
                    createdAt = existing?.takeIf { it.deletedAt == null }?.createdAt ?: now,
                    updatedAt = now,
                    deletedAt = null,
                    pendingSync = true,
                ),
            )
            existing == null -> Unit
            syncActive() -> db.favourites().upsert(existing.copy(updatedAt = now, deletedAt = now, pendingSync = true))
            else -> db.favourites().hardDelete(trackId)
        }
    }

    suspend fun deleteRecognition(recognitionId: String) {
        if (syncActive()) {
            db.recognitions().softDelete(recognitionId, clock())
        } else {
            db.recognitions().hardDelete(recognitionId)
            db.tracks().deleteOrphans()
        }
    }

    suspend fun clearHistory() {
        db.withTransaction {
            if (syncActive()) {
                db.recognitions().softDeleteAll(clock())
            } else {
                db.recognitions().hardDeleteAll()
                db.tracks().deleteOrphans()
            }
        }
    }

    suspend fun allEntries(): List<HistoryEntry> = db.recognitions().allActive().map { it.toEntry() }

    suspend fun applyEnrichment(trackId: String, enrichment: Enrichment) {
        val track = db.tracks().byId(trackId) ?: return
        db.tracks().update(
            track.copy(
                album = track.album ?: enrichment.album,
                releaseDate = track.releaseDate ?: enrichment.releaseDate,
                durationMs = track.durationMs ?: enrichment.durationMs,
                artworkUrl = track.artworkUrl ?: enrichment.artworkUrl,
                enrichmentSource = enrichment.source,
                enrichedAt = clock(),
                updatedAt = clock(),
            ),
        )
    }

    suspend fun markEnrichmentAttempted(trackId: String) {
        val track = db.tracks().byId(trackId) ?: return
        db.tracks().update(track.copy(enrichedAt = clock()))
    }

    // ----------------------------------------------------------------------------- sessions

    suspend fun startSession(): String {
        val id = UUID.randomUUID().toString()
        db.sessions().insert(ListeningSessionEntity(id, startedAt = clock(), endedAt = null, trackCount = 0, userStopped = false))
        return id
    }

    suspend fun endSession(id: String, userStopped: Boolean) = db.sessions().end(id, clock(), userStopped)

    suspend fun closeDanglingSessions(exceptId: String = "") = db.sessions().closeDangling(clock(), exceptId)

    fun observeSession(id: String): Flow<ListeningSessionSummary?> = db.sessions().observe(id).map { entity ->
        entity?.let { ListeningSessionSummary(it.id, it.startedAt, it.endedAt, it.trackCount, it.userStopped) }
    }

    // --------------------------------------------------------------------------------- sync

    suspend fun pendingRecognitions(limit: Int): List<SyncRecognitionDto> =
        db.recognitions().pendingSync(limit).map { it.toSyncDto() }

    suspend fun markRecognitionSynced(id: String, updatedAt: Long) = db.recognitions().markSynced(id, updatedAt)

    suspend fun pendingFavourites(limit: Int): List<SyncFavouriteDto> =
        db.favourites().pendingSync(limit).map { it.toSyncDto() }

    suspend fun markFavouriteSynced(trackKey: String, updatedAt: Long) {
        val track = db.tracks().byKey(trackKey) ?: return
        db.favourites().markSynced(track.id, updatedAt)
    }

    /** Applies a recognition from the server using last-writer-wins. */
    suspend fun applyRemoteRecognition(remote: SyncRecognitionDto) = db.withTransaction {
        val local = db.recognitions().byId(remote.id)
        if (!SyncMerge.incomingWins(local?.updatedAt, remote.updatedAt)) return@withTransaction
        if (local == null && remote.deleted) return@withTransaction
        val trackId = local?.trackId ?: upsertTrack(remote.toTrack(), remote.updatedAt, explicitKey = remote.trackKey).first
        db.recognitions().upsert(
            RecognitionEntity(
                id = remote.id,
                trackId = trackId,
                recognizedAt = remote.recognizedAt,
                providerId = remote.providerId,
                sessionId = remote.sessionId,
                matchConfidence = remote.confidence,
                recognitionMode = runCatching { RecognitionMode.valueOf(remote.mode.uppercase()) }.getOrDefault(RecognitionMode.MANUAL).name,
                updatedAt = remote.updatedAt,
                deletedAt = if (remote.deleted) remote.updatedAt else null,
                pendingSync = false,
            ),
        )
    }

    suspend fun applyRemoteFavourite(remote: SyncFavouriteDto) = db.withTransaction {
        val track = db.tracks().byKey(remote.trackKey)
        if (track == null && remote.deleted) return@withTransaction
        val trackId = track?.id ?: upsertTrack(
            RecognizedTrack(providerTrackId = remote.providerTrackId, title = remote.title, artist = remote.artist, artworkUrl = remote.artworkUrl),
            remote.updatedAt,
            explicitKey = remote.trackKey,
        ).first
        val local = db.favourites().byTrack(trackId)
        if (!SyncMerge.incomingWins(local?.updatedAt, remote.updatedAt)) return@withTransaction
        db.favourites().upsert(
            FavouriteEntity(
                trackId = trackId,
                createdAt = remote.createdAt,
                updatedAt = remote.updatedAt,
                deletedAt = if (remote.deleted) remote.updatedAt else null,
                pendingSync = false,
            ),
        )
    }

    suspend fun purgeTombstones() {
        db.withTransaction {
            db.recognitions().purgeSyncedTombstones()
            db.favourites().purgeSyncedTombstones()
            db.tracks().deleteOrphans()
        }
    }

    /** After signing in, upload everything that exists locally. */
    suspend fun markEverythingPendingSync() {
        db.maintenance().markAllRecognitionsPending()
        db.favourites().markAllPending()
    }

    suspend fun deleteAllLocalData() = withContext(Dispatchers.IO) {
        db.clearAllTables()
    }

    // ------------------------------------------------------------------------------ helpers

    private suspend fun upsertTrack(track: RecognizedTrack, now: Long, explicitKey: String? = null): Pair<String, Boolean> {
        val key = explicitKey ?: TrackKeys.identityKey(track)
        val existing = db.tracks().byKey(key)
        if (existing == null) {
            val id = UUID.randomUUID().toString()
            db.tracks().insert(
                TrackEntity(
                    id = id,
                    trackKey = key,
                    providerTrackId = track.providerTrackId,
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    releaseDate = track.releaseDate,
                    artworkUrl = track.artworkUrl,
                    isrc = track.isrc,
                    genres = track.genres.takeIf { it.isNotEmpty() }?.let { json.encodeToString(genresSerializer, it) },
                    explicit = track.explicit,
                    durationMs = track.durationMs,
                    externalUrlsJson = track.externalUrls.takeIf { it.isNotEmpty() }?.let { json.encodeToString(linksSerializer, it) },
                    enrichmentSource = null,
                    enrichedAt = null,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            return id to true
        }
        // Keep what we have, fill gaps with anything new the provider returned.
        val mergedLinks = parseLinks(existing.externalUrlsJson) + track.externalUrls
        val updated = existing.copy(
            providerTrackId = existing.providerTrackId ?: track.providerTrackId,
            album = existing.album ?: track.album,
            releaseDate = existing.releaseDate ?: track.releaseDate,
            artworkUrl = track.artworkUrl ?: existing.artworkUrl,
            isrc = existing.isrc ?: track.isrc,
            genres = existing.genres ?: track.genres.takeIf { it.isNotEmpty() }?.let { json.encodeToString(genresSerializer, it) },
            explicit = existing.explicit ?: track.explicit,
            durationMs = existing.durationMs ?: track.durationMs,
            externalUrlsJson = mergedLinks.takeIf { it.isNotEmpty() }?.let { json.encodeToString(linksSerializer, it) },
        )
        if (updated != existing) db.tracks().update(updated.copy(updatedAt = now))
        return existing.id to false
    }

    private fun parseGenres(raw: String?): List<String> =
        raw?.let { runCatching { json.decodeFromString(genresSerializer, it) }.getOrNull() } ?: emptyList()

    private fun parseLinks(raw: String?): Map<String, String> =
        raw?.let { runCatching { json.decodeFromString(linksSerializer, it) }.getOrNull() } ?: emptyMap()

    private fun TrackEntity.toDomain() = RecognizedTrack(
        providerTrackId = providerTrackId,
        title = title,
        artist = artist,
        album = album,
        releaseDate = releaseDate,
        artworkUrl = artworkUrl,
        isrc = isrc,
        genres = parseGenres(genres),
        explicit = explicit,
        durationMs = durationMs,
        externalUrls = parseLinks(externalUrlsJson),
    )

    private fun HistoryRecord.toEntry() = HistoryEntry(
        recognitionId = recognitionId,
        trackId = trackId,
        track = RecognizedTrack(
            providerTrackId = providerTrackId,
            title = title,
            artist = artist,
            album = album,
            releaseDate = releaseDate,
            artworkUrl = artworkUrl,
            isrc = isrc,
            genres = parseGenres(genres),
            explicit = explicit,
            durationMs = durationMs,
            externalUrls = parseLinks(externalUrlsJson),
        ),
        recognizedAt = recognizedAt,
        providerId = providerId,
        mode = runCatching { RecognitionMode.valueOf(recognitionMode) }.getOrDefault(RecognitionMode.MANUAL),
        sessionId = sessionId,
        confidence = matchConfidence,
        favourite = isFavourite,
        enrichmentSource = enrichmentSource,
    )

    private fun HistoryRecord.toSyncDto() = SyncRecognitionDto(
        id = recognitionId,
        trackKey = trackKey,
        title = title,
        artist = artist,
        album = album,
        releaseDate = releaseDate,
        artworkUrl = artworkUrl,
        isrc = isrc,
        providerTrackId = providerTrackId,
        genres = parseGenres(genres),
        externalUrls = parseLinks(externalUrlsJson),
        providerId = providerId,
        recognizedAt = recognizedAt,
        mode = recognitionMode.lowercase(),
        sessionId = sessionId,
        confidence = matchConfidence,
        updatedAt = updatedAt,
        deleted = deletedAt != null,
    )

    private fun FavouriteRecord.toSyncDto() = SyncFavouriteDto(
        trackKey = trackKey,
        title = title,
        artist = artist,
        artworkUrl = artworkUrl,
        providerTrackId = providerTrackId,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deleted = deletedAt != null,
    )

    private fun SyncRecognitionDto.toTrack() = RecognizedTrack(
        providerTrackId = providerTrackId,
        title = title,
        artist = artist,
        album = album,
        releaseDate = releaseDate,
        artworkUrl = artworkUrl,
        isrc = isrc,
        genres = genres,
        externalUrls = externalUrls,
    )

    private fun likePattern(query: String): String {
        val escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return "%$escaped%"
    }
}
