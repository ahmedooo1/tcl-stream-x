package fr.ahmad.tclstreamx.data.xtream

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStreamReader

enum class XtreamError { NETWORK, HTTP, AUTH, INACTIVE, EXPIRED, FORMAT }

/** Error whose message never contains the login (built from codes, not from URLs). */
class XtreamException(val error: XtreamError, val httpCode: Int = 0) : IOException("xtream ${error.name}${if (httpCode > 0) " HTTP $httpCode" else ""}")

/** Account state as reported by the panel. Contains no secret. */
data class XtreamAccount(
    val status: String,
    /** Expiry in epoch seconds, null when the account does not expire. */
    val expiresAt: Long?,
    val maxConnections: Int?,
    val activeConnections: Int?,
    val isTrial: Boolean,
    val outputFormats: List<String>,
) {
    /** Live stream container preferred by the player: HLS when the account allows it, MPEG-TS otherwise. */
    val liveExtension: String get() = if (outputFormats.isEmpty() || "m3u8" in outputFormats) "m3u8" else "ts"
    val liveFallbackExtension: String? get() = if (liveExtension == "m3u8" && (outputFormats.isEmpty() || "ts" in outputFormats)) "ts" else null

    /** Compact, secret-free summary stored with the playlist: status|expiry|max|trial. */
    fun summary(): String = listOf(status, expiresAt?.toString().orEmpty(), maxConnections?.toString().orEmpty(), if (isTrial) "1" else "0").joinToString("|")

    companion object {
        fun parseSummary(s: String?): XtreamAccount? {
            val p = s?.split('|') ?: return null
            if (p.size < 4) return null
            return XtreamAccount(p[0], p[1].toLongOrNull(), p[2].toIntOrNull(), null, p[3] == "1", emptyList())
        }
    }
}

enum class XtreamSection(val categoriesAction: String, val listAction: String) {
    LIVE("get_live_categories", "get_live_streams"),
    MOVIES("get_vod_categories", "get_vod_streams"),
    SERIES("get_series_categories", "get_series"),
}

data class XtreamEpisode(
    val id: String,
    val season: Int,
    val number: Int,
    val title: String,
    val extension: String,
    val plot: String?,
    val durationSecs: Long?,
    val image: String?,
)

data class XtreamSeriesInfo(
    val name: String?,
    val plot: String?,
    val cover: String?,
    val genre: String?,
    val releaseDate: String?,
    val rating: String?,
    /** Season numbers in order, with their display names when the panel provides them. */
    val seasons: List<Pair<Int, String?>>,
    val episodes: List<XtreamEpisode>,
)

/** One programme of a channel's guide as the panel gives it, with replay availability. */
data class XtreamProgramme(
    val title: String,
    val description: String?,
    /** Start in the panel's own time zone, "yyyy-MM-dd HH:mm:ss" (what the replay address needs). */
    val startLocal: String,
    val start: Long,
    val stop: Long,
    val hasArchive: Boolean,
)

data class XtreamMovieInfo(val plot: String?, val genre: String?, val releaseDate: String?, val durationSecs: Long?, val rating: String?, val cast: String?, val director: String?)

/**
 * Client of the Xtream Codes `player_api.php` interface.
 *
 * Lists are streamed entry by entry (see [JsonStream]); nothing here logs or embeds the request URL
 * in exceptions, because the login is part of every request.
 */
class XtreamClient(private val http: OkHttpClient, private val userAgent: String = DEFAULT_UA) {

