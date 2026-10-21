package fr.ahmad.tclstreamx.data.check

import fr.ahmad.tclstreamx.data.db.AppDatabase
import fr.ahmad.tclstreamx.data.db.ChannelEntity
import fr.ahmad.tclstreamx.data.db.ContentKind
import fr.ahmad.tclstreamx.data.db.StreamStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

data class CheckProgress(val running: Boolean, val done: Int, val total: Int, val online: Int)

/**
 * Background availability checks. Limited concurrency so a TV on Wi-Fi is not flooded,
 * cancellable, and results are written to the database where the lists observe them.
 */
class AvailabilityChecker(
    private val db: AppDatabase,
    private val probe: StreamProbe,
    private val scope: CoroutineScope,
) {
    private val _progress = MutableStateFlow(CheckProgress(false, 0, 0, 0))
    val progress: StateFlow<CheckProgress> = _progress.asStateFlow()
    private var job: Job? = null

    /** Checks up to [limit] channels of a lineup, oldest results first. */
    fun checkLineup(playlistId: Long, group: String?, favoritesOnly: Boolean, kind: Int = ContentKind.LIVE, limit: Int = DEFAULT_LIMIT) {
        if (job?.isActive == true) return
        job = scope.launch {
            val channels = db.channels().stalest(playlistId, group, if (favoritesOnly) 1 else 0, kind, limit)
            runChecks(channels)
        }
    }

    fun checkChannels(channels: List<ChannelEntity>) {
        if (job?.isActive == true) return
        job = scope.launch { runChecks(channels) }
    }

    fun cancel() {
        job?.cancel()
        _progress.value = _progress.value.copy(running = false)
    }

    /** Single channel, used by the player when playback fails, so the list shows the reason. */
    suspend fun checkOne(channel: ChannelEntity): ProbeResult = withContext(Dispatchers.IO) {
        val result = probeWithBackups(channel)
        db.channels().updateStatus(channel.channelKey, result.status.code, result.detail, System.currentTimeMillis())
        result
    }

    private suspend fun runChecks(channels: List<ChannelEntity>) = withContext(Dispatchers.IO) {
        val total = channels.size
        var done = 0
        var online = 0
        _progress.value = CheckProgress(true, 0, total, 0)
        val gate = Semaphore(CONCURRENCY)
        channels.map { ch ->
            async {
                gate.withPermit {
                    val result = if (ch.drm) ProbeResult(StreamStatus.DRM, "DRM declared by playlist") else probeWithBackups(ch)
                    db.channels().updateStatus(ch.channelKey, result.status.code, result.detail, System.currentTimeMillis())
                    synchronized(this@AvailabilityChecker) {
                        done++
                        if (result.status == StreamStatus.ONLINE) online++
                        _progress.value = CheckProgress(true, done, total, online)
                    }
                }
            }
        }.awaitAll()
        _progress.value = CheckProgress(false, done, total, online)
    }

    private fun probeWithBackups(ch: ChannelEntity): ProbeResult {
        val urls = listOf(ch.url) + ch.backupUrls.orEmpty().lines().filter { it.isNotBlank() }
        var last = ProbeResult(StreamStatus.UNKNOWN, "")
        for (u in urls) {
            last = probe.probe(u, ch.userAgent, ch.referrer)
            if (last.status == StreamStatus.ONLINE) return last
        }
        return last
    }

    companion object {
        const val CONCURRENCY = 4
        const val DEFAULT_LIMIT = 300
    }
}
