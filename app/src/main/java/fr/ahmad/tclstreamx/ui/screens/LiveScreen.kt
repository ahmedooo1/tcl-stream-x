package fr.ahmad.tclstreamx.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.tv.material3.Text
import fr.ahmad.tclstreamx.R
import fr.ahmad.tclstreamx.TclApp
import fr.ahmad.tclstreamx.data.db.ChannelEntity
import fr.ahmad.tclstreamx.data.db.StreamStatus
import fr.ahmad.tclstreamx.player.PlayItem
import fr.ahmad.tclstreamx.player.PlaybackController
import fr.ahmad.tclstreamx.ui.components.ChannelLogo
import fr.ahmad.tclstreamx.ui.components.LiveBadge
import fr.ahmad.tclstreamx.ui.components.ProgressLine
import fr.ahmad.tclstreamx.ui.components.StatusDot
import fr.ahmad.tclstreamx.ui.components.TallyRow
import fr.ahmad.tclstreamx.ui.components.Tag
import fr.ahmad.tclstreamx.ui.components.VideoSurface
import fr.ahmad.tclstreamx.ui.components.statusLabel
import fr.ahmad.tclstreamx.ui.nav.Navigator
import fr.ahmad.tclstreamx.ui.nav.Routes
import fr.ahmad.tclstreamx.ui.theme.LocalTallyType
import fr.ahmad.tclstreamx.ui.theme.Palette
import fr.ahmad.tclstreamx.ui.theme.hasRoomForMonitor
import fr.ahmad.tclstreamx.ui.theme.isCompactWidth
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Three columns, read left to right like a control room: groups, channel list, and a monitor
 * that previews the focused channel live. OK goes fullscreen on the same player instance.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LiveScreen(navigator: Navigator, playback: PlaybackController, vm: LiveViewModel) {
    val type = LocalTallyType.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.applicationContext as TclApp
    val settings by app.container.settings.settings.collectAsState(initial = null)
    val playlists by vm.playlists.collectAsState()
    val playlistId by vm.playlistId.collectAsState()
    val group by vm.group.collectAsState()
    val groups by vm.groups.collectAsState()
    val regions by vm.regions.collectAsState()
    val region by vm.region.collectAsState()
    val total by vm.totalCount.collectAsState()
    val favorites by vm.favoriteKeys.collectAsState()
    val focused by vm.focused.collectAsState()
    val progress by vm.checkProgress.collectAsState()
    val paged = vm.channels.collectAsLazyPagingItems()
    val listState = rememberLazyListState()
    val firstChannel = remember { FocusRequester() }
    var goingFullscreen by remember { mutableStateOf(false) }
    var focusedOnce by remember { mutableStateOf(false) }
    val compact = isCompactWidth()
    val monitor = hasRoomForMonitor()

    // Live preview of the focused channel after a short dwell, like flicking through monitors.
    LaunchedEffect(focused?.channelKey, settings?.previewOnFocus) {
        val ch = focused ?: return@LaunchedEffect
        if (settings?.previewOnFocus != true || ch.drm) return@LaunchedEffect
        delay(PREVIEW_DWELL_MS)
        if (playback.ui.value.item?.key != ch.channelKey) playback.play(PlayItem.fromChannel(ch, vm.lineup()))
    }
    LaunchedEffect(paged.itemCount) {
        if (!focusedOnce && paged.itemCount > 0) {
            focusedOnce = true
            runCatching { firstChannel.requestFocus() }
        }
    }
    DisposableEffect(Unit) {
        onDispose { if (!goingFullscreen) playback.stop() }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { playback.ensurePrepared() }

    fun open(ch: ChannelEntity) {
        if (playback.ui.value.item?.key != ch.channelKey) playback.play(PlayItem.fromChannel(ch, vm.lineup()))
        goingFullscreen = true
        navigator.openPlayer()
    }

    // Channel list, shared by the phone and TV layouts.
    val channelColumn: @Composable (Modifier) -> Unit = { mod ->
        Column(mod) {
            RegionStrip(regions, region, vm::selectRegion, Modifier.padding(bottom = 8.dp), PaddingValues(0.dp))
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(bottom = 10.dp)) {
                val title = when (val g = group) {
                    GroupSel.All -> stringResource(R.string.group_all)
                    GroupSel.Favorites -> stringResource(R.string.group_favorites)
                    is GroupSel.Named -> groupLabel(g.title)
                }
                if (compact) Spacer(Modifier.weight(1f))
                else Text(title, style = type.headline, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                TallyRow(onClick = { navigator.go(Routes.GUIDE) }, modifier = Modifier.height(40.dp).padding(end = 6.dp)) {
                    Text(
                        stringResource(R.string.live_guide),
                        style = type.label.copy(color = Palette.Text),
                        maxLines = 1,
                        modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 12.dp),
                    )
                }
                TallyRow(onClick = vm::checkCurrentLineup, modifier = Modifier.height(40.dp).widthIn(max = 170.dp)) {
                    Text(
                        if (progress.running) stringResource(R.string.live_checking, progress.done, progress.total)
                        else if (progress.total > 0) stringResource(R.string.live_check_done, progress.online, progress.total)
                        else stringResource(R.string.live_check),
                        style = type.label.copy(color = Palette.Text),
                        maxLines = 1,
                        modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 12.dp),
                    )
                }
            }
            if (paged.itemCount == 0) {
                Text(stringResource(R.string.live_empty), style = type.body.copy(color = Palette.TextDim), modifier = Modifier.padding(top = 24.dp))
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().focusRestorer(),
                contentPadding = PaddingValues(bottom = 32.dp),
            ) {
                items(count = paged.itemCount, key = paged.itemKey { it.id }) { index ->
                    val ch = paged[index]
                    if (ch == null) {
                        Box(Modifier.fillMaxWidth().height(ROW_HEIGHT))
                    } else {
                        ChannelRow(
                            ch = ch,
                            favorite = ch.channelKey in favorites,
                            modifier = if (index == 0) Modifier.focusRequester(firstChannel) else Modifier,
                            onFocus = { vm.focused.value = ch },
                            onClick = { open(ch) },
                            onLongClick = { vm.toggleFavorite(ch) },
                        )
                    }
                }
            }
        }
    }

    val chooseGroup: (GroupSel) -> Unit = { vm.group.value = it }

    when {
        compact -> Column(Modifier.fillMaxSize().padding(top = 8.dp)) {
            // Portrait phone: playlist switch and groups on top, channels full width, no preview monitor.
            Column(Modifier.padding(horizontal = 12.dp)) {
                val currentName = playlists.firstOrNull { it.id == playlistId }?.name ?: stringResource(R.string.group_all)
                TallyRow(
                    onClick = {
                        val ids = listOf(0L) + playlists.map { it.id }
                        vm.selectPlaylist(ids[(ids.indexOf(playlistId) + 1) % ids.size])
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                        Text(currentName, style = type.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("%,d".format(Locale.ROOT, total) + "  ⇄", style = type.mono)
                    }
                }
            }
            LazyRow(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item { GroupChip(stringResource(R.string.group_favorites), group == GroupSel.Favorites) { chooseGroup(GroupSel.Favorites) } }
                item { GroupChip(stringResource(R.string.group_all), group == GroupSel.All) { chooseGroup(GroupSel.All) } }
                items(groups, key = { "g:" + it.groupTitle }) { g ->
                    GroupChip(groupLabel(g.groupTitle), group == GroupSel.Named(g.groupTitle), onLongClick = { lockCategory(context, app.container, g.groupTitle) }) { chooseGroup(GroupSel.Named(g.groupTitle)) }
                }
            }
            channelColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp))
        }
        else -> Row(Modifier.fillMaxSize().padding(top = 28.dp)) {
            // ---- column 1: playlists + groups ----------------------------------------------
            Column(Modifier.width(if (monitor) 210.dp else 200.dp).fillMaxHeight().padding(start = 12.dp)) {
            val currentName = playlists.firstOrNull { it.id == playlistId }?.name ?: stringResource(R.string.group_all)
            TallyRow(
                onClick = {
                    val ids = listOf(0L) + playlists.map { it.id }
                    vm.selectPlaylist(ids[(ids.indexOf(playlistId) + 1) % ids.size])
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                    Text(currentName, style = type.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("%,d".format(Locale.ROOT, total) + "  ⇄", style = type.mono)
                }
            }
                Spacer(Modifier.height(10.dp))
                LazyColumn(Modifier.fillMaxSize().focusRestorer(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    item {
                        GroupRow(stringResource(R.string.group_favorites), favorites.size, group == GroupSel.Favorites) { chooseGroup(GroupSel.Favorites) }
                    }
                    item {
                        GroupRow(stringResource(R.string.group_all), total, group == GroupSel.All) { chooseGroup(GroupSel.All) }
                    }
                    items(groups, key = { "g:" + it.groupTitle }) { g ->
                        GroupRow(groupLabel(g.groupTitle), g.count, group == GroupSel.Named(g.groupTitle), onLongClick = { lockCategory(context, app.container, g.groupTitle) }) { chooseGroup(GroupSel.Named(g.groupTitle)) }
                    }
                }
            }
            // ---- column 2: channels ---------------------------------------------------------
            channelColumn(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 18.dp))
            // ---- column 3: monitor (TV and large screens only) ------------------------------
            if (monitor) Monitor(focused, vm, playback, Modifier.width(340.dp).fillMaxHeight().padding(end = 28.dp))
        }
    }
}