    fun authenticate(server: String, c: XtreamCredentials, nowSeconds: Long = System.currentTimeMillis() / 1000): XtreamAccount {
        val root = get(XtreamUrls.api(server, c)) { JsonStream(it).readRoot() }
        @Suppress("UNCHECKED_CAST")
        val obj = root as? Map<String, Any?> ?: throw XtreamException(XtreamError.FORMAT)
        val user = obj.obj("user_info") ?: throw XtreamException(XtreamError.AUTH)
        if (user.text("auth") == "0") throw XtreamException(XtreamError.AUTH)
        val status = user.text("status") ?: "Active"
        val exp = user.long("exp_date")?.takeIf { it > 0 }
        val formats = (user["allowed_output_formats"] as? List<*>)?.mapNotNull { (it as? String)?.lowercase() }.orEmpty()
        val account = XtreamAccount(
            status = status,
            expiresAt = exp,
            maxConnections = user.long("max_connections")?.toInt(),
            activeConnections = user.long("active_cons")?.toInt(),
            isTrial = user.text("is_trial") == "1",
            outputFormats = formats,
        )
        if (!status.equals("Active", ignoreCase = true)) {
            throw XtreamException(if (status.equals("Expired", true)) XtreamError.EXPIRED else XtreamError.INACTIVE)
        }
        if (exp != null && exp < nowSeconds) throw XtreamException(XtreamError.EXPIRED)
        return account
    }

    /** category_id → category name. */
    fun categories(server: String, c: XtreamCredentials, section: XtreamSection): Map<String, String> {
        val out = HashMap<String, String>()
        get(XtreamUrls.api(server, c, section.categoriesAction)) { reader ->
            JsonStream(reader).forEachElement { e ->
                val id = e.text("category_id") ?: return@forEachElement
                out[id] = e.text("category_name") ?: ""
            }
        }
        return out
    }

    /** Streams every entry of a section to [onEntry], one parsed object at a time. */
    fun list(server: String, c: XtreamCredentials, section: XtreamSection, onEntry: (Map<String, Any?>) -> Unit) {
        get(XtreamUrls.api(server, c, section.listAction)) { reader -> JsonStream(reader).forEachElement(onEntry) }
    }

    fun seriesInfo(server: String, c: XtreamCredentials, seriesId: String): XtreamSeriesInfo {
        val root = get(XtreamUrls.api(server, c, "get_series_info", mapOf("series_id" to seriesId))) { JsonStream(it).readRoot() }
        @Suppress("UNCHECKED_CAST")
        val obj = root as? Map<String, Any?> ?: throw XtreamException(XtreamError.FORMAT)
        return parseSeriesInfo(obj)
    }

    /** Full guide of one channel, past programmes included (titles are base64 in this API). */
    fun epgTable(server: String, c: XtreamCredentials, streamId: String): List<XtreamProgramme> {
        val root = get(XtreamUrls.api(server, c, "get_simple_data_table", mapOf("stream_id" to streamId))) { JsonStream(it).readRoot() }
        @Suppress("UNCHECKED_CAST")
        val listings = ((root as? Map<String, Any?>)?.get("epg_listings") as? List<*>).orEmpty()
        return listings.mapNotNull { raw ->
            @Suppress("UNCHECKED_CAST")
            val e = raw as? Map<String, Any?> ?: return@mapNotNull null
            val start = e.long("start_timestamp") ?: return@mapNotNull null
            val stop = e.long("stop_timestamp") ?: return@mapNotNull null
            val startLocal = e.text("start") ?: return@mapNotNull null
            XtreamProgramme(
                title = decode64(e.text("title")) ?: return@mapNotNull null,
                description = decode64(e.text("description")),
                startLocal = startLocal,
                start = start * 1000,
                stop = stop * 1000,
                hasArchive = e.text("has_archive") == "1",
            )
        }.sortedBy { it.start }
    }

    fun movieInfo(server: String, c: XtreamCredentials, vodId: String): XtreamMovieInfo {
        val root = get(XtreamUrls.api(server, c, "get_vod_info", mapOf("vod_id" to vodId))) { JsonStream(it).readRoot() }
        @Suppress("UNCHECKED_CAST")
        val info = (root as? Map<String, Any?>)?.obj("info") ?: throw XtreamException(XtreamError.FORMAT)
        return XtreamMovieInfo(
            plot = info.text("plot") ?: info.text("description"),
            genre = info.text("genre"),
            releaseDate = info.text("releasedate") ?: info.text("release_date"),
            durationSecs = info.long("duration_secs"),
            rating = info.text("rating"),
            cast = info.text("cast") ?: info.text("actors"),
            director = info.text("director"),
        )
    }

