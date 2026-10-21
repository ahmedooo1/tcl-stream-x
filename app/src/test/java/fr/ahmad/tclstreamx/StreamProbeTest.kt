package fr.ahmad.tclstreamx

import fr.ahmad.tclstreamx.data.check.StreamProbe
import fr.ahmad.tclstreamx.data.db.StreamStatus
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Exercises the real probe over HTTP against a local server: no network needed. */
class StreamProbeTest {
    private lateinit var server: MockWebServer
    private val probe = StreamProbe(OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build())

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/ok/master.m3u8" -> hls("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=640x360\nv/index.m3u8\n")
                "/ok/v/index.m3u8" -> hls("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nseg1.ts\n#EXTINF:6,\nseg2.ts\n")
                "/ok/v/seg2.ts" -> MockResponse().setResponseCode(206).setHeader("Content-Type", "video/mp2t").setBody("G".repeat(4096))
                "/geo/master.m3u8" -> MockResponse().setResponseCode(403)
                "/drm/master.m3u8" -> hls("#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://k\"\n#EXTINF:6,\na.ts\n")
                "/deadseg/index.m3u8" -> hls("#EXTM3U\n#EXTINF:6,\nmissing.ts\n")
                "/html" -> MockResponse().setHeader("Content-Type", "text/html").setBody("<html>portal</html>")
                "/ua/index.m3u8" -> if (request.getHeader("User-Agent") == "Custom/1" && request.getHeader("Referer") == "https://ref/")
                    hls("#EXTM3U\n#EXTINF:6,\ns.ts\n") else MockResponse().setResponseCode(403)
                "/ua/s.ts" -> MockResponse().setBody("x".repeat(100))
                "/slow/index.m3u8" -> hls("#EXTM3U\n").setBodyDelay(5, TimeUnit.SECONDS)
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    private fun hls(body: String) = MockResponse().setHeader("Content-Type", "application/vnd.apple.mpegurl").setBody(body)

    @After
    fun tearDown() = server.shutdown()

    private fun url(path: String) = server.url(path).toString()

    @Test fun onlineWhenManifestVariantAndSegmentLoad() =
        assertEquals(StreamStatus.ONLINE, probe.probe(url("/ok/master.m3u8"), null, null).status)

    @Test fun forbiddenIsReportedNotBypassed() =
        assertEquals(StreamStatus.FORBIDDEN, probe.probe(url("/geo/master.m3u8"), null, null).status)

    @Test fun drmIsDetected() =
        assertEquals(StreamStatus.DRM, probe.probe(url("/drm/master.m3u8"), null, null).status)

    @Test fun missingSegmentIsOffline() =
        assertEquals(StreamStatus.OFFLINE, probe.probe(url("/deadseg/index.m3u8"), null, null).status)

    @Test fun htmlPortalIsInvalid() =
        assertEquals(StreamStatus.INVALID, probe.probe(url("/html"), null, null).status)

    @Test fun sendsPlaylistHeaders() =
        assertEquals(StreamStatus.ONLINE, probe.probe(url("/ua/index.m3u8"), "Custom/1", "https://ref/").status)

    @Test fun timeoutIsReported() =
        assertEquals(StreamStatus.TIMEOUT, probe.probe(url("/slow/index.m3u8"), null, null).status)
}
