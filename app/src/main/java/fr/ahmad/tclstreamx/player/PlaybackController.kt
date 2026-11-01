package fr.ahmad.tclstreamx.player

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.MediaSession
import fr.ahmad.tclstreamx.TclApp
import fr.ahmad.tclstreamx.data.db.ChannelEntity
import fr.ahmad.tclstreamx.data.db.HistoryEntity
import fr.ahmad.tclstreamx.data.db.StreamStatus
import fr.ahmad.tclstreamx.data.db.VodProgressEntity
import fr.ahmad.tclstreamx.data.xtream.XtreamUrls
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

data class TrackOption(val label: String, val selected: Boolean, val groupIndex: Int?, val trackIndex: Int?)

data class VideoInfo(val width: Int, val height: Int, val codec: String?, val bitrate: Int, val frameRate: Float)

data class PlayerUi(
    val item: PlayItem? = null,
    val buffering: Boolean = false,
    val playing: Boolean = false,
    val issue: PlaybackIssue? = null,
    val attempt: String? = null,
    val video: VideoInfo? = null,
    val quality: List<TrackOption> = emptyList(),
    val audio: List<TrackOption> = emptyList(),
    val text: List<TrackOption> = emptyList(),
    val resizeMode: Int = 0,
    val isFavorite: Boolean = false,
    /** Playback speed of films and episodes. */
    val speed: Float = 1f,
    /** Minutes left before the sleep timer stops playback, null when off. */
    val sleepMinutes: Int? = null,
    /** Live channel paused by the user: when it was paused (0 = not paused). */
    val livePausedAt: Long = 0L,
    /** How far behind the live edge the picture is after earlier pauses. */
    val liveBehindMs: Long = 0L,
    /** Name of the channel watched before this one, for the "previous channel" key. */
    val previousTitle: String? = null,
)

