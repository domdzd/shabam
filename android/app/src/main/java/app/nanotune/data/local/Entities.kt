package app.nanotune.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tracks",
    indices = [Index(value = ["track_key"], unique = true), Index("title"), Index("artist")],
)
data class TrackEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "track_key") val trackKey: String,
    @ColumnInfo(name = "provider_track_id") val providerTrackId: String?,
    val title: String,
    val artist: String,
    val album: String?,
    @ColumnInfo(name = "release_date") val releaseDate: String?,
    @ColumnInfo(name = "artwork_url") val artworkUrl: String?,
    val isrc: String?,
    /** JSON array of genre names. */
    val genres: String?,
    val explicit: Boolean?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long?,
    @ColumnInfo(name = "external_urls_json") val externalUrlsJson: String?,
    /** Set when album/date/duration were filled in after recognition, e.g. `musicbrainz`. */
    @ColumnInfo(name = "enrichment_source") val enrichmentSource: String?,
    @ColumnInfo(name = "enriched_at") val enrichedAt: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** Only successful matches are stored; failed attempts are never persisted. */
@Entity(
    tableName = "recognitions",
    foreignKeys = [
        ForeignKey(entity = TrackEntity::class, parentColumns = ["id"], childColumns = ["track_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("track_id"), Index("recognized_at"), Index("session_id")],
)
data class RecognitionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "track_id") val trackId: String?,
    @ColumnInfo(name = "recognized_at") val recognizedAt: Long,
    @ColumnInfo(name = "provider_id") val providerId: String,
    @ColumnInfo(name = "session_id") val sessionId: String?,
    @ColumnInfo(name = "match_confidence") val matchConfidence: Float?,
    /** `MANUAL` or `SESSION`. */
    @ColumnInfo(name = "recognition_mode") val recognitionMode: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /** Tombstone for cloud sync; rows with a value are hidden everywhere. */
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
    @ColumnInfo(name = "pending_sync") val pendingSync: Boolean,
)

@Entity(
    tableName = "favourites",
    foreignKeys = [
        ForeignKey(entity = TrackEntity::class, parentColumns = ["id"], childColumns = ["track_id"], onDelete = ForeignKey.CASCADE),
    ],
)
data class FavouriteEntity(
    @PrimaryKey @ColumnInfo(name = "track_id") val trackId: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
    @ColumnInfo(name = "pending_sync") val pendingSync: Boolean,
)

@Entity(tableName = "listening_sessions", indices = [Index("started_at")])
data class ListeningSessionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "ended_at") val endedAt: Long?,
    @ColumnInfo(name = "track_count") val trackCount: Int,
    @ColumnInfo(name = "user_stopped") val userStopped: Boolean,
)

/** Flat projection of a recognition joined with its track and favourite state. */
data class HistoryRecord(
    @ColumnInfo(name = "recognition_id") val recognitionId: String,
    @ColumnInfo(name = "track_id") val trackId: String,
    @ColumnInfo(name = "recognized_at") val recognizedAt: Long,
    @ColumnInfo(name = "provider_id") val providerId: String,
    @ColumnInfo(name = "session_id") val sessionId: String?,
    @ColumnInfo(name = "match_confidence") val matchConfidence: Float?,
    @ColumnInfo(name = "recognition_mode") val recognitionMode: String,
    @ColumnInfo(name = "track_key") val trackKey: String,
    @ColumnInfo(name = "provider_track_id") val providerTrackId: String?,
    val title: String,
    val artist: String,
    val album: String?,
    @ColumnInfo(name = "release_date") val releaseDate: String?,
    @ColumnInfo(name = "artwork_url") val artworkUrl: String?,
    val isrc: String?,
    val genres: String?,
    val explicit: Boolean?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long?,
    @ColumnInfo(name = "external_urls_json") val externalUrlsJson: String?,
    @ColumnInfo(name = "enrichment_source") val enrichmentSource: String?,
    @ColumnInfo(name = "is_favourite") val isFavourite: Boolean,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
)

/** Favourite joined with the track fields needed for sync. */
data class FavouriteRecord(
    @ColumnInfo(name = "track_id") val trackId: String,
    @ColumnInfo(name = "track_key") val trackKey: String,
    val title: String,
    val artist: String,
    @ColumnInfo(name = "artwork_url") val artworkUrl: String?,
    @ColumnInfo(name = "provider_track_id") val providerTrackId: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
)