@Composable
private fun GroupChip(label: String, selected: Boolean, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    TallyRow(onClick = onClick, onLongClick = onLongClick, selected = selected, modifier = Modifier.height(42.dp)) { focused ->
        Text(
            label,
            style = LocalTallyType.current.body.copy(color = if (focused || selected) Palette.Text else Palette.TextDim),
            maxLines = 1,
            modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 12.dp),
        )
    }
}

@Composable
private fun GroupRow(label: String, count: Int, selected: Boolean, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val type = LocalTallyType.current
    TallyRow(onClick = onClick, onLongClick = onLongClick, selected = selected, modifier = Modifier.fillMaxWidth().height(44.dp)) { focused ->
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = type.body.copy(color = if (focused || selected) Palette.Text else Palette.TextDim),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text("%,d".format(Locale.ROOT, count), style = type.mono)
        }
    }
}

private val ROW_HEIGHT = 58.dp

@Composable
private fun ChannelRow(
    ch: ChannelEntity,
    favorite: Boolean,
    modifier: Modifier,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val type = LocalTallyType.current
    val compact = isCompactWidth()
    TallyRow(onClick = onClick, onLongClick = onLongClick, onFocus = onFocus, modifier = modifier.fillMaxWidth().height(ROW_HEIGHT)) { focused ->
        Row(Modifier.fillMaxSize().padding(horizontal = if (compact) 8.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                ch.position.toString(),
                style = type.numeralSmall.copy(color = if (focused) Palette.Tally else Palette.TextFaint),
                modifier = Modifier.width(if (compact) 34.dp else 52.dp),
                maxLines = 1,
            )
            ChannelLogo(ch.logo, ch.name, if (compact) Modifier.size(width = 52.dp, height = 32.dp) else Modifier.size(width = 62.dp, height = 38.dp))
            Spacer(Modifier.width(if (compact) 10.dp else 14.dp))
            Column(Modifier.weight(1f)) {
                Text(ch.name, style = type.title.copy(fontSize = type.body.fontSize), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ch.language?.takeIf { it.isNotBlank() }?.let { Text(it.uppercase(), style = type.mono) }
                    if (favorite) Text("★", style = type.mono.copy(color = Palette.Caution))
                    if (ch.drm) Text(stringResource(R.string.live_drm_flag), style = type.mono.copy(color = Palette.Dead))
                }
            }
            StatusDot(StreamStatus.of(ch.status))
        }
    }
}

