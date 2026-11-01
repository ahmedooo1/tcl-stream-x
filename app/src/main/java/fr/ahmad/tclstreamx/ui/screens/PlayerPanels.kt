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

// Overlays, panels and helpers of the player screen (PlayerScreen.kt).

/**
 * Touch-only controls (phones, tablets). Not focusable, so the remote keeps its own mapping:
 * the D-pad never lands on these buttons.
 */
@Composable
internal fun TouchBar(
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
    onRotate: () -> Unit,
    onCast: () -> Unit,
    hasNext: Boolean,
    onNextEpisode: () -> Unit,
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
            TouchButton(if (playing) "❚❚" else "▶", onPlayPause)
            TouchButton("▼", onPrev)
            TouchButton("▲", onNext)
        } else {
            TouchButton("−10", onRewind)
            TouchButton(if (playing) "❚❚" else "▶", onPlayPause)
            TouchButton("+10", onForward)
            if (hasNext) TouchButton("⏭", onNextEpisode)
        }
        TouchButton("⟳", onRotate)
        TouchIconButton(AppIcons.Cast, onCast)
        TouchButton("⚙", onOptions)
    }
}

@Composable
internal fun TouchIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .background(Palette.Graphite.copy(alpha = 0.8f))
            .focusProperties { canFocus = false }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(icon, Palette.Text, size = 24.dp)
    }
}

@Composable
internal fun TouchButton(label: String, onClick: () -> Unit) {
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
internal fun InfoBar(
    item: PlayItem,
    ui: fr.ahmad.tclstreamx.player.PlayerUi,
    live: LiveViewModel,
    position: Long,
    duration: Long,
    modifier: Modifier,
    onScrub: (Long) -> Unit,
) {
    val type = LocalTallyType.current
    val epg by remember(item.tvgId) { live.epgFor(item.tvgId) }.collectAsState(initial = emptyList())
    val compact = isCompactWidth()
    val tv = isTv()
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Palette.Ink.copy(alpha = 0.92f), Palette.Ink)))
            .padding(
                start = if (compact) 16.dp else 48.dp, end = if (compact) 16.dp else 48.dp,
                top = if (compact) 32.dp else 64.dp, bottom = if (compact) 16.dp else 32.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            item.channel?.let {
                Text(it.position.toString(), style = (if (compact) type.headline else type.display).copy(color = Palette.Tally), modifier = Modifier.padding(end = if (compact) 10.dp else 20.dp))
            }
            if (item.isLive) ChannelLogo(item.logo, item.title, if (compact) Modifier.size(width = 56.dp, height = 34.dp) else Modifier.size(width = 96.dp, height = 58.dp))
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
                    Text("${clockTime(p.start)} - ${clockTime(p.stop)}", style = type.mono, maxLines = 1)
                }
                if (p.start <= now) ProgressLine((now - p.start).toFloat() / (p.stop - p.start).coerceAtLeast(1), Modifier.fillMaxWidth())
            }
            epg.getOrNull(1)?.let { p ->
                Row {
                    Text(stringResource(R.string.next), style = type.mono, modifier = Modifier.width(96.dp))
                    Text("${clockTime(p.start)}  ${p.title}", style = type.body.copy(color = Palette.TextDim), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (ui.livePausedAt > 0 || ui.liveBehindMs > 0) LiveDelay(ui)
            if (tv) Text(stringResource(if (ui.livePausedAt > 0) R.string.player_paused_hint else R.string.player_hint), style = type.mono)
        } else {
            if (duration > 0) {
                SeekBar(position, duration, onScrub)
                Row {
                    Text(formatTime(position), style = type.mono.copy(color = Palette.Text))
                    Spacer(Modifier.weight(1f))
                    Text(formatTime(duration), style = type.mono)
                }
            }
            if (tv) Text(stringResource(R.string.player_hint_vod), style = type.mono)
        }
    }
}

