package app.nanotune.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

private const val HISTORY_SELECT = """
    SELECT r.id AS recognition_id, r.track_id AS track_id, r.recognized_at AS recognized_at,
           r.provider_id AS provider_id, r.session_id AS session_id, r.match_confidence AS match_confidence,
           r.recognition_mode AS recognition_mode, r.updated_at AS updated_at, r.deleted_at AS deleted_at,
           t.track_key AS track_key, t.provider_track_id AS provider_track_id, t.title AS title,
           t.artist AS artist, t.album AS album, t.release_date AS release_date, t.artwork_url AS artwork_url,
           t.isrc AS isrc, t.genres AS genres, t.explicit AS explicit, t.duration_ms AS duration_ms,
           t.external_urls_json AS external_urls_json, t.enrichment_source AS enrichment_source,
           EXISTS(SELECT 1 FROM favourites f WHERE f.track_id = t.id AND f.deleted_at IS NULL) AS is_favourite
    FROM recognitions r INNER JOIN tracks t ON t.id = r.track_id
"""

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks WHERE track_key = :trackKey LIMIT 1")
    suspend fun byKey(trackKey: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE id = :id LIMIT 1")
    fun observe(id: String): Flow<TrackEntity?>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(track: TrackEntity)

    @Update
    suspend fun update(track: TrackEntity)

    @Query(
        """
        DELETE FROM tracks WHERE id NOT IN (SELECT track_id FROM recognitions WHERE track_id IS NOT NULL)
          AND id NOT IN (SELECT track_id FROM favourites)
        """,
    )
    suspend fun deleteOrphans(): Int
}

@Dao
interface RecognitionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(recognition: RecognitionEntity)

    @Upsert
    suspend fun upsert(recognition: RecognitionEntity)

    @Query("SELECT * FROM recognitions WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): RecognitionEntity?

    @Query(
        HISTORY_SELECT + """
        WHERE r.deleted_at IS NULL
          AND r.recognized_at >= :sinceEpochMs
          AND (:query = '' OR t.title LIKE :pattern ESCAPE '\' OR t.artist LIKE :pattern ESCAPE '\'
               OR t.album LIKE :pattern ESCAPE '\')
          AND (:favouritesOnly = 0 OR EXISTS(SELECT 1 FROM favourites f2 WHERE f2.track_id = t.id AND f2.deleted_at IS NULL))
        ORDER BY r.recognized_at DESC
        """,
    )
    fun observeHistory(query: String, pattern: String, sinceEpochMs: Long, favouritesOnly: Boolean): Flow<List<HistoryRecord>>

    @Query(HISTORY_SELECT + " WHERE r.deleted_at IS NULL AND r.session_id = :sessionId ORDER BY r.recognized_at DESC")
    fun observeSession(sessionId: String): Flow<List<HistoryRecord>>

    @Query(HISTORY_SELECT + " WHERE r.deleted_at IS NULL AND r.track_id = :trackId ORDER BY r.recognized_at DESC")
    fun observeForTrack(trackId: String): Flow<List<HistoryRecord>>

    @Query(HISTORY_SELECT + " WHERE r.deleted_at IS NULL ORDER BY r.recognized_at DESC")
    suspend fun allActive(): List<HistoryRecord>

    @Query(HISTORY_SELECT + " WHERE r.pending_sync = 1 ORDER BY r.updated_at ASC LIMIT :limit")
    suspend fun pendingSync(limit: Int): List<HistoryRecord>

    @Query("UPDATE recognitions SET pending_sync = 0 WHERE id = :id AND updated_at = :updatedAt")
    suspend fun markSynced(id: String, updatedAt: Long)

    @Query("UPDATE recognitions SET deleted_at = :now, updated_at = :now, pending_sync = 1 WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("UPDATE recognitions SET deleted_at = :now, updated_at = :now, pending_sync = 1 WHERE deleted_at IS NULL")
    suspend fun softDeleteAll(now: Long)

    @Query("DELETE FROM recognitions WHERE id = :id")
    suspend fun hardDelete(id: String)

    @Query("DELETE FROM recognitions")
    suspend fun hardDeleteAll()

    @Query("DELETE FROM recognitions WHERE deleted_at IS NOT NULL AND pending_sync = 0")
    suspend fun purgeSyncedTombstones(): Int

    @Query("DELETE FROM recognitions WHERE deleted_at IS NOT NULL")
    suspend fun purgeAllTombstones(): Int

    @Query("SELECT COUNT(*) FROM recognitions WHERE deleted_at IS NULL")
    fun observeCount(): Flow<Int>
}

@Dao
interface FavouriteDao {
    @Query("SELECT * FROM favourites WHERE track_id = :trackId LIMIT 1")
    suspend fun byTrack(trackId: String): FavouriteEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM favourites WHERE track_id = :trackId AND deleted_at IS NULL)")
    fun observeIsFavourite(trackId: String): Flow<Boolean>

    @Upsert
    suspend fun upsert(favourite: FavouriteEntity)

    @Query("DELETE FROM favourites WHERE track_id = :trackId")
    suspend fun hardDelete(trackId: String)

    @Query(
        """
        SELECT f.track_id AS track_id, t.track_key AS track_key, t.title AS title, t.artist AS artist,
               t.artwork_url AS artwork_url, t.provider_track_id AS provider_track_id,
               f.created_at AS created_at, f.updated_at AS updated_at, f.deleted_at AS deleted_at
        FROM favourites f INNER JOIN tracks t ON t.id = f.track_id
        WHERE f.pending_sync = 1 ORDER BY f.updated_at ASC LIMIT :limit
        """,
    )
    suspend fun pendingSync(limit: Int): List<FavouriteRecord>

    @Query("UPDATE favourites SET pending_sync = 0 WHERE track_id = :trackId AND updated_at = :updatedAt")
    suspend fun markSynced(trackId: String, updatedAt: Long)

    @Query("DELETE FROM favourites WHERE deleted_at IS NOT NULL AND pending_sync = 0")
    suspend fun purgeSyncedTombstones(): Int

    @Query("DELETE FROM favourites WHERE deleted_at IS NOT NULL")
    suspend fun purgeAllTombstones(): Int

    @Query("UPDATE favourites SET pending_sync = 1")
    suspend fun markAllPending()
}

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(session: ListeningSessionEntity)

    @Query("SELECT * FROM listening_sessions WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): ListeningSessionEntity?

    @Query("SELECT * FROM listening_sessions WHERE id = :id LIMIT 1")
    fun observe(id: String): Flow<ListeningSessionEntity?>

    @Query("UPDATE listening_sessions SET track_count = track_count + 1 WHERE id = :id")
    suspend fun incrementTrackCount(id: String)

    @Query("UPDATE listening_sessions SET ended_at = :endedAt, user_stopped = :userStopped WHERE id = :id AND ended_at IS NULL")
    suspend fun end(id: String, endedAt: Long, userStopped: Boolean)

    /** Closes sessions left open by process death, a crash or a revoked permission. */
    @Query("UPDATE listening_sessions SET ended_at = :now, user_stopped = 0 WHERE ended_at IS NULL AND id != :exceptId")
    suspend fun closeDangling(now: Long, exceptId: String): Int

    @Query("SELECT * FROM listening_sessions ORDER BY started_at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ListeningSessionEntity>>
}

@Dao
interface MaintenanceDao {
    @Query("UPDATE recognitions SET pending_sync = 1")
    suspend fun markAllRecognitionsPending()
}
