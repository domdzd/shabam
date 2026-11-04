package app.nanotune.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [TrackEntity::class, RecognitionEntity::class, FavouriteEntity::class, ListeningSessionEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class NanoTuneDatabase : RoomDatabase() {
    abstract fun tracks(): TrackDao
    abstract fun recognitions(): RecognitionDao
    abstract fun favourites(): FavouriteDao
    abstract fun sessions(): SessionDao
    abstract fun maintenance(): MaintenanceDao

    companion object {
        const val NAME = "nanotune.db"

        fun create(context: Context): NanoTuneDatabase =
            Room.databaseBuilder(context.applicationContext, NanoTuneDatabase::class.java, NAME)
                // Schema changes must ship explicit migrations (schemas/ is exported for that).
                .build()
    }
}
