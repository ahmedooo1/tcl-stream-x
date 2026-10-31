package fr.ahmad.tclstreamx

import fr.ahmad.tclstreamx.data.lan.LanUploadServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LanUploadServerTest {
    private lateinit var dir: File
    private lateinit var server: LanUploadServer
    private var receivedName: String? = null
    private var receivedText: String? = null
    private var receivedUrl: String? = null
    private val latch = CountDownLatch(1)
    private val http = OkHttpClient()
    private var port = 0

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("lan").toFile()
        server = LanUploadServer(
            dir,
            onFile = { n, f -> receivedName = n; receivedText = f.readText(); latch.countDown() },
            onUrl = { n, u -> receivedName = n; receivedUrl = u; latch.countDown() },
        )
        port = server.start()
    }

    @After
    fun tearDown() {
        server.stop()
        dir.deleteRecursively()
    }

    private fun post(path: String, body: String) = http.newCall(
        Request.Builder().url("http://127.0.0.1:$port$path").post(body.toRequestBody("text/plain".toMediaType())).build()
    ).execute().use { it.code }

    @Test
    fun servesPage() {
        val html = http.newCall(Request.Builder().url("http://127.0.0.1:$port/").build()).execute().use { it.body!!.string() }
        assertTrue(html.contains("StreamX"))
    }

    @Test
    fun rejectsWrongPin() {
        assertEquals(403, post("/upload?pin=000000&name=x", "#EXTM3U"))
    }

    @Test
    fun acceptsFileWithPinAndSanitizesName() {
        assertEquals(200, post("/upload?pin=${server.pin}&name=My%20List%3Cscript%3E", "#EXTM3U\n#EXTINF:-1,A\nhttp://a/b.m3u8\n"))
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals("My Listscript", receivedName)
        assertTrue(receivedText!!.startsWith("#EXTM3U"))
    }

    @Test
    fun acceptsOnlyHttpUrls() {
        assertEquals(400, post("/url?pin=${server.pin}&name=x", "file:///etc/passwd"))
        assertEquals(200, post("/url?pin=${server.pin}&name=x", "https://example.com/list.m3u"))
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals("https://example.com/list.m3u", receivedUrl)
    }
}
