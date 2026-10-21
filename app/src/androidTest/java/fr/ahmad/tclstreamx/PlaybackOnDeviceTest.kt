package fr.ahmad.tclstreamx

import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.ahmad.tclstreamx.data.catalog.CatalogFile
import fr.ahmad.tclstreamx.player.PlayItem
import fr.ahmad.tclstreamx.player.PlaybackController
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Plays real streams through the app's own PlaybackController (same OkHttp stack, load control,
 * error handling and fallbacks as on the TV) and records what actually happened. Nothing mocked.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackOnDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val app = inst.targetContext.applicationContext as TclApp
    private val json = Json { ignoreUnknownKeys = true }

    private data class Target(val label: String, val item: PlayItem, val group: String)

    private data class Outcome(
        val ready: Boolean, val readyMs: Long, val advancedMs: Long, val firstFrame: Boolean,
        val width: Int, val height: Int, val qualities: Int, val audios: Int, val texts: Int,
        val error: String?, val selectionWorked: Boolean?,
    )

    @Test
    fun streamsReallyPlay() {
        val catalog = app.assets.open("catalog.json").use { json.decodeFromString(CatalogFile.serializer(), it.readBytes().decodeToString()) }
        val verified = catalog.channels.filter { it.status == "ok" && it.geo == null }
        // Diverse sample: per category and language, so Arabic, French and sports are all covered.
        val picked = verified.groupBy { it.category + "/" + it.language }.values.flatMap { it.take(2) }.take(MAX_CHANNELS)
        val targets = picked.map { ch ->
            val ok = ch.streams.filter { it.status == "ok" }.ifEmpty { ch.streams }
            Target(
                ch.name,
                PlayItem(key = "test:" + ch.id, title = ch.name, urls = ok.map { it.url }, userAgent = ok.first().userAgent, referrer = ok.first().referrer, isLive = true),
                "channel:${ch.category}/${ch.language}",
            )
        }.toMutableList()
        val openMedia = json.parseToJsonElement(app.assets.open("open_media.json").use { it.readBytes().decodeToString() }) as JsonObject
        (openMedia["films"] as JsonArray).map { it as JsonObject }.forEach { f ->
            val key = (f["key"] as JsonPrimitive).content
            if (key in setOf("open:bbb", "open:tears-dash", "open:bipbop")) {
                targets += Target(
                    (f["title"] as JsonPrimitive).content,
                    PlayItem(key = "test:$key", title = key, urls = listOf((f["url"] as JsonPrimitive).content), isLive = false),
                    "vod:$key",
                )
            }
        }

        val results = targets.map { it to play(it) }
        val report = buildJsonObject {
            put("device", "${Build.MANUFACTURER} ${Build.MODEL} API ${Build.VERSION.SDK_INT}")
            put("catalogVerifiedChannels", verified.size)
            put("results", buildJsonArray {
                results.forEach { (t, o) ->
                    add(buildJsonObject {
                        put("name", t.label); put("group", t.group); put("url", t.item.urls.first())
                        put("ready", o.ready); put("readyMs", o.readyMs); put("advancedMs", o.advancedMs)
                        put("firstFrameRendered", o.firstFrame); put("video", "${o.width}x${o.height}")
                        put("qualityOptions", o.qualities); put("audioTracks", o.audios); put("textOptions", o.texts)
                        o.selectionWorked?.let { put("trackSelectionWorked", it) }
                        o.error?.let { put("error", it) }
                    })
                }
            })
        }
        CiReport.write("playback-report.json", report.toString())
        // Test plays must not show up in "continue watching" on the screenshots that follow.
        kotlinx.coroutines.runBlocking { targets.forEach { app.container.db.vodProgress().delete(it.item.key) } }

        val channelResults = results.filter { it.first.group.startsWith("channel:") }
        val channelPass = channelResults.count { it.second.ready && it.second.advancedMs >= MIN_ADVANCE_MS }
        val vodPass = results.filter { it.first.group.startsWith("vod:") }.count { it.second.ready }
        // Live streams depend on third-party servers seen from a CI datacenter: require a clear majority, report everything.
        assertTrue("channels playing: $channelPass/${channelResults.size}", channelResults.isEmpty() || channelPass * 10 >= channelResults.size * 6)
        assertTrue("open media playing: $vodPass", vodPass >= 2)
    }

    private fun play(t: Target): Outcome {
        lateinit var controller: PlaybackController
        val thread = HandlerThread("frames").apply { start() }
        var reader: ImageReader? = null
        val firstFrame = java.util.concurrent.atomic.AtomicBoolean(false)
        inst.runOnMainSync {
            controller = PlaybackController(app)
            controller.play(t.item)
            val p = controller.player.value!!
            p.volume = 0f
            if (Build.VERSION.SDK_INT >= 29) {
                reader = ImageReader.newInstance(1280, 720, ImageFormat.PRIVATE, 4, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE).also { r ->
                    r.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(thread.looper))
                    p.setVideoSurface(r.surface)
                }
            }
            p.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() { firstFrame.set(true) }
            })
        }
        val start = System.currentTimeMillis()
        var readyAt = 0L
        var advanced = 0L
        var error: String? = null
        while (System.currentTimeMillis() - start < TIMEOUT_MS) {
            Thread.sleep(POLL_MS)
            var state = 0
            var playing = false
            inst.runOnMainSync {
                val p = controller.player.value
                state = p?.playbackState ?: 0
                playing = p?.isPlaying == true
                controller.ui.value.issue?.let { error = it.name }
            }
            if (error != null) break
            if (state == Player.STATE_READY && readyAt == 0L) readyAt = System.currentTimeMillis() - start
            // Wall-clock time actually spent playing (live positions jump when the player re-aligns on the live edge).
            if (playing) advanced += POLL_MS
            if (readyAt > 0 && advanced >= TARGET_ADVANCE_MS && (firstFrame.get() || Build.VERSION.SDK_INT < 29)) break
        }
        var selection: Boolean? = null
        var q = 0; var a = 0; var x = 0; var w = 0; var h = 0
        inst.runOnMainSync {
            val ui = controller.ui.value
            q = ui.quality.size; a = ui.audio.size; x = ui.text.size
            ui.video?.let { w = it.width; h = it.height }
            // Exercise manual quality selection where a stream offers several renditions.
            val manual = ui.quality.firstOrNull { it.groupIndex != null }
            if (readyAt > 0 && manual != null && ui.quality.size > 2) {
                controller.selectTrack(C.TRACK_TYPE_VIDEO, manual)
                selection = controller.player.value?.trackSelectionParameters?.overrides?.isNotEmpty() == true
            }
        }
        if (selection == true) Thread.sleep(3_000)
        inst.runOnMainSync {
            if (selection == true) selection = controller.player.value?.playerError == null
            controller.onBackground()
        }
        reader?.close()
        thread.quitSafely()
        return Outcome(readyAt > 0, readyAt, advanced, firstFrame.get(), w, h, q, a, x, error, selection)
    }

    companion object {
        private const val MAX_CHANNELS = 14
        private const val POLL_MS = 250L
        private const val TIMEOUT_MS = 30_000L
        private const val TARGET_ADVANCE_MS = 5_000L
        private const val MIN_ADVANCE_MS = 2_000L
    }
}
