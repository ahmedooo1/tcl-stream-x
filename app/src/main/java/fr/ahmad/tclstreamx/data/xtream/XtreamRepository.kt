package fr.ahmad.tclstreamx.data.xtream

import fr.ahmad.tclstreamx.data.db.AppDatabase
import fr.ahmad.tclstreamx.data.db.PlaylistKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Server and login of one account, kept only in memory once decrypted. */
class XtreamLogin(val server: String, val credentials: XtreamCredentials) {
    override fun toString(): String = "XtreamLogin(***)"
}

/**
 * Access to the Xtream Codes accounts the user added: decrypts logins on demand, resolves
 * `xtream://` references into playable addresses and fetches series details (seasons, episodes)
 * lazily, so a library of tens of thousands of series is never expanded up front.
 */
class XtreamRepository(
    private val db: AppDatabase,
    private val client: XtreamClient,
    private val box: SecretBox,
) {
    private val logins = ConcurrentHashMap<Long, XtreamLogin>()
    private val seriesCache = object : LinkedHashMap<String, XtreamSeriesInfo>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, XtreamSeriesInfo>?) = size > SERIES_CACHE
    }

    suspend fun login(playlistId: Long): XtreamLogin? {
        logins[playlistId]?.let { return it }
        val p = db.playlists().byId(playlistId) ?: return null
        if (p.kind != PlaylistKind.XTREAM) return null
        val server = p.source ?: return null
        val sealed = p.secret ?: return null
        val creds = withContext(Dispatchers.Default) { box.open(sealed) }?.let { XtreamCredentials.decode(it) } ?: return null
        return XtreamLogin(server, creds).also { logins[playlistId] = it }
    }

    fun forget(playlistId: Long) {
        logins.remove(playlistId)
        synchronized(seriesCache) { seriesCache.keys.removeAll { it.startsWith("$playlistId/") } }
    }

    /** Returns [url] unchanged when it is not an internal reference; null when it cannot be resolved. */
    suspend fun resolve(url: String): String? {
        val parsed = XtreamUrls.parse(url) ?: return if (XtreamUrls.isInternal(url)) null else url
        val login = login(parsed.playlistId) ?: return null
        return XtreamUrls.resolve(parsed, login.server, login.credentials)
    }

    suspend fun seriesInfo(seriesUrl: String): XtreamSeriesInfo {
        val parsed = XtreamUrls.parse(seriesUrl)?.takeIf { it.ref == XtreamRef.SERIES } ?: throw XtreamException(XtreamError.FORMAT)
        val cacheKey = "${parsed.playlistId}/${parsed.id}"
        synchronized(seriesCache) { seriesCache[cacheKey] }?.let { return it }
        val login = login(parsed.playlistId) ?: throw XtreamException(XtreamError.AUTH)
        val info = withContext(Dispatchers.IO) { client.seriesInfo(login.server, login.credentials, parsed.id) }
        synchronized(seriesCache) { seriesCache[cacheKey] = info }
        return info
    }

    suspend fun movieInfo(movieUrl: String): XtreamMovieInfo? {
        val parsed = XtreamUrls.parse(movieUrl)?.takeIf { it.ref == XtreamRef.MOVIE } ?: return null
        val login = login(parsed.playlistId) ?: return null
        return withContext(Dispatchers.IO) { client.movieInfo(login.server, login.credentials, parsed.id) }
    }

    /** Reference of an episode of the series behind [seriesUrl]. */
    fun episodeRef(seriesUrl: String, episode: XtreamEpisode): String? {
        val parsed = XtreamUrls.parse(seriesUrl) ?: return null
        return XtreamUrls.ref(parsed.playlistId, XtreamRef.EPISODE, episode.id, episode.extension)
    }

    fun seal(c: XtreamCredentials): String = box.seal(c.encode())

    companion object {
        private const val SERIES_CACHE = 40

        fun isSeries(url: String): Boolean = XtreamUrls.parse(url)?.ref == XtreamRef.SERIES
    }
}
