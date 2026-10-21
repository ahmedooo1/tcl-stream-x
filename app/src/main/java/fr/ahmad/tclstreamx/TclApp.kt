package fr.ahmad.tclstreamx

import android.app.Application
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.decode.SvgDecoder
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
            override fun d(tag: String, message: String, throwable: Throwable?) { android.util.Log.d(tag, line(message, throwable)) }
            override fun i(tag: String, message: String, throwable: Throwable?) { android.util.Log.i(tag, line(message, throwable)) }
            override fun w(tag: String, message: String, throwable: Throwable?) { android.util.Log.w(tag, line(message, throwable)) }
            override fun e(tag: String, message: String, throwable: Throwable?) { android.util.Log.e(tag, line(message, throwable)) }
        })
    }

    /** Message and exception, both redacted; the stack trace is reduced to the exception chain's types and messages. */
    private fun line(message: String, t: Throwable?): String {
        val chain = generateSequence(t) { it.cause }.take(5).joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message.orEmpty()}" }
        return Redactor.redact(if (chain.isEmpty()) message else "$message | $chain")
    }

    /** Channel logos: bounded caches so thousands of logos never exhaust a 2 GB TV. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(container.http)
        // Some channel logos are SVG files.
        .components { add(SvgDecoder.Factory()) }
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.12).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("logos")).maxSizeBytes(64L * 1024 * 1024).build() }
        .crossfade(false)
        .respectCacheHeaders(false)
        .build()
}
