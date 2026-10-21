package fr.ahmad.tclstreamx.data.check

import fr.ahmad.tclstreamx.data.db.StreamStatus
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownHostException

data class ProbeResult(val status: StreamStatus, val detail: String)

data class Variant(val uri: String, val bandwidth: Long, val height: Int)

data class ManifestInfo(
    val isHls: Boolean,
    val isDash: Boolean,
    val variants: List<Variant>,
    val segments: List<String>,
    val drm: Boolean,
)

/** Pure manifest inspection, unit tested without network. */
object ManifestInspector {
    private val BANDWIDTH = Regex("BANDWIDTH=(\\d+)")
    private val RESOLUTION = Regex("RESOLUTION=\\d+x(\\d+)")
    private val DRM_KEY = Regex(
        "#EXT-X-(?:SESSION-)?KEY:[^\\n]*(?:METHOD=SAMPLE-AES|KEYFORMAT=\"(?!identity\")[^\"]+\")",
        RegexOption.IGNORE_CASE,
    )

    fun inspect(text: String): ManifestInfo {
        val head = text.take(4096)
        if (head.contains("<MPD")) {
            val drm = text.contains("<ContentProtection", ignoreCase = true) &&
                (text.contains("edef8ba9", ignoreCase = true) || text.contains("9a04f079", ignoreCase = true) ||
                    text.contains("cenc:default_KID", ignoreCase = true))
            return ManifestInfo(isHls = false, isDash = true, variants = emptyList(), segments = emptyList(), drm = drm)
        }
        if (!head.trimStart('﻿', ' ', '\r', '\n').startsWith("#EXTM3U")) {
            return ManifestInfo(isHls = false, isDash = false, variants = emptyList(), segments = emptyList(), drm = false)
        }
        val variants = ArrayList<Variant>()
        val segments = ArrayList<String>()
        var pendingInf: String? = null
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            when {
                line.startsWith("#EXT-X-STREAM-INF") -> pendingInf = line
                line.startsWith("#") -> Unit
                pendingInf != null -> {
                    val inf = pendingInf!!
                    variants += Variant(
                        uri = line,
                        bandwidth = BANDWIDTH.find(inf)?.groupValues?.get(1)?.toLongOrNull() ?: 0,
                        height = RESOLUTION.find(inf)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                    )
                    pendingInf = null
                }
                else -> segments += line
            }
        }
        return ManifestInfo(isHls = true, isDash = false, variants = variants, segments = segments, drm = DRM_KEY.containsMatchIn(text))
    }

    fun statusForHttp(code: Int): StreamStatus = when (code) {
        401, 407 -> StreamStatus.AUTH_REQUIRED
        403, 451 -> StreamStatus.FORBIDDEN
        else -> StreamStatus.OFFLINE
    }
}

/**
 * Checks that a stream is really reachable: manifest, then (for HLS) one variant and one media
 * segment. It sends the same headers the player would and never tries to get around a refusal.
 */
class StreamProbe(private val http: OkHttpClient) {

