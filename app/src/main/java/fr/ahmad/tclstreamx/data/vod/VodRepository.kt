package fr.ahmad.tclstreamx.data.vod

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

enum class VodShelf { MOVIES, SERIES, ANIMATION }

/** Subsections of the public-domain shelves, as fixed Internet Archive search clauses. */
enum class Genre(val shelf: VodShelf, val clause: String?) {
    MOVIES_ALL(VodShelf.MOVIES, null),
    COMEDY(VodShelf.MOVIES, "subject:(comedy)"),
    DRAMA(VodShelf.MOVIES, "subject:(drama)"),
    HORROR(VodShelf.MOVIES, "subject:(horror)"),
    SCIFI(VodShelf.MOVIES, "subject:(\"science fiction\" OR sci-fi OR scifi)"),
    WESTERN(VodShelf.MOVIES, "subject:(western)"),
    CRIME(VodShelf.MOVIES, "subject:(\"film noir\" OR noir OR crime)"),
    WAR(VodShelf.MOVIES, "subject:(war)"),
    SILENT(VodShelf.MOVIES, "subject:(silent)"),
    SERIES_ALL(VodShelf.SERIES, null),
    SITCOM(VodShelf.SERIES, "subject:(comedy OR sitcom)"),
    MYSTERY(VodShelf.SERIES, "subject:(mystery OR detective OR crime)"),
    TV_WESTERN(VodShelf.SERIES, "subject:(western)"),
    TV_SCIFI(VodShelf.SERIES, "subject:(\"science fiction\" OR sci-fi OR scifi)"),
    ADVENTURE(VodShelf.SERIES, "subject:(adventure)"),
    KIDS(VodShelf.SERIES, "subject:(children OR kids)"),
    ANIMATION_ALL(VodShelf.ANIMATION, null),
    FLEISCHER(VodShelf.ANIMATION, "title:(popeye OR superman OR \"betty boop\")"),
    LOONEY(VodShelf.ANIMATION, "title:(looney OR merrie OR \"bugs bunny\" OR daffy OR porky)"),
    FELIX(VodShelf.ANIMATION, "title:(felix)");

    companion object {
        fun of(shelf: VodShelf) = entries.filter { it.shelf == shelf }
    }
}

/** A title in a list. [archiveId] is set for Internet Archive items, which are resolved on demand. */
data class VodItem(
    val key: String,
    val title: String,
    val year: String?,
    val poster: String?,
    val description: String?,
    val license: String?,
    val archiveId: String? = null,
    val directUrl: String? = null,
)

data class Subtitle(val url: String, val language: String?, val mimeType: String)

/** A playable file inside an item (a film, or one episode of a series item). */
data class VodFile(val title: String, val url: String, val durationSec: Double?, val subtitles: List<Subtitle>)

@Serializable
data class OpenMedia(val films: List<OpenFilm>, val services: List<FreeService>)

@Serializable
data class OpenFilm(
    val key: String,
    val title: String,
    val year: Int,
    val license: String,
    val kind: String,
    val url: String,
    val poster: String? = null,
)

@Serializable
data class FreeService(val name: String, val query: String, val lang: String)

data class LocalVideo(val id: Long, val title: String, val uri: String, val durationMs: Long, val folder: String?)

/**
 * Legal video sources only:
 *  - Internet Archive collections of public-domain / freely licensed films (official public API, no key)
 *  - openly licensed films bundled as a list (Blender Foundation films and sample streams)
 *  - the user's own files through MediaStore
 */
