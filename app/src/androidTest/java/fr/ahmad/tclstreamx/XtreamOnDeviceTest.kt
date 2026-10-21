package fr.ahmad.tclstreamx

import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.ahmad.tclstreamx.data.db.ContentKind
import fr.ahmad.tclstreamx.data.m3u.M3uParser
import fr.ahmad.tclstreamx.player.Lineup
import fr.ahmad.tclstreamx.player.PlayItem
import fr.ahmad.tclstreamx.player.PlaybackController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Xtream Codes support on a real device, against a panel simulated on the device itself.
 *
 * Part A (synthetic, performance): 115 000 generated entries imported into the real Room database
 * through the real importer, with the login encrypted by the real Android Keystore.
 * Part B (real playback): the simulated panel answers stream requests the way panels do, with a
 * redirect, here to the openly licensed test streams the app ships (Apple BipBop, Blender films),
 * so the whole account path (reference → login decrypted in memory → redirect → ExoPlayer) plays real video.
 *
 * The login is a placeholder generated for the test; no real account is used.
 */
@RunWith(AndroidJUnit4::class)
class XtreamOnDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val app = inst.targetContext.applicationContext as TclApp
    private lateinit var server: MockWebServer
    private val user = "ci-demo"
    private val pass = "placeholder-" + System.nanoTime()

    @Before fun setUp() {
        server = MockWebServer()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test
    fun accountImportSearchSeriesAndPlayback() = runBlocking {
        val openMedia = Json.parseToJsonElement(app.assets.open("open_media.json").use { it.readBytes().decodeToString() }) as JsonObject
        val films = (openMedia["films"] as JsonArray).map { it as JsonObject }
            .associate { (it["key"] as JsonPrimitive).content to (it["url"] as JsonPrimitive).content }
        val hls = films.getValue("open:bipbop")
        // Films and episodes are redirected to an openly licensed MP4 that starts quickly (index at the
        // start of the file), chosen among the shipped open films: some mirrors put it at the end and
        // need a second long request, which says nothing about the account path being tested.
        // Null when no open MP4 answered from CI this time: films and episodes are then reported as
        // skipped (the live check already covers the same resolution path), never counted as passed.
        val mp4 = films.filterKeys { it in setOf("open:bbb", "open:sintel", "open:elephants", "open:tears") }
            .values.firstOrNull { fastStart(it) }
        val panel = DevicePanel(user, pass, LIVE, MOVIES, SERIES, hls, mp4 ?: hls)
        server.dispatcher = panel
        server.start()
        val base = server.url("/").toString().trimEnd('/')

        // ---- A. synthetic import -----------------------------------------------------------
        val rt = Runtime.getRuntime()
        rt.gc()
        val heapBefore = rt.totalMemory() - rt.freeMemory()
        val t0 = System.currentTimeMillis()
        val id = app.container.importer.addXtream("CI synthetic panel", base, user, pass)
        val importMs = System.currentTimeMillis() - t0
        val heapAfter = rt.totalMemory() - rt.freeMemory()
        val playlist = app.container.db.playlists().byId(id)!!
        assertEquals(LIVE + MOVIES + SERIES, playlist.channelCount)

        // The login is stored encrypted only, and no row carries it.
        assertNotNull(playlist.secret)
        assertFalse(playlist.secret!!.contains(pass))
        assertFalse(playlist.source!!.contains(pass))
        val leaking = app.container.db.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM channels WHERE url LIKE ? OR backupUrls LIKE ?", arrayOf("%$pass%", "%$pass%"))
            .use { it.moveToFirst(); it.getInt(0) }
        assertEquals(0, leaking)

        val dao = app.container.db.channels()
        val q0 = System.currentTimeMillis()
        val regions = dao.observeRegions(id, ContentKind.LIVE).first()
        val regionsMs = System.currentTimeMillis() - q0
        assertTrue(regions.any { it.code == "FR" } && regions.any { it.code == "ar" })
        val q1 = System.currentTimeMillis()
        val frPage = dao.firstOfLineup(id, null, 0, ContentKind.LIVE, "FR", 60)
        val regionPageMs = System.currentTimeMillis() - q1
        assertEquals(60, frPage.size)
        assertTrue(frPage.all { it.country == "FR" })
        val q2 = System.currentTimeMillis()
        val groups = dao.observeGroups(id, ContentKind.MOVIE, null).first()
        val groupsMs = System.currentTimeMillis() - q2
        assertEquals(300, groups.size)
        val q3 = System.currentTimeMillis()
        val hits = dao.searchKind(M3uParser.normalize("Film 3999"), ContentKind.MOVIE, 60)
        val searchMs = System.currentTimeMillis() - q3
        assertTrue(hits.any { it.name.startsWith("Film 3999 ") })
        val first = dao.firstOfLineup(id, null, 0, ContentKind.LIVE, null, 1).single()
        val q4 = System.currentTimeMillis()
        val next = dao.next(id, null, 0, ContentKind.LIVE, null, first.playlistId, first.position)
        val zapQueryMs = System.currentTimeMillis() - q4
        assertNotNull(next)

        // Series: seasons and episodes come from the panel only when a series is opened.
        val series = dao.firstOfLineup(id, null, 0, ContentKind.SERIES, null, 1).single()
        val s0 = System.currentTimeMillis()
        val info = app.container.xtream.seriesInfo(series.url)
        val seriesMs = System.currentTimeMillis() - s0
        assertEquals(3, info.seasons.size)
        assertEquals(30, info.episodes.size)

        CiReport.write(
            "xtream-import-report.json",
            """{"live":$LIVE,"movies":$MOVIES,"series":$SERIES,"importMs":$importMs,"heapDeltaMb":${(heapAfter - heapBefore) / 1_048_576},""" +
                """"panelMb":${"%.1f".format(java.util.Locale.ROOT, panel.bytesServed / 1_048_576.0)},"regions":${regions.size},"regionsMs":$regionsMs,""" +
                """"regionPageMs":$regionPageMs,"groupsMs":$groupsMs,"searchMs":$searchMs,"zapQueryMs":$zapQueryMs,"seriesInfoMs":$seriesMs}""",
        )

        // ---- B. real playback through the account path --------------------------------------
        val movie = dao.firstOfLineup(id, null, 0, ContentKind.MOVIE, null, 1).single()
        val episodeRef = app.container.xtream.episodeRef(series.url, info.episodes.first())!!
        val targets = listOfNotNull(
            "live" to PlayItem.fromChannel(first, Lineup(id, null, false, ContentKind.LIVE)),
            if (mp4 != null) "movie" to PlayItem.fromChannel(movie, null) else null,
            if (mp4 != null) "episode" to PlayItem(key = "test:episode", title = "episode", urls = listOf(episodeRef), isLive = false) else null,
        )
        val results = targets.map { (label, item) -> label to play(item) }
        val stats = panel.streamRequests
        CiReport.write(
            "xtream-playback-report.json",
            "{\"results\":[" + results.joinToString(",") { (label, r) ->
                """{"kind":"$label","ready":${r.first},"readyMs":${r.second},"playingMs":${r.third}}"""
            } + "],\"vodSkipped\":${mp4 == null},\"panelStreamRequests\":$stats,\"redirectTargets\":{\"live\":\"${hls}\",\"vod\":\"${mp4 ?: ""}\"}}",
        )
        app.container.importer.delete(id)
        assertTrue("stream requests reached the panel with the decrypted login: $stats", stats >= targets.size)
        assertTrue("account playback: $results", results.all { it.second.first })
    }

    /** True when the first 256 KB of the file already contain the MP4 index ('moov'). */
    private fun fastStart(url: String): Boolean = runCatching {
        val req = okhttp3.Request.Builder().url(url).header("Range", "bytes=0-262143").build()
        app.container.http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use false
            // Read at most 256 KB even if the server ignores the Range header.
            val src = resp.body!!.source()
            val buf = Buffer()
            while (buf.size < 262_144) if (src.read(buf, 262_144 - buf.size) == -1L) break
            buf.readByteArray().decodeToString().contains("moov")
        }
    }.getOrDefault(false)

    /** Returns (ready, ms to ready, ms spent playing). */
    private fun play(item: PlayItem): Triple<Boolean, Long, Long> {
        lateinit var controller: PlaybackController
        inst.runOnMainSync {
            controller = PlaybackController(app)
            controller.play(item)
        }
        val start = System.currentTimeMillis()
        var readyAt = 0L
        var playing = 0L
        var failed = false
        while (System.currentTimeMillis() - start < 30_000) {
            Thread.sleep(250)
            var state = 0
            var isPlaying = false
            inst.runOnMainSync {
                val p = controller.player.value
                p?.volume = 0f
                state = p?.playbackState ?: 0
                isPlaying = p?.isPlaying == true
                failed = controller.ui.value.issue != null
            }
            if (failed) break
            if (state == Player.STATE_READY && readyAt == 0L) readyAt = System.currentTimeMillis() - start
            if (isPlaying) playing += 250
            if (playing >= 4_000) break
        }
        inst.runOnMainSync { controller.onBackground() }
        return Triple(readyAt > 0 && !failed, readyAt, playing)
    }

    /** Simulated panel: API answers generated on the fly, stream paths redirected to open test media. */
    private class DevicePanel(
        private val user: String,
        private val pass: String,
        private val live: Int,
        private val movies: Int,
        private val series: Int,
        private val hls: String,
        private val mp4: String,
    ) : Dispatcher() {
        @Volatile var bytesServed = 0L
        @Volatile var streamRequests = 0
        private val prefixes = listOf("FR", "AR", "UK", "DE", "IT", "ES", "TR", "MA", "US", "BE")

        override fun dispatch(request: RecordedRequest): MockResponse {
            val url = request.requestUrl ?: return MockResponse().setResponseCode(400)
            val segments = url.pathSegments
            // Stream paths: /live|movie|series/<user>/<pass>/<id>.<ext>
            if (segments.size == 4 && segments[0] in setOf("live", "movie", "series")) {
                if (segments[1] != user || segments[2] != pass) return MockResponse().setResponseCode(401)
                streamRequests++
                val target = if (segments[0] == "live") hls else mp4
                return MockResponse().setResponseCode(302).setHeader("Location", target)
            }
            if (segments.lastOrNull() != "player_api.php") return MockResponse().setResponseCode(404)
            if (url.queryParameter("username") != user || url.queryParameter("password") != pass) {
                return MockResponse().setBody("""{"user_info":{"auth":0}}""")
            }
            val body = Buffer()
            when (url.queryParameter("action")) {
                null -> body.writeUtf8("""{"user_info":{"auth":1,"status":"Active","exp_date":null,"max_connections":"1","is_trial":"0","allowed_output_formats":["m3u8","ts"]},"server_info":{}}""")
                "get_live_categories", "get_vod_categories", "get_series_categories" -> {
                    body.writeUtf8("[")
                    for (i in 0 until 300) {
                        if (i > 0) body.writeUtf8(",")
                        val label = listOf("SPORTS", "CINEMA", "NEWS", "KIDS", "DOCS", "MUSIC")[i % 6]
                        body.writeUtf8("""{"category_id":"$i","category_name":"${prefixes[i % prefixes.size]}| $label $i","parent_id":0}""")
                    }
                    body.writeUtf8("]")
                }
                "get_live_streams" -> list(body, live) { i ->
                    """{"num":$i,"name":"Channel $i Sports HD","stream_type":"live","stream_id":$i,"stream_icon":"","epg_channel_id":"ch$i.example","category_id":"${i % 300}"}"""
                }
                "get_vod_streams" -> list(body, movies) { i ->
                    """{"num":$i,"name":"Film $i (2024)","stream_type":"movie","stream_id":"$i","stream_icon":"","category_id":"${i % 300}","container_extension":"mp4"}"""
                }
                "get_series" -> list(body, series) { i ->
                    """{"num":$i,"name":"Série $i","series_id":$i,"cover":"","category_id":"${i % 300}"}"""
                }
                "get_series_info" -> {
                    body.writeUtf8("""{"info":{"name":"Série","plot":"Synthetic"},"seasons":[],"episodes":{""")
                    for (s in 1..3) {
                        if (s > 1) body.writeUtf8(",")
                        body.writeUtf8("\"$s\":[")
                        for (e in 1..10) {
                            if (e > 1) body.writeUtf8(",")
                            body.writeUtf8("""{"id":"${s * 100 + e}","episode_num":$e,"season":$s,"title":"E$e","container_extension":"mp4"}""")
                        }
                        body.writeUtf8("]")
                    }
                    body.writeUtf8("}}")
                }
                else -> return MockResponse().setResponseCode(404)
            }
            bytesServed += body.size
            return MockResponse().setBody(body)
        }

        private fun list(body: Buffer, n: Int, item: (Int) -> String) {
            body.writeUtf8("[")
            for (i in 1..n) {
                if (i > 1) body.writeUtf8(",")
                body.writeUtf8(item(i))
            }
            body.writeUtf8("]")
        }
    }

    companion object {
        private const val LIVE = 60_000
        private const val MOVIES = 40_000
        private const val SERIES = 15_000
    }
}
