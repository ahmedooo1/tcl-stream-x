package fr.ahmad.tclstreamx

import fr.ahmad.tclstreamx.data.remote.RemoteMedia
import fr.ahmad.tclstreamx.data.remote.RemotePlayServer
import fr.ahmad.tclstreamx.data.remote.TokenStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Phone-to-TV receiver: pairing with an on-screen code, token check, validation of what is played. */
class RemotePlayServerTest {
    private val tokens = HashSet<String>()
    private val store = object : TokenStore {
        override fun contains(token: String) = token in tokens
        override fun add(token: String) { tokens += token }
    }
    @Volatile private var shownCode: String? = null
    private val played = ArrayList<RemoteMedia>()
    private lateinit var server: RemotePlayServer
    private val http = OkHttpClient()
    private var base = ""

    @Before fun setUp() {
        server = RemotePlayServer("Salon \"TV\"", store, { shownCode = it }, { synchronized(played) { played += it } })
        base = "http://127.0.0.1:${server.start()}"
    }

    @After fun tearDown() = server.stop()

    private fun post(path: String, json: String, token: String? = null): Pair<Int, String> {
        val req = Request.Builder().url(base + path).post(json.toRequestBody("application/json".toMediaType()))
            .apply { if (token != null) header("X-Token", token) }.build()
        return http.newCall(req).execute().use { it.code to it.body!!.string() }
    }

    private val media = RemoteMedia("France 24", listOf("https://example.org/live.m3u8"), "UA", null, true, null)

    @Test fun infoAnswersWithEscapedName() {
        val body = http.newCall(Request.Builder().url("$base/info").build()).execute().use { it.body!!.string() }
        assertEquals("""{"app":"TCLStreamX","v":1,"name":"Salon \"TV\""}""", body)
    }

    @Test fun playRequiresAPairedToken() {
        assertEquals(401, post("/play", RemotePlayServer.mediaJson(media)).first)
        assertEquals(401, post("/play", RemotePlayServer.mediaJson(media), "0".repeat(32)).first)
        assertTrue(played.isEmpty())
    }

    @Test fun pairingWithTheCodeShownOnTheTvGivesAWorkingToken() {
        assertEquals(200, post("/pair", "{}").first)
        val code = shownCode
        assertNotNull(code)
        assertEquals(403, post("/pair/confirm", """{"code":"${if (code == "1111") "2222" else "1111"}"}""").first)
        val (status, body) = post("/pair/confirm", """{"code":"$code"}""")
        assertEquals(200, status)
        assertNull("the code disappears from the TV once used", shownCode)
        val token = Regex("\"token\":\"([0-9a-f]{32})\"").find(body)!!.groupValues[1]
        assertEquals(200, post("/play", RemotePlayServer.mediaJson(media), token).first)
        assertEquals(listOf(media), played)
        // The same code cannot be reused.
        assertEquals(403, post("/pair/confirm", """{"code":"$code"}""").first)
    }

    @Test fun fiveWrongCodesCancelThePairing() {
        post("/pair", "{}")
        val code = shownCode!!
        val wrong = if (code == "1000") "1001" else "1000"
        repeat(5) { assertEquals(403, post("/pair/confirm", """{"code":"$wrong"}""").first) }
        assertNull(shownCode)
        assertEquals(403, post("/pair/confirm", """{"code":"$code"}""").first)
    }

    @Test fun onlyHttpStreamsAreAccepted() {
        post("/pair", "{}")
        val body = post("/pair/confirm", """{"code":"$shownCode"}""").second
        val token = Regex("\"token\":\"([0-9a-f]{32})\"").find(body)!!.groupValues[1]
        assertEquals(400, post("/play", """{"title":"x","urls":["file:///sdcard/a.mp4","javascript:alert(1)"]}""", token).first)
        assertEquals(400, post("/play", "not json", token).first)
        assertTrue(played.isEmpty())
        val parsed = RemotePlayServer.toMedia(RemotePlayServer.parse(RemotePlayServer.mediaJson(media))!!)
        assertEquals(media, parsed)
    }
}
