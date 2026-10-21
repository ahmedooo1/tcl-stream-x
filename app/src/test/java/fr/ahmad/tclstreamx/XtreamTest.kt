package fr.ahmad.tclstreamx

import fr.ahmad.tclstreamx.data.db.ContentKind
import fr.ahmad.tclstreamx.data.m3u.RegionTagger
import fr.ahmad.tclstreamx.data.xtream.JsonFormatException
import fr.ahmad.tclstreamx.data.xtream.JsonStream
import fr.ahmad.tclstreamx.data.xtream.Redactor
import fr.ahmad.tclstreamx.data.xtream.XtreamAccount
import fr.ahmad.tclstreamx.data.xtream.XtreamClient
import fr.ahmad.tclstreamx.data.xtream.XtreamCredentials
import fr.ahmad.tclstreamx.data.xtream.XtreamError
import fr.ahmad.tclstreamx.data.xtream.XtreamException
import fr.ahmad.tclstreamx.data.xtream.XtreamMapper
import fr.ahmad.tclstreamx.data.xtream.XtreamRef
import fr.ahmad.tclstreamx.data.xtream.XtreamSection
import fr.ahmad.tclstreamx.data.xtream.XtreamUrls
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.StringReader

/**
 * Xtream Codes support against a mock panel. The login used here is a placeholder made up for the
 * test ("demo"/"s3cr3t pass"); no real account is used anywhere in the test suite.
 */
class XtreamTest {
    private lateinit var server: MockWebServer
    private val creds = XtreamCredentials("demo", "s3cr3t pass")
    private val client = XtreamClient(OkHttpClient())

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun base() = server.url("/").toString().trimEnd('/')

    // ---- JSON reader -----------------------------------------------------------------------

    @Test fun jsonHandlesEscapesTypesAndBom() {
        val out = ArrayList<Map<String, Any?>>()
        JsonStream(StringReader("﻿ [{\"a\":\"x\\\"y\\u00e9\\n\",\"n\":12,\"f\":-1.5e3,\"t\":true,\"z\":null,\"l\":[1,{\"k\":\"v\"}]}]")).forEachElement { out += it }
        assertEquals(1, out.size)
        assertEquals("x\"yé\n", out[0]["a"])
        assertEquals("12", out[0]["n"])
        assertEquals("-1.5e3", out[0]["f"])
        assertEquals(true, out[0]["t"])
        assertTrue(out[0].containsKey("z"))
    }

    @Test fun jsonAcceptsIndexedObjectsAndEmptyBodies() {
        val out = ArrayList<Map<String, Any?>>()
        JsonStream(StringReader("{\"7\":{\"stream_id\":7},\"8\":{\"stream_id\":8}}")).forEachElement { out += it }
        assertEquals(listOf("7", "8"), out.map { it["stream_id"] })
        JsonStream(StringReader("")).forEachElement { fail("empty body has no entry") }
        JsonStream(StringReader("[]")).forEachElement { fail("empty array has no entry") }
    }

    @Test fun jsonRejectsTruncatedAndTooDeepInput() {
        for (bad in listOf("[{\"a\":1}", "[{\"a\":\"unterminated", "{\"a\":tru}", "[" + "[".repeat(100) + "]".repeat(101))) {
            try {
                JsonStream(StringReader(bad)).forEachElement { }
                fail("accepted: $bad")
            } catch (e: JsonFormatException) {
                // expected
            }
        }
    }

    // ---- URLs and secrets ---------------------------------------------------------------

