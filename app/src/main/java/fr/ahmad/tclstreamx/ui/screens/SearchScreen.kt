package fr.ahmad.tclstreamx.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import kotlinx.coroutines.delay
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
import fr.ahmad.tclstreamx.data.db.GroupHit
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
    // Words spoken on Home (voice search) arrive here.
    var query by remember { mutableStateOf(SearchRequest.take() ?: "") }
    val voice = rememberVoice { query = it }
    // Filters: section (-1 = all), source playlist (0 = all), country or language, available channels only.
    var kind by remember { mutableIntStateOf(-1) }
    var source by remember { mutableLongStateOf(0L) }
    var region by remember { mutableStateOf<String?>(null) }
    var onlineOnly by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf(SearchResults(emptyList(), emptyList(), emptyList())) }
    var openSeries by remember { mutableStateOf<ChannelEntity?>(null) }
    var groups by remember { mutableStateOf<List<GroupHit>>(emptyList()) }
    // A category opened from the results: its whole content replaces the result list until "back".
    var openGroup by remember { mutableStateOf<GroupHit?>(null) }
    var groupItems by remember { mutableStateOf<List<ChannelEntity>>(emptyList()) }
    LaunchedEffect(openGroup) {
        val g = openGroup ?: return@LaunchedEffect
        groupItems = c.db.channels().firstOfLineup(0, g.groupTitle, 0, g.kind, null, 2000)
    }
    val first = remember { FocusRequester() }
    val playlists by c.db.playlists().observeAll().collectAsState(initial = emptyList())
    val regions by remember(kind, source) { c.db.channels().observeRegions(source, kind) }.collectAsState(initial = emptyList())

    // Results follow the typing (short pause so every key does not start a query).
    LaunchedEffect(query, kind, source, region, onlineOnly) {
        val q = M3uParser.normalize(query)
        if (q.length < 2) {
            results = SearchResults(emptyList(), emptyList(), emptyList())
            groups = emptyList()
            return@LaunchedEffect
        }
        delay(250)
        val dao = c.db.channels()
        val online = if (onlineOnly) 1 else 0
        suspend fun section(k: Int, limit: Int) =
            if (kind < 0 || kind == k) dao.searchFiltered(q, k, source, region, online, limit) else emptyList()
        val limit = if (kind < 0) PER_SECTION else PER_SECTION * 4
        results = SearchResults(section(ContentKind.LIVE, limit), section(ContentKind.MOVIE, limit), section(ContentKind.SERIES, limit))
        groups = dao.searchGroups(query.trim(), 40).filter { kind < 0 || it.kind == kind }
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
                InputField(
                    query,
                    stringResource(R.string.search_hint),
                    onSubmit = { query = it },
                    onChange = { query = it },
                    rowModifier = Modifier.focusRequester(first),
                )
                Spacer(Modifier.height(8.dp))
                androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                    VoiceButton(voice, 48.dp)
                    androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
                    val v = voice.state
                    Text(
                        when {
                            v.listening -> v.partial.ifBlank { stringResource(R.string.voice_listening) }
                            v.message != null -> v.message!!
                            else -> stringResource(R.string.home_voice)
                        },
                        style = type.body.copy(color = if (v.message != null && !v.listening) Palette.Caution else Palette.TextDim),
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.height(10.dp))
            }
            // ---- filters ----
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val kinds = listOf(-1 to R.string.search_filter_all, ContentKind.LIVE to R.string.search_live, ContentKind.MOVIE to R.string.search_movies, ContentKind.SERIES to R.string.search_series)
                    items(kinds, key = { "k" + it.first }) { (k, label) ->
                        FilterChip(stringResource(label), kind == k) { kind = k; region = null }
                    }
                    item(key = "online") {
                        FilterChip("● " + stringResource(R.string.search_filter_online), onlineOnly) { onlineOnly = !onlineOnly }
                    }
                    item(key = "source") {
                        val ids = listOf(0L) + playlists.map { it.id }
                        val label = playlists.firstOrNull { it.id == source }?.name ?: stringResource(R.string.search_filter_sources)
                        FilterChip("$label  ⇄", source != 0L) {
                            source = ids[(ids.indexOf(source) + 1) % ids.size]
                            region = null
                        }
                    }
                }
            }
            item { RegionStrip(regions, region, { region = it }, Modifier.padding(top = 6.dp, bottom = 6.dp), PaddingValues(0.dp)) }

            openGroup?.let { g ->
                item(key = "back") {
                    FilterChip("←  " + stringResource(R.string.search_back), false) { openGroup = null }
                }
                item(key = "gtitle") {
                    Text(groupLabel(g.groupTitle) + "  " + g.count, style = type.headline, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                }
                items(groupItems, key = { "gi" + it.id }) { ch -> ResultRow(ch) { open(ch) } }
                return@LazyColumn
            }
            if (groups.isNotEmpty()) {
                item(key = "hg") {
                    Text(stringResource(R.string.search_categories) + "  " + groups.size, style = type.headline, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
                }
                item(key = "groups") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(groups, key = { "g${it.kind}:${it.groupTitle}" }) { g ->
                            val section = stringResource(
                                when (g.kind) {
                                    ContentKind.MOVIE -> R.string.search_movies
                                    ContentKind.SERIES -> R.string.search_series
                                    else -> R.string.search_live
                                }
                            )
                            FilterChip("${groupLabel(g.groupTitle)} · $section · ${g.count}", false) { openGroup = g }
                        }
                    }
                }
            }
            if (query.length >= 2 && results.isEmpty && groups.isEmpty()) {
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
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    TallyRow(onClick = onClick, selected = selected, modifier = Modifier.height(40.dp)) { focused ->
        Text(
            label,
            style = LocalTallyType.current.label.copy(color = if (focused || selected) Palette.Text else Palette.TextDim),
            maxLines = 1,
            modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 12.dp),
        )
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