@Composable
private fun Monitor(focused: ChannelEntity?, vm: LiveViewModel, playback: PlaybackController, modifier: Modifier) {
    val type = LocalTallyType.current
    val player by playback.player.collectAsState()
    val ui by playback.ui.collectAsState()
    val epg by vm.focusedEpg.collectAsState()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Palette.Graphite)
                .border(1.dp, Palette.Hairline)
        ) {
            val showing = focused != null && ui.item?.key == focused.channelKey
            if (showing) VideoSurface(player, 0, Modifier.fillMaxSize())
            if (focused == null) {
                Text(stringResource(R.string.live_select_channel), style = type.bodySmall, modifier = Modifier.align(Alignment.Center))
            } else if (showing && ui.issue != null) {
                Text(issueText(ui.issue!!), style = type.bodySmall.copy(color = Palette.Text), modifier = Modifier.align(Alignment.Center).padding(16.dp))
            }
            if (showing && ui.playing) LiveBadge(Modifier.align(Alignment.TopStart).padding(8.dp))
            ui.video?.takeIf { showing && it.height > 0 }?.let {
                Tag("${it.height}p", Modifier.align(Alignment.TopEnd).padding(8.dp))
            }
        }
        if (focused != null) {
            Text(focused.name, style = type.headline, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val status = StreamStatus.of(focused.status)
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(status)
                Spacer(Modifier.width(8.dp))
                Text(statusLabel(status), style = type.bodySmall.copy(color = Palette.Text))
                if (focused.statusCheckedAt > 0) {
                    Text(" · " + relativeTime(focused.statusCheckedAt), style = type.bodySmall)
                }
            }
            focused.owner?.let { Text(stringResource(R.string.live_owner, it), style = type.bodySmall) }
            focused.geo?.let { Text(stringResource(R.string.live_geo, it), style = type.bodySmall.copy(color = Palette.Caution)) }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Hairline))
            if (epg.isEmpty()) {
                Text(stringResource(R.string.live_no_epg), style = type.bodySmall)
            } else {
                val now = System.currentTimeMillis()
                epg.take(3).forEachIndexed { i, p ->
                    Row {
                        Text(clockTime(p.start), style = type.mono.copy(color = if (i == 0) Palette.Tally else Palette.TextDim), modifier = Modifier.width(64.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.title, style = if (i == 0) type.title else type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (i == 0 && p.start <= now) {
                                Spacer(Modifier.height(4.dp))
                                ProgressLine((now - p.start).toFloat() / (p.stop - p.start).coerceAtLeast(1), Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val PREVIEW_DWELL_MS = 900L
