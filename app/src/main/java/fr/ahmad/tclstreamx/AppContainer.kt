package fr.ahmad.tclstreamx

import android.content.Context
import fr.ahmad.tclstreamx.data.catalog.BuiltInCatalog
import fr.ahmad.tclstreamx.data.check.AvailabilityChecker
import fr.ahmad.tclstreamx.data.check.StreamProbe
import fr.ahmad.tclstreamx.data.db.AppDatabase
import fr.ahmad.tclstreamx.data.epg.EpgRepository
import fr.ahmad.tclstreamx.data.m3u.PlaylistImporter
import fr.ahmad.tclstreamx.data.settings.SettingsStore
import fr.ahmad.tclstreamx.data.sports.SportsRepository
import fr.ahmad.tclstreamx.data.vod.VodRepository
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
    private val errors = CoroutineExceptionHandler { _, t -> android.util.Log.w("TclStreamX", "background task failed", t) }
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

    val importer = PlaylistImporter(db, http, context.contentResolver)
    val catalog = BuiltInCatalog(context, db)
    val checker = AvailabilityChecker(db, StreamProbe(probeHttp), appScope)
    val epg = EpgRepository(db, http)
    val sports = SportsRepository(context, http)
    val vod = VodRepository(context, http)

    fun startup() {
        appScope.launch {
            catalog.installOrUpdate()
            val s = settings.current()
            val now = System.currentTimeMillis()
            if (s.autoCheckBuiltin && now - s.lastBuiltinCheck > BUILTIN_CHECK_EVERY_MS) {
                db.playlists().builtin()?.let { checker.checkLineup(it.id, null, false) }
                settings.markBuiltinChecked(now)
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
    }
}
