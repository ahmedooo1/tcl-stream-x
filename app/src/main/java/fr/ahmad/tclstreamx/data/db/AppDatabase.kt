package fr.ahmad.tclstreamx.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter
    fun kindToString(kind: PlaylistKind): String = kind.name

    @TypeConverter
    fun stringToKind(value: String): PlaylistKind = PlaylistKind.valueOf(value)
}

@Database(
    entities = [
        PlaylistEntity::class,
        ChannelEntity::class,
        FavoriteEntity::class,
        HistoryEntity::class,
        ProgrammeEntity::class,
        VodProgressEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playlists(): PlaylistDao
    abstract fun channels(): ChannelDao
    abstract fun favorites(): FavoriteDao
    abstract fun history(): HistoryDao
    abstract fun epg(): EpgDao
    abstract fun vodProgress(): VodProgressDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "tclstreamx.db")
                // Write-ahead logging lets the UI read while a large playlist is being imported.
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