    @Test fun serverAddressesAreNormalised() {
        assertEquals("http://panel.example:8080", XtreamUrls.normalizeServer("panel.example:8080"))
        assertEquals("http://panel.example:8080", XtreamUrls.normalizeServer("http://panel.example:8080/player_api.php"))
        assertEquals("https://panel.example/sub", XtreamUrls.normalizeServer("https://panel.example/sub/"))
        assertNull(XtreamUrls.normalizeServer("ftp://panel.example"))
        assertNull(XtreamUrls.normalizeServer("not a url"))
        val link = XtreamUrls.fromPlaylistLink("http://panel.example:80/get.php?username=demo&password=a%2Bb&type=m3u_plus")
        assertNotNull(link)
        assertEquals("http://panel.example:80", link!!.first)
        assertEquals("a+b", link.second.password)
    }

    @Test fun referencesNeverContainTheLoginAndResolveInMemory() {
        val ref = XtreamUrls.ref(4, XtreamRef.MOVIE, "991", "mkv")
        assertEquals("xtream://4/movie/991.mkv", ref)
        val real = XtreamUrls.resolve(XtreamUrls.parse(ref)!!, "http://panel.example", creds)
        assertEquals("http://panel.example/movie/demo/s3cr3t%20pass/991.mkv", real)
        assertNull(XtreamUrls.parse("xtream://4/live/../../etc"))
        assertNull(XtreamUrls.resolve(XtreamUrls.parse("xtream://4/series/12")!!, "http://panel.example", creds))
        assertFalse(creds.toString().contains("s3cr3t"))
    }

    @Test fun redactorRemovesLoginsFromPathsAndQueries() {
        val api = XtreamUrls.api("http://panel.example", creds, "get_vod_streams")
        assertFalse(Redactor.redact("failed $api").contains("s3cr3t"))
        assertFalse(Redactor.redact("GET http://panel.example/live/demo/secret/1.ts 404").contains("secret"))
        assertEquals("http://x/live/***/***/1.ts", Redactor.redact("http://x/live/demo/secret/1.ts"))
    }

    // ---- API ------------------------------------------------------------------------------

    @Test fun authenticateReadsAccountState() {
        server.enqueue(MockResponse().setBody(
            """{"user_info":{"auth":1,"status":"Active","exp_date":"4102444800","max_connections":"2","active_cons":"0","is_trial":"0",
               "allowed_output_formats":["m3u8","ts"]},"server_info":{"url":"panel.example"}}"""
        ))
        val a = client.authenticate(base(), creds, nowSeconds = 1_700_000_000)
        assertEquals("Active", a.status)
        assertEquals(4102444800L, a.expiresAt)
        assertEquals(2, a.maxConnections)
        assertEquals("m3u8", a.liveExtension)
        assertEquals("ts", a.liveFallbackExtension)
        val req = server.takeRequest()
        assertTrue(req.path!!.startsWith("/player_api.php?username=demo&password=s3cr3t+pass"))
        assertEquals(a.status, XtreamAccount.parseSummary(a.summary())!!.status)
    }

