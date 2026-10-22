package fr.ahmad.tclstreamx.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.tv.material3.Text
import fr.ahmad.tclstreamx.R
import fr.ahmad.tclstreamx.TclApp
import fr.ahmad.tclstreamx.data.db.ChannelEntity
import fr.ahmad.tclstreamx.data.db.ProgrammeEntity
import fr.ahmad.tclstreamx.data.xtream.XtreamUrls
import fr.ahmad.tclstreamx.player.PlayItem
import fr.ahmad.tclstreamx.player.PlaybackController
import fr.ahmad.tclstreamx.ui.components.ChannelLogo
import fr.ahmad.tclstreamx.ui.components.TallyRow
import fr.ahmad.tclstreamx.ui.nav.Navigator
import fr.ahmad.tclstreamx.ui.theme.LocalTallyType
import fr.ahmad.tclstreamx.ui.theme.Palette
import fr.ahmad.tclstreamx.ui.theme.isCompactWidth

private const val HALF_HOUR = 30 * 60_000L
private const val SPAN = 3 * 3600_000L

/**
 * TV guide grid: the channels of the current list (same filters as the channel screen) against a
 * three-hour window that moves by an hour and a half. A programme on air opens the channel; a past
 * one opens the replay of channels whose subscription keeps it.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun GuideScreen(navigator: Navigator, playback: PlaybackController, vm: LiveViewModel) {
    val type = LocalTallyType.current
    val compact = isCompactWidth()
    val paged = vm.channels.collectAsLazyPagingItems()
    val now = remember { System.currentTimeMillis() }
    var windowStart by remember { mutableLongStateOf(now - now % HALF_HOUR - HALF_HOUR) }
    var replay by remember { mutableStateOf<ChannelEntity?>(null) }
    val nameWidth = if (compact) 110.dp else 190.dp

    fun playLive(ch: ChannelEntity) {
        playback.play(PlayItem.fromChannel(ch, vm.lineup()))
        navigator.openPlayer()
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(top = if (compact) 10.dp else 26.dp, start = 12.dp, end = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                Text(stringResource(R.string.guide_title), style = if (compact) type.headline else type.display, modifier = Modifier.weight(1f))
                TallyRow(onClick = { windowStart -= SPAN / 2 }, modifier = Modifier.size(width = 56.dp, height = 44.dp)) {
                    Text("◀", style = type.title, modifier = Modifier.align(Alignment.Center))
                }
                Text(
                    clockTime(windowStart) + " – " + clockTime(windowStart + SPAN),
                    style = type.mono.copy(color = Palette.Text),
                    modifier = Modifier.padding(horizontal = 10.dp),
                )
                TallyRow(onClick = { windowStart += SPAN / 2 }, modifier = Modifier.size(width = 56.dp, height = 44.dp)) {
                    Text("▶", style = type.title, modifier = Modifier.align(Alignment.Center))
                }
            }
            // Time ruler, one mark per half hour.
            Row(Modifier.fillMaxWidth().height(22.dp)) {
                Spacer(Modifier.width(nameWidth))
                BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                    for (i in 0 until (SPAN / HALF_HOUR).toInt()) {
                        Text(
                            clockTime(windowStart + i * HALF_HOUR),
                            style = type.mono,
                            modifier = Modifier.offset(x = maxWidth * (i * HALF_HOUR / SPAN.toFloat())),
                        )
                    }
                }
            }
            LazyColumn(Modifier.fillMaxSize().focusRestorer(), contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(count = paged.itemCount, key = paged.itemKey { it.id }) { index ->
                    val ch = paged[index] ?: return@items
                    GuideRow(ch, windowStart, nameWidth, onLive = { playLive(ch) }, onPast = {
                        if (ch.archiveDays > 0 && XtreamUrls.isInternal(ch.url)) replay = ch else playLive(ch)
                    })
                }
            }
        }
        replay?.let { ch ->
            ReplayPanel(ch, Modifier.align(Alignment.CenterEnd), onClose = { replay = null }) { item ->
                playback.play(item)
                navigator.openPlayer()
            }
        }
    }
}

@Composable
private fun GuideRow(ch: ChannelEntity, windowStart: Long, nameWidth: androidx.compose.ui.unit.Dp, onLive: () -> Unit, onPast: () -> Unit) {
    val c = (LocalContext.current.applicationContext as TclApp).container
    val type = LocalTallyType.current
    var programmes by remember(ch.tvgId, windowStart) { mutableStateOf<List<ProgrammeEntity>>(emptyList()) }
    LaunchedEffect(ch.tvgId, windowStart) {
        val id = ch.tvgId?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val dao = c.db.epg()
        programmes = dao.inRange(id.lowercase(), windowStart, windowStart + SPAN)
            .ifEmpty { dao.inRange(id.substringBefore('@').lowercase(), windowStart, windowStart + SPAN) }
    }
    val now = System.currentTimeMillis()
    Row(Modifier.fillMaxWidth().height(58.dp), verticalAlignment = Alignment.CenterVertically) {
        TallyRow(onClick = onLive, modifier = Modifier.width(nameWidth).fillMaxHeight()) {
            Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ChannelLogo(ch.logo, ch.name, Modifier.size(width = 40.dp, height = 28.dp))
                Spacer(Modifier.width(8.dp))
                Text(ch.name, style = type.label.copy(color = Palette.Text), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().padding(start = 4.dp).background(Palette.Graphite)) {
            if (programmes.isEmpty()) {
                TallyRow(onClick = onLive, modifier = Modifier.fillMaxSize()) {
                    Text(stringResource(R.string.live_no_epg), style = type.bodySmall, modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 10.dp))
                }
            }
            programmes.forEach { p ->
                val from = maxOf(p.start, windowStart)
                val to = minOf(p.stop, windowStart + SPAN)
                if (to > from) {
                    val x = maxWidth * ((from - windowStart) / SPAN.toFloat())
                    val w = maxWidth * ((to - from) / SPAN.toFloat())
                    val onAir = p.start <= now && now < p.stop
                    TallyRow(
                        onClick = { if (p.stop <= now) onPast() else onLive() },
                        selected = onAir,
                        modifier = Modifier.offset(x = x).width(w).fillMaxHeight().padding(1.dp),
                    ) { focused ->
                        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(
                                p.title,
                                style = type.label.copy(color = if (focused || onAir) Palette.Text else Palette.TextDim),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(clockTime(p.start), style = type.mono, maxLines = 1)
                        }
                    }
                }
            }
            // Now line.
            if (now in windowStart until windowStart + SPAN) {
                Box(Modifier.offset(x = maxWidth * ((now - windowStart) / SPAN.toFloat())).width(2.dp).fillMaxHeight().background(Palette.Tally))
            }
        }
    }
}
