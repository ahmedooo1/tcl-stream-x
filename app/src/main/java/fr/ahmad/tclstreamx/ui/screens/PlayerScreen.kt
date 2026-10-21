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
import fr.ahmad.tclstreamx.data.db.StreamStatus
import kotlinx.coroutines.delay
import java.util.Locale

private enum class Panel { NONE, CHANNELS, OPTIONS }

@Composable
fun PlayerScreen(navigator: Navigator, playback: PlaybackController, live: LiveViewModel) {
    val ui by playback.ui.collectAsState()
    val player by playback.player.collectAsState()
    val type = LocalTallyType.current
    val item = ui.item
    var overlay by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf(Panel.NONE) }
    var lastInput by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var digits by remember { mutableStateOf("") }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    val rootFocus = remember { FocusRequester() }
    val errorFocus = remember { FocusRequester() }

    val keepPlayingOnExit = remember { navigator.previousRoute() == Routes.LIVE }
    LaunchedEffect(Unit) { runCatching { rootFocus.requestFocus() } }
    // Also fires on first composition: rebuilds the pipeline after standby or an input switch.
    LifecycleEventEffect(Lifecycle.Event.ON_START) { playback.ensurePrepared() }
    DisposableEffect(Unit) {
        // Back to the live list: the preview keeps the channel running. Anywhere else: stop.
        onDispose { if (!keepPlayingOnExit) playback.stop() }
    }
    // Auto-hide the info bar.
    LaunchedEffect(lastInput, panel, ui.issue) {
        if (panel == Panel.NONE && ui.issue == null) {
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
                        if (!isLive) player?.let { if (it.isPlaying) it.pause() else it.play() }
                        if (isLive && overlay) overlay = false else touch()
                        true
                    }
                    e.key == Key.MediaPlayPause -> { player?.let { if (it.isPlaying) it.pause() else it.play() }; touch(); true }
                    !isLive && e.key == Key.DirectionLeft -> { player?.let { it.seekTo((it.currentPosition - 10_000).coerceAtLeast(0)) }; touch(); true }
                    !isLive && e.key == Key.DirectionRight -> { player?.let { it.seekTo(it.currentPosition + 30_000) }; touch(); true }
                    e.key == Key.MediaRewind -> { player?.let { it.seekTo((it.currentPosition - 30_000).coerceAtLeast(0)) }; touch(); true }
                    e.key == Key.MediaFastForward -> { player?.let { it.seekTo(it.currentPosition + 30_000) }; touch(); true }
                    isLive && e.key == Key.DirectionLeft -> { panel = Panel.CHANNELS; true }
                    e.key == Key.DirectionRight || e.key == Key.Menu || (!isLive && e.key == Key.DirectionUp) -> { panel = Panel.OPTIONS; true }
                    e.key == Key.Info || e.key == Key.DirectionDown -> { touch(); true }
                    else -> false
                }
            }
            .focusable()
            // Touch: a tap shows or hides the controls, or closes an open panel.
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    if (panel != Panel.NONE) {
                        panel = Panel.NONE
                    } else {
                        overlay = !overlay
                        lastInput = System.currentTimeMillis()
                    }
                })
            }
    ) {
        VideoSurface(player, ui.resizeMode, Modifier.fillMaxSize())

        if (ui.buffering && ui.issue == null) {
            Box(Modifier.align(Alignment.Center).background(Palette.Ink.copy(alpha = 0.6f)).padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(ui.attempt?.let { stringResource(R.string.player_trying, it) } ?: "···", style = type.mono.copy(color = Palette.Text))
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
            InfoBar(item, ui, live, position, duration, Modifier.align(Alignment.BottomCenter))
            TouchBar(
                isLive = item.isLive,
                playing = ui.playing,
                modifier = Modifier.align(Alignment.TopCenter),
                onBack = { navigator.back() },
                onChannels = { panel = Panel.CHANNELS },
                onOptions = { panel = Panel.OPTIONS },
                onPrev = { playback.zap(-1); touch() },
                onNext = { playback.zap(+1); touch() },
                onPlayPause = { player?.let { if (it.isPlaying) it.pause() else it.play() }; touch() },
                onRewind = { player?.let { it.seekTo((it.currentPosition - 10_000).coerceAtLeast(0)) }; touch() },
                onForward = { player?.let { it.seekTo(it.currentPosition + 30_000) }; touch() },
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
                TallyRow(onClick = { navigator.back() }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text(stringResource(R.string.player_back), style = type.title, modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 14.dp))
                }
            }
        }

        when (panel) {
            Panel.CHANNELS -> ChannelPanel(live, playback, Modifier.align(Alignment.CenterStart)) { panel = Panel.NONE; runCatching { rootFocus.requestFocus() } }
            Panel.OPTIONS -> OptionsPanel(playback, Modifier.align(Alignment.CenterEnd))
            Panel.NONE -> Unit
        }
    }
}

