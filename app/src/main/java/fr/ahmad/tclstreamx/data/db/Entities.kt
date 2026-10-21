package fr.ahmad.tclstreamx.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class PlaylistKind { BUILTIN, URL, FILE, LAN, XTREAM }

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: PlaylistKind,
    /** http(s) URL, content:// URI or null for the built-in catalog. */
    val source: String?,
    /** XMLTV guide announced by the playlist header (url-tvg / x-tvg-url) or set by the user. */
    val epgUrl: String? = null,
    /** Channels belong to a generation; a re-import becomes visible atomically when this is bumped. */
    val activeGeneration: Int = 0,
    val channelCount: Int = 0,
    val updatedAt: Long = 0,
    val lastError: String? = null,
    /** For the built-in catalog: generatedAt of the bundled asset, to refresh after an app update. */
    val sourceVersion: Long = 0,
    /** Xtream Codes login, encrypted with a Keystore key (see SecretBox). Never stored in clear. */
    val secret: String? = null,
    /** Xtream Codes account state without secrets: status|expiry|max connections|trial. */
    val accountInfo: String? = null,
)

@Entity(
    tableName = "channels",
    indices = [
        Index(value = ["playlistId", "generation", "position"]),
        Index(value = ["playlistId", "generation", "groupTitle", "position"]),
        Index(value = ["kind", "groupTitle", "position"]),
        Index(value = ["kind", "country"]),
        Index(value = ["kind", "language"]),
        Index(value = ["nameNorm"]),
        Index(value = ["channelKey"]),
        Index(value = ["tvgId"]),
    ],
)
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val generation: Int,
    /** 1-based order inside the playlist, also used as the channel number. */
    val position: Int,
    val name: String,
    val nameNorm: String,
    val url: String,
    /** Extra URLs for the same channel, tried in order when the main one fails (newline separated). */
    val backupUrls: String? = null,
    val logo: String? = null,
    val groupTitle: String = "",
    /** Built-in catalog only: news, sports, entertainment. */
    val category: String? = null,
    val tvgId: String? = null,
    val language: String? = null,
    val country: String? = null,
    val userAgent: String? = null,
    val referrer: String? = null,
    /** The playlist declares a DRM licence (KODIPROP); such channels are flagged, never played. */
    val drm: Boolean = false,
    /** Section of the app the entry belongs to: [ContentKind.LIVE], [ContentKind.MOVIE] or [ContentKind.SERIES]. */
    @ColumnInfo(defaultValue = "0") val kind: Int = ContentKind.LIVE,
    /** Stable identity across re-imports, used by favorites and history. */
    val channelKey: String,
    val owner: String? = null,
    val geo: String? = null,
    @ColumnInfo(defaultValue = "0") val status: Int = StreamStatus.UNKNOWN.code,
    val statusDetail: String? = null,
    @ColumnInfo(defaultValue = "0") val statusCheckedAt: Long = 0,
)

/** Top-level sections: playlists mixing live channels, films and series are split on import. */
object ContentKind {
    const val LIVE = 0
    const val MOVIE = 1
    const val SERIES = 2
}

/** Result of the last availability probe of a channel. */
enum class StreamStatus(val code: Int) {
    UNKNOWN(0), ONLINE(1), OFFLINE(2), FORBIDDEN(3), AUTH_REQUIRED(4), DRM(5), TIMEOUT(6), INVALID(7);

    companion object {
        fun of(code: Int): StreamStatus = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val channelKey: String,
    val addedAt: Long,
)

@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey val channelKey: String,
    val watchedAt: Long,
)

@Entity(
    tableName = "programmes",
    indices = [Index(value = ["channelTvgId", "start"]), Index(value = ["stop"])],
)
data class ProgrammeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channelTvgId: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String? = null,
)

/** Resume points for movies, episodes and local files. */
@Entity(tableName = "vod_progress")
data class VodProgressEntity(
    @PrimaryKey val key: String,
    val title: String,
    val uri: String,
    val poster: String?,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
)

/** A country (upper case ISO code) or a language (lower case ISO code) with its number of entries. */
data class RegionCount(
    val code: String,
    val count: Int,
)

data class GroupCount(
    val groupTitle: String,
    val count: Int,
)