class VodRepository(private val context: Context, private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    val openMedia: OpenMedia by lazy {
        context.assets.open("open_media.json").use { json.decodeFromString(OpenMedia.serializer(), it.readBytes().decodeToString()) }
    }

    /**
     * [genre] is one of the fixed clauses of [Genre] (never user text); [query] is sanitized user text.
     */
    suspend fun archiveShelf(shelf: VodShelf, page: Int, query: String? = null, genre: Genre? = null): List<VodItem> = withContext(Dispatchers.IO) {
        val base = when (shelf) {
            VodShelf.MOVIES -> "collection:(feature_films) AND mediatype:(movies)"
            VodShelf.SERIES -> "collection:(classic_tv) AND mediatype:(movies)"
            VodShelf.ANIMATION -> "collection:(classic_cartoons) AND mediatype:(movies)"
        }
        val withGenre = genre?.clause?.let { "$base AND $it" } ?: base
        val q = if (query.isNullOrBlank()) withGenre else "$withGenre AND title:(${sanitize(query)})"
        val url = "https://archive.org/advancedsearch.php".toHttpUrl().newBuilder()
            .addQueryParameter("q", q)
            .addQueryParameter("fl[]", "identifier")
            .addQueryParameter("fl[]", "title")
            .addQueryParameter("fl[]", "year")
            .addQueryParameter("fl[]", "description")
            .addQueryParameter("fl[]", "licenseurl")
            .addQueryParameter("sort[]", "downloads desc")
            .addQueryParameter("rows", PAGE_SIZE.toString())
            .addQueryParameter("page", page.coerceAtLeast(1).toString())
            .addQueryParameter("output", "json")
            .build()
        val root = getJson(url.toString())
        val docs = ((root["response"] as? JsonObject)?.get("docs") as? JsonArray).orEmpty()
        docs.mapNotNull { d ->
            val o = d as? JsonObject ?: return@mapNotNull null
            val id = o.str("identifier") ?: return@mapNotNull null
            VodItem(
                key = "ia:$id",
                title = o.str("title") ?: id,
                year = o.str("year"),
                poster = "https://archive.org/services/img/$id",
                description = o.str("description")?.replace(HTML_TAG, " ")?.trim()?.take(600),
                license = o.str("licenseurl"),
                archiveId = id,
            )
        }
    }

    /** Lists the playable MP4 files of an Internet Archive item with their subtitle files. */
    suspend fun archiveFiles(identifier: String): List<VodFile> = withContext(Dispatchers.IO) {
        require(IDENTIFIER.matches(identifier)) { "bad identifier" }
        val root = getJson("https://archive.org/metadata/$identifier")
        val files = (root["files"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val subs = files.mapNotNull { f ->
            val name = f.str("name") ?: return@mapNotNull null
            val mime = when {
                name.endsWith(".srt", true) -> "application/x-subrip"
                name.endsWith(".vtt", true) -> "text/vtt"
                else -> return@mapNotNull null
            }
            Subtitle(downloadUrl(identifier, name), languageFromName(name), mime)
        }
        val videos = files.filter { f ->
            val name = f.str("name").orEmpty()
            name.endsWith(".mp4", true) && f.str("source") != "metadata"
        }
        // Prefer the derivative "h.264" or "MPEG4" encodes, one per original.
        videos.groupBy { (it.str("original") ?: it.str("name")).orEmpty().substringBeforeLast('.') }
            .values
            .mapNotNull { group -> group.minByOrNull { formatRank(it.str("format")) } }
            .sortedBy { it.str("name") }
            .map { f ->
                val name = f.str("name")!!
                VodFile(
                    title = f.str("title") ?: name.substringBeforeLast('.').replace('_', ' '),
                    url = downloadUrl(identifier, name),
                    durationSec = f.str("length")?.toDoubleOrNull(),
                    subtitles = subs,
                )
            }
    }

    suspend fun localVideos(): List<LocalVideo> = withContext(Dispatchers.IO) {
        val out = ArrayList<LocalVideo>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
        )
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, null, null,
            "${MediaStore.Video.Media.DATE_ADDED} DESC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val bucketCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            while (c.moveToNext() && out.size < MAX_LOCAL) {
                val id = c.getLong(idCol)
                out += LocalVideo(
                    id = id,
                    title = c.getString(nameCol) ?: "video",
                    uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id).toString(),
                    durationMs = c.getLong(durCol),
                    folder = c.getString(bucketCol),
                )
            }
        }
        out
    }

    private fun getJson(url: String): JsonObject {
        val req = Request.Builder().url(url).header("User-Agent", "TCLStreamX/1.0").build()
        val body = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            resp.body?.string() ?: throw IOException("empty")
        }
        return json.parseToJsonElement(body) as? JsonObject ?: throw IOException("unexpected response")
    }

    private fun JsonObject.str(key: String): String? = when (val v = this[key]) {
        is JsonPrimitive -> v.contentOrNull
        is JsonArray -> (v.firstOrNull() as? JsonPrimitive)?.contentOrNull
        else -> null
    }

    companion object {
        const val PAGE_SIZE = 40
        private const val MAX_LOCAL = 2000
        private val IDENTIFIER = Regex("^[A-Za-z0-9._-]{1,100}$")
        private val HTML_TAG = Regex("<[^>]*>")

        /** Keeps letters, digits and spaces so user text cannot change the search syntax. */
        fun sanitize(q: String): String = q.filter { it.isLetterOrDigit() || it == ' ' }.trim().take(60).ifEmpty { "*" }

        private fun downloadUrl(id: String, name: String) =
            "https://archive.org/download/$id/" + name.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

        private fun formatRank(format: String?) = when (format?.lowercase()) {
            "h.264", "h.264 ia" -> 0
            "mpeg4" -> 1
            "512kb mpeg4" -> 2
            else -> 3
        }

        private fun languageFromName(name: String): String? =
            Regex("[._-]([a-z]{2,3})\\.(srt|vtt)$", RegexOption.IGNORE_CASE).find(name)?.groupValues?.get(1)?.lowercase()
    }
}
