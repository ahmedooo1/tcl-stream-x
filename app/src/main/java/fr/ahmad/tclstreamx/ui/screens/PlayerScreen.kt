package fr.ahmad.tclstreamx.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.C
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.tv.material3.Text
import fr.ahmad.tclstreamx.R
import fr.ahmad.tclstreamx.ui.components.AppIcon
import fr.ahmad.tclstreamx.ui.components.AppIcons
import fr.ahmad.tclstreamx.player.PlayItem
import fr.ahmad.tclstreamx.player.PlaybackController
import fr.ahmad.tclstreamx.player.TrackOption
import fr.ahmad.tclstreamx.ui.components.ChannelLogo
import fr.ahmad.tclstreamx.ui.components.Clock
import fr.ahmad.tclstreamx.ui.components.LiveBadge
import fr.ahmad.tclstreamx.ui.components.ProgressLine
import fr.ahmad.tclstreamx.ui.components.StatusDot
import fr.ahmad.tclstreamx.ui.components.TallyRow
import fr.ahmad.tclstreamx.ui.components.Tag
import fr.ahmad.tclstreamx.ui.components.VideoSurface
import fr.ahmad.tclstreamx.ui.nav.Navigator
import fr.ahmad.tclstreamx.ui.nav.Routes
import fr.ahmad.tclstreamx.ui.theme.LocalTallyType
import fr.ahmad.tclstreamx.ui.theme.Palette
import fr.ahmad.tclstreamx.ui.theme.isCompactWidth
import fr.ahmad.tclstreamx.ui.theme.isTv
import fr.ahmad.tclstreamx.data.db.StreamStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private enum class Panel { NONE, CHANNELS, OPTIONS, CAST, REPLAY }

