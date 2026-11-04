package fr.ahmad.tclstreamx

import android.content.Context
import fr.ahmad.tclstreamx.data.catalog.BuiltInCatalog
import fr.ahmad.tclstreamx.data.check.AvailabilityChecker
import fr.ahmad.tclstreamx.data.check.StreamProbe
import fr.ahmad.tclstreamx.data.db.AppDatabase
import fr.ahmad.tclstreamx.data.db.PlaylistKind
import fr.ahmad.tclstreamx.data.epg.EpgRepository
import fr.ahmad.tclstreamx.data.m3u.PlaylistImporter
import fr.ahmad.tclstreamx.data.settings.SettingsStore
import fr.ahmad.tclstreamx.data.sports.SportsRepository
import fr.ahmad.tclstreamx.data.remote.RemoteHub
import fr.ahmad.tclstreamx.data.update.Updater
import fr.ahmad.tclstreamx.data.vod.VodRepository
import fr.ahmad.tclstreamx.player.cast.ChromecastController
import fr.ahmad.tclstreamx.data.xtream.KeystoreSecretBox
import fr.ahmad.tclstreamx.data.xtream.Redactor
import fr.ahmad.tclstreamx.data.xtream.XtreamClient
import fr.ahmad.tclstreamx.data.xtream.XtreamRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Manual dependency container: the app is small enough not to need a DI framework. */
class AppContainer(private val context: Context) {
    // Only the exception type and a redacted message are logged: never a stack trace that could carry an address with a login.
    private val errors = CoroutineExceptionHandler { _, t ->
        android.util.Log.w("TclStreamX", "background task failed: ${t.javaClass.simpleName} ${Redactor.redact(t.message)}")
    }
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + errors)

    val db: AppDatabase = AppDatabase.build(context)
    val settings = SettingsStore(context)

    /** General client: playlists, guides, APIs, logos. */
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .cache(Cache(context.cacheDir.resolve("http"), 32L * 1024 * 1024))
        .build()

    /** Player client: no HTTP cache (live segments), shorter connect timeout to fail over quickly. */
    val playerHttp: OkHttpClient = http.newBuilder()
        .cache(null)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Probe client: short timeouts, many small requests. */
    private val probeHttp: OkHttpClient = http.newBuilder()
        .cache(null)
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Panel API client: lists of tens of megabytes, so a longer read timeout and no HTTP cache. */
    private val xtreamHttp: OkHttpClient = http.newBuilder()
        .cache(null)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()
    val xtreamClient = XtreamClient(xtreamHttp)
    val xtream = XtreamRepository(db, xtreamClient, KeystoreSecretBox())
    val importer = PlaylistImporter(db, http, context.contentResolver, xtream, xtreamClient)
    val catalog = BuiltInCatalog(context, db)
    val checker = AvailabilityChecker(db, StreamProbe(probeHttp), appScope)
    val epg = EpgRepository(db, http) { xtream.resolve(it) }
    val sports = SportsRepository(context, http)
    val vod = VodRepository(context, http)
    /** Phone to TV over Wi-Fi between two copies of the app. */
    val remote = RemoteHub(context, http)
    /** Chromecast / Google TV sender (lazy: needs the main thread and Google Play services). */
    val chromecast = ChromecastController(context)
    /** New builds of the app from its GitHub releases (only builds whose tests passed). */
    val updater = Updater(context, http)

    fun startup() {
        appScope.launch {
            catalog.installOrUpdate()
            runCatching { updater.check() }
            val s = settings.current()
            // Parental lock: locked categories start hidden at every launch.
            if (s.pinSet) db.locks().setHidden(1)
            refreshStale()
        }
    }

    @Volatile private var backgroundAt = 0L
    private val refreshing = java.util.concurrent.atomic.AtomicBoolean(false)

    private var updateLoop: kotlinx.coroutines.Job? = null

    /** The app left the screen (home button, other app, TV standby). */
    fun onBackground() {
        backgroundAt = System.currentTimeMillis()
        updateLoop?.cancel()
        updateLoop = null
    }

    /**
     * While the app is on screen it looks for a new build every half hour, so the update banner on
     * Home appears by itself (a TV app can stay open for days). Two requests an hour stay far below
     * GitHub's limit for unauthenticated clients.
     */
    private fun watchUpdates() {
        if (updateLoop?.isActive == true) return
        updateLoop = appScope.launch {
            while (true) {
                kotlinx.coroutines.delay(UPDATE_EVERY_MS)
                val s = updater.state.value
                if (s !is fr.ahmad.tclstreamx.data.update.UpdateState.Downloading && s !is fr.ahmad.tclstreamx.data.update.UpdateState.Ready) {
                    runCatching { updater.check() }
                }
            }
        }
    }

    /**
     * The app is back on screen. A TV keeps the process alive for days, so the work done at launch is
     * repeated here: locked categories close again after a long absence, and stale lists, guide and
     * built-in checks are refreshed.
     */
    fun onForeground() {
        watchUpdates()
        val left = backgroundAt
        if (left == 0L) return
        backgroundAt = 0L
        appScope.launch {
            if (settings.current().pinSet && System.currentTimeMillis() - left > RELOCK_AFTER_MS) db.locks().setHidden(1)
            if (System.currentTimeMillis() - left > UPDATE_EVERY_MS) runCatching { updater.check() }
            refreshStale()
        }
    }

    /** Built-in channel check, playlists, accounts and guide: each only when older than its period. One run at a time. */
    private suspend fun refreshStale() {
        if (!refreshing.compareAndSet(false, true)) return
        try {
            val s = settings.current()
            val now = System.currentTimeMillis()
            if (s.autoCheckBuiltin && now - s.lastBuiltinCheck > BUILTIN_CHECK_EVERY_MS) {
                db.playlists().builtin()?.let { checker.checkLineup(it.id, null, false) }
                settings.markBuiltinChecked(now)
            }
            // Playlists and accounts older than a day are refreshed (new films, episodes, channels).
            if (s.autoRefresh) {
                db.playlists().all()
                    .filter { (it.kind == PlaylistKind.URL || it.kind == PlaylistKind.XTREAM) && now - it.updatedAt > REFRESH_EVERY_MS }
                    .forEach { runCatching { importer.refresh(it.id) } }
            }
            if (now - s.lastEpgRefresh > EPG_EVERY_MS) {
                epg.refresh(s.epgUrl)
                settings.markEpgRefreshed(now)
            }
        } finally {
            refreshing.set(false)
        }
    }

    companion object {
        private const val BUILTIN_CHECK_EVERY_MS = 6 * 3600_000L
        private const val EPG_EVERY_MS = 12 * 3600_000L
        private const val REFRESH_EVERY_MS = 24 * 3600_000L
        private const val RELOCK_AFTER_MS = 10 * 60_000L
        private const val UPDATE_EVERY_MS = 30 * 60_000L
    }
}
