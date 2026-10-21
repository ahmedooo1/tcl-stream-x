package fr.ahmad.tclstreamx.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import fr.ahmad.tclstreamx.data.db.ContentKind
import fr.ahmad.tclstreamx.data.db.StreamStatus
import fr.ahmad.tclstreamx.data.m3u.M3uParser
import fr.ahmad.tclstreamx.data.xtream.XtreamRepository
import fr.ahmad.tclstreamx.player.PlayItem
import fr.ahmad.tclstreamx.player.PlaybackController
import fr.ahmad.tclstreamx.ui.components.ChannelLogo
import fr.ahmad.tclstreamx.ui.components.InputField
import fr.ahmad.tclstreamx.ui.components.StatusDot
import fr.ahmad.tclstreamx.ui.components.TallyRow
import fr.ahmad.tclstreamx.ui.nav.Navigator
import fr.ahmad.tclstreamx.ui.theme.LocalTallyType
import fr.ahmad.tclstreamx.ui.theme.Palette
import fr.ahmad.tclstreamx.ui.theme.screenPadding

private data class SearchResults(val live: List<ChannelEntity>, val movies: List<ChannelEntity>, val series: List<ChannelEntity>) {
    val isEmpty get() = live.isEmpty() && movies.isEmpty() && series.isEmpty()
}

/**
 * One search over everything the user has: live channels, films and series of every playlist and
 * account. Each section is queried separately with its own limit, so a library dominated by films
 * still shows the matching channels.
 */
@Composable
fun SearchScreen(navigator: Navigator, playback: PlaybackController) {
    val c = (LocalContext.current.applicationContext as TclApp).container
    val type = LocalTallyType.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(SearchResults(emptyList(), emptyList(), emptyList())) }
    var openSeries by remember { mutableStateOf<ChannelEntity?>(null) }
    val first = remember { FocusRequester() }

    LaunchedEffect(query) {
        val q = M3uParser.normalize(query)
        results = if (q.length < 2) {
            SearchResults(emptyList(), emptyList(), emptyList())
        } else {
            val dao = c.db.channels()
            SearchResults(
                dao.searchKind(q, ContentKind.LIVE, PER_SECTION),
                dao.searchKind(q, ContentKind.MOVIE, PER_SECTION),
                dao.searchKind(q, ContentKind.SERIES, PER_SECTION),
            )
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    fun open(ch: ChannelEntity) {
        if (ch.kind == ContentKind.SERIES && XtreamRepository.isSeries(ch.url)) {
            openSeries = ch
            return
        }
        playback.play(PlayItem.fromChannel(ch, null))
        navigator.openPlayer()
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = screenPadding(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item { Text(stringResource(R.string.nav_search), style = type.display, modifier = Modifier.padding(bottom = 12.dp)) }
            item {
                InputField(query, stringResource(R.string.search_hint), onSubmit = { query = it }, rowModifier = Modifier.focusRequester(first))
                Spacer(Modifier.height(12.dp))
            }
            if (query.length >= 2 && results.isEmpty) {
                item { Text(stringResource(R.string.search_empty), style = type.body) }
            }
            listOf(
                R.string.search_live to results.live,
                R.string.search_movies to results.movies,
                R.string.search_series to results.series,
            ).forEach { (title, list) ->
                if (list.isNotEmpty()) {
                    item(key = "h:$title") {
                        Text(
                            stringResource(title) + "  " + list.size + if (list.size >= PER_SECTION) "+" else "",
                            style = type.headline,
                            modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
                        )
                    }
                    items(list, key = { it.id }) { ch -> ResultRow(ch) { open(ch) } }
                }
            }
        }
        openSeries?.let { series ->
            SeriesPanel(series, Modifier.align(Alignment.CenterEnd), onClose = { openSeries = null }) { item ->
                playback.play(item)
                navigator.openPlayer()
            }
        }
    }
}

@Composable
private fun ResultRow(ch: ChannelEntity, onClick: () -> Unit) {
    val type = LocalTallyType.current
    TallyRow(onClick = onClick, modifier = Modifier.fillMaxWidth().height(58.dp)) {
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(ch.logo, ch.name, Modifier.size(width = 62.dp, height = 38.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(ch.name, style = type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(groupLabel(ch.groupTitle), ch.country?.let { regionLabel(it) }).joinToString(" · "),
                    style = type.mono,
                    maxLines = 1,
                )
            }
            if (ch.kind == ContentKind.LIVE) StatusDot(StreamStatus.of(ch.status))
            else Text("▶", style = type.mono.copy(color = Palette.Tally))
        }
    }
}

private const val PER_SECTION = 60