/**
 * Owns the single ExoPlayer of the app. The live preview on the channel list and the fullscreen
 * player share it, so going fullscreen from a preview does not rebuffer, and a TV with few
 * hardware decoders never has two video pipelines open.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackController(app: Application) : AndroidViewModel(app) {
    private val container = (app as TclApp).container
    private val _ui = MutableStateFlow(PlayerUi())
    val ui: StateFlow<PlayerUi> = _ui.asStateFlow()

    private val _player = MutableStateFlow<ExoPlayer?>(null)
    val player: StateFlow<ExoPlayer?> = _player.asStateFlow()

    private var urlIndex = 0
    private var forceHls = false
    private var autoRetries = 0
    private var retryJob: Job? = null
    private var resolveJob: Job? = null
    private var progressJob: Job? = null
    private var stallJob: Job? = null
    private var previousLive: PlayItem? = null
    private var session: MediaSession? = null

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            _ui.update { it.copy(buffering = state == Player.STATE_BUFFERING) }
            watchStall(state == Player.STATE_BUFFERING)
            if (state == Player.STATE_READY) {
                autoRetries = 0
                _ui.update { it.copy(issue = null, attempt = null) }
                _ui.value.item?.channel?.let { markStatus(it, StreamStatus.ONLINE, "playback") }
            }
            if (state == Player.STATE_ENDED) {
                saveProgress(completed = true)
                playNext()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _ui.update { it.copy(playing = isPlaying) }
        }

        override fun onTracksChanged(tracks: Tracks) = refreshTracks()

        override fun onVideoSizeChanged(videoSize: VideoSize) = refreshVideoInfo()

        override fun onPlayerError(error: PlaybackException) = handleError(error)
    }

    private fun obtainPlayer(): ExoPlayer {
        _player.value?.let { return it }
        val ctx = getApplication<Application>()
        val loadControl = DefaultLoadControl.Builder()
            // Live IPTV: start fast, keep memory bounded on 2 GB TVs.
            .setBufferDurationsMs(12_000, 40_000, 1_500, 3_000)
            .setTargetBufferBytes(40 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        val renderers = DefaultRenderersFactory(ctx).setEnableDecoderFallback(true)
        val selector = DefaultTrackSelector(ctx).apply {
            setParameters(buildUponParameters().setPreferredTextLanguage(Locale.getDefault().language).build())
        }
        val p = ExoPlayer.Builder(ctx, renderers)
            .setLoadControl(loadControl)
            .setTrackSelector(selector)
            // Pauses for other apps' audio (voice assistant, calls) and when headphones are unplugged.
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            // Keeps Wi-Fi and CPU awake while a channel plays, so the stream does not stall when the TV idles.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        p.addListener(listener)
        // An active media session tells Google TV that something is playing: no screen saver, remote media keys work.
        session = MediaSession.Builder(ctx, p).build()
        _player.value = p
        return p
    }

    /**
     * A live stream that stays in "loading" forever (server accepts the connection but sends nothing)
     * raises no player error. After STALL_MS the load is retried, then the failure is shown.
     */
    private fun watchStall(buffering: Boolean) {
        stallJob?.cancel()
        if (!buffering || _ui.value.item?.isLive != true) return
        stallJob = viewModelScope.launch {
            delay(STALL_MS)
            val p = _player.value ?: return@launch
            if (p.playbackState != Player.STATE_BUFFERING || _ui.value.issue != null) return@launch
            if (autoRetries < MAX_AUTO_RETRIES) {
                autoRetries++
                urlIndex = 0
                forceHls = false
                load(resumeFromSaved = false)
            } else {
                p.stop()
                _ui.update { it.copy(issue = PlaybackIssue.TIMEOUT, buffering = false) }
            }
        }
    }

    fun play(item: PlayItem) {
        retryJob?.cancel()
        saveProgress(completed = false)
        urlIndex = 0
        forceHls = false
        autoRetries = 0
        val old = _ui.value.item
        if (old != null && old.isLive && item.isLive && old.key != item.key) previousLive = old
        _ui.update {
            PlayerUi(item = item, resizeMode = it.resizeMode, sleepMinutes = it.sleepMinutes, previousTitle = previousLive?.title)
        }
        // Speed is per title: live channels always run at normal speed.
        _player.value?.setPlaybackSpeed(1f)
        if (item.drm) {
            _ui.update { it.copy(issue = PlaybackIssue.DRM) }
            _player.value?.stop()
            return
        }
        load(resumeFromSaved = !item.isLive)
        viewModelScope.launch {
            if (item.isLive) {
                container.db.history().touch(HistoryEntity(item.key, System.currentTimeMillis()))
                container.db.history().trim(KEEP_HISTORY)
                container.settings.setLastChannel(item.key)
            }
            val fav = container.db.favorites().isFavorite(item.key)
            _ui.update { it.copy(isFavorite = fav) }
        }
        startProgressTicker()
    }

    // ---- pause, live pause and previous channel -------------------------------------------

    /** Play/pause for films and live channels. A paused live channel continues where it stopped while the stream allows it. */
    fun togglePause() {
        val p = _player.value ?: return
        if (p.playWhenReady) pause() else resume()
    }

    fun pause() {
        val p = _player.value ?: return
        p.pause()
        if (_ui.value.item?.isLive == true && _ui.value.livePausedAt == 0L) {
            _ui.update { it.copy(livePausedAt = System.currentTimeMillis()) }
        }
    }

    fun resume() {
        val p = _player.value ?: return
        val pausedAt = _ui.value.livePausedAt
        if (pausedAt > 0) {
            _ui.update { it.copy(livePausedAt = 0L, liveBehindMs = it.liveBehindMs + (System.currentTimeMillis() - pausedAt)) }
        }
        p.play()
    }

    /** Back to the live edge after a pause. */
    fun goLive() {
        val p = _player.value ?: return
        _ui.update { it.copy(livePausedAt = 0L, liveBehindMs = 0L) }
        p.seekToDefaultPosition()
        p.play()
    }

    /** Swaps to the channel watched before (remote 0 or "previous" key). */
    fun recall(): Boolean {
        val prev = previousLive ?: return false
        play(prev)
        return true
    }

    /** Re-creates the pipeline after the app came back to the foreground. */
    fun ensurePrepared() {
        val item = _ui.value.item ?: return
        if (_player.value == null && !item.drm) load(resumeFromSaved = !item.isLive)
    }

    fun retry() {
        urlIndex = 0
        forceHls = false
        autoRetries = 0
        _ui.update { it.copy(issue = null) }
        load(resumeFromSaved = false)
    }

    private fun load(resumeFromSaved: Boolean) {
        val item = _ui.value.item ?: return
        val ref = item.urls.getOrNull(urlIndex) ?: return
        resolveJob?.cancel()
        if (!XtreamUrls.isInternal(ref)) {
            start(item, ref, resumeFromSaved)
            return
        }
        // Account references are turned into real addresses in memory, right before playback.
        val index = urlIndex
        resolveJob = viewModelScope.launch {
            val url = container.xtream.resolve(ref)
            if (_ui.value.item !== item || urlIndex != index) return@launch
            if (url == null) {
                _player.value?.stop()
                _ui.update { it.copy(issue = PlaybackIssue.AUTH, buffering = false) }
                return@launch
            }
            start(item, url, resumeFromSaved)
        }
    }

    private fun start(item: PlayItem, url: String, resumeFromSaved: Boolean) {
        val p = obtainPlayer()
        val ctx = getApplication<Application>()
        val http = OkHttpDataSource.Factory(container.playerHttp)
            .setUserAgent(item.userAgent ?: USER_AGENT)
            .setDefaultRequestProperties(item.referrer?.let { mapOf("Referer" to it) } ?: emptyMap())
        val dataSource = DefaultDataSource.Factory(ctx, http)
        val builder = MediaItem.Builder()
            .setUri(url)
            .setMediaId(item.key)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(item.title).build())
        (if (forceHls) MimeTypes.APPLICATION_M3U8 else inferMime(url))?.let { builder.setMimeType(it) }
        if (item.subtitles.isNotEmpty()) {
            builder.setSubtitleConfigurations(item.subtitles.map { s ->
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(s.url))
                    .setMimeType(s.mimeType)
                    .setLanguage(s.language)
                    .build()
            })
        }
        val source = DefaultMediaSourceFactory(dataSource).createMediaSource(builder.build())
        p.setMediaSource(source)
        p.prepare()
        p.playWhenReady = true
        if (resumeFromSaved) {
            viewModelScope.launch {
                val saved = container.db.vodProgress().get(item.key)
                if (saved != null && saved.positionMs > 10_000 && saved.positionMs < saved.durationMs * 0.95) {
                    p.seekTo(saved.positionMs)
                }
            }
        }
        _ui.update {
            it.copy(
                attempt = if (item.urls.size > 1) "${urlIndex + 1}/${item.urls.size}" else null,
                // A reload always starts at the live edge.
                livePausedAt = 0L,
                liveBehindMs = if (item.isLive) 0L else it.liveBehindMs,
            )
        }
    }

    private fun handleError(error: PlaybackException) {
        val item = _ui.value.item ?: return
        val p = _player.value ?: return
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            // The pause lasted longer than the stream keeps: continue from the live edge.
            _ui.update { it.copy(liveBehindMs = 0L, livePausedAt = 0L) }
            p.seekToDefaultPosition()
            p.prepare()
            return
        }
        if (!forceHls && PlaybackErrors.shouldRetryAsHls(error) && inferMime(item.urls[urlIndex]) == null) {
            forceHls = true
            load(resumeFromSaved = false)
            return
        }
        val issue = PlaybackErrors.classify(error)
        if (urlIndex + 1 < item.urls.size) {
            urlIndex++
            forceHls = false
            load(resumeFromSaved = false)
            return
        }
        if (issue.retryable && autoRetries < MAX_AUTO_RETRIES) {
            autoRetries++
            _ui.update { it.copy(buffering = true) }
            retryJob = viewModelScope.launch {
                delay(RETRY_DELAY_MS * autoRetries)
                urlIndex = 0
                forceHls = false
                load(resumeFromSaved = false)
            }
            return
        }
        _ui.update { it.copy(issue = issue, buffering = false) }
        item.channel?.let {
            val status = when (issue) {
                PlaybackIssue.FORBIDDEN -> StreamStatus.FORBIDDEN
                PlaybackIssue.AUTH -> StreamStatus.AUTH_REQUIRED
                PlaybackIssue.DRM -> StreamStatus.DRM
                PlaybackIssue.TIMEOUT -> StreamStatus.TIMEOUT
                PlaybackIssue.FORMAT, PlaybackIssue.DECODER -> StreamStatus.INVALID
                else -> StreamStatus.OFFLINE
            }
            markStatus(it, status, "playback: ${error.errorCodeName}")
        }
    }

    private fun markStatus(channel: ChannelEntity, status: StreamStatus, detail: String) {
        viewModelScope.launch {
            container.db.channels().updateStatus(channel.channelKey, status.code, detail, System.currentTimeMillis())
        }
    }

    // ---- zapping -------------------------------------------------------------------------

    fun zap(direction: Int) {
        val item = _ui.value.item ?: return
        val ch = item.channel ?: return
        val lineup = item.lineup ?: Lineup(playlistId = ch.playlistId, kind = ch.kind)
        viewModelScope.launch {
            val dao = container.db.channels()
            val fav = if (lineup.favoritesOnly) 1 else 0
            val target = if (direction > 0) {
                dao.next(lineup.playlistId, lineup.group, fav, lineup.kind, lineup.region, ch.playlistId, ch.position)
                    ?: dao.firstOfLineup(lineup.playlistId, lineup.group, fav, lineup.kind, lineup.region, 1).firstOrNull()
            } else {
                dao.previous(lineup.playlistId, lineup.group, fav, lineup.kind, lineup.region, ch.playlistId, ch.position)
                    ?: dao.lastOfLineup(lineup.playlistId, lineup.group, fav, lineup.kind, lineup.region)
            }
            if (target != null && target.channelKey != ch.channelKey) play(PlayItem.fromChannel(target, lineup))
        }
    }

    /** Remote digits: channel number inside the current playlist. */
    fun playNumber(number: Int) {
        if (number == 0) { recall(); return }
        val ch = _ui.value.item?.channel ?: return
        viewModelScope.launch {
            container.db.channels().byNumber(ch.playlistId, number)?.let {
                play(PlayItem.fromChannel(it, _ui.value.item?.lineup))
            }
        }
    }

    fun toggleFavorite() {
        val item = _ui.value.item ?: return
        viewModelScope.launch {
            val favs = container.db.favorites()
            if (favs.isFavorite(item.key)) favs.remove(item.key)
            else favs.add(fr.ahmad.tclstreamx.data.db.FavoriteEntity(item.key, System.currentTimeMillis()))
            val now = favs.isFavorite(item.key)
            _ui.update { it.copy(isFavorite = now) }
        }
    }

    // ---- tracks --------------------------------------------------------------------------

    fun selectTrack(type: Int, option: TrackOption) {
        val p = _player.value ?: return
        val b = p.trackSelectionParameters.buildUpon()
        if (option.groupIndex == null || option.trackIndex == null) {
            b.clearOverridesOfType(type)
            if (type == C.TRACK_TYPE_TEXT) b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            val group = p.currentTracks.groups.getOrNull(option.groupIndex) ?: return
            b.setTrackTypeDisabled(type, false)
            b.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex))
        }
        p.trackSelectionParameters = b.build()
        refreshTracks()
    }

    /** Next episode of a series, if there is one. */
    fun playNext(): Boolean {
        val next = _ui.value.item?.upNext?.firstOrNull() ?: return false
        play(next.copy(upNext = _ui.value.item!!.upNext.drop(1)))
        return true
    }

    fun seekBy(deltaMs: Long) {
        val p = _player.value ?: return
        val duration = p.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        val target = (p.currentPosition + deltaMs).coerceAtLeast(0).let { if (duration != null) it.coerceAtMost(duration - 1_000) else it }
        p.seekTo(target)
    }

    fun seekTo(positionMs: Long) {
        _player.value?.seekTo(positionMs.coerceAtLeast(0))
    }

    fun cycleSpeed() {
        val speeds = listOf(1f, 1.25f, 1.5f, 2f, 0.75f)
        val next = speeds[(speeds.indexOf(_ui.value.speed).coerceAtLeast(0) + 1) % speeds.size]
        _player.value?.setPlaybackSpeed(next)
        _ui.update { it.copy(speed = next) }
    }

    private var sleepJob: Job? = null

    /** Sleep timer: off, 15, 30, 60, 90 minutes, then off again. Stops playback when it runs out. */
    fun cycleSleep() {
        val steps = listOf(null, 15, 30, 60, 90)
        val next = steps[(steps.indexOf(_ui.value.sleepMinutes) + 1) % steps.size]
        sleepJob?.cancel()
        _ui.update { it.copy(sleepMinutes = next) }
        if (next == null) return
        sleepJob = viewModelScope.launch {
            var left = next
            while (left > 0) {
                delay(60_000)
                left--
                _ui.update { it.copy(sleepMinutes = left) }
            }
            _player.value?.pause()
            _ui.update { it.copy(sleepMinutes = null) }
        }
    }

    fun cycleResize() {
        _ui.update { it.copy(resizeMode = (it.resizeMode + 1) % 3) }
    }

    private fun refreshTracks() {
        val p = _player.value ?: return
        val groups = p.currentTracks.groups
        val overrides = p.trackSelectionParameters.overrides
        fun options(type: Int, label: (Format) -> String): List<TrackOption> = groups.mapIndexedNotNull { gi, g ->
            if (g.type != type) null else (0 until g.length).filter { g.isTrackSupported(it) }.map { ti ->
                val overridden = overrides[g.mediaTrackGroup]?.trackIndices?.contains(ti) == true
                TrackOption(label(g.getTrackFormat(ti)), overridden || (overrides.isEmpty() && g.isTrackSelected(ti) && type != C.TRACK_TYPE_VIDEO), gi, ti)
            }
        }.flatten()
        val videoOverride = groups.any { it.type == C.TRACK_TYPE_VIDEO && overrides.containsKey(it.mediaTrackGroup) }
        val quality = listOf(TrackOption(AUTO, !videoOverride, null, null)) +
            options(C.TRACK_TYPE_VIDEO) { f ->
                buildString {
                    append(if (f.height > 0) "${f.height}p" else "?")
                    if (f.bitrate > 0) append(" · %.1f Mb/s".format(Locale.US, f.bitrate / 1_000_000f))
                }
            }.distinctBy { it.label }.sortedByDescending { it.label.substringBefore('p').toIntOrNull() ?: 0 }
        val audio = options(C.TRACK_TYPE_AUDIO) { f -> trackName(f, "audio") }
        val textDisabled = p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        val textTracks = options(C.TRACK_TYPE_TEXT) { f -> trackName(f, "sub") }
        val anyTextSelected = !textDisabled && groups.any { it.type == C.TRACK_TYPE_TEXT && it.isSelected }
        val text = listOf(TrackOption(OFF, !anyTextSelected, null, null)) +
            textTracks.map { it.copy(selected = !textDisabled && it.selected) }
        _ui.update { it.copy(quality = quality, audio = audio, text = text) }
        refreshVideoInfo()
    }

    private fun refreshVideoInfo() {
        val p = _player.value ?: return
        val f = p.videoFormat
        _ui.update {
            it.copy(video = if (f == null) null else VideoInfo(f.width, f.height, f.codecs ?: f.sampleMimeType, f.bitrate, f.frameRate))
        }
    }

    private fun trackName(f: Format, fallback: String): String {
        val lang = f.language?.let { code -> Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault()).ifBlank { code } }
        return listOfNotNull(f.label, lang, if (f.channelCount > 2) "${f.channelCount}ch" else null)
            .distinct().joinToString(" · ").ifEmpty { fallback }
    }

    // ---- lifecycle -----------------------------------------------------------------------

    private fun startProgressTicker() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (isActive) {
                delay(PROGRESS_EVERY_MS)
                saveProgress(completed = false)
            }
        }
    }

    private fun saveProgress(completed: Boolean) {
        val item = _ui.value.item ?: return
        if (item.isLive) return
        val p = _player.value ?: return
        val duration = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        val position = if (completed) duration else p.currentPosition
        viewModelScope.launch {
            container.db.vodProgress().save(
                VodProgressEntity(item.key, item.title, item.urls.first(), item.poster, position, duration, System.currentTimeMillis())
            )
        }
    }

    /**
     * Address of the current item for another player app installed on the device (for formats the
     * built-in player cannot decode). Resolved on demand; the caller hands it straight to the intent.
     */
    suspend fun externalUrl(): String? {
        val item = _ui.value.item ?: return null
        val ref = item.urls.getOrNull(urlIndex) ?: item.urls.firstOrNull() ?: return null
        return container.xtream.resolve(ref)
    }

    fun stop() {
        saveProgress(completed = false)
        retryJob?.cancel()
        resolveJob?.cancel()
        progressJob?.cancel()
        stallJob?.cancel()
        _player.value?.stop()
        sleepJob?.cancel()
        _ui.update { PlayerUi(resizeMode = it.resizeMode) }
    }

    /** Called when the activity stops: frees the decoder for other apps (TV standby, input switch). */
    fun onBackground() {
        saveProgress(completed = false)
        retryJob?.cancel()
        stallJob?.cancel()
        session?.release()
        session = null
        _player.value?.let {
            it.removeListener(listener)
            it.release()
        }
        _player.value = null
    }

    override fun onCleared() {
        onBackground()
        super.onCleared()
    }

    companion object {
        const val USER_AGENT = "TCLStreamX/1.0 (Linux; Android TV)"
        const val AUTO = "auto"
        const val OFF = "off"
        private const val MAX_AUTO_RETRIES = 2
        private const val STALL_MS = 25_000L
        private const val RETRY_DELAY_MS = 2_500L
        private const val PROGRESS_EVERY_MS = 10_000L
        private const val KEEP_HISTORY = 50

        fun inferMime(url: String): String? {
            val path = Uri.parse(url).path.orEmpty().lowercase()
            return when {
                path.endsWith(".m3u8") || path.endsWith(".m3u") -> MimeTypes.APPLICATION_M3U8
                path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
                else -> null
            }
        }
    }
}