@Composable
fun PlayerScreen(navigator: Navigator, playback: PlaybackController, live: LiveViewModel) {
    val ui by playback.ui.collectAsState()
    val player by playback.player.collectAsState()
    val type = LocalTallyType.current
    val item = ui.item
    val appSettings by (LocalContext.current.applicationContext as fr.ahmad.tclstreamx.TclApp).container.settings.settings.collectAsState(initial = null)
    var overlay by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf(Panel.NONE) }
    var lastInput by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var digits by remember { mutableStateOf("") }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    val rootFocus = remember { FocusRequester() }
    val errorFocus = remember { FocusRequester() }
    val externalScope = androidx.compose.runtime.rememberCoroutineScope()
    val externalContext = LocalContext.current
    var noExternalPlayer by remember(item?.key) { mutableStateOf(false) }

    val keepPlayingOnExit = remember { navigator.previousRoute() == Routes.LIVE }
    // Phones: the rotate button locks the player in landscape; leaving the player gives rotation back.
    val activity = LocalContext.current as? android.app.Activity
    var landscape by remember { mutableStateOf(false) }
    LaunchedEffect(landscape) {
        activity?.requestedOrientation =
            if (landscape) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    DisposableEffect(Unit) {
        onDispose { activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
    LaunchedEffect(Unit) { runCatching { rootFocus.requestFocus() } }
    // Phone: video fullscreen, status and navigation bars hidden (a swipe from the edge shows them);
    // they come back when leaving the player.
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = activity?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { bars?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    // Also fires on first composition: rebuilds the pipeline after standby or an input switch.
    LifecycleEventEffect(Lifecycle.Event.ON_START) { playback.ensurePrepared() }
    DisposableEffect(Unit) {
        // Back to the live list: the preview keeps the channel running. Anywhere else: stop.
        onDispose { if (!keepPlayingOnExit) playback.stop() }
    }
    // Auto-hide the info bar.
    LaunchedEffect(lastInput, panel, ui.issue, ui.livePausedAt) {
        // A paused live channel keeps its bar on screen, with how far behind live it is.
        if (panel == Panel.NONE && ui.issue == null && ui.livePausedAt == 0L) {
            delay(OVERLAY_MS)
            overlay = false
        }
    }
    // Number entry: commit 1.8 s after the last digit.
    LaunchedEffect(digits) {
        if (digits.isNotEmpty()) {
            delay(1_800)
            digits.toIntOrNull()?.let(playback::playNumber)
            digits = ""
        }
    }
    // VOD position ticker (only while the bar is visible).
    LaunchedEffect(overlay, player, item?.key) {
        while (overlay && item != null && !item.isLive) {
            player?.let {
                position = it.currentPosition
                duration = it.duration.takeIf { d -> d != C.TIME_UNSET } ?: 0L
            }
            delay(500)
        }
    }
    LaunchedEffect(ui.issue) {
        if (ui.issue != null) runCatching { errorFocus.requestFocus() }
    }

    BackHandler(enabled = panel != Panel.NONE) {
        panel = Panel.NONE
        runCatching { rootFocus.requestFocus() }
    }

    fun touch() {
        lastInput = System.currentTimeMillis()
        overlay = true
    }

    // ---- seeking: presses and double taps add up, the player jumps once the user stops ----
    var pendingSeek by remember { mutableStateOf<Long?>(null) }
    var seekTotal by remember { mutableLongStateOf(0L) }
    fun nudge(delta: Long) {
        val p = player ?: return
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        val base = pendingSeek ?: p.currentPosition
        pendingSeek = (base + delta).coerceAtLeast(0).let { if (dur != null) it.coerceAtMost(dur - 1_000) else it }
        seekTotal += delta
        touch()
    }
    LaunchedEffect(pendingSeek) {
        val target = pendingSeek ?: return@LaunchedEffect
        delay(SEEK_COMMIT_MS)
        playback.seekTo(target)
        position = target
        pendingSeek = null
    }
    LaunchedEffect(seekTotal, pendingSeek) {
        if (seekTotal != 0L && pendingSeek == null) {
            delay(900)
            seekTotal = 0
        }
    }
    /** Holding the key speeds up: 10 s steps, then 30 s, 1 min, 2 min. */
    fun stepFor(repeat: Int): Long = when {
        repeat >= 20 -> 120_000
        repeat >= 8 -> 60_000
        repeat >= 3 -> 30_000
        else -> 10_000
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || panel != Panel.NONE || ui.issue != null) return@onPreviewKeyEvent false
                val isLive = item?.isLive == true
                val digit = digitOf(e.key)
                when {
                    digit != null && isLive -> { digits = (digits + digit).takeLast(4); touch(); true }
                    e.key == Key.ChannelUp || (isLive && e.key == Key.DirectionUp) -> { playback.zap(+1); touch(); true }
                    e.key == Key.ChannelDown || (isLive && e.key == Key.DirectionDown) -> { playback.zap(-1); touch(); true }
                    e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter -> {
                        when {
                            !isLive -> { playback.togglePause(); touch() }
                            ui.livePausedAt > 0 -> { playback.resume(); touch() }
                            overlay -> overlay = false
                            else -> touch()
                        }
                        true
                    }
                    e.key == Key.MediaPlayPause -> { playback.togglePause(); touch(); true }
                    e.key == Key.MediaPlay -> { playback.resume(); touch(); true }
                    e.key == Key.MediaPause -> { playback.pause(); touch(); true }
                    isLive && e.key == Key.MediaPrevious -> { playback.recall(); touch(); true }
                    !isLive && e.key == Key.DirectionLeft -> { nudge(-stepFor(e.nativeKeyEvent.repeatCount)); true }
                    !isLive && e.key == Key.DirectionRight -> { nudge(stepFor(e.nativeKeyEvent.repeatCount)); true }
                    e.key == Key.MediaRewind -> { nudge(-30_000); true }
                    e.key == Key.MediaFastForward -> { nudge(30_000); true }
                    e.key == Key.MediaNext -> { if (!playback.playNext()) playback.zap(+1); touch(); true }
                    isLive && e.key == Key.DirectionLeft -> { panel = Panel.CHANNELS; true }
                    e.key == Key.DirectionRight || e.key == Key.Menu || (!isLive && e.key == Key.DirectionUp) -> { panel = Panel.OPTIONS; true }
                    e.key == Key.Info || e.key == Key.DirectionDown -> { touch(); true }
                    else -> false
                }
            }
            .focusable()
            // Touch: a tap shows or hides the controls, or closes an open panel.
            .pointerInput(item?.isLive) {
                detectTapGestures(
                    // Double tap on the left or right third: back or forward 10 s (adds up when repeated).
                    onDoubleTap = { o ->
                        if (item != null && !item.isLive && panel == Panel.NONE) {
                            when {
                                o.x < size.width / 3f -> nudge(-10_000)
                                o.x > size.width * 2f / 3f -> nudge(10_000)
                                else -> { playback.togglePause(); touch() }
                            }
                        }
                    },
                    onTap = {
                    if (panel != Panel.NONE) {
                        panel = Panel.NONE
                    } else {
                        overlay = !overlay
                        lastInput = System.currentTimeMillis()
                    }
                })
            }
    ) {
        VideoSurface(player, ui.resizeMode, Modifier.fillMaxSize(), appSettings?.subtitleScale ?: 1f, appSettings?.subtitleStyle ?: 0)

        if (ui.buffering && ui.issue == null) {
            Box(Modifier.align(Alignment.Center).background(Palette.Ink.copy(alpha = 0.6f)).padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(ui.attempt?.let { stringResource(R.string.player_trying, it) } ?: "···", style = type.mono.copy(color = Palette.Text))
            }
        }

        // Seek feedback in the middle of the picture: direction, amount and target time.
        if (seekTotal != 0L && item != null && !item.isLive) {
            Column(
                Modifier.align(Alignment.Center).background(Palette.Ink.copy(alpha = 0.72f), androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).padding(horizontal = 22.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text((if (seekTotal < 0) "⏪  −" else "⏩  +") + formatTime(kotlin.math.abs(seekTotal)), style = type.headline.copy(color = Palette.Text))
                Text(formatTime(pendingSeek ?: position) + " / " + formatTime(duration), style = type.mono.copy(color = Palette.Text))
            }
        }

        if (digits.isNotEmpty()) {
            Text(
                digits,
                style = type.display.copy(color = Palette.Text),
                modifier = Modifier.align(Alignment.TopEnd).padding(36.dp).background(Palette.Ink.copy(alpha = 0.7f)).padding(horizontal = 18.dp, vertical = 6.dp),
            )
        }

        if (item != null && overlay && ui.issue == null) {
            InfoBar(
                item, ui, live, pendingSeek ?: position, duration,
                Modifier.align(Alignment.BottomCenter).windowInsetsPadding(BottomBars),
                onScrub = { target -> pendingSeek = target; touch() },
            )
            TouchBar(
                isLive = item.isLive,
                playing = ui.playing,
                modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(TopBars),
                onBack = { navigator.back() },
                onChannels = { panel = Panel.CHANNELS },
                onOptions = { panel = Panel.OPTIONS },
                onPrev = { playback.zap(-1); touch() },
                onNext = { playback.zap(+1); touch() },
                onPlayPause = { playback.togglePause(); touch() },
                onRewind = { nudge(-10_000) },
                onForward = { nudge(10_000) },
                hasNext = item.upNext.isNotEmpty(),
                onNextEpisode = { playback.playNext(); touch() },
                onRotate = { landscape = !landscape; touch() },
                onCast = { panel = Panel.CAST },
            )
        }

        ui.issue?.let { issue ->
            Column(
                Modifier.align(Alignment.Center).padding(16.dp).widthIn(max = 560.dp).fillMaxWidth().background(Palette.Graphite).padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.player_error_title), style = type.headline)
                Text(issueText(issue), style = type.body)
                Spacer(Modifier.height(6.dp))
                TallyRow(onClick = playback::retry, modifier = Modifier.fillMaxWidth().height(48.dp).focusRequester(errorFocus)) {
                    Text(stringResource(R.string.player_retry), style = type.title, modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 14.dp))
                }
                if (item?.isLive == true) {
                    TallyRow(onClick = { playback.zap(+1) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Text(stringResource(R.string.player_next_channel), style = type.title, modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 14.dp))
                    }
                }
                // Formats the built-in player cannot read: hand the stream to another player app the user installed (VLC, etc.).
                if (issue == fr.ahmad.tclstreamx.player.PlaybackIssue.FORMAT || issue == fr.ahmad.tclstreamx.player.PlaybackIssue.DECODER) {
                    TallyRow(
                        onClick = {
                            externalScope.launch {
                                val url = playback.externalUrl() ?: return@launch
                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
                                    .setDataAndType(android.net.Uri.parse(url), "video/*")
                                    .putExtra("title", item?.title)
                                try {
                                    externalContext.startActivity(intent)
                                } catch (e: android.content.ActivityNotFoundException) {
                                    noExternalPlayer = true
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) {
                        Text(stringResource(R.string.player_external), style = type.title, modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 14.dp))
                    }
                    if (noExternalPlayer) Text(stringResource(R.string.player_external_none), style = type.bodySmall.copy(color = Palette.Caution))
                }
                TallyRow(onClick = { navigator.back() }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text(stringResource(R.string.player_back), style = type.title, modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 14.dp))
                }
            }
        }

        when (panel) {
            Panel.CHANNELS -> ChannelPanel(live, playback, Modifier.align(Alignment.CenterStart).windowInsetsPadding(WindowInsets.safeDrawing)) { panel = Panel.NONE; runCatching { rootFocus.requestFocus() } }
            Panel.OPTIONS -> OptionsPanel(playback, Modifier.align(Alignment.CenterEnd).windowInsetsPadding(WindowInsets.safeDrawing), onReplay = { panel = Panel.REPLAY }) { panel = Panel.CAST }
            Panel.CAST -> CastPanel(playback, Modifier.align(Alignment.CenterEnd).windowInsetsPadding(WindowInsets.safeDrawing))
            Panel.REPLAY -> ui.item?.channel?.let { ch ->
                ReplayPanel(ch, Modifier.align(Alignment.CenterEnd).windowInsetsPadding(WindowInsets.safeDrawing), onClose = { panel = Panel.NONE }) { replayItem ->
                    panel = Panel.NONE
                    playback.play(replayItem)
                }
            }
            Panel.NONE -> Unit
        }
    }
}
