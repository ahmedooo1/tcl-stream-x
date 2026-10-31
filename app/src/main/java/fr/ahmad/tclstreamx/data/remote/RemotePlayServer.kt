package fr.ahmad.tclstreamx.data.remote

import fr.ahmad.tclstreamx.data.xtream.JsonStream
import fr.ahmad.tclstreamx.data.xtream.text
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlin.concurrent.thread

/** A stream sent from a phone, validated: http(s) addresses only, bounded sizes. */
data class RemoteMedia(
    val title: String,
    val urls: List<String>,
    val userAgent: String?,
    val referrer: String?,
    val live: Boolean,
    val logo: String?,
)

/** Tokens of the phones the user paired with this TV. */
interface TokenStore {
    fun contains(token: String): Boolean
    fun add(token: String)
}

/**
 * Receiver running on the TV while the app is in the foreground: a phone running StreamX sends
 * the channel it is watching and the TV plays it.
 *
 * Pairing: the phone asks to pair, the TV shows a 4-digit code on screen, the user types it on the
 * phone and gets a random 128-bit token kept by both sides. Only requests carrying a known token can
 * start playback. A pairing code is valid 2 minutes and 5 attempts; one pairing at a time.
 */
class RemotePlayServer(
    private val deviceName: String,
    private val tokens: TokenStore,
    /** Shows the code on the TV (null hides it). */
    private val onPairingCode: (String?) -> Unit,
    private val onPlay: (RemoteMedia) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val random = SecureRandom()
    private var server: ServerSocket? = null
    @Volatile private var running = false

    private var pendingCode: String? = null
    private var pendingUntil = 0L
    private var attempts = 0

    val port: Int get() = server?.localPort ?: 0

    fun start(): Int {
        val socket = ServerSocket()
        socket.reuseAddress = true
        // A fixed port lets the user type the TV address by hand when discovery is blocked by the router.
        try {
            socket.bind(InetSocketAddress(PREFERRED_PORT))
        } catch (e: IOException) {
            socket.bind(InetSocketAddress(0))
        }
        server = socket
        running = true
        thread(name = "remote-play", isDaemon = true) {
            while (running) {
                val client = try { socket.accept() } catch (e: IOException) { break }
                try { handle(client) } catch (e: IOException) { /* client went away */ } finally { client.close() }
            }
        }
        return socket.localPort
    }

    fun stop() {
        running = false
        server?.close()
        server = null
        clearPairing()
    }

    @Synchronized
    private fun clearPairing() {
        if (pendingCode != null) onPairingCode(null)
        pendingCode = null
        attempts = 0
    }

    private fun handle(client: Socket) {
        client.soTimeout = 10_000
        val input = BufferedInputStream(client.getInputStream())
        val out = client.getOutputStream()
        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) return respond(out, 400, """{"ok":false,"error":"request"}""")
        val method = parts[0]
        val path = parts[1].substringBefore('?')
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0 && headers.size < 40) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length < 0 || length > MAX_BODY) return respond(out, 413, """{"ok":false,"error":"size"}""")
        val body = if (length > 0) readBody(input, length) else ""
        when {
            method == "GET" && path == "/info" -> respond(out, 200, """{"app":"TCLStreamX","v":1,"name":${json(deviceName)}}""")
            method == "POST" && path == "/pair" -> respond(out, 200, startPairing())
            method == "POST" && path == "/pair/confirm" -> {
                val code = parse(body)?.text("code").orEmpty()
                val token = confirm(code)
                if (token == null) respond(out, 403, """{"ok":false,"error":"code"}""")
                else respond(out, 200, """{"ok":true,"token":"$token"}""")
            }
            method == "POST" && path == "/play" -> {
                val token = headers["x-token"].orEmpty()
                if (token.length != 32 || !tokens.contains(token)) return respond(out, 401, """{"ok":false,"error":"token"}""")
                val media = parse(body)?.let(::toMedia) ?: return respond(out, 400, """{"ok":false,"error":"media"}""")
                onPlay(media)
                respond(out, 200, """{"ok":true}""")
            }
            else -> respond(out, 404, """{"ok":false,"error":"path"}""")
        }
    }

    @Synchronized
    private fun startPairing(): String {
        val now = clock()
        if (pendingCode != null && now < pendingUntil) return """{"ok":true,"pending":true}"""
        val code = (random.nextInt(9000) + 1000).toString()
        pendingCode = code
        pendingUntil = now + CODE_TTL_MS
        attempts = 0
        onPairingCode(code)
        return """{"ok":true}"""
    }

    @Synchronized
    private fun confirm(code: String): String? {
        val expected = pendingCode ?: return null
        if (clock() > pendingUntil) {
            clearPairing()
            return null
        }
        attempts++
        if (code != expected) {
            if (attempts >= MAX_ATTEMPTS) clearPairing()
            return null
        }
        val bytes = ByteArray(16).also(random::nextBytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        tokens.add(token)
        clearPairing()
        return token
    }

    companion object {
        /** DNS-SD type, written without the trailing dot as NsdManager expects. */
        const val SERVICE_TYPE = "_tclstreamx._tcp"
        const val PREFERRED_PORT = 8765
        private const val MAX_BODY = 32 * 1024
        private const val CODE_TTL_MS = 120_000L
        private const val MAX_ATTEMPTS = 5

        @Suppress("UNCHECKED_CAST")
        internal fun parse(body: String): Map<String, Any?>? =
            runCatching { JsonStream(java.io.StringReader(body)).readRoot() as? Map<String, Any?> }.getOrNull()

        internal fun toMedia(m: Map<String, Any?>): RemoteMedia? {
            val urls = (m["urls"] as? List<*>)?.mapNotNull { it as? String }
                ?.filter { (it.startsWith("http://") || it.startsWith("https://")) && it.length <= 4096 && it.none(Char::isWhitespace) }
                ?.take(5).orEmpty()
            if (urls.isEmpty()) return null
            return RemoteMedia(
                title = (m.text("title") ?: "StreamX").take(200),
                urls = urls,
                userAgent = m.text("userAgent")?.take(300),
                referrer = m.text("referrer")?.takeIf { it.startsWith("http") }?.take(1000),
                live = m["live"] == true,
                logo = m.text("logo")?.takeIf { it.startsWith("http") }?.take(1000),
            )
        }

        fun json(s: String?): String {
            if (s == null) return "null"
            val sb = StringBuilder("\"")
            for (c in s) {
                when {
                    c == '"' -> sb.append("\\\"")
                    c == '\\' -> sb.append("\\\\")
                    c == '\n' -> sb.append("\\n")
                    c == '\r' -> sb.append("\\r")
                    c == '\t' -> sb.append("\\t")
                    c < ' ' -> sb.append("\\u%04x".format(c.code))
                    else -> sb.append(c)
                }
            }
            return sb.append('"').toString()
        }

        fun mediaJson(m: RemoteMedia): String = buildString {
            append("{\"title\":").append(json(m.title))
            append(",\"urls\":[").append(m.urls.joinToString(",") { json(it) }).append(']')
            append(",\"userAgent\":").append(json(m.userAgent))
            append(",\"referrer\":").append(json(m.referrer))
            append(",\"live\":").append(m.live)
            append(",\"logo\":").append(json(m.logo))
            append('}')
        }

        private fun readLine(input: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b == -1) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(b.toChar())
                if (sb.length > 8192) throw IOException("line too long")
            }
        }

        private fun readBody(input: InputStream, length: Int): String {
            val bytes = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(bytes, read, length - read)
                if (n == -1) break
                read += n
            }
            return String(bytes, 0, read, Charsets.UTF_8)
        }

        private fun respond(out: OutputStream, code: Int, body: String) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val status = when (code) { 200 -> "OK"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 413 -> "Payload Too Large"; else -> "Bad Request" }
            out.write("HTTP/1.1 $code $status\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(bytes)
            out.flush()
        }
    }
}
