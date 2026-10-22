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
            val now = System.currentTimeMillis()
            if (s.autoCheckBuiltin && now - s.lastBuiltinCheck > BUILTIN_CHECK_EVERY_MS) {
                db.playlists().builtin()?.let { checker.checkLineup(it.id, null, false) }
                settings.markBuiltinChecked(now)
            }
            // Parental lock: locked categories start hidden at every launch.
            if (s.pinSet) db.locks().setHidden(1)
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
        }
    }

    companion object {
        private const val BUILTIN_CHECK_EVERY_MS = 6 * 3600_000L
        private const val EPG_EVERY_MS = 12 * 3600_000L
        private const val REFRESH_EVERY_MS = 24 * 3600_000L
    }
}