@Composable
internal fun ChannelPanel(live: LiveViewModel, playback: PlaybackController, modifier: Modifier, onDone: () -> Unit) {
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
internal fun OptionsPanel(playback: PlaybackController, modifier: Modifier, onReplay: () -> Unit, onCast: () -> Unit) {
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
            TallyRow(onClick = onCast, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(AppIcons.Cast, Palette.Text, size = 22.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.cast_title), style = type.title)
                }
            }
        }
        if ((ui.item?.channel?.archiveDays ?: 0) > 0) {
            item {
                TallyRow(onClick = onReplay, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("⟲  " + stringResource(R.string.player_replay), style = type.title, modifier = Modifier.weight(1f))
                        Text(stringResource(R.string.player_replay_days, ui.item?.channel?.archiveDays ?: 0), style = type.mono)
                    }
                }
            }
        }
        if (ui.item?.isLive == true) {
            item {
                val paused = ui.livePausedAt > 0
                TallyRow(onClick = playback::togglePause, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                    Text(
                        (if (paused) "▶  " else "❚❚  ") + stringResource(if (paused) R.string.player_resume else R.string.player_pause),
                        style = type.title,
                        modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 18.dp),
                    )
                }
            }
            if (ui.livePausedAt > 0 || ui.liveBehindMs > 0) {
                item {
                    TallyRow(onClick = playback::goLive, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                        Text("⏭  " + stringResource(R.string.player_go_live), style = type.title, modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 18.dp))
                    }
                }
            }
            ui.previousTitle?.let { prev ->
                item {
                    TallyRow(onClick = { playback.recall() }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("↺  " + stringResource(R.string.player_previous_channel), style = type.title)
                            Spacer(Modifier.width(12.dp))
                            Text(prev, style = type.mono, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        if (ui.item?.isLive == false) {
            item {
                TallyRow(onClick = playback::cycleSpeed, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.player_speed), style = type.title, modifier = Modifier.weight(1f))
                        Text("×" + ("%.2f".format(Locale.ROOT, ui.speed).trimEnd('0').trimEnd('.')), style = type.mono.copy(color = Palette.Text))
                    }
                }
            }
        }
        item {
            TallyRow(onClick = playback::cycleSleep, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.player_sleep), style = type.title, modifier = Modifier.weight(1f))
                    Text(
                        ui.sleepMinutes?.let { stringResource(R.string.player_sleep_left, it) } ?: stringResource(R.string.player_off),
                        style = type.mono.copy(color = if (ui.sleepMinutes != null) Palette.Tally else Palette.Text),
                    )
                }
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

internal fun androidx.compose.foundation.lazy.LazyListScope.trackSection(
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

internal fun digitOf(key: Key): Char? = when (key) {
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

/** "Paused" or "N min behind live", refreshed every second while the bar is shown. */
@Composable
internal fun LiveDelay(ui: fr.ahmad.tclstreamx.player.PlayerUi) {
    val type = LocalTallyType.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(ui.livePausedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val behind = ui.liveBehindMs + if (ui.livePausedAt > 0) (now - ui.livePausedAt).coerceAtLeast(0) else 0L
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (ui.livePausedAt > 0) Text("❚❚  " + stringResource(R.string.player_paused_live), style = type.title.copy(color = Palette.Caution))
        Text(stringResource(R.string.player_behind_live, formatTime(behind)), style = type.mono.copy(color = Palette.Text))
    }
}

internal fun formatTime(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(Locale.ROOT, s / 3600, (s % 3600) / 60, s % 60)
    else "%02d:%02d".format(Locale.ROOT, s / 60, s % 60)
}

internal const val OVERLAY_MS = 5_000L

/** Overlays stay clear of the notch, the status bar and the navigation buttons when they are shown. */
internal val TopBars: WindowInsets
    @Composable get() = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
internal val BottomBars: WindowInsets
    @Composable get() = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)

internal const val SEEK_COMMIT_MS = 450L

/**
 * Timeline of films and episodes: tap or drag anywhere on it to jump (touch). Always left to right,
 * like every video timeline, also in the Arabic interface.
 */
@Composable
internal fun SeekBar(position: Long, duration: Long, onScrub: (Long) -> Unit) {
    var width by remember { androidx.compose.runtime.mutableIntStateOf(1) }
    val f = (position.toFloat() / duration).coerceIn(0f, 1f)
    fun at(x: Float) = ((x.coerceIn(0f, width.toFloat()) / width) * duration).toLong()
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(30.dp)
                .onSizeChanged { width = it.width.coerceAtLeast(1) }
                .pointerInput(duration) { detectTapGestures { o -> onScrub(at(o.x)) } }
                .pointerInput(duration) {
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        onScrub(at(change.position.x))
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxWidth().height(4.dp).background(Palette.Hairline, androidx.compose.foundation.shape.RoundedCornerShape(2.dp)))
            Box(Modifier.fillMaxWidth(f).height(4.dp).background(Palette.Tally, androidx.compose.foundation.shape.RoundedCornerShape(2.dp)))
            Box(
                Modifier
                    .offset { androidx.compose.ui.unit.IntOffset((f * width - 8.dp.toPx()).toInt(), 0) }
                    .size(16.dp)
                    .background(Palette.Text, androidx.compose.foundation.shape.CircleShape)
            )
        }
    }
}
