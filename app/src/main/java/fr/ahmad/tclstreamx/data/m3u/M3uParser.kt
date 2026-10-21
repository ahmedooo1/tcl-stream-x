package fr.ahmad.tclstreamx.data.m3u

import java.io.BufferedReader
import java.io.Reader
import java.security.MessageDigest
import java.text.Normalizer

/** One playable entry of an M3U/M3U8 playlist. */
data class M3uEntry(
    val name: String,
    val url: String,
    val logo: String?,
    val group: String,
    val tvgId: String?,
    val language: String?,
    val country: String?,
    val userAgent: String?,
    val referrer: String?,
    val drm: Boolean,
)

data class M3uHeader(val epgUrls: List<String>)

data class M3uStats(var entries: Int = 0, var skippedUnsupported: Int = 0, var skippedMalformed: Int = 0)

/**
 * Streaming parser: reads line by line and hands every entry to [onEntry], so a 100 MB playlist
 * never has to be held in memory. All text is treated as untrusted and length-capped.
 */
/** Mirrors data.db.ContentKind so the parser stays free of Android/Room types (unit tested on the JVM). */
object ContentKindCodes {
    const val LIVE = 0
    const val MOVIE = 1
    const val SERIES = 2
}

class M3uParser {

    fun parse(reader: Reader, onHeader: (M3uHeader) -> Unit = {}, onEntry: (M3uEntry) -> Unit): M3uStats {
        val stats = M3uStats()
        val br = if (reader is BufferedReader) reader else BufferedReader(reader, BUFFER)
        var pending: Pending? = null
        var first = true
        while (true) {
            var line = br.readLine() ?: break
            if (first) {
                line = line.removePrefix("﻿")
                first = false
            }
            line = line.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> onHeader(M3uHeader(headerEpg(line)))
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    if (pending != null) stats.skippedMalformed++
                    pending = parseExtinf(line)
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) ->
                    pending?.let { if (it.group.isEmpty()) it.group = cap(line.substring(8).trim(), MAX_GROUP) }
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> pending?.applyVlcOpt(line.substring(11))
                line.startsWith("#KODIPROP:", ignoreCase = true) -> pending?.applyKodiProp(line.substring(10))
                line.startsWith("#EXTHTTP:", ignoreCase = true) -> pending?.applyExtHttp(line.substring(9))
                line.startsWith("#") -> Unit // other directives are not needed
                else -> {
                    val p = pending ?: Pending(name = "")
                    pending = null
                    val entry = p.toEntry(line)
                    when {
                        entry == null -> stats.skippedMalformed++
                        !isSupportedScheme(entry.url) -> stats.skippedUnsupported++
                        else -> {
                            stats.entries++
                            onEntry(entry)
                        }
                    }
                }
            }
        }
        if (pending != null) stats.skippedMalformed++
        return stats
    }

    internal class Pending(
        var name: String,
        var attrs: Map<String, String> = emptyMap(),
        var group: String = "",
        var userAgent: String? = null,
        var referrer: String? = null,
        var drm: Boolean = false,
    ) {
        fun applyVlcOpt(opt: String) {
            val (k, v) = opt.split('=', limit = 2).let { if (it.size == 2) it[0].trim() to it[1].trim() else return }
            when (k.lowercase()) {
                "http-user-agent" -> userAgent = cap(v, MAX_HEADER)
                "http-referrer", "http-referer" -> referrer = cap(v, MAX_HEADER)
            }
        }

        fun applyKodiProp(prop: String) {
            val key = prop.substringBefore('=').trim().lowercase()
            if (key.endsWith("license_type") || key.endsWith("license_key") || key.endsWith("drm_legacy") || key.endsWith("drm")) {
                drm = true
            }
        }

        fun applyExtHttp(json: String) {
            HTTP_JSON_UA.find(json)?.let { userAgent = cap(it.groupValues[1], MAX_HEADER) }
            HTTP_JSON_REF.find(json)?.let { referrer = cap(it.groupValues[1], MAX_HEADER) }
        }

        fun toEntry(rawUrl: String): M3uEntry? {
            var url = rawUrl
            var ua = userAgent
            var ref = referrer
            // Kodi style: url|User-Agent=...&Referer=...
            val pipe = url.indexOf('|')
            if (pipe > 0) {
                url.substring(pipe + 1).split('&').forEach { kv ->
                    val k = kv.substringBefore('=').trim().lowercase()
                    val v = kv.substringAfter('=', "").trim()
                    when (k) {
                        "user-agent" -> ua = cap(v, MAX_HEADER)
                        "referer", "referrer" -> ref = cap(v, MAX_HEADER)
                    }
                }
                url = url.substring(0, pipe)
            }
            if (url.length > MAX_URL || url.any { it.isWhitespace() }) return null
            val title = name.ifBlank { attrs["tvg-name"].orEmpty() }.ifBlank { url.substringAfterLast('/').substringBefore('?') }
            return M3uEntry(
                name = cap(title.trim(), MAX_NAME),
                url = url,
                logo = attrs["tvg-logo"]?.takeIf { it.startsWith("http") }?.let { cap(it, MAX_URL) },
                group = group.ifEmpty { cap(attrs["group-title"].orEmpty().trim(), MAX_GROUP) },
                tvgId = attrs["tvg-id"]?.trim()?.takeIf { it.isNotEmpty() }?.let { cap(it, MAX_ID) },
                language = attrs["tvg-language"]?.let { cap(it, MAX_ID) },
                country = attrs["tvg-country"]?.let { cap(it, MAX_ID) },
                userAgent = ua ?: attrs["user-agent"]?.let { cap(it, MAX_HEADER) },
                referrer = ref ?: attrs["referrer"]?.let { cap(it, MAX_HEADER) },
                drm = drm,
            )
        }
    }

    companion object {
        private const val BUFFER = 64 * 1024
        const val MAX_NAME = 200
        const val MAX_GROUP = 120
        const val MAX_URL = 4096
        const val MAX_HEADER = 512
        const val MAX_ID = 120
        private val HTTP_JSON_UA = Regex("\"user-agent\"\\s*:\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
        private val HTTP_JSON_REF = Regex("\"refere?r\"\\s*:\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)

        private fun cap(s: String, max: Int) = if (s.length <= max) s else s.substring(0, max)

        fun isSupportedScheme(url: String): Boolean {
            val lower = url.lowercase()
            return lower.startsWith("http://") || lower.startsWith("https://")
        }

        /** EXTINF line: `#EXTINF:-1 key="value" key2="a,b",Display name`. Commas inside quotes are not separators. */
        internal fun parseExtinf(line: String): Pending {
            val attrs = HashMap<String, String>()
            var i = line.indexOf(':').let { if (it < 0) line.length else it + 1 }
            // skip duration
            while (i < line.length && !line[i].isWhitespace() && line[i] != ',') i++
            var titleStart = -1
            while (i < line.length) {
                val c = line[i]
                if (c == ',') {
                    titleStart = i + 1
                    break
                }
                if (c.isWhitespace()) { i++; continue }
                val keyStart = i
                while (i < line.length && line[i] != '=' && line[i] != ',' && !line[i].isWhitespace()) i++
                val key = line.substring(keyStart, i).lowercase()
                if (i < line.length && line[i] == '=') {
                    i++
                    val value: String
                    if (i < line.length && (line[i] == '"' || line[i] == '\'')) {
                        val quote = line[i]
                        val end = line.indexOf(quote, i + 1).let { if (it < 0) line.length else it }
                        value = line.substring(i + 1, end)
                        i = (end + 1).coerceAtMost(line.length)
                    } else {
                        val vs = i
                        while (i < line.length && !line[i].isWhitespace() && line[i] != ',') i++
                        value = line.substring(vs, i)
                    }
                    if (key.isNotEmpty() && attrs.size < 32) attrs[key] = cap(value, MAX_URL)
                }
            }
            val title = if (titleStart in 0..line.length) line.substring(titleStart) else ""
            return Pending(name = cap(title.trim(), MAX_NAME), attrs = attrs)
        }

        internal fun headerEpg(line: String): List<String> {
            val attrs = parseExtinf("#EXTINF:0 " + line.substringAfter("#EXTM3U", "").trim() + ",").attrs
            return listOfNotNull(attrs["url-tvg"], attrs["x-tvg-url"], attrs["tvg-url"])
                .flatMap { it.split(',') }
                .map { it.trim() }
                .filter { it.startsWith("http://") || it.startsWith("https://") }
                .distinct()
        }

        private val EPISODE = Regex("""(?i)\bS\d{1,2}\s*[.\-_ ]?\s*E\d{1,4}\b|\b\d{1,2}x\d{2,3}\b|(?:الحلقة|حلقة)\s*\d+|\bEp(?:isode)?\.?\s*\d+\b""")
        private val VIDEO_FILE = Regex("""(?i)\.(mp4|mkv|avi|mov|m4v|webm|wmv|flv)$""")
        // Plural forms only: "Serie A" is a football channel group, not a series section.
        private val SERIES_WORDS = listOf("series", "séries", "tv show", "مسلسل", "مسلسلات", "حلقات")
        private val MOVIE_WORDS = listOf("vod", "film", "movie", "cinema", "cinéma", "افلام", "أفلام", "فيلم", "سينما")

        /**
         * Splits mixed IPTV playlists into sections. Signals, strongest first: Xtream URL paths
         * (/series/, /movie/), episode markers in the title (S01E02, 1x02, الحلقة 3), group names,
         * then a video-file extension. Anything else is a live channel.
         */
        fun classify(e: M3uEntry): Int {
            val path = e.url.substringBefore('?').lowercase()
            val group = e.group.lowercase()
            return when {
                "/series/" in path -> ContentKindCodes.SERIES
                "/movie/" in path || "/movies/" in path -> ContentKindCodes.MOVIE
                EPISODE.containsMatchIn(e.name) -> ContentKindCodes.SERIES
                SERIES_WORDS.any { it in group } -> ContentKindCodes.SERIES
                MOVIE_WORDS.any { it in group } -> ContentKindCodes.MOVIE
                VIDEO_FILE.containsMatchIn(path) -> ContentKindCodes.MOVIE
                else -> ContentKindCodes.LIVE
            }
        }

        /** "Breaking Bad S02 E05 - Title" -> "Breaking Bad": the show an episode belongs to. */
        fun seriesShow(name: String): String {
            val m = EPISODE.find(name) ?: return name.trim()
            val head = name.substring(0, m.range.first).trim().trimEnd('-', '|', ':', '.', '–', ' ')
            return head.ifBlank { name.trim() }
        }

        private val COMBINING = Regex("\\p{Mn}+")
        private val SPACES = Regex("\\s+")

        /** Lowercase, accents and Arabic diacritics removed: "Télé Liban" and "tele liban" match. */
        fun normalize(s: String): String =
            COMBINING.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "")
                .lowercase()
                .replace('ـ', ' ') // tatweel
                .replace(SPACES, " ")
                .trim()

        fun channelKey(tvgId: String?, name: String, group: String): String {
            val base = if (!tvgId.isNullOrBlank()) "t:${tvgId.lowercase()}|${normalize(name)}" else "n:${normalize(name)}|${normalize(group)}"
            val digest = MessageDigest.getInstance("SHA-1").digest(base.toByteArray())
            return digest.take(10).joinToString("") { "%02x".format(it) }
        }
    }
}
