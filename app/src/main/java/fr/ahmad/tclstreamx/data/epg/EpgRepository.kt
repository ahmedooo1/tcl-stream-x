package fr.ahmad.tclstreamx.data.epg

import android.util.Xml
import fr.ahmad.tclstreamx.data.db.AppDatabase
import fr.ahmad.tclstreamx.data.db.ProgrammeEntity
import fr.ahmad.tclstreamx.data.m3u.M3uParser
import fr.ahmad.tclstreamx.data.xtream.XtreamUrls
import fr.ahmad.tclstreamx.data.m3u.PlaylistImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

sealed interface EpgState {
    data object Idle : EpgState
    data class Loading(val source: String) : EpgState
    data class Done(val programmes: Int, val sources: Int, val failed: Int) : EpgState
}

class EpgRepository(
    private val db: AppDatabase,
    private val http: OkHttpClient,
    /** Turns internal `xtream://` guide references into real addresses, in memory only. */
    private val resolve: suspend (String) -> String?,
) {
    private val _state = MutableStateFlow<EpgState>(EpgState.Idle)
    val state: StateFlow<EpgState> = _state.asStateFlow()

    /** Downloads every guide (user setting first, then playlist headers) and replaces the stored programmes. */
    suspend fun refresh(userEpgUrl: String?): EpgState = withContext(Dispatchers.IO) {
        val urls = (listOf(userEpgUrl?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_GUIDE) +
            db.playlists().all().mapNotNull { it.epgUrl })
            .filter { M3uParser.isSupportedScheme(it) || XtreamUrls.isInternal(it) }
            .distinct()
        if (urls.isEmpty()) {
            return@withContext EpgState.Done(0, 0, 0).also { _state.value = it }
        }
        val wanted = HashSet<String>()
        db.channels().allTvgIds().forEach {
            wanted += it.lowercase()
            wanted += it.substringBefore('@').lowercase()
        }
        val now = System.currentTimeMillis()
        val parser = XmltvParser(wanted, now - PAST_WINDOW_MS, now + FUTURE_WINDOW_MS)
        db.epg().clear()
        var total = 0
        var failed = 0
        for (source in urls) {
            // The state is shown on screen: an account guide is labelled by its reference, never by its address.
            _state.value = EpgState.Loading(if (XtreamUrls.isInternal(source)) source.substringBefore("/epg") else source)
            try {
                val url = resolve(source) ?: throw IOException("guide unavailable")
                total += download(url) { stream ->
                    val xml = Xml.newPullParser()
                    xml.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                    xml.setInput(stream, null)
                    val batch = ArrayList<ProgrammeEntity>(BATCH)
                    val n = parser.parse(xml) { p ->
                        batch += ProgrammeEntity(channelTvgId = p.channel, start = p.start, stop = p.stop, title = p.title, description = p.description)
                        if (batch.size >= BATCH) {
                            db.epg().insertAllBlocking(batch.toList())
                            batch.clear()
                        }
                    }
                    if (batch.isNotEmpty()) db.epg().insertAllBlocking(batch)
                    n
                }
            } catch (e: IOException) {
                failed++
            } catch (e: org.xmlpull.v1.XmlPullParserException) {
                failed++
            }
        }
        EpgState.Done(total, urls.size, failed).also { _state.value = it }
    }

    private fun <T> download(url: String, block: (InputStream) -> T): T {
        val request = Request.Builder().url(url).header("User-Agent", PlaylistImporter.USER_AGENT).build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("empty body")
            val raw = PlaylistImporter.maybeGunzip(body.byteStream()).buffered()
            raw.mark(4)
            val b1 = raw.read()
            val b2 = raw.read()
            raw.reset()
            return if (b1 == 'P'.code && b2 == 'K'.code) {
                ZipInputStream(raw).use { zip ->
                    zip.nextEntry ?: throw IOException("empty zip")
                    block(zip)
                }
            } else {
                block(raw)
            }
        }
    }

    companion object {
        private const val BATCH = 1000

        /**
         * Free community XMLTV guide (xmltvfr.fr). Checked from CI: it covers the French built-in
         * channels and several international ones (France 24, DW, BFM, Arte...). Replaced by the
         * user's own guide when one is set in the settings.
         */
        const val DEFAULT_GUIDE = "https://xmltvfr.fr/xmltv/xmltv.xml.gz"
        private const val PAST_WINDOW_MS = 3 * 3600_000L
        private const val FUTURE_WINDOW_MS = 36 * 3600_000L
    }
}