    fun probe(url: String, userAgent: String?, referrer: String?): ProbeResult {
        return try {
            val manifest = get(url, userAgent, referrer, MAX_MANIFEST)
            if (manifest.code !in 200..299) {
                return ProbeResult(ManifestInspector.statusForHttp(manifest.code), "HTTP ${manifest.code}")
            }
            if (manifest.isMedia) {
                return ProbeResult(StreamStatus.ONLINE, "direct media (${manifest.contentType})")
            }
            val info = ManifestInspector.inspect(manifest.body)
            when {
                info.isDash -> return if (info.drm) ProbeResult(StreamStatus.DRM, "DASH protected by DRM")
                else ProbeResult(StreamStatus.ONLINE, "DASH manifest")
                !info.isHls -> return ProbeResult(StreamStatus.INVALID, "not a stream manifest")
                info.drm -> return ProbeResult(StreamStatus.DRM, "HLS protected by DRM")
            }
            var mediaUrl = manifest.finalUrl
            var media = info
            if (info.variants.isNotEmpty()) {
                val lightest = info.variants.minBy { if (it.bandwidth > 0) it.bandwidth else Long.MAX_VALUE }
                mediaUrl = resolve(manifest.finalUrl, lightest.uri) ?: return ProbeResult(StreamStatus.INVALID, "bad variant URI")
                val variant = get(mediaUrl, userAgent, referrer, MAX_MANIFEST)
                if (variant.code !in 200..299) {
                    return ProbeResult(ManifestInspector.statusForHttp(variant.code), "variant HTTP ${variant.code}")
                }
                mediaUrl = variant.finalUrl
                media = ManifestInspector.inspect(variant.body)
                if (media.drm) return ProbeResult(StreamStatus.DRM, "HLS protected by DRM")
            }
            val lastSegment = media.segments.lastOrNull() ?: return ProbeResult(StreamStatus.INVALID, "no segment")
            val segUrl = resolve(mediaUrl, lastSegment) ?: return ProbeResult(StreamStatus.INVALID, "bad segment URI")
            val seg = get(segUrl, userAgent, referrer, SEGMENT_BYTES, range = true)
            if (seg.code !in 200..299 || seg.bytes == 0) {
                ProbeResult(ManifestInspector.statusForHttp(seg.code), "segment HTTP ${seg.code}")
            } else {
                ProbeResult(StreamStatus.ONLINE, "segment ${seg.bytes} B")
            }
        } catch (e: InterruptedIOException) {
            ProbeResult(StreamStatus.TIMEOUT, "timeout")
        } catch (e: UnknownHostException) {
            ProbeResult(StreamStatus.OFFLINE, "unknown host")
        } catch (e: IOException) {
            ProbeResult(StreamStatus.OFFLINE, e.javaClass.simpleName)
        } catch (e: IllegalArgumentException) {
            ProbeResult(StreamStatus.INVALID, "invalid URL")
        }
    }

    private class Fetched(
        val code: Int,
        val finalUrl: String,
        val body: String,
        val bytes: Int,
        val contentType: String,
        val isMedia: Boolean,
    )

    private fun get(url: String, ua: String?, referrer: String?, limit: Int, range: Boolean = false): Fetched {
        val builder = Request.Builder().url(url).header("User-Agent", ua ?: DEFAULT_UA)
        if (!referrer.isNullOrBlank()) builder.header("Referer", referrer)
        if (range) builder.header("Range", "bytes=0-${limit - 1}")
        http.newCall(builder.build()).execute().use { resp ->
            val type = resp.header("Content-Type").orEmpty().lowercase()
            val path = resp.request.url.encodedPath.lowercase()
            val looksLikeManifest = type.contains("mpegurl") || type.contains("dash+xml") ||
                path.endsWith(".m3u8") || path.endsWith(".m3u") || path.endsWith(".mpd")
            val isMedia = !range && !looksLikeManifest &&
                (type.startsWith("video/") || type.startsWith("audio/") || type == "application/octet-stream")
            val buffer = ByteArray(limit)
            var read = 0
            val stream = resp.body?.byteStream()
            if (stream != null && resp.isSuccessful) {
                while (read < limit) {
                    val n = stream.read(buffer, read, limit - read)
                    if (n < 0) break
                    read += n
                    if (isMedia || range) break
                }
            }
            val text = if (isMedia || range) "" else String(buffer, 0, read, Charsets.UTF_8)
            return Fetched(resp.code, resp.request.url.toString(), text, read, type, isMedia)
        }
    }

    private fun resolve(base: String, ref: String): String? =
        base.toHttpUrlOrNull()?.resolve(ref)?.toString()

    companion object {
        const val DEFAULT_UA = "TCLStreamX/1.0 (Linux; Android TV)"
        private const val MAX_MANIFEST = 2 * 1024 * 1024
        private const val SEGMENT_BYTES = 64 * 1024
    }
}
