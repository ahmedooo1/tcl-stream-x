package fr.ahmad.tclstreamx.data.xtream

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** Login of an Xtream Codes account. Never printed: toString() hides both fields. */
class XtreamCredentials(val username: String, val password: String) {
    override fun toString(): String = "XtreamCredentials(***)"

    fun encode(): String = username + SEPARATOR + password

    companion object {
        private const val SEPARATOR = '\u0000'

        fun decode(s: String): XtreamCredentials? {
            val i = s.indexOf(SEPARATOR)
            if (i <= 0 || i == s.length - 1) return null
            return XtreamCredentials(s.substring(0, i), s.substring(i + 1))
        }
    }
}

/** What an internal `xtream://` reference points to. */
enum class XtreamRef(val segment: String) {
    LIVE("live"), MOVIE("movie"), SERIES("series"), EPISODE("episode"), EPG("epg");

    companion object {
        fun of(segment: String): XtreamRef? = entries.firstOrNull { it.segment == segment }
    }
}

data class ParsedRef(val playlistId: Long, val ref: XtreamRef, val id: String, val extension: String?)

/**
 * URL helpers for the Xtream Codes API.
 *
 * Playable addresses of a panel embed the username and password in the path. They are never
 * stored: the database keeps `xtream://<playlist>/<type>/<id>.<ext>` references, turned into a
 * real address only in memory, at the moment the player or the guide needs it.
 */
object XtreamUrls {
    const val SCHEME = "xtream://"

    fun isInternal(url: String): Boolean = url.startsWith(SCHEME)

    fun ref(playlistId: Long, ref: XtreamRef, id: String, extension: String? = null): String =
        SCHEME + playlistId + "/" + ref.segment + "/" + id + (extension?.let { ".$it" } ?: "")

    fun parse(url: String): ParsedRef? {
        if (!isInternal(url)) return null
        val parts = url.removePrefix(SCHEME).split('/')
        if (parts.size != 3) return null
        val pid = parts[0].toLongOrNull() ?: return null
        val ref = XtreamRef.of(parts[1]) ?: return null
        val last = parts[2]
        val dot = last.lastIndexOf('.')
        val id = if (dot > 0) last.substring(0, dot) else last
        val ext = if (dot > 0) last.substring(dot + 1) else null
        if (id.isEmpty() || !id.all { it.isLetterOrDigit() || it == '-' || it == '_' }) return null
        if (ext != null && !ext.all { it.isLetterOrDigit() }) return null
        return ParsedRef(pid, ref, id, ext)
    }

    /**
     * Accepts what people paste: `host:port`, `http://host:port/`, the player_api.php address or
     * a full `get.php?...` link. Returns the base URL (scheme, host, port, optional path prefix) or null.
     */
    fun normalizeServer(input: String): String? {
        var s = input.trim()
        if (s.isEmpty() || s.any { it.isWhitespace() }) return null
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) {
            if ("://" in s) return null
            s = "http://$s"
        }
        val uri = runCatching { URI(s) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host ?: return null
        if (host.isEmpty()) return null
        var path = uri.rawPath.orEmpty()
        for (endpoint in listOf("player_api.php", "get.php", "xmltv.php", "panel_api.php")) {
            if (path.endsWith("/$endpoint")) path = path.removeSuffix(endpoint)
        }
        path = path.trimEnd('/')
        val port = if (uri.port > 0) ":${uri.port}" else ""
        return "$scheme://$host$port$path"
    }

    /** Server and login found in a pasted `get.php?username=…&password=…` link, if it is one. */
    fun fromPlaylistLink(link: String): Pair<String, XtreamCredentials>? {
        val uri = runCatching { URI(link.trim()) }.getOrNull() ?: return null
        val path = uri.rawPath.orEmpty()
        if (!path.endsWith("/get.php") && !path.endsWith("/player_api.php")) return null
        val query = uri.rawQuery ?: return null
        val params = query.split('&').mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else it.substring(0, i) to URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }.toMap()
        val user = params["username"]?.takeIf { it.isNotEmpty() } ?: return null
        val pass = params["password"]?.takeIf { it.isNotEmpty() } ?: return null
        val server = normalizeServer(link) ?: return null
        return server to XtreamCredentials(user, pass)
    }

    fun api(server: String, c: XtreamCredentials, action: String? = null, extra: Map<String, String> = emptyMap()): String = buildString {
        append(server).append("/player_api.php?username=").append(q(c.username)).append("&password=").append(q(c.password))
        if (action != null) append("&action=").append(q(action))
        extra.forEach { (k, v) -> append('&').append(q(k)).append('=').append(q(v)) }
    }

    fun epg(server: String, c: XtreamCredentials): String =
        "$server/xmltv.php?username=${q(c.username)}&password=${q(c.password)}"

    /** Real address of a playable reference; null for references that are not playable (a series). */
    fun resolve(parsed: ParsedRef, server: String, c: XtreamCredentials): String? {
        val auth = "/" + seg(c.username) + "/" + seg(c.password) + "/"
        val ext = parsed.extension?.let { ".$it" } ?: ""
        return when (parsed.ref) {
            XtreamRef.LIVE -> "$server/live$auth${parsed.id}$ext"
            XtreamRef.MOVIE -> "$server/movie$auth${parsed.id}$ext"
            XtreamRef.EPISODE -> "$server/series$auth${parsed.id}$ext"
            XtreamRef.EPG -> epg(server, c)
            XtreamRef.SERIES -> null
        }
    }

    private fun q(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** Path segment encoding: like a query value, but a space must be %20, not '+'. */
    private fun seg(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

/**
 * Removes logins from any text before it can reach a log, an error message or the screen:
 * `username=`/`password=` query values and the two path segments after /live/, /movie/,
 * /series/ or /timeshift/ in panel addresses.
 */
object Redactor {
    private val query = Regex("(?i)\\b(username|password|user|pass|token)=([^&\\s\"']*)")
    private val path = Regex("(?i)/(live|movie|series|timeshift)/[^/\\s\"']+/[^/\\s\"']+/")

    fun redact(text: String?): String {
        if (text == null) return ""
        return path.replace(query.replace(text) { m -> "${m.groupValues[1]}=***" }) { m -> "/${m.groupValues[1]}/***/***/" }
    }
}
