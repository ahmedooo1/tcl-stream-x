package fr.ahmad.tclstreamx.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
    version = 2,
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
                .addMigrations(MIGRATION_1_2)
                .build()

        /** v2: content kind (live / movie / series). Existing rows are live channels until re-imported. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE channels ADD COLUMN kind INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_channels_kind_groupTitle_position ON channels (kind, groupTitle, position)")
            }
        }
    }
}