/**
 * Touch-only controls (phones, tablets). Not focusable, so the remote keeps its own mapping:
 * the D-pad never lands on these buttons.
 */
@Composable
private fun TouchBar(
    isLive: Boolean,
    playing: Boolean,
    modifier: Modifier,
    onBack: () -> Unit,
    onChannels: () -> Unit,
    onOptions: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onPlayPause: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Palette.Ink.copy(alpha = 0.85f), Color.Transparent)))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TouchButton("←", onBack)
        Spacer(Modifier.weight(1f))
        if (isLive) {
            TouchButton("☰", onChannels)
            TouchButton("▼", onPrev)
            TouchButton("▲", onNext)
        } else {
            TouchButton("−10", onRewind)
            TouchButton(if (playing) "❚❚" else "▶", onPlayPause)
            TouchButton("+30", onForward)
        }
        TouchButton("⚙", onOptions)
    }
}

@Composable
private fun TouchButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .background(Palette.Graphite.copy(alpha = 0.8f))
            .focusProperties { canFocus = false }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = LocalTallyType.current.title.copy(color = Palette.Text))
    }
}

@Composable
private fun InfoBar(item: PlayItem, ui: fr.ahmad.tclstreamx.player.PlayerUi, live: LiveViewModel, position: Long, duration: Long, modifier: Modifier) {
    val type = LocalTallyType.current
    val epg by remember(item.tvgId) { live.epgFor(item.tvgId) }.collectAsState(initial = emptyList())
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Palette.Ink.copy(alpha = 0.92f), Palette.Ink)))
            .padding(start = 48.dp, end = 48.dp, top = 64.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            item.channel?.let {
                Text(it.position.toString(), style = type.display.copy(color = Palette.Tally), modifier = Modifier.padding(end = 20.dp))
            }
            if (item.isLive) ChannelLogo(item.logo, item.title, Modifier.size(width = 96.dp, height = 58.dp))
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(item.title, style = type.headline, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (item.isLive) LiveBadge()
                    if (ui.isFavorite) Text("★", style = type.title.copy(color = Palette.Caution))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ui.video?.takeIf { it.height > 0 }?.let { v ->
                        Tag("${v.width}×${v.height}")
                        v.codec?.let { Tag(it.substringBefore('.').uppercase(Locale.ROOT)) }
                        if (v.bitrate > 0) Tag("%.1f Mb/s".format(Locale.ROOT, v.bitrate / 1_000_000f))
                        if (v.frameRate > 0) Tag("%.0f fps".format(Locale.ROOT, v.frameRate))
                    }
                    item.channel?.let { StatusDot(StreamStatus.of(it.status)) }
                }
            }
            Clock()
        }
        if (item.isLive) {
            val now = System.currentTimeMillis()
            epg.firstOrNull()?.let { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.now), style = type.mono.copy(color = Palette.Tally), modifier = Modifier.width(96.dp))
                    Text(p.title, style = type.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("${clockTime(p.start)}–${clockTime(p.stop)}", style = type.mono)
                }
                if (p.start <= now) ProgressLine((now - p.start).toFloat() / (p.stop - p.start).coerceAtLeast(1), Modifier.fillMaxWidth())
            }
            epg.getOrNull(1)?.let { p ->
                Row {
                    Text(stringResource(R.string.next), style = type.mono, modifier = Modifier.width(96.dp))
                    Text("${clockTime(p.start)}  ${p.title}", style = type.body.copy(color = Palette.TextDim), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(stringResource(R.string.player_hint), style = type.mono)
        } else {
            if (duration > 0) {
                ProgressLine(position.toFloat() / duration, Modifier.fillMaxWidth())
                Row {
                    Text(formatTime(position), style = type.mono.copy(color = Palette.Text))
                    Spacer(Modifier.weight(1f))
                    Text(formatTime(duration), style = type.mono)
                }
            }
            Text(stringResource(R.string.player_hint_vod), style = type.mono)
        }
    }
}