    @Test fun authenticateRejectsWrongLoginExpiredAndDisabledAccounts() {
        server.enqueue(MockResponse().setBody("""{"user_info":{"auth":0}}"""))
        server.enqueue(MockResponse().setBody("""{"user_info":{"auth":1,"status":"Expired"}}"""))
        server.enqueue(MockResponse().setBody("""{"user_info":{"auth":1,"status":"Active","exp_date":"1000"}}"""))
        server.enqueue(MockResponse().setBody("""{"user_info":{"auth":1,"status":"Banned"}}"""))
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody("<html>not json</html>"))
        val expected = listOf(XtreamError.AUTH, XtreamError.EXPIRED, XtreamError.EXPIRED, XtreamError.INACTIVE, XtreamError.AUTH, XtreamError.FORMAT)
        for (e in expected) {
            try {
                client.authenticate(base(), creds)
                fail("expected $e")
            } catch (x: XtreamException) {
                assertEquals(e, x.error)
                assertFalse("error message must not contain the password", x.message!!.contains("s3cr3t"))
            }
        }
    }

    @Test fun seriesInfoAcceptsSeasonMapsAndFlatLists() {
        server.enqueue(MockResponse().setBody(
            """{"seasons":[{"season_number":1,"name":"Saison 1"}],"info":{"name":"Show","plot":"p","genre":"Drame"},
               "episodes":{"2":[{"id":"21","episode_num":1,"title":"S2E1","container_extension":"mp4"}],
                           "1":[{"id":"12","episode_num":2,"title":"S1E2","container_extension":"mkv","info":{"duration_secs":1500}},
                                {"id":"11","episode_num":1,"title":"S1E1","container_extension":"mp4"}]}}"""
        ))
        val info = client.seriesInfo(base(), creds, "5")
        assertEquals(listOf("11", "12", "21"), info.episodes.map { it.id })
        assertEquals(listOf(1 to "Saison 1", 2 to null), info.seasons)
        assertEquals("mkv", info.episodes[1].extension)
        assertEquals(1500L, info.episodes[1].durationSecs)
        assertTrue(server.takeRequest().path!!.contains("action=get_series_info&series_id=5"))

        server.enqueue(MockResponse().setBody("""{"episodes":[[{"id":"1","season":3,"episode_num":1}]]}"""))
        assertEquals(3, client.seriesInfo(base(), creds, "6").episodes.single().season)
    }

    @Test fun mapperKeepsSourceNamesAndCategoriesAsIs() {
        val account = XtreamAccount("Active", null, 1, 0, false, listOf("ts"))
        val m = XtreamMapper(9, 1, "panel.example", account)
        val cats = mapOf("1" to "FR| SPORTS", "2" to "AR | أفلام")
        val live = m.map(XtreamSection.LIVE, mapOf("stream_id" to "77", "name" to "Any Sports HD", "category_id" to "1", "epg_channel_id" to "any.fr"), cats, 1)!!
        assertEquals("Any Sports HD", live.name)
        assertEquals("FR| SPORTS", live.groupTitle)
        assertEquals("xtream://9/live/77.ts", live.url)
        assertEquals("FR", live.country)
        assertEquals("fr", live.language)
        assertEquals(ContentKind.LIVE, live.kind)
        val movie = m.map(XtreamSection.MOVIES, mapOf("stream_id" to 5, "name" to "Film", "category_id" to "2", "container_extension" to "../x"), cats, 2)!!
        assertEquals("xtream://9/movie/5.mp4", movie.url)
        assertEquals("ar", movie.language)
        assertNull(m.map(XtreamSection.SERIES, mapOf("name" to "no id"), cats, 3))
        assertNull(m.map(XtreamSection.LIVE, mapOf("stream_id" to "1/../2", "name" to "x"), cats, 3))
        assertEquals(m.key(XtreamSection.LIVE, "77"), live.channelKey)
    }

    @Test fun regionTaggerReadsProviderPrefixesAndNames() {
        assertEquals("FR", RegionTagger.tag("FR| SPORTS").country)
        assertEquals("ar", RegionTagger.tag("|AR| MBC").language)
        assertEquals("GB", RegionTagger.tag("[UK] News").country)
        assertEquals("MA", RegionTagger.tag("Maroc TV").country)
        assertEquals("ar", RegionTagger.tag("قنوات عربية").language)
        assertNull(RegionTagger.tag("Sports").country)
        assertNull(RegionTagger.tag("TV - Docs").language)
        assertEquals("fr", RegionTagger.tag("x", "fr;be", "French").language)
    }

    // ---- large synthetic library (performance, generated data) ---------------------------

    /**
     * A panel answering with 60 000 channels, 40 000 films and 15 000 series, streamed through the
     * client and the mapper the way the importer does. Results go to build/perf for the CI summary.
     */
    @Test fun largeSyntheticPanelIsStreamedWithBoundedMemory() {
        val sizes = mapOf(XtreamSection.LIVE to 60_000, XtreamSection.MOVIES to 40_000, XtreamSection.SERIES to 15_000)
        server.dispatcher = SyntheticPanel(sizes)
        val account = client.authenticate(base(), creds)
        val mapper = XtreamMapper(1, 1, "panel.example", account)
        val rt = Runtime.getRuntime()
        System.gc()
        val heapBefore = rt.totalMemory() - rt.freeMemory()
        var peak = heapBefore
        val counts = HashMap<XtreamSection, Int>()
        val keys = HashSet<String>()
        var position = 0
        val started = System.nanoTime()
        for (section in XtreamSection.entries) {
            val cats = client.categories(base(), creds, section)
            client.list(base(), creds, section) { e ->
                val row = mapper.map(section, e, cats, ++position) ?: return@list
                assertTrue(row.url.startsWith(XtreamUrls.SCHEME))
                keys += row.channelKey
                counts[section] = (counts[section] ?: 0) + 1
                if (position % 5000 == 0) peak = maxOf(peak, rt.totalMemory() - rt.freeMemory())
            }
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(sizes, counts)
        assertEquals(sizes.values.sum(), keys.size)
        val peakMb = (peak - heapBefore) / (1024 * 1024)
        File("build/perf").mkdirs()
        File("build/perf/xtream-jvm.json").writeText(
            """{"live":${counts[XtreamSection.LIVE]},"movies":${counts[XtreamSection.MOVIES]},"series":${counts[XtreamSection.SERIES]},""" +
                """"elapsedMs":$elapsedMs,"heapGrowthMb":$peakMb,"bytes":${(server.dispatcher as SyntheticPanel).bytesServed}}"""
        )
        println("synthetic panel: ${keys.size} entries in $elapsedMs ms, heap growth ~$peakMb MB")
    }

    /** Generates panel answers on the fly; names and categories deliberately include sports and cinema words. */
    private class SyntheticPanel(private val sizes: Map<XtreamSection, Int>) : Dispatcher() {
        @Volatile var bytesServed = 0L
        private val countries = listOf("FR", "AR", "UK", "DE", "IT", "ES", "TR", "MA", "US", "BE")

        override fun dispatch(request: RecordedRequest): MockResponse {
            val action = request.requestUrl?.queryParameter("action")
            val body = Buffer()
            when (action) {
                null -> body.writeUtf8("""{"user_info":{"auth":1,"status":"Active","exp_date":null,"max_connections":"1","allowed_output_formats":["m3u8","ts"]}}""")
                "get_live_categories", "get_vod_categories", "get_series_categories" -> {
                    body.writeUtf8("[")
                    for (i in 0 until 300) {
                        if (i > 0) body.writeUtf8(",")
                        val label = listOf("SPORTS", "CINEMA", "NEWS", "KIDS", "DOCS", "MUSIC")[i % 6]
                        body.writeUtf8("""{"category_id":"$i","category_name":"${countries[i % countries.size]}| $label $i","parent_id":0}""")
                    }
                    body.writeUtf8("]")
                }
                "get_live_streams" -> list(body, sizes.getValue(XtreamSection.LIVE)) { i ->
                    """{"num":$i,"name":"Channel $i Sports HD","stream_type":"live","stream_id":$i,"stream_icon":"http://img.example/$i.png","epg_channel_id":"ch$i.example","category_id":"${i % 300}","tv_archive":0}"""
                }
                "get_vod_streams" -> list(body, sizes.getValue(XtreamSection.MOVIES)) { i ->
                    """{"num":$i,"name":"Film $i (2024)","stream_type":"movie","stream_id":"$i","stream_icon":"http://img.example/m$i.jpg","rating":"7.1","category_id":"${i % 300}","container_extension":"mkv"}"""
                }
                "get_series" -> list(body, sizes.getValue(XtreamSection.SERIES)) { i ->
                    """{"num":$i,"name":"Série $i","series_id":$i,"cover":"http://img.example/s$i.jpg","plot":"Lorem ipsum $i","category_id":"${i % 300}"}"""
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
}
