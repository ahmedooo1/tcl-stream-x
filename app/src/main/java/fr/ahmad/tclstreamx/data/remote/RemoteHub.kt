package fr.ahmad.tclstreamx.data.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A TV running StreamX found on the local network. */
data class RemoteTv(val name: String, val host: String, val port: Int, val paired: Boolean)

enum class SendResult { OK, NOT_PAIRED, NETWORK }

/**
 * "Send to the TV" between two devices running StreamX on the same Wi-Fi:
 * the TV side runs [RemotePlayServer] and announces itself with DNS-SD (NSD); the phone side finds
 * it, pairs once with the code shown on the TV, then sends the channel it is playing.
 */
class RemoteHub(private val context: Context, http: OkHttpClient) {
    private val nsd: NsdManager? = context.getSystemService(NsdManager::class.java)
    private val client = http.newBuilder().cache(null).connectTimeout(4, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
    private val receiverPrefs = context.getSharedPreferences("remote_receiver", Context.MODE_PRIVATE)
    private val senderPrefs = context.getSharedPreferences("remote_sender", Context.MODE_PRIVATE)

    // ---- TV side --------------------------------------------------------------------------

    private val _pairingCode = MutableStateFlow<String?>(null)
    /** Code to show on the TV while a phone is pairing. */
    val pairingCode: StateFlow<String?> = _pairingCode.asStateFlow()
    private val _incoming = MutableSharedFlow<RemoteMedia>(extraBufferCapacity = 1)
    val incoming: SharedFlow<RemoteMedia> = _incoming.asSharedFlow()
    private var server: RemotePlayServer? = null
    private val _receiverAddress = MutableStateFlow<String?>(null)
    /** "192.168.1.50:8765" while the TV receives, to type on the phone if discovery is blocked. */
    val receiverAddress: StateFlow<String?> = _receiverAddress.asStateFlow()
    private var registration: NsdManager.RegistrationListener? = null

    private val tokenStore = object : TokenStore {
        override fun contains(token: String) = receiverPrefs.getStringSet(KEY_TOKENS, emptySet())!!.contains(token)
        override fun add(token: String) {
            val all = receiverPrefs.getStringSet(KEY_TOKENS, emptySet())!!.toMutableSet()
            all += token
            receiverPrefs.edit().putStringSet(KEY_TOKENS, all.toList().takeLast(MAX_PAIRED).toSet()).apply()
        }
    }

    @Synchronized
    fun startReceiver(deviceName: String) {
        val manager = nsd ?: return
        if (server != null) return
        val s = RemotePlayServer(deviceName, tokenStore, { _pairingCode.value = it }, { _incoming.tryEmit(it) })
        val port = try { s.start() } catch (e: IOException) { return }
        server = s
        _receiverAddress.value = localIpv4()?.let { "$it:$port" }
        val info = NsdServiceInfo().apply {
            serviceName = deviceName
            serviceType = RemotePlayServer.SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        registration = listener
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    @Synchronized
    fun stopReceiver() {
        registration?.let { l -> runCatching { nsd?.unregisterService(l) } }
        registration = null
        server?.stop()
        server = null
        _receiverAddress.value = null
        _pairingCode.value = null
    }

    // ---- phone side -----------------------------------------------------------------------

    private val _tvs = MutableStateFlow<List<RemoteTv>>(emptyList())
    val tvs: StateFlow<List<RemoteTv>> = _tvs.asStateFlow()
    private var discovery: NsdManager.DiscoveryListener? = null
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    @Synchronized
    fun startDiscovery() {
        val manager = nsd ?: return
        if (discovery != null) return
        _tvs.value = emptyList()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) = enqueueResolve(info)
            override fun onServiceLost(info: NsdServiceInfo) {
                _tvs.value = _tvs.value.filterNot { it.name == info.serviceName }
            }
        }
        discovery = listener
        runCatching { manager.discoverServices(RemotePlayServer.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    @Synchronized
    fun stopDiscovery() {
        discovery?.let { l -> runCatching { nsd?.stopServiceDiscovery(l) } }
        discovery = null
        resolveQueue.clear()
        resolving = false
    }

    /** NSD resolves one service at a time on older Android versions: queue them. */
    @Synchronized
    private fun enqueueResolve(info: NsdServiceInfo) {
        resolveQueue.addLast(info)
        if (!resolving) resolveNext()
    }

    @Synchronized
    private fun resolveNext() {
        val next = resolveQueue.removeFirstOrNull()
        val manager = nsd
        if (next == null || manager == null) {
            resolving = false
            return
        }
        resolving = true
        @Suppress("DEPRECATION")
        manager.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = resolveNext()
            override fun onServiceResolved(info: NsdServiceInfo) {
                val host = info.host?.hostAddress
                if (host != null) {
                    val tv = RemoteTv(info.serviceName, host, info.port, token(info.serviceName) != null)
                    _tvs.value = (_tvs.value.filterNot { it.name == tv.name } + tv).sortedBy { it.name }
                }
                resolveNext()
            }
        })
    }

    /**
     * TV typed by hand ("192.168.1.50" or "192.168.1.50:8765"): checked with /info, then listed like a
     * discovered one. Used when the router blocks multicast discovery (guest or "isolated" Wi-Fi).
     */
    suspend fun addManual(input: String): RemoteTv? {
        val text = input.trim()
        val host = text.substringBefore(':')
        val port = text.substringAfter(':', "").toIntOrNull() ?: RemotePlayServer.PREFERRED_PORT
        if (!Regex("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$").matches(host) || port !in 1..65535) return null
        val probe = RemoteTv(host, host, port, false)
        val body = withContext(Dispatchers.IO) {
            try {
                client.newCall(Request.Builder().url(base(probe) + "/info").build()).execute().use { r ->
                    if (r.isSuccessful) r.body?.string().orEmpty().take(2048) else null
                }
            } catch (e: IOException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
        } ?: return null
        if (!body.contains("\"TCLStreamX\"")) return null
        val name = Regex("\"name\":\"([^\"]{1,60})\"").find(body)?.groupValues?.get(1) ?: host
        val tv = RemoteTv(name, host, port, token(name) != null)
        _tvs.value = (_tvs.value.filterNot { it.name == tv.name } + tv).sortedBy { it.name }
        return tv
    }

    private fun localIpv4(): String? = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    private fun token(name: String): String? = senderPrefs.getString("token:$name", null)

    private fun base(tv: RemoteTv): String {
        val host = if (':' in tv.host) "[${tv.host.substringBefore('%')}]" else tv.host
        return "http://$host:${tv.port}"
    }

    /** Asks the TV to show a pairing code. */
    suspend fun requestPairing(tv: RemoteTv): Boolean = post(base(tv) + "/pair", "{}", null)?.first == 200

    suspend fun confirmPairing(tv: RemoteTv, code: String): Boolean {
        val (status, body) = post(base(tv) + "/pair/confirm", "{\"code\":${RemotePlayServer.json(code.trim())}}", null) ?: return false
        if (status != 200) return false
        val token = Regex("\"token\":\"([0-9a-f]{32})\"").find(body)?.groupValues?.get(1) ?: return false
        senderPrefs.edit().putString("token:${tv.name}", token).apply()
        _tvs.value = _tvs.value.map { if (it.name == tv.name) it.copy(paired = true) else it }
        return true
    }

    suspend fun send(tv: RemoteTv, media: RemoteMedia): SendResult {
        val token = token(tv.name) ?: return SendResult.NOT_PAIRED
        val (status, _) = post(base(tv) + "/play", RemotePlayServer.mediaJson(media), token) ?: return SendResult.NETWORK
        return when (status) {
            200 -> SendResult.OK
            401 -> {
                senderPrefs.edit().remove("token:${tv.name}").apply()
                _tvs.value = _tvs.value.map { if (it.name == tv.name) it.copy(paired = false) else it }
                SendResult.NOT_PAIRED
            }
            else -> SendResult.NETWORK
        }
    }

    private suspend fun post(url: String, json: String, token: String?): Pair<Int, String>? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).post(json.toRequestBody("application/json".toMediaType()))
            .apply { if (token != null) header("X-Token", token) }
            .build()
        try {
            client.newCall(req).execute().use { it.code to (it.body?.string().orEmpty().take(4096)) }
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    companion object {
        private const val KEY_TOKENS = "tokens"
        private const val MAX_PAIRED = 20
    }
}