@Composable
private fun ChannelPanel(live: LiveViewModel, playback: PlaybackController, modifier: Modifier, onDone: () -> Unit) {
    val type = LocalTallyType.current
    val paged = live.channels.collectAsLazyPagingItems()
    val first = remember { FocusRequester() }
    LaunchedEffect(paged.itemCount > 0) { if (paged.itemCount > 0) runCatching { first.requestFocus() } }
    Column(modifier.widthIn(max = 420.dp).fillMaxWidth(0.88f).fillMaxHeight().background(Palette.Graphite.copy(alpha = 0.96f)).padding(vertical = 24.dp)) {
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(count = paged.itemCount, key = paged.itemKey { it.id }) { i ->
                val ch = paged[i] ?: return@items
                TallyRow(
                    onClick = {
                        playback.play(PlayItem.fromChannel(ch, live.lineup()))
                        onDone()
                    },
                    selected = playback.ui.value.item?.key == ch.channelKey,
                    modifier = Modifier.fillMaxWidth().height(52.dp).let { if (i == 0) it.focusRequester(first) else it },
                ) { focused ->
                    Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(ch.position.toString(), style = type.numeralSmall.copy(color = if (focused) Palette.Tally else Palette.TextFaint), modifier = Modifier.width(52.dp))
                        Text(ch.name, style = type.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        StatusDot(StreamStatus.of(ch.status))
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionsPanel(playback: PlaybackController, modifier: Modifier) {
    val ui by playback.ui.collectAsState()
    val type = LocalTallyType.current
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    val auto = stringResource(R.string.player_auto)
    val off = stringResource(R.string.player_off)
    fun label(o: TrackOption) = when (o.label) {
        PlaybackController.AUTO -> auto
        PlaybackController.OFF -> off
        else -> o.label
    }
    LazyColumn(
        modifier.widthIn(max = 400.dp).fillMaxWidth(0.88f).fillMaxHeight().background(Palette.Graphite.copy(alpha = 0.96f)),
        contentPadding = PaddingValues(vertical = 24.dp),
    ) {
        item {
            val fav = ui.isFavorite
            TallyRow(onClick = playback::toggleFavorite, modifier = Modifier.fillMaxWidth().height(50.dp).focusRequester(first)) {
                Text(
                    (if (fav) "★  " else "☆  ") + stringResource(if (fav) R.string.live_remove_favorite else R.string.live_add_favorite),
                    style = type.title,
                    modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 18.dp),
                )
            }
        }
        item {
            val labels = listOf(R.string.player_aspect_fit, R.string.player_aspect_zoom, R.string.player_aspect_stretch)
            TallyRow(onClick = playback::cycleResize, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.player_aspect), style = type.title, modifier = Modifier.weight(1f))
                    Text(stringResource(labels[ui.resizeMode]), style = type.mono.copy(color = Palette.Text))
                }
            }
        }
        trackSection(R.string.player_quality, ui.quality, C.TRACK_TYPE_VIDEO, playback, ::label)
        trackSection(R.string.player_audio, ui.audio, C.TRACK_TYPE_AUDIO, playback, ::label)
        trackSection(R.string.player_subtitles, ui.text, C.TRACK_TYPE_TEXT, playback, ::label)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.trackSection(
    title: Int,
    options: List<TrackOption>,
    trackType: Int,
    playback: PlaybackController,
    label: (TrackOption) -> String,
) {
    item {
        Text(
            stringResource(title),
            style = LocalTallyType.current.headline,
            modifier = Modifier.padding(start = 22.dp, top = 22.dp, bottom = 6.dp),
        )
    }
    if (options.isEmpty()) {
        item { Text(stringResource(R.string.player_no_tracks), style = LocalTallyType.current.bodySmall, modifier = Modifier.padding(start = 22.dp)) }
    }
    items(options, key = { "$trackType:${it.groupIndex}:${it.trackIndex}" }) { o ->
        TallyRow(onClick = { playback.selectTrack(trackType, o) }, selected = o.selected, modifier = Modifier.fillMaxWidth().height(46.dp)) {
            Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label(o), style = LocalTallyType.current.body, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (o.selected) Text("●", style = LocalTallyType.current.mono.copy(color = Palette.Tally))
            }
        }
    }
}

private fun digitOf(key: Key): Char? = when (key) {
    Key.Zero, Key.NumPad0 -> '0'
    Key.One, Key.NumPad1 -> '1'
    Key.Two, Key.NumPad2 -> '2'
    Key.Three, Key.NumPad3 -> '3'
    Key.Four, Key.NumPad4 -> '4'
    Key.Five, Key.NumPad5 -> '5'
    Key.Six, Key.NumPad6 -> '6'
    Key.Seven, Key.NumPad7 -> '7'
    Key.Eight, Key.NumPad8 -> '8'
    Key.Nine, Key.NumPad9 -> '9'
    else -> null
}

private fun formatTime(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(Locale.ROOT, s / 3600, (s % 3600) / 60, s % 60)
    else "%02d:%02d".format(Locale.ROOT, s / 60, s % 60)
}

private const val OVERLAY_MS = 5_000L
