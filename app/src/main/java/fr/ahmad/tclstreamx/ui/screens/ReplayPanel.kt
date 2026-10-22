package fr.ahmad.tclstreamx.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import fr.ahmad.tclstreamx.R
import fr.ahmad.tclstreamx.TclApp
import fr.ahmad.tclstreamx.data.db.ChannelEntity
import fr.ahmad.tclstreamx.data.xtream.XtreamProgramme
import fr.ahmad.tclstreamx.player.PlayItem
import fr.ahmad.tclstreamx.ui.components.TallyRow
import fr.ahmad.tclstreamx.ui.theme.LocalTallyType
import fr.ahmad.tclstreamx.ui.theme.Palette
import java.io.IOException
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/**
 * Replay (catch-up) of a live channel whose subscription keeps past programmes: one tab per day,
 * the programmes of that day that the source still holds, newest first.
 */
@Composable
fun ReplayPanel(channel: ChannelEntity, modifier: Modifier, onClose: () -> Unit, onPlay: (PlayItem) -> Unit) {
    val c = (LocalContext.current.applicationContext as TclApp).container
    val type = LocalTallyType.current
    var programmes by remember(channel.url) { mutableStateOf<List<XtreamProgramme>?>(null) }
    var failed by remember(channel.url) { mutableStateOf(false) }
    var day by remember(channel.url) { mutableStateOf(0) }
    val firstRow = remember { FocusRequester() }
    BackHandler(onBack = onClose)
    LaunchedEffect(channel.url) {
        try {
            val now = System.currentTimeMillis()
            programmes = c.xtream.programmes(channel.url).filter { it.hasArchive && it.stop < now }
        } catch (e: IOException) {
            failed = true
        }
    }
    val all = programmes.orEmpty()
    val days = all.map { dayIndex(it.start) }.distinct().sorted()
    val shown = all.filter { dayIndex(it.start) == day }.sortedByDescending { it.start }
    LaunchedEffect(days) { if (days.isNotEmpty() && day !in days) day = days.first() }
    LaunchedEffect(shown.size, day) { if (shown.isNotEmpty()) runCatching { firstRow.requestFocus() } }

    LazyColumn(
        modifier.widthIn(max = 520.dp).fillMaxWidth(0.94f).fillMaxHeight().background(Palette.Graphite.copy(alpha = 0.97f)).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item { Text(stringResource(R.string.replay_title), style = type.headline) }
        item { Text(channel.name, style = type.mono) }
        when {
            failed -> item { Text(stringResource(R.string.vod_error), style = type.body.copy(color = Palette.Caution)) }
            programmes == null -> item { Text(stringResource(R.string.vod_loading), style = type.body) }
            all.isEmpty() -> item { Text(stringResource(R.string.replay_empty), style = type.body) }
            else -> {
                item {
                    LazyRow(Modifier.padding(vertical = 8.dp), contentPadding = PaddingValues(0.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(days, key = { "d$it" }) { d ->
                            TallyRow(onClick = { day = d }, selected = d == day, modifier = Modifier.height(40.dp)) { focused ->
                                Text(
                                    dayLabel(d),
                                    style = type.label.copy(color = if (focused || d == day) Palette.Text else Palette.TextDim),
                                    modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 12.dp),
                                )
                            }
                        }
                    }
                }
                items(shown, key = { "p" + it.start }) { p ->
                    TallyRow(
                        onClick = {
                            val ref = c.xtream.replayRef(channel.url, p) ?: return@TallyRow
                            onPlay(
                                PlayItem(
                                    key = "replay:${channel.channelKey}:${p.start}",
                                    title = "${channel.name} · ${p.title}",
                                    subtitle = clockTime(p.start),
                                    urls = listOf(ref),
                                    isLive = false,
                                    logo = channel.logo,
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth().height(56.dp).let { if (p == shown.first()) it.focusRequester(firstRow) else it },
                    ) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(clockTime(p.start), style = type.mono.copy(color = Palette.Tally), modifier = Modifier.width(64.dp))
                            Text(p.title, style = type.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text("${(p.stop - p.start) / 60_000} min", style = type.mono)
                        }
                    }
                }
            }
        }
    }
}

/** 0 = today, 1 = yesterday... (device time zone). */
private fun dayIndex(millis: Long): Int {
    val today = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    return if (millis >= today) 0 else ((today - millis) / 86_400_000L + 1).toInt()
}

@Composable
private fun dayLabel(index: Int): String = when (index) {
    0 -> stringResource(R.string.replay_today)
    1 -> stringResource(R.string.replay_yesterday)
    else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(System.currentTimeMillis() - index * 86_400_000L))
}