    private fun <T> get(url: String, block: (java.io.Reader) -> T): T {
        val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw XtreamException(XtreamError.NETWORK)
        } catch (e: IllegalArgumentException) {
            throw XtreamException(XtreamError.NETWORK)
        }
        response.use { resp ->
            if (resp.code == 401 || resp.code == 403) throw XtreamException(XtreamError.AUTH, resp.code)
            if (!resp.isSuccessful) throw XtreamException(XtreamError.HTTP, resp.code)
            val body = resp.body ?: throw XtreamException(XtreamError.FORMAT)
            try {
                return block(InputStreamReader(body.byteStream(), Charsets.UTF_8))
            } catch (e: JsonFormatException) {
                throw XtreamException(XtreamError.FORMAT)
            } catch (e: XtreamException) {
                throw e
            } catch (e: IOException) {
                throw XtreamException(XtreamError.NETWORK)
            }
        }
    }

    companion object {
        const val DEFAULT_UA = "TCLStreamX/1.0 (Linux; Android TV)"

        fun decode64(s: String?): String? = s?.let {
            runCatching { String(java.util.Base64.getDecoder().decode(it.trim()), Charsets.UTF_8) }.getOrElse { _ -> it }
        }?.trim()?.ifEmpty { null }

        @Suppress("UNCHECKED_CAST")
        fun parseSeriesInfo(obj: Map<String, Any?>): XtreamSeriesInfo {
            val info = obj.obj("info")
            val seasonNames = HashMap<Int, String?>()
            (obj["seasons"] as? List<*>)?.forEach { s ->
                val m = s as? Map<String, Any?> ?: return@forEach
                val n = m.long("season_number")?.toInt() ?: return@forEach
                seasonNames[n] = m.text("name")
            }
            val episodes = ArrayList<XtreamEpisode>()
            fun addEpisode(seasonKey: Int?, raw: Any?) {
                val e = raw as? Map<String, Any?> ?: return
                val id = e.text("id") ?: return
                if (!id.all { it.isLetterOrDigit() }) return
                val season = e.long("season")?.toInt() ?: seasonKey ?: 1
                val einfo = e.obj("info")
                episodes += XtreamEpisode(
                    id = id,
                    season = season,
                    number = e.long("episode_num")?.toInt() ?: 0,
                    title = e.text("title") ?: "",
                    extension = e.text("container_extension")?.lowercase()?.takeIf { x -> x.all { it.isLetterOrDigit() } } ?: "mp4",
                    plot = einfo?.text("plot"),
                    durationSecs = einfo?.long("duration_secs"),
                    image = einfo?.text("movie_image") ?: einfo?.text("cover_big"),
                )
            }
            // "episodes" is usually {"1": [...], "2": [...]}, sometimes a flat or nested list.
            when (val eps = obj["episodes"]) {
                is Map<*, *> -> eps.forEach { (k, v) ->
                    val seasonKey = (k as? String)?.toIntOrNull()
                    (v as? List<*>)?.forEach { addEpisode(seasonKey, it) }
                }
                is List<*> -> eps.forEach { item ->
                    if (item is List<*>) item.forEach { addEpisode(null, it) } else addEpisode(null, item)
                }
                else -> Unit
            }
            episodes.sortWith(compareBy({ it.season }, { it.number }))
            val seasons = (episodes.map { it.season }.toSortedSet()).map { it to seasonNames[it] }
            return XtreamSeriesInfo(
                name = info?.text("name"),
                plot = info?.text("plot"),
                cover = info?.text("cover"),
                genre = info?.text("genre"),
                releaseDate = info?.text("releaseDate") ?: info?.text("release_date"),
                rating = info?.text("rating"),
                seasons = seasons,
                episodes = episodes,
            )
        }
    }
}
