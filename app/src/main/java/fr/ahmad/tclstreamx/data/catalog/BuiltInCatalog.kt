package fr.ahmad.tclstreamx.data.catalog

import android.content.Context
import androidx.room.withTransaction
import fr.ahmad.tclstreamx.data.db.AppDatabase
import fr.ahmad.tclstreamx.data.db.ChannelEntity
import fr.ahmad.tclstreamx.data.db.PlaylistEntity
import fr.ahmad.tclstreamx.data.db.PlaylistKind
import fr.ahmad.tclstreamx.data.db.StreamStatus
import fr.ahmad.tclstreamx.data.m3u.M3uParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CatalogFile(
    val schema: Int,
    val generatedAt: Long,
    val checkedFrom: String? = null,
    val channels: List<CatalogChannel>,
)

@Serializable
data class CatalogChannel(
    val id: String,
    val name: String,
    val logo: String? = null,
    val category: String,
    val language: String,
    val country: String = "",
    val website: String = "",
    val owner: String,
    val geo: String? = null,
    val status: String,
    val streams: List<CatalogStream>,
)

@Serializable
data class CatalogStream(
    val url: String,
    val userAgent: String? = null,
    val referrer: String? = null,
    val quality: Int = 0,
    val status: String,
    val detail: String = "",
    val checkedAt: Long = 0,
)

/**
 * Loads the bundled catalog (assets/catalog.json, produced and checked by tools/build_catalog.py)
 * into a read-only playlist. The status shown on first launch is the one measured at build time;
 * the app then re-checks from the TV itself.
 */
class BuiltInCatalog(private val context: Context, private val db: AppDatabase) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun installOrUpdate(): Unit = withContext(Dispatchers.IO) {
        val file = context.assets.open(ASSET).use { json.decodeFromString(CatalogFile.serializer(), it.readBytes().decodeToString()) }
        val existing = db.playlists().builtin()
        if (existing != null && existing.sourceVersion == file.generatedAt) return@withContext
        val playlistId = existing?.id ?: db.playlists().insert(
            PlaylistEntity(name = NAME, kind = PlaylistKind.BUILTIN, source = null)
        )
        val generation = (existing?.activeGeneration ?: 0) + 1
        val order = listOf("news", "entertainment", "sports", "kids", "documentary", "music", "religious")
        val sorted = file.channels.sortedWith(compareBy({ order.indexOf(it.category) }, { languageRank(it.language) }))
        val rows = sorted.mapIndexed { index, ch ->
            val ordered = ch.streams.sortedBy { if (it.status == "ok") 0 else 1 }
            val best = ordered.first()
            ChannelEntity(
                playlistId = playlistId,
                generation = generation,
                position = index + 1,
                name = ch.name,
                nameNorm = M3uParser.normalize(ch.name),
                url = best.url,
                backupUrls = ordered.drop(1).joinToString("\n") { it.url }.ifEmpty { null },
                logo = ch.logo,
                // Subsection = category and language, e.g. "news:ar" shown as "Information · العربية".
                groupTitle = "${ch.category}:${ch.language}",
                category = ch.category,
                tvgId = ch.id.substringBefore('@'),
                language = ch.language,
                country = ch.country,
                userAgent = best.userAgent,
                referrer = best.referrer,
                channelKey = "b:" + ch.id,
                owner = ch.owner,
                geo = ch.geo,
                status = statusOf(best.status).code,
                statusDetail = best.detail.ifEmpty { null },
                statusCheckedAt = best.checkedAt * 1000,
            )
        }
        db.withTransaction {
            db.channels().insertAll(rows)
            val current = db.playlists().byId(playlistId) ?: PlaylistEntity(id = playlistId, name = NAME, kind = PlaylistKind.BUILTIN, source = null)
            db.playlists().upsert(
                current.copy(
                    activeGeneration = generation,
                    channelCount = rows.size,
                    updatedAt = System.currentTimeMillis(),
                    sourceVersion = file.generatedAt,
                )
            )
            db.channels().deleteOtherGenerations(playlistId, generation)
        }
    }

    private fun languageRank(lang: String) = when (lang) {
        "fr" -> 0
        "ar" -> 1
        else -> 2
    }

    companion object {
        const val ASSET = "catalog.json"
        const val NAME = "TCL Stream X"

        fun statusOf(s: String): StreamStatus = when (s) {
            "ok" -> StreamStatus.ONLINE
            "forbidden_or_geo" -> StreamStatus.FORBIDDEN
            "auth_required" -> StreamStatus.AUTH_REQUIRED
            "drm" -> StreamStatus.DRM
            "timeout" -> StreamStatus.TIMEOUT
            "invalid_manifest", "empty_segment" -> StreamStatus.INVALID
            "unchecked" -> StreamStatus.UNKNOWN
            else -> StreamStatus.OFFLINE
        }
    }
}
