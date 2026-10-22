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
import fr.ahmad.tclstreamx.data.xtream.XtreamEpisode
import fr.ahmad.tclstreamx.data.xtream.XtreamSeriesInfo
import fr.ahmad.tclstreamx.player.PlayItem
import fr.ahmad.tclstreamx.ui.components.TallyRow
import fr.ahmad.tclstreamx.ui.theme.LocalTallyType
import fr.ahmad.tclstreamx.ui.theme.Palette
import java.io.IOException
import java.util.Locale

/**
 * Seasons and episodes of a series from an Xtream account. They are fetched from the panel only
 * when the series is opened (and kept in a small cache), never during the library import.
 */
@Composable
fun SeriesPanel(series: ChannelEntity, modifier: Modifier, onClose: () -> Unit, onPlay: (PlayItem) -> Unit) {
    val c = (LocalContext.current.applicationContext as TclApp).container
    val type = LocalTallyType.current
    var info by remember(series.url) { mutableStateOf<XtreamSeriesInfo?>(null) }
    var failed by remember(series.url) { mutableStateOf(false) }
    var season by remember(series.url) { mutableStateOf<Int?>(null) }
    val firstEpisode = remember { FocusRequester() }
    BackHandler(onBack = onClose)
    LaunchedEffect(series.url) {
        try {
            val got = c.xtream.seriesInfo(series.url)
            info = got
            season = got.seasons.firstOrNull()?.first
        } catch (e: IOException) {
            failed = true
        }
    }
    val episodes = info?.episodes.orEmpty().filter { it.season == season }
    LaunchedEffect(season, info) { if (episodes.isNotEmpty()) runCatching { firstEpisode.requestFocus() } }

    fun itemFor(ep: XtreamEpisode): PlayItem? {
        val ref = c.xtream.episodeRef(series.url, ep) ?: return null
        val code = "S%02dE%02d".format(Locale.ROOT, ep.season, ep.number)
        val title = listOf(series.name, code, ep.title.takeIf { it.isNotBlank() && !it.contains(series.name, true) })
            .filterNotNull().joinToString(" · ")
        return PlayItem(key = "${series.channelKey}:e${ep.id}", title = title, urls = listOf(ref), isLive = false, poster = ep.image ?: series.logo)
    }

    fun play(ep: XtreamEpisode) {
        val all = info?.episodes.orEmpty()
        val index = all.indexOf(ep)
        // The following episodes, across seasons, play one after another.
        val upNext = all.drop(index + 1).take(MAX_UP_NEXT).mapNotNull(::itemFor)
        onPlay(itemFor(ep)?.copy(upNext = upNext) ?: return)
    }

    LazyColumn(
        modifier.widthIn(max = 520.dp).fillMaxWidth(0.94f).fillMaxHeight().background(Palette.Graphite).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item { Text(series.name, style = type.headline, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        info?.let { i ->
            val meta = listOfNotNull(i.genre, i.releaseDate, i.rating?.takeIf { it != "0" }?.let { "★ $it" }).joinToString(" · ")
            if (meta.isNotEmpty()) item { Text(meta, style = type.mono) }
            i.plot?.let { item { Text(it, style = type.bodySmall, maxLines = 6, overflow = TextOverflow.Ellipsis) } }
        }
        when {
            failed -> item { Text(stringResource(R.string.vod_error), style = type.body.copy(color = Palette.Caution)) }
            info == null -> item { Text(stringResource(R.string.vod_loading), style = type.body) }
            info!!.episodes.isEmpty() -> item { Text(stringResource(R.string.vod_no_files), style = type.body) }
            else -> {
                item {
                    LazyRow(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        contentPadding = PaddingValues(0.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(info!!.seasons, key = { "s" + it.first }) { (n, name) ->
                            TallyRow(onClick = { season = n }, selected = n == season, modifier = Modifier.height(40.dp)) { focused ->
                                Text(
                                    name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.series_season, n),
                                    style = type.label.copy(color = if (focused || n == season) Palette.Text else Palette.TextDim),
                                    maxLines = 1,
                                    modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 12.dp),
                                )
                            }
                        }
                    }
                }
                items(episodes, key = { "e" + it.id }) { ep ->
                    TallyRow(
                        onClick = { play(ep) },
                        modifier = Modifier.fillMaxWidth().height(56.dp).let { if (ep == episodes.first()) it.focusRequester(firstEpisode) else it },
                    ) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.series_episode, ep.number), style = type.mono.copy(color = Palette.Tally), modifier = Modifier.width(64.dp))
                            Text(ep.title.ifBlank { series.name }, style = type.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            ep.durationSecs?.takeIf { it > 0 }?.let { Text("${it / 60} min", style = type.mono) }
                        }
                    }
                }
            }
        }
    }
}

private const val MAX_UP_NEXT = 60
