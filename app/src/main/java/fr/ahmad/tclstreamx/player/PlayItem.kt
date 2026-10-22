package fr.ahmad.tclstreamx.player

import fr.ahmad.tclstreamx.data.db.ChannelEntity
import fr.ahmad.tclstreamx.data.db.ContentKind
import fr.ahmad.tclstreamx.data.vod.Subtitle

/** Which list the player zaps through with channel up/down. */
data class Lineup(
    val playlistId: Long = 0,
    val group: String? = null,
    val favoritesOnly: Boolean = false,
    val kind: Int = ContentKind.LIVE,
    /** Country (upper case) or language (lower case) filter, null for all. */
    val region: String? = null,
)

data class PlayItem(
    val key: String,
    val title: String,
    val subtitle: String? = null,
    val urls: List<String>,
    val userAgent: String? = null,
    val referrer: String? = null,
    val isLive: Boolean,
    val drm: Boolean = false,
    val subtitles: List<Subtitle> = emptyList(),
    val poster: String? = null,
    val logo: String? = null,
    val tvgId: String? = null,
    val channel: ChannelEntity? = null,
    val lineup: Lineup? = null,
    /** Episodes that follow this one, played automatically at the end (series). */
    val upNext: List<PlayItem> = emptyList(),
) {
    companion object {
        fun fromChannel(c: ChannelEntity, lineup: Lineup?): PlayItem = PlayItem(
            key = c.channelKey,
            title = c.name,
            subtitle = c.groupTitle.ifBlank { null },
            urls = listOf(c.url) + c.backupUrls.orEmpty().lines().filter { it.isNotBlank() },
            userAgent = c.userAgent,
            referrer = c.referrer,
            isLive = c.kind == ContentKind.LIVE,
            drm = c.drm,
            logo = c.logo,
            tvgId = c.tvgId,
            channel = c,
            lineup = lineup ?: Lineup(playlistId = c.playlistId, kind = c.kind),
        )
    }
}

enum class PlaybackIssue(val retryable: Boolean) {
    NETWORK(true),
    TIMEOUT(true),
    FORBIDDEN(false),
    AUTH(false),
    NOT_FOUND(false),
    SERVER(true),
    FORMAT(false),
    DRM(false),
    DECODER(false),
    UNKNOWN(true),
}
