package fr.ahmad.tclstreamx.player.cast

import android.content.Context
import android.net.Uri
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.common.images.WebImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Cast options: Google's Default Media Receiver, which every Chromecast and Google TV runs; no registration needed. */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder().setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID).build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

data class CastDevice(val id: String, val name: String, val description: String?)

data class CastMedia(val url: String, val title: String, val live: Boolean, val image: String?)

/**
 * Chromecast / Google TV "Chromecast built-in" sender. Initialised lazily on the main thread;
 * devices without Google Play services simply report that casting is unavailable.
 */
class ChromecastController(private val context: Context) {
    private var castContext: CastContext? = null
    private var router: MediaRouter? = null
    private var selector: MediaRouteSelector? = null
    private var pending: CastMedia? = null

    private val _devices = MutableStateFlow<List<CastDevice>>(emptyList())
    val devices: StateFlow<List<CastDevice>> = _devices.asStateFlow()

    /** Name of the device currently playing, null when not casting. */
    private val _connected = MutableStateFlow<String?>(null)
    val connected: StateFlow<String?> = _connected.asStateFlow()

    private val routerCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) = onConnected(session)
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = onConnected(session)
        override fun onSessionEnded(session: CastSession, error: Int) { _connected.value = null }
        override fun onSessionStarting(session: CastSession) = Unit
        override fun onSessionStartFailed(session: CastSession, error: Int) { _connected.value = null }
        override fun onSessionEnding(session: CastSession) = Unit
        override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
        override fun onSessionResumeFailed(session: CastSession, error: Int) = Unit
        override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
    }

    /** False when Google Play services (and so Cast) is not available on this device. */
    fun available(): Boolean = init() != null

    private fun init(): CastContext? {
        castContext?.let { return it }
        return try {
            @Suppress("DEPRECATION")
            val cc = CastContext.getSharedInstance(context)
            castContext = cc
            router = MediaRouter.getInstance(context)
            selector = MediaRouteSelector.Builder()
                .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
                .build()
            cc.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
            cc.sessionManager.currentCastSession?.let { s -> _connected.value = s.castDevice?.friendlyName }
            cc
        } catch (e: Exception) {
            // No Play services, outdated Play services or an unsupported device: casting is just not offered.
            null
        }
    }

    fun startScan() {
        init() ?: return
        router?.addCallback(selector!!, routerCallback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        refresh()
    }

    fun stopScan() {
        router?.removeCallback(routerCallback)
    }

    private fun refresh() {
        val r = router ?: return
        val sel = selector ?: return
        _devices.value = r.routes
            .filter { !it.isDefault && it.isEnabled && it.matchesSelector(sel) }
            .map { CastDevice(it.id, it.name, it.description) }
    }

    /** Connects to the device (if needed) and starts playing [media] on it. */
    fun cast(deviceId: String, media: CastMedia) {
        val cc = init() ?: return
        pending = media
        val session = cc.sessionManager.currentCastSession
        val route = router?.routes?.firstOrNull { it.id == deviceId } ?: return
        if (session != null && session.isConnected && route.isSelected) {
            onConnected(session)
        } else {
            router?.selectRoute(route)
        }
    }

    fun stop() {
        castContext?.sessionManager?.endCurrentSession(true)
        _connected.value = null
    }

    private fun onConnected(session: CastSession) {
        _connected.value = session.castDevice?.friendlyName
        val media = pending ?: return
        pending = null
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, media.title)
            media.image?.let { addImage(WebImage(Uri.parse(it))) }
        }
        val info = MediaInfo.Builder(media.url)
            .setStreamType(if (media.live) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(contentType(media.url, media.live))
            .setMetadata(metadata)
            .build()
        session.remoteMediaClient?.load(MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).build())
    }

    companion object {
        fun contentType(url: String, live: Boolean): String {
            val path = Uri.parse(url).path.orEmpty().lowercase()
            return when {
                path.endsWith(".m3u8") || path.endsWith(".m3u") -> "application/x-mpegURL"
                path.endsWith(".mpd") -> "application/dash+xml"
                path.endsWith(".mp4") || path.endsWith(".m4v") -> "video/mp4"
                path.endsWith(".mkv") -> "video/x-matroska"
                path.endsWith(".webm") -> "video/webm"
                path.endsWith(".ts") -> "video/mp2t"
                live -> "application/x-mpegURL"
                else -> "video/mp4"
            }
        }
    }
}
