package fr.ahmad.tclstreamx.data.lan

import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Tiny HTTP server, active only while the "send from phone" screen is open, so a playlist
 * can be sent from a phone browser instead of typing a URL with the remote.
 * Protections: random 6-digit PIN, body size limit, one request at a time, socket timeouts.
 */
class LanUploadServer(
    private val cacheDir: File,
    private val onFile: (name: String, file: File) -> Unit,
    private val onUrl: (name: String, url: String) -> Unit,
) {
    val pin: String = (SecureRandom().nextInt(900_000) + 100_000).toString()
    private var server: ServerSocket? = null
    @Volatile private var running = false

    val port: Int get() = server?.localPort ?: 0

    fun start(): Int {
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(PORT_PREFERRED).takeIf { isFree(PORT_PREFERRED) } ?: InetSocketAddress(0))
        server = socket
        running = true
        thread(name = "lan-upload", isDaemon = true) {
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
    }

    private fun handle(client: Socket) {
        client.soTimeout = 30_000
        val input = BufferedInputStream(client.getInputStream())
        val out = client.getOutputStream()
        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) return respond(out, 400, "text/plain", "bad request")
        val method = parts[0]
        val target = parts[1]
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0 && headers.size < 50) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val path = target.substringBefore('?')
        val query = parseQuery(target.substringAfter('?', ""))
        when {
            method == "GET" && path == "/" -> respond(out, 200, "text/html; charset=utf-8", PAGE)
            method == "POST" && (path == "/upload" || path == "/url") -> {
                if (query["pin"] != pin) return respond(out, 403, "application/json", "{\"ok\":false,\"error\":\"pin\"}")
                val length = headers["content-length"]?.toLongOrNull()
                    ?: return respond(out, 411, "application/json", "{\"ok\":false,\"error\":\"length\"}")
                if (length <= 0 || length > MAX_BODY) return respond(out, 413, "application/json", "{\"ok\":false,\"error\":\"size\"}")
                val name = (query["name"] ?: "Playlist").take(80).filter { it.isLetterOrDigit() || it in " ._-()" }.ifBlank { "Playlist" }
                if (path == "/upload") {
                    val file = File.createTempFile("lan-", ".m3u", cacheDir)
                    file.outputStream().use { copyExactly(input, it, length) }
                    respond(out, 200, "application/json", "{\"ok\":true}")
                    onFile(name, file)
                } else {
                    if (length > 8192) return respond(out, 413, "application/json", "{\"ok\":false,\"error\":\"size\"}")
                    val body = ByteArray(length.toInt())
                    var read = 0
                    while (read < body.size) {
                        val n = input.read(body, read, body.size - read)
                        if (n < 0) break
                        read += n
                    }
                    val url = String(body, 0, read, Charsets.UTF_8).trim()
                    if (!(url.startsWith("http://") || url.startsWith("https://")) || url.any { it.isWhitespace() }) {
                        return respond(out, 400, "application/json", "{\"ok\":false,\"error\":\"url\"}")
                    }
                    respond(out, 200, "application/json", "{\"ok\":true}")
                    onUrl(name, url)
                }
            }
            else -> respond(out, 404, "text/plain", "not found")
        }
    }

    private fun copyExactly(input: InputStream, out: OutputStream, length: Long) {
        val buf = ByteArray(64 * 1024)
        var remaining = length
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) throw IOException("truncated upload")
            out.write(buf, 0, n)
            remaining -= n
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 8192) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
        throw IOException("header too long")
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code OK\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    private fun parseQuery(q: String): Map<String, String> =
        q.split('&').filter { it.contains('=') }.take(10).associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }

    private fun isFree(port: Int): Boolean = try {
        ServerSocket(port).close(); true
    } catch (e: IOException) {
        false
    }

    companion object {
        const val PORT_PREFERRED = 8765
        const val MAX_BODY = 150L * 1024 * 1024

        fun localIpv4(): String? = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
                ?.hostAddress
        } catch (e: IOException) {
            null
        }

        private val PAGE = """<!doctype html><html lang="fr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>StreamX</title>
<style>body{font-family:system-ui,sans-serif;background:#0c0d0f;color:#f2f3f4;margin:0;padding:24px;max-width:520px}
h1{font-size:28px;margin:0 0 4px}p{color:#9aa0a6}label{display:block;margin:18px 0 6px}
input{width:100%;box-sizing:border-box;padding:12px;border:1px solid #2b2f35;background:#15171a;color:#f2f3f4;font-size:16px}
button{margin-top:12px;padding:12px 18px;border:0;background:#ff4d2e;color:#0c0d0f;font-weight:700;font-size:16px}
#s{margin-top:16px;color:#59d98e}</style></head><body>
<h1>StreamX</h1><p>Envoyer une playlist M3U vers la TV · إرسال قائمة تشغيل إلى التلفاز</p>
<label>PIN</label><input id="pin" inputmode="numeric" maxlength="6">
<label>Nom · الاسم</label><input id="nm" value="Ma playlist">
<label>Fichier .m3u / .m3u8</label><input id="file" type="file" accept=".m3u,.m3u8,audio/x-mpegurl,application/vnd.apple.mpegurl,text/plain">
<button onclick="sendFile()">Envoyer le fichier</button>
<label>ou URL</label><input id="url" type="url" placeholder="https://...">
<button onclick="sendUrl()">Envoyer l'URL</button><div id="s"></div>
<script>
function q(){return '?pin='+encodeURIComponent(pin.value)+'&name='+encodeURIComponent(nm.value)}
function done(r){s.textContent=r.ok?'Reçu ✓ regardez la TV':'Erreur: '+(r.error||'')}
function sendFile(){var f=file.files[0];if(!f){s.textContent='Choisissez un fichier';return}
fetch('/upload'+q(),{method:'POST',body:f}).then(r=>r.json()).then(done).catch(e=>s.textContent=e)}
function sendUrl(){fetch('/url'+q(),{method:'POST',body:url.value}).then(r=>r.json()).then(done).catch(e=>s.textContent=e)}
</script></body></html>"""
    }
}
