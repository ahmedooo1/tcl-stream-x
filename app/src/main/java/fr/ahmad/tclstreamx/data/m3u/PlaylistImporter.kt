package fr.ahmad.tclstreamx.data.m3u

import android.content.ContentResolver
import android.net.Uri
import androidx.room.withTransaction
import fr.ahmad.tclstreamx.data.db.AppDatabase
import fr.ahmad.tclstreamx.data.db.ChannelEntity
import fr.ahmad.tclstreamx.data.db.PlaylistEntity
import fr.ahmad.tclstreamx.data.db.PlaylistKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

sealed interface ImportState {
    data object Idle : ImportState
    data class Running(val playlistName: String, val parsed: Int) : ImportState
    data class Done(val playlistName: String, val channels: Int, val skipped: Int) : ImportState
    data class Failed(val playlistName: String, val reason: ImportError) : ImportState
}

enum class ImportError { INVALID_URL, NETWORK, HTTP, EMPTY, TOO_LARGE, UNREADABLE }

class ImportException(val error: ImportError, message: String) : IOException(message)

/**
 * Imports M3U playlists of any size: the file is streamed, parsed line by line and written in
 * batches into a new "generation" of rows. The playlist switches to the new generation only when
 * the import succeeded, so a failed refresh never leaves a half-imported list on screen.
 */
class PlaylistImporter(
    private val db: AppDatabase,
    private val http: OkHttpClient,
    private val resolver: ContentResolver,
) {
    private val _state = MutableStateFlow<ImportState>(ImportState.Idle)
    val state: StateFlow<ImportState> = _state.asStateFlow()

    suspend fun addFromUrl(name: String, url: String): Long {
        val clean = url.trim()
        if (!M3uParser.isSupportedScheme(clean)) throw ImportException(ImportError.INVALID_URL, "Only http(s) URLs are accepted")
        val id = db.playlists().insert(PlaylistEntity(name = name.ifBlank { hostOf(clean) }, kind = PlaylistKind.URL, source = clean))
        refresh(id)
        return id
    }

    suspend fun addFromUri(name: String, uri: Uri): Long {
        val id = db.playlists().insert(PlaylistEntity(name = name, kind = PlaylistKind.FILE, source = uri.toString()))
        refresh(id)
        return id
    }

    /** Playlist uploaded from a phone through the local network page. The temp file is deleted afterwards. */
    suspend fun addFromLocalFile(name: String, file: File): Long {
        val id = db.playlists().insert(PlaylistEntity(name = name, kind = PlaylistKind.LAN, source = null))
        try {
            val playlist = requireNotNull(db.playlists().byId(id))
            import(playlist) { file.inputStream() }
        } finally {
            file.delete()
        }
        return id
    }

    suspend fun refresh(playlistId: Long) {
        val playlist = db.playlists().byId(playlistId) ?: return
        val source = playlist.source ?: return
        when (playlist.kind) {
            PlaylistKind.URL -> import(playlist) { openUrl(source) }
            PlaylistKind.FILE -> import(playlist) {
                resolver.openInputStream(Uri.parse(source))
                    ?: throw ImportException(ImportError.UNREADABLE, "Cannot open the selected file")
            }
            PlaylistKind.BUILTIN, PlaylistKind.LAN -> Unit
        }
    }

    suspend fun delete(playlistId: Long) {
        db.withTransaction {
            db.channels().deleteForPlaylist(playlistId)
            db.playlists().delete(playlistId)
        }
    }

    private fun openUrl(url: String): InputStream {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw ImportException(ImportError.NETWORK, e.message ?: "network error")
        }
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw ImportException(ImportError.HTTP, "HTTP $code")
        }
        val body = response.body ?: throw ImportException(ImportError.EMPTY, "empty body")
        return body.byteStream()
    }

    private suspend fun import(playlist: PlaylistEntity, open: () -> InputStream) = withContext(Dispatchers.IO) {
        val generation = playlist.activeGeneration + 1
        val parser = M3uParser()
        var headerEpg: String? = null
        var count = 0
        val batch = ArrayList<ChannelEntity>(BATCH)
        val context = coroutineContext
        _state.value = ImportState.Running(playlist.name, 0)
        try {
            val stats = open().use { raw ->
                val input = maybeGunzip(raw)
                parser.parse(
                    InputStreamReader(input, Charsets.UTF_8),
                    onHeader = { h -> if (headerEpg == null) headerEpg = h.epgUrls.firstOrNull() },
                ) { e ->
                    if (count >= MAX_CHANNELS) throw ImportException(ImportError.TOO_LARGE, "more than $MAX_CHANNELS channels")
                    count++
                    batch += ChannelEntity(
                        playlistId = playlist.id,
                        generation = generation,
                        position = count,
                        name = e.name,
                        nameNorm = M3uParser.normalize(e.name),
                        url = e.url,
                        logo = e.logo,
                        groupTitle = e.group,
                        tvgId = e.tvgId,
                        language = e.language,
                        country = e.country,
                        userAgent = e.userAgent,
                        referrer = e.referrer,
                        drm = e.drm,
                        kind = M3uParser.classify(e),
                        channelKey = M3uParser.channelKey(e.tvgId, e.name, e.group),
                    )
                    if (batch.size >= BATCH) {
                        context.ensureActive()
                        db.channels().insertAllBlocking(batch.toList())
                        batch.clear()
                        _state.value = ImportState.Running(playlist.name, count)
                    }
                }
            }
            if (batch.isNotEmpty()) db.channels().insertAllBlocking(batch.toList())
            if (count == 0) throw ImportException(ImportError.EMPTY, "no playable entry")
            db.withTransaction {
                val current = db.playlists().byId(playlist.id) ?: return@withTransaction
                db.playlists().upsert(
                    current.copy(
                        activeGeneration = generation,
                        channelCount = count,
                        updatedAt = System.currentTimeMillis(),
                        lastError = null,
                        epgUrl = current.epgUrl ?: headerEpg,
                    )
                )
                db.channels().deleteOtherGenerations(playlist.id, generation)
            }
            _state.value = ImportState.Done(playlist.name, count, stats.skippedUnsupported + stats.skippedMalformed)
        } catch (t: Throwable) {
            val error = (t as? ImportException)?.error
                ?: if (t is IOException) ImportError.NETWORK else ImportError.UNREADABLE
            withContext(NonCancellable) {
                // Drop the partial generation, keep whatever was visible before.
                db.channels().deleteOtherGenerations(playlist.id, playlist.activeGeneration)
                db.playlists().byId(playlist.id)?.let { db.playlists().upsert(it.copy(lastError = error.name)) }
            }
            if (t is CancellationException) {
                _state.value = ImportState.Idle
                throw t
            }
            _state.value = ImportState.Failed(playlist.name, error)
        }
    }

    fun resetState() {
        _state.value = ImportState.Idle
    }

    companion object {
        const val USER_AGENT = "TCLStreamX/1.0 (Linux; Android TV)"
        private const val BATCH = 500
        const val MAX_CHANNELS = 250_000

        fun hostOf(url: String): String = Uri.parse(url).host ?: url

        /** Some servers send gzip without Content-Encoding; detect it by its magic bytes. */
        fun maybeGunzip(input: InputStream): InputStream {
            val buffered = input.buffered()
            buffered.mark(2)
            val b1 = buffered.read()
            val b2 = buffered.read()
            buffered.reset()
            return if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(buffered, 64 * 1024) else buffered
        }
    }
}
