package fr.ahmad.tclstreamx

import android.app.Application
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import fr.ahmad.tclstreamx.data.xtream.Redactor

class TclApp : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        installRedactingPlayerLog()
        container = AppContainer(this)
        container.startup()
    }

    /**
     * The player logs load errors with their addresses; for subscription accounts an address holds the
     * login, so every player log line goes through the redactor before reaching logcat.
     */
    @androidx.annotation.OptIn(UnstableApi::class)
    private fun installRedactingPlayerLog() {
        Log.setLogger(object : Log.Logger {
            override fun d(tag: String, message: String) { android.util.Log.d(tag, Redactor.redact(message)) }
            override fun i(tag: String, message: String) { android.util.Log.i(tag, Redactor.redact(message)) }
            override fun w(tag: String, message: String) { android.util.Log.w(tag, Redactor.redact(message)) }
            override fun e(tag: String, message: String) { android.util.Log.e(tag, Redactor.redact(message)) }
        })
    }

    /** Channel logos: bounded caches so thousands of logos never exhaust a 2 GB TV. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(container.http)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.12).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("logos")).maxSizeBytes(64L * 1024 * 1024).build() }
        .crossfade(false)
        .respectCacheHeaders(false)
        .build()
}
