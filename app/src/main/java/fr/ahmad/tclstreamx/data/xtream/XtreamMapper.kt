package fr.ahmad.tclstreamx.data.xtream

import fr.ahmad.tclstreamx.data.db.ChannelEntity
import fr.ahmad.tclstreamx.data.db.ContentKind
import fr.ahmad.tclstreamx.data.m3u.M3uParser
import fr.ahmad.tclstreamx.data.m3u.RegionTagger
import java.security.MessageDigest

/**
 * Turns panel entries into rows. Names and categories are taken as the panel sends them, whatever
 * they are about; only the address is rewritten into an `xtream://` reference without the login.
 */
class XtreamMapper(
    private val playlistId: Long,
    private val generation: Int,
    /** Host of the panel, part of the stable key so favorites survive a refresh. */
    private val serverHost: String,
    private val account: XtreamAccount,
) {
    fun map(section: XtreamSection, e: Map<String, Any?>, categories: Map<String, String>, position: Int): ChannelEntity? {
        val idKey = if (section == XtreamSection.SERIES) "series_id" else "stream_id"
        val id = e.text(idKey) ?: return null
        if (!id.all { it.isLetterOrDigit() }) return null
        val name = (e.text("name") ?: e.text("title") ?: return null).take(MAX_NAME)
        val categoryId = e.text("category_id") ?: (e["category_ids"] as? List<*>)?.firstOrNull()?.toString()
        val group = categoryId?.let { categories[it] }.orEmpty().take(MAX_NAME)
        val region = RegionTagger.tag(group)
        val (kind, url, backup, logo) = when (section) {
            XtreamSection.LIVE -> Quad(
                ContentKind.LIVE,
                XtreamUrls.ref(playlistId, XtreamRef.LIVE, id, account.liveExtension),
                account.liveFallbackExtension?.let { XtreamUrls.ref(playlistId, XtreamRef.LIVE, id, it) },
                e.text("stream_icon"),
            )
            XtreamSection.MOVIES -> Quad(
                ContentKind.MOVIE,
                XtreamUrls.ref(playlistId, XtreamRef.MOVIE, id, safeExtension(e.text("container_extension"), "mp4")),
                null,
                e.text("stream_icon") ?: e.text("cover"),
            )
            XtreamSection.SERIES -> Quad(
                ContentKind.SERIES,
                XtreamUrls.ref(playlistId, XtreamRef.SERIES, id),
                null,
                e.text("cover") ?: e.text("stream_icon"),
            )
        }
        return ChannelEntity(
            playlistId = playlistId,
            generation = generation,
            position = position,
            name = name,
            nameNorm = M3uParser.normalize(name),
            url = url,
            backupUrls = backup,
            logo = logo?.takeIf { it.startsWith("http://") || it.startsWith("https://") }?.take(MAX_URL),
            groupTitle = group,
            tvgId = if (section == XtreamSection.LIVE) e.text("epg_channel_id")?.take(MAX_NAME) else null,
            language = region.language,
            country = region.country,
            kind = kind,
            channelKey = key(section, id),
        )
    }

    fun key(section: XtreamSection, id: String): String = stableKey(serverHost, section.name, id)

    private data class Quad(val kind: Int, val url: String, val backup: String?, val logo: String?)

    companion object {
        private const val MAX_NAME = 300
        private const val MAX_URL = 2048

        fun safeExtension(raw: String?, fallback: String): String =
            raw?.lowercase()?.takeIf { it.isNotEmpty() && it.length <= 5 && it.all { c -> c.isLetterOrDigit() } } ?: fallback

        fun stableKey(host: String, section: String, id: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest("xt|$host|$section|$id".toByteArray())
            return "x" + digest.take(10).joinToString("") { "%02x".format(it) }
        }
    }
}
