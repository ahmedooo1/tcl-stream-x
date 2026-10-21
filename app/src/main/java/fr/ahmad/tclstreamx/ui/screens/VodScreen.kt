package fr.ahmad.tclstreamx.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import fr.ahmad.tclstreamx.R
import fr.ahmad.tclstreamx.TclApp
import fr.ahmad.tclstreamx.data.db.ChannelEntity
import fr.ahmad.tclstreamx.data.db.ContentKind
import fr.ahmad.tclstreamx.data.db.GroupCount
import fr.ahmad.tclstreamx.data.m3u.M3uParser
import fr.ahmad.tclstreamx.data.vod.Genre
import fr.ahmad.tclstreamx.data.vod.VodFile
import fr.ahmad.tclstreamx.data.vod.VodItem
import fr.ahmad.tclstreamx.data.vod.VodRepository
import fr.ahmad.tclstreamx.data.vod.VodShelf
import fr.ahmad.tclstreamx.data.xtream.XtreamRepository
import fr.ahmad.tclstreamx.player.Lineup
import fr.ahmad.tclstreamx.player.PlayItem
import fr.ahmad.tclstreamx.player.PlaybackController
import fr.ahmad.tclstreamx.ui.components.InputField
import fr.ahmad.tclstreamx.ui.components.TallyCard
import fr.ahmad.tclstreamx.ui.components.TallyRow
import fr.ahmad.tclstreamx.ui.nav.Navigator
import fr.ahmad.tclstreamx.ui.theme.LocalTallyType
import fr.ahmad.tclstreamx.ui.theme.Palette
import fr.ahmad.tclstreamx.ui.theme.isCompactWidth
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.IOException

/** One subsection of Films / Séries / Animation. */
private sealed interface VodSection {
    /** A group-title of the user's own playlists (entries sorted into this section on import). */
    data class Playlist(val group: String, val kind: Int, val count: Int) : VodSection
    data class Archive(val genre: Genre) : VodSection
    /** Free 24/7 film or series channels of the built-in catalog (official FAST channels). */
    data class Channels(val category: String, val count: Int) : VodSection
    data object OpenFilms : VodSection
    data object Services : VodSection
    /** Start page of the section: a featured title and one row per category, like a streaming service. */
    data object Featured : VodSection
}

private val ANIMATION_WORDS = listOf("anim", "cartoon", "kids", "enfant", "jeunesse", "dessin", "كرتون", "أطفال", "اطفال", "انمي", "أنمي")

/**
 * Films, Séries and Animation share one layout: a column of subsections (playlist groups first,
 * then public-domain genres, open films and official services) and the content of the selected one.
 * On a narrow portrait screen the subsections become a horizontal strip above the content.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun VodScreen(shelf: VodShelf, navigator: Navigator, playback: PlaybackController) {
    val c = (LocalContext.current.applicationContext as TclApp).container
    val type = LocalTallyType.current
    val compact = isCompactWidth()

    var region by remember(shelf) { mutableStateOf<String?>(null) }
    val regions by remember(shelf) {
        c.db.channels().observeRegions(0, if (shelf == VodShelf.SERIES) ContentKind.SERIES else ContentKind.MOVIE)
    }.collectAsState(initial = emptyList())
    val groupsFlow = remember(shelf, region) {
        val dao = c.db.channels()
        when (shelf) {
            VodShelf.MOVIES -> dao.observeGroups(0, ContentKind.MOVIE, region).map { m ->
                m.map { VodSection.Playlist(it.groupTitle, ContentKind.MOVIE, it.count) }
            }
            VodShelf.SERIES -> dao.observeGroups(0, ContentKind.SERIES, region).map { s ->
                s.map { VodSection.Playlist(it.groupTitle, ContentKind.SERIES, it.count) }
            }
            VodShelf.ANIMATION -> combine(dao.observeGroups(0, ContentKind.MOVIE, region), dao.observeGroups(0, ContentKind.SERIES, region)) { m, s ->
                fun pick(list: List<GroupCount>, kind: Int) = list
                    .filter { g -> ANIMATION_WORDS.any { it in g.groupTitle.lowercase() } }
                    .map { VodSection.Playlist(it.groupTitle, kind, it.count) }
                pick(m, ContentKind.MOVIE) + pick(s, ContentKind.SERIES)
            }
        }
    }
    val playlistSections by groupsFlow.collectAsState(initial = emptyList())
    val archiveSections = remember(shelf) { Genre.of(shelf).map { VodSection.Archive(it) } }
    val hasOpenFilms = shelf != VodShelf.SERIES
    var selected by remember(shelf) { mutableStateOf<VodSection>(VodSection.Featured) }
    // A category that disappears after a change of country or language sends back to the start page.
    LaunchedEffect(playlistSections, region) {
        val current = selected
        if (current is VodSection.Playlist && playlistSections.none { it.group == current.group && it.kind == current.kind }) {
            selected = VodSection.Featured
        }
    }
    val pickRegion: (String?) -> Unit = { region = it }
    val channelCategory = when (shelf) {
        VodShelf.MOVIES -> "movies"
        VodShelf.SERIES -> "series"
        VodShelf.ANIMATION -> "kids"
    }
    val channelRows by remember(shelf) { c.db.channels().observeCategory(channelCategory) }.collectAsState(initial = emptyList())
    val channelSections = if (channelRows.isEmpty()) emptyList() else listOf(VodSection.Channels(channelCategory, channelRows.size))
    val sections: List<VodSection> = listOf(VodSection.Featured) + playlistSections + channelSections + archiveSections +
        (if (hasOpenFilms) listOf(VodSection.OpenFilms, VodSection.Services) else emptyList())

    val title = stringResource(
        when (shelf) {
            VodShelf.MOVIES -> R.string.nav_movies
            VodShelf.SERIES -> R.string.nav_series
            VodShelf.ANIMATION -> R.string.nav_animation
        }
    )
    val pick: (VodSection) -> Unit = { selected = it }

    if (compact) {
        Column(Modifier.fillMaxSize().padding(top = 12.dp)) {
            Text(title, style = type.headline, modifier = Modifier.padding(horizontal = 16.dp))
            LazyRow(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(sections, key = { sectionKey(it) }) { s ->
                    TallyRow(onClick = { pick(s) }, selected = s == selected, modifier = Modifier.height(44.dp)) {
                        Text(sectionLabel(s), style = type.body, maxLines = 1, modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 12.dp))
                    }
                }
            }
            RegionStrip(regions, region, pickRegion, Modifier.padding(bottom = 8.dp))
            SectionContent(selected, shelf, region, playlistSections, pick, navigator, playback, Modifier.weight(1f))
        }
    } else {
        Row(Modifier.fillMaxSize().padding(top = 28.dp)) {
            LazyColumn(
                Modifier.width(260.dp).fillMaxHeight().padding(start = 12.dp).focusRestorer(),
                contentPadding = PaddingValues(bottom = 32.dp),
            ) {
                item { Text(title, style = type.display, modifier = Modifier.padding(start = 14.dp, bottom = 12.dp)) }
                item { SectionRow(VodSection.Featured, selected == VodSection.Featured) { pick(VodSection.Featured) } }
                if (playlistSections.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.section_my_playlists)) }
                    items(playlistSections, key = { sectionKey(it) }) { s -> SectionRow(s, s == selected) { pick(s) } }
                }
                if (channelSections.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.section_free_channels)) }
                    items(channelSections, key = { sectionKey(it) }) { s -> SectionRow(s, s == selected) { pick(s) } }
                }
                item { SectionHeader(stringResource(R.string.section_public_domain)) }
                items(archiveSections, key = { sectionKey(it) }) { s -> SectionRow(s, s == selected) { pick(s) } }
                if (hasOpenFilms) {
                    item { SectionHeader(" ") }
                    item { SectionRow(VodSection.OpenFilms, selected == VodSection.OpenFilms) { pick(VodSection.OpenFilms) } }
                    item { SectionRow(VodSection.Services, selected == VodSection.Services) { pick(VodSection.Services) } }
                }
            }
            Column(Modifier.weight(1f)) {
                RegionStrip(regions, region, pickRegion, Modifier.padding(bottom = 8.dp), PaddingValues(start = 16.dp, end = 24.dp))
                SectionContent(selected, shelf, region, playlistSections, pick, navigator, playback, Modifier.weight(1f))
            }
        }
    }
}

private fun sectionKey(s: VodSection): String = when (s) {
    is VodSection.Playlist -> "p${s.kind}:${s.group}"
    is VodSection.Archive -> "a:${s.genre.name}"
    is VodSection.Channels -> "c:${s.category}"
    VodSection.OpenFilms -> "open"
    VodSection.Services -> "svc"
    VodSection.Featured -> "featured"
}

@Composable
private fun sectionLabel(s: VodSection): String = when (s) {
    is VodSection.Playlist -> s.group.ifBlank { stringResource(R.string.group_none) }
    is VodSection.Archive -> genreLabel(s.genre)
    is VodSection.Channels -> groupLabel(s.category)
    VodSection.OpenFilms -> stringResource(R.string.section_open_films)
    VodSection.Services -> stringResource(R.string.vod_services)
    VodSection.Featured -> stringResource(R.string.vod_featured)
}

@Composable
private fun genreLabel(g: Genre): String {
    val ctx = LocalContext.current
    val id = ctx.resources.getIdentifier("genre_${g.name}", "string", ctx.packageName)
    return if (id != 0) stringResource(id) else g.name
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = LocalTallyType.current.label, modifier = Modifier.padding(start = 14.dp, top = 16.dp, bottom = 4.dp))
}

@Composable
private fun SectionRow(s: VodSection, selected: Boolean, onClick: () -> Unit) {
    val type = LocalTallyType.current
    TallyRow(onClick = onClick, selected = selected, modifier = Modifier.fillMaxWidth().height(44.dp)) { focused ->
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                sectionLabel(s),
                style = type.body.copy(color = if (focused || selected) Palette.Text else Palette.TextDim),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (s is VodSection.Playlist) Text("%,d".format(java.util.Locale.ROOT, s.count), style = type.mono)
            if (s is VodSection.Channels) Text("%,d".format(java.util.Locale.ROOT, s.count), style = type.mono)
        }
    }
}

@Composable
private fun SectionContent(
    section: VodSection,
    shelf: VodShelf,
    region: String?,
    playlistSections: List<VodSection.Playlist>,
    onPick: (VodSection) -> Unit,
    navigator: Navigator,
    playback: PlaybackController,
    modifier: Modifier,
) {
    Box(modifier.fillMaxHeight()) {
        when (section) {
            is VodSection.Playlist ->
                if (section.kind == ContentKind.SERIES) PlaylistShows(section.group, region, navigator, playback)
                else PlaylistMovies(section.group, region, navigator, playback)
            is VodSection.Archive -> ArchiveGrid(shelf, section.genre, navigator, playback)
            is VodSection.Channels -> CategoryChannels(section.category, navigator, playback)
            VodSection.OpenFilms -> OpenFilmsGrid(shelf, navigator, playback)
            VodSection.Services -> ServicesGrid()
            VodSection.Featured -> FeaturedHome(shelf, region, playlistSections, navigator, playback, onOpen = onPick)
        }
    }
}

private val GRID = GridCells.Adaptive(minSize = 156.dp)

/** Live film or series channels, played like any channel (zapping stays inside the category). */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun CategoryChannels(category: String, navigator: Navigator, playback: PlaybackController) {
    val c = (LocalContext.current.applicationContext as TclApp).container
    val rows by remember(category) { c.db.channels().observeCategory(category) }.collectAsState(initial = emptyList())
    LazyVerticalGrid(
        columns = GRID,
        modifier = Modifier.fillMaxSize().focusRestorer(),
        contentPadding = GRID_PADDING,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { GridTitle(groupLabel(category), stringResource(R.string.section_free_channels_note)) }
        items(rows, key = { it.id }) { ch ->
            ChannelTile(ch) {
                playback.play(PlayItem.fromChannel(ch, null))
                navigator.openPlayer()
            }
        }
    }
}
private val GRID_PADDING = PaddingValues(start = 16.dp, end = 24.dp, top = 8.dp, bottom = 40.dp)

// ---- the user's playlists ----------------------------------------------------------------

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PlaylistMovies(group: String, region: String?, navigator: Navigator, playback: PlaybackController) {
    val c = (LocalContext.current.applicationContext as TclApp).container
    val flow = remember(group, region) {
        Pager(PagingConfig(pageSize = 60, prefetchDistance = 60, enablePlaceholders = true, maxSize = 600)) {
            c.db.channels().paging(0, group, 0, ContentKind.MOVIE, region)
        }.flow
    }
    val paged = flow.collectAsLazyPagingItems()
    LazyVerticalGrid(
        columns = GRID,
        modifier = Modifier.fillMaxSize().focusRestorer(),
        contentPadding = GRID_PADDING,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { GridTitle(group, stringResource(R.string.vod_items, paged.itemCount)) }
        items(count = paged.itemCount, key = paged.itemKey { it.id }) { i ->
            val ch = paged[i] ?: return@items
            ChannelPoster(ch) {
                playback.play(PlayItem.fromChannel(ch, Lineup(0, group, false, ContentKind.MOVIE, region)))
                navigator.openPlayer()
            }
        }
    }
}

private data class Show(val name: String, val logo: String?, val episodes: List<ChannelEntity>)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PlaylistShows(group: String, region: String?, navigator: Navigator, playback: PlaybackController) {
    val c = (LocalContext.current.applicationContext as TclApp).container
    // Xtream accounts give one row per series (episodes are fetched when opened): page through them.
    // M3U playlists give one row per episode: those are grouped by show name.
    var plain by remember(group) { mutableStateOf<Int?>(null) }
    LaunchedEffect(group) { plain = c.db.channels().countPlainEntries(ContentKind.SERIES, group) }
    when (plain) {
        null -> Unit
        0 -> AccountShows(group, region, navigator, playback)
        else -> PlaylistEpisodeShows(group, navigator, playback)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun AccountShows(group: String, region: String?, navigator: Navigator, playback: PlaybackController) {
    val c = (LocalContext.current.applicationContext as TclApp).container
    val flow = remember(group, region) {
        Pager(PagingConfig(pageSize = 60, prefetchDistance = 60, enablePlaceholders = true, maxSize = 600)) {
            c.db.channels().paging(0, group, 0, ContentKind.SERIES, region)
        }.flow
    }
    val paged = flow.collectAsLazyPagingItems()
    var open by remember(group) { mutableStateOf<ChannelEntity?>(null) }
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GRID,
            modifier = Modifier.fillMaxSize().focusRestorer(),
            contentPadding = GRID_PADDING,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { GridTitle(group, stringResource(R.string.vod_shows, paged.itemCount)) }
            items(count = paged.itemCount, key = paged.itemKey { it.id }) { i ->
                val ch = paged[i] ?: return@items
                ChannelPoster(ch) { open = ch }
            }
        }
        open?.let { series ->
            SeriesPanel(series, Modifier.align(Alignment.CenterEnd), onClose = { open = null }) { item ->
                playback.play(item)
                navigator.openPlayer()
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PlaylistEpisodeShows(group: String, navigator: Navigator, playback: PlaybackController) {
    val c = (LocalContext.current.applicationContext as TclApp).container
    var shows by remember(group) { mutableStateOf<List<Show>?>(null) }
    var open by remember(group) { mutableStateOf<Show?>(null) }
    var openAccountSeries by remember(group) { mutableStateOf<ChannelEntity?>(null) }
    LaunchedEffect(group) {
        val all = c.db.channels().allInGroup(ContentKind.SERIES, group, MAX_EPISODES)
        shows = all.groupBy { M3uParser.normalize(M3uParser.seriesShow(it.name)) }
            .values
            .map { eps -> Show(M3uParser.seriesShow(eps.first().name), eps.firstNotNullOfOrNull { it.logo }, eps) }
    }
    val list = shows
    Box(Modifier.fillMaxSize()) {
    LazyVerticalGrid(
        columns = GRID,
        modifier = Modifier.fillMaxSize().focusRestorer(),
        contentPadding = GRID_PADDING,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            GridTitle(group, if (list == null) stringResource(R.string.vod_loading) else stringResource(R.string.vod_shows, list.size))
        }
        if (list != null) {
            items(list, key = { M3uParser.normalize(it.name) + ":" + it.episodes.first().id }) { show ->
                PosterTile(VodItem(show.name, show.name, stringResource(R.string.vod_episodes, show.episodes.size), show.logo, null, null)) {
                    val only = show.episodes.singleOrNull()
                    if (only != null && XtreamRepository.isSeries(only.url)) openAccountSeries = only else open = show
                }
            }
        }
    }
    open?.let { show ->
        EpisodesPanel(show, Modifier.align(Alignment.CenterEnd), onClose = { open = null }) { ep ->
            playback.play(PlayItem.fromChannel(ep, Lineup(0, group, false, ContentKind.SERIES)))
            navigator.openPlayer()
        }
    }
    openAccountSeries?.let { series ->
        SeriesPanel(series, Modifier.align(Alignment.CenterEnd), onClose = { openAccountSeries = null }) { item ->
            playback.play(item)
            navigator.openPlayer()
        }
    }
    }
}

@Composable
private fun EpisodesPanel(
    show: Show,
    modifier: Modifier,
    onClose: () -> Unit,
    onPlay: (ChannelEntity) -> Unit,
) {
    val type = LocalTallyType.current
    val first = remember { FocusRequester() }
    BackHandler(onBack = onClose)
    LaunchedEffect(show.name) { runCatching { first.requestFocus() } }
    LazyColumn(
        modifier.widthIn(max = 460.dp).fillMaxWidth(0.92f).fillMaxHeight().background(Palette.Graphite).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item { Text(show.name, style = type.headline) }
        item { Text(stringResource(R.string.vod_episodes, show.episodes.size), style = type.title.copy(color = Palette.Tally)) }
        items(show.episodes, key = { it.id }) { ep ->
            TallyRow(
                onClick = { onPlay(ep) },
                modifier = Modifier.fillMaxWidth().height(52.dp).let { if (ep == show.episodes.first()) it.focusRequester(first) else it },
            ) {
                Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("▶", style = type.mono.copy(color = Palette.Tally), modifier = Modifier.width(24.dp))
                    Text(ep.name, style = type.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

// ---- public domain -------------------------------------------------------------------------

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ArchiveGrid(shelf: VodShelf, genre: Genre, navigator: Navigator, playback: PlaybackController) {
    val c = (LocalContext.current.applicationContext as TclApp).container
    val type = LocalTallyType.current
    val scope = rememberCoroutineScope()
    val results = remember(genre) { mutableStateListOf<VodItem>() }
    var page by remember(genre) { mutableIntStateOf(1) }
    var query by remember(genre) { mutableStateOf("") }
    var loading by remember(genre) { mutableStateOf(false) }
    var failed by remember(genre) { mutableStateOf(false) }
    var detail by remember(genre) { mutableStateOf<VodItem?>(null) }

    fun load(reset: Boolean) {
        if (loading) return
        loading = true
        failed = false
        scope.launch {
            try {
                val next = if (reset) 1 else page + 1
                val got = c.vod.archiveShelf(shelf, next, query, genre)
                if (reset) results.clear()
                results.addAll(got.filter { n -> results.none { it.key == n.key } })
                page = next
            } catch (e: IOException) {
                failed = true
            } catch (e: IllegalArgumentException) {
                failed = true
            } finally {
                loading = false
            }
        }
    }
    LaunchedEffect(genre) { load(reset = true) }

    LazyVerticalGrid(
        columns = GRID,
        modifier = Modifier.fillMaxSize().focusRestorer(),
        contentPadding = GRID_PADDING,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GridTitle(genreLabel(genre), stringResource(R.string.vod_archive))
                InputField(
                    value = query,
                    placeholder = stringResource(R.string.vod_search_hint),
                    onSubmit = { q -> if (q != query) { query = q; load(reset = true) } },
                    modifier = Modifier.widthIn(max = 420.dp),
                )
            }
        }
        items(results, key = { it.key }) { v -> PosterTile(v) { detail = v } }
        item(span = { GridItemSpan(maxLineSpan) }) {
            when {
                failed -> Text(stringResource(R.string.vod_error), style = type.body.copy(color = Palette.Caution))
                loading -> Text(stringResource(R.string.vod_loading), style = type.body)
                results.size >= page * VodRepository.PAGE_SIZE ->
                    TallyRow(onClick = { load(reset = false) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Text(stringResource(R.string.vod_load_more), style = type.title, modifier = Modifier.align(Alignment.Center))
                    }
                else -> Unit
            }
        }
    }
    detail?.let { d ->
        Box(Modifier.fillMaxSize()) {
            DetailPanel(d, Modifier.align(Alignment.CenterEnd), onClose = { detail = null }) { file ->
                playback.play(
                    PlayItem(
                        key = "${d.key}/${file.url.substringAfterLast('/')}",
                        title = if (file.title.equals(d.title, true)) d.title else "${d.title} · ${file.title}",
                        urls = listOf(file.url),
                        isLive = false,
                        subtitles = file.subtitles,
                        poster = d.poster,
                    )
                )
                navigator.openPlayer()
            }
        }
    }
}

@Composable
private fun OpenFilmsGrid(shelf: VodShelf, navigator: Navigator, playback: PlaybackController) {
    val c = (LocalContext.current.applicationContext as TclApp).container
    val films = c.vod.openMedia.films.filter {
        when (shelf) {
            VodShelf.MOVIES -> it.kind == "film" || it.kind == "demo"
            VodShelf.ANIMATION -> it.kind == "animation"
            VodShelf.SERIES -> false
        }
    }.map { VodItem(it.key, it.title, it.year.toString(), it.poster, null, it.license, directUrl = it.url) }
    LazyVerticalGrid(
        columns = GRID,
        modifier = Modifier.fillMaxSize(),
        contentPadding = GRID_PADDING,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { GridTitle(stringResource(R.string.section_open_films), stringResource(R.string.vod_open_films)) }
        items(films, key = { it.key }) { v ->
            PosterTile(v) {
                playback.play(PlayItem(key = v.key, title = v.title, urls = listOf(v.directUrl!!), isLive = false, poster = v.poster))
                navigator.openPlayer()
            }
        }
    }
}

@Composable
private fun ServicesGrid() {
    val context = LocalContext.current
    val c = (context.applicationContext as TclApp).container
    val type = LocalTallyType.current
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 220.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = GRID_PADDING,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { GridTitle(stringResource(R.string.vod_services), stringResource(R.string.sports_open_service)) }
        servicesItems(c.vod.openMedia.services, type) { openService(context, it.pkg, it.query) }
    }
}

private fun LazyGridScope.servicesItems(
    services: List<fr.ahmad.tclstreamx.data.vod.FreeService>,
    type: fr.ahmad.tclstreamx.ui.theme.TallyType,
    onOpen: (fr.ahmad.tclstreamx.data.vod.FreeService) -> Unit,
) {
    items(services, key = { "svc:" + it.name }) { svc ->
        val name = svc.name
        TallyRow(onClick = { onOpen(svc) }, modifier = Modifier.height(56.dp)) {
            Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = type.body, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("↗", style = type.mono)
            }
        }
    }
}

// ---- shared pieces -------------------------------------------------------------------------

@Composable
private fun GridTitle(title: String, subtitle: String) {
    Column(Modifier.padding(bottom = 4.dp)) {
        Text(title, style = LocalTallyType.current.headline, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(subtitle, style = LocalTallyType.current.bodySmall)
    }
}

@Composable
private fun ChannelPoster(ch: ChannelEntity, modifier: Modifier = Modifier, onClick: () -> Unit) {
    PosterTile(VodItem(ch.channelKey, ch.name, null, ch.logo, null, null), modifier, onClick)
}

@Composable
private fun PosterTile(item: VodItem, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val type = LocalTallyType.current
    TallyCard(onClick = onClick, modifier = modifier) { focused ->
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).background(Palette.Raised)) {
                Text(
                    item.title.take(2).uppercase(),
                    style = type.numeral.copy(color = Palette.TextFaint),
                    modifier = Modifier.align(Alignment.Center),
                )
                if (item.poster != null) {
                    AsyncImage(model = item.poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(item.title, style = type.body.copy(color = if (focused) Palette.Text else Palette.TextDim), maxLines = 2, overflow = TextOverflow.Ellipsis)
                item.year?.let { Text(it, style = type.mono) }
            }
        }
    }
}

@Composable
private fun DetailPanel(vod: VodItem, modifier: Modifier, onClose: () -> Unit, onPlay: (VodFile) -> Unit) {
    val c = (LocalContext.current.applicationContext as TclApp).container
    val type = LocalTallyType.current
    var files by remember(vod.key) { mutableStateOf<List<VodFile>?>(null) }
    var error by remember(vod.key) { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    BackHandler(onBack = onClose)
    LaunchedEffect(vod.key) {
        try {
            files = c.vod.archiveFiles(vod.archiveId ?: return@LaunchedEffect)
        } catch (e: IOException) {
            error = true
        } catch (e: IllegalArgumentException) {
            error = true
        }
    }
    LaunchedEffect(files) { if (!files.isNullOrEmpty()) runCatching { first.requestFocus() } }
    LazyColumn(
        modifier.widthIn(max = 460.dp).fillMaxWidth(0.92f).fillMaxHeight().background(Palette.Graphite).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Text(vod.title, style = type.headline) }
        item { Text(listOfNotNull(vod.year, vod.license?.let { "· " + it.substringAfter("://").take(40) }).joinToString(" "), style = type.mono) }
        vod.description?.let { item { Text(it, style = type.bodySmall, maxLines = 8, overflow = TextOverflow.Ellipsis) } }
        when {
            error -> item { Text(stringResource(R.string.vod_error), style = type.body.copy(color = Palette.Caution)) }
            files == null -> item { Text(stringResource(R.string.vod_loading), style = type.body) }
            files!!.isEmpty() -> item { Text(stringResource(R.string.vod_no_files), style = type.body) }
            else -> {
                item { Text(stringResource(R.string.vod_episodes, files!!.size), style = type.title.copy(color = Palette.Tally)) }
                items(files!!, key = { it.url }) { f ->
                    TallyRow(
                        onClick = { onPlay(f) },
                        modifier = Modifier.fillMaxWidth().height(52.dp).let { if (f == files!!.first()) it.focusRequester(first) else it },
                    ) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("▶", style = type.mono.copy(color = Palette.Tally), modifier = Modifier.width(24.dp))
                            Text(f.title, style = type.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            f.durationSec?.let { Text("${(it / 60).toInt()} min", style = type.mono) }
                            if (f.subtitles.isNotEmpty()) Text(" CC", style = type.mono)
                        }
                    }
                }
            }
        }
    }
}

private const val MAX_EPISODES = 20_000

// ---- start page ("à la une") -------------------------------------------------------------

/** Something the start page can open: an entry of the user's sources or a public-domain title. */
private sealed interface Pick {
    data class Channel(val ch: ChannelEntity) : Pick
    data class Archive(val item: VodItem) : Pick
}

private const val MAX_PLAYLIST_ROWS = 14
private const val ROW_ITEMS = 24

/**
 * The first page of Films / Séries / Animation, organised like a streaming service: a featured title,
 * "continue watching", then one horizontal row per category (the user's sources first, then the free
 * channels and the public-domain genres). Rows load only when they scroll into view, from the local
 * database or a cached search, so the page opens immediately even with a library of 100 000 titles.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun FeaturedHome(
    shelf: VodShelf,
    region: String?,
    playlistSections: List<VodSection.Playlist>,
    navigator: Navigator,
    playback: PlaybackController,
    onOpen: (VodSection) -> Unit,
) {
    val c = (LocalContext.current.applicationContext as TclApp).container
    val compact = isCompactWidth()
    var hero by remember(shelf, region) { mutableStateOf<Pick?>(null) }
    var detail by remember { mutableStateOf<VodItem?>(null) }
    var series by remember { mutableStateOf<ChannelEntity?>(null) }
    val resume by remember { c.db.vodProgress().observeContinue(20) }.collectAsState(initial = emptyList())
    val category = when (shelf) {
        VodShelf.MOVIES -> "movies"
        VodShelf.SERIES -> "series"
        VodShelf.ANIMATION -> "kids"
    }
    val freeChannels by remember(shelf) { c.db.channels().observeCategory(category) }.collectAsState(initial = emptyList())
    val genres = remember(shelf) { Genre.of(shelf) }
    val tileWidth = if (compact) 150.dp else 200.dp
    val offerHero: (Pick) -> Unit = { if (hero == null) hero = it }

    // The featured title does not wait for its row to scroll into view: first title of the user's
    // first category, else a free channel, else the most watched public-domain title.
    LaunchedEffect(shelf, region, playlistSections.firstOrNull()?.group, freeChannels.isNotEmpty()) {
        if (hero != null) return@LaunchedEffect
        val first = playlistSections.firstOrNull()
        val fromSource = first?.let { c.db.channels().firstOfLineup(0, it.group, 0, it.kind, region, 12).firstOrNull { ch -> ch.logo != null } }
        val pick: Pick? = fromSource?.let { Pick.Channel(it) }
            ?: freeChannels.firstOrNull { it.logo != null }?.let { Pick.Channel(it) }
            ?: try {
                c.vod.archiveShelf(shelf, 1, null, genres.first()).firstOrNull { !it.description.isNullOrBlank() }?.let { Pick.Archive(it) }
            } catch (e: IOException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
        if (hero == null && pick != null) hero = pick
    }

    fun open(p: Pick) {
        when (p) {
            is Pick.Channel -> if (XtreamRepository.isSeries(p.ch.url)) {
                series = p.ch
            } else {
                playback.play(PlayItem.fromChannel(p.ch, null))
                navigator.openPlayer()
            }
            is Pick.Archive -> {
                val direct = p.item.directUrl
                if (direct != null) {
                    playback.play(PlayItem(key = p.item.key, title = p.item.title, urls = listOf(direct), isLive = false, poster = p.item.poster))
                    navigator.openPlayer()
                } else {
                    detail = p.item
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().focusRestorer(),
            contentPadding = PaddingValues(bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 18.dp else 24.dp),
        ) {
            hero?.let { h -> item(key = "hero") { Hero(h, compact) { open(h) } } }
            if (hero == null) item(key = "top") { Spacer(Modifier.height(8.dp)) }
            if (resume.isNotEmpty() && shelf != VodShelf.ANIMATION) {
                item(key = "resume") {
                    ShelfRow(stringResource(R.string.home_continue), null) {
                        items(resume, key = { "r:" + it.key }) { r ->
                            PosterTile(VodItem(r.key, r.title, null, r.poster, null, null), Modifier.width(tileWidth)) {
                                playback.play(PlayItem(key = r.key, title = r.title, urls = listOf(r.uri), isLive = false, poster = r.poster))
                                navigator.openPlayer()
                            }
                        }
                    }
                }
            }
            items(playlistSections.take(MAX_PLAYLIST_ROWS), key = { "pl:${it.kind}:${it.group}" }) { s ->
                SourceRow(s, region, tileWidth, offerHero, ::open) { onOpen(s) }
            }
            if (freeChannels.isNotEmpty()) {
                item(key = "free") {
                    ShelfRow(groupLabel(category), { onOpen(VodSection.Channels(category, freeChannels.size)) }) {
                        items(freeChannels.take(ROW_ITEMS), key = { "f:" + it.id }) { ch ->
                            ChannelPoster(ch, Modifier.width(tileWidth)) { open(Pick.Channel(ch)) }
                        }
                    }
                }
            }
            items(genres, key = { "g:" + it.name }) { g ->
                ArchiveRow(shelf, g, tileWidth, offerHero, ::open) { onOpen(VodSection.Archive(g)) }
            }
        }
        detail?.let { d ->
            DetailPanel(d, Modifier.align(Alignment.CenterEnd), onClose = { detail = null }) { file ->
                playback.play(
                    PlayItem(
                        key = "${d.key}/${file.url.substringAfterLast('/')}",
                        title = if (file.title.equals(d.title, true)) d.title else "${d.title} · ${file.title}",
                        urls = listOf(file.url),
                        isLive = false,
                        subtitles = file.subtitles,
                        poster = d.poster,
                    )
                )
                navigator.openPlayer()
            }
        }
        series?.let { show ->
            SeriesPanel(show, Modifier.align(Alignment.CenterEnd), onClose = { series = null }) { item ->
                playback.play(item)
                navigator.openPlayer()
            }
        }
    }
}

@Composable
private fun Hero(pick: Pick?, compact: Boolean, onPlay: () -> Unit) {
    val type = LocalTallyType.current
    val (title, text, image) = when (pick) {
        is Pick.Channel -> Triple(pick.ch.name, pick.ch.groupTitle.ifBlank { null }, pick.ch.logo)
        is Pick.Archive -> Triple(pick.item.title, pick.item.description, pick.item.poster)
        null -> Triple(null, null, null)
    }
    Box(Modifier.fillMaxWidth().height(if (compact) 230.dp else 320.dp).background(Palette.Graphite)) {
        if (image != null) {
            AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        // Legibility of the title over any poster: dark at the bottom and on the reading side.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Palette.Ink.copy(alpha = 0.55f), Palette.Ink))))
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Palette.Ink.copy(alpha = 0.75f), Color.Transparent))))
        if (title != null) {
            Column(
                Modifier.align(Alignment.BottomStart).padding(start = 20.dp, end = 20.dp, bottom = 18.dp).widthIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(title, style = if (compact) type.headline else type.display, maxLines = 2, overflow = TextOverflow.Ellipsis)
                text?.let { Text(it, style = type.bodySmall.copy(color = Palette.Text), maxLines = 3, overflow = TextOverflow.Ellipsis) }
                TallyRow(onClick = onPlay, modifier = Modifier.width(190.dp).height(48.dp)) {
                    Text("▶  " + stringResource(R.string.vod_play), style = type.title, modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 16.dp))
                }
            }
        }
    }
}

/** Title line and one horizontal row of tiles; "see all" opens the full category. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ShelfRow(title: String, onAll: (() -> Unit)?, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    val type = LocalTallyType.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = type.headline, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (onAll != null) {
                TallyRow(onClick = onAll, modifier = Modifier.height(38.dp)) {
                    Text(stringResource(R.string.vod_see_all) + "  ›", style = type.label.copy(color = Palette.Text), modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 12.dp))
                }
            }
        }
        LazyRow(
            Modifier.fillMaxWidth().focusRestorer(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun RowPlaceholder(width: androidx.compose.ui.unit.Dp) {
    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(4) { Box(Modifier.width(width).aspectRatio(16f / 10f).background(Palette.Raised)) }
    }
}

/** First titles of one category of the user's sources (a single indexed query). */
@Composable
private fun SourceRow(
    s: VodSection.Playlist,
    region: String?,
    tileWidth: androidx.compose.ui.unit.Dp,
    offerHero: (Pick) -> Unit,
    open: (Pick) -> Unit,
    onAll: () -> Unit,
) {
    val c = (LocalContext.current.applicationContext as TclApp).container
    var rows by remember(s.group, s.kind, region) { mutableStateOf<List<ChannelEntity>?>(null) }
    LaunchedEffect(s.group, s.kind, region) {
        val got = c.db.channels().firstOfLineup(0, s.group, 0, s.kind, region, ROW_ITEMS)
        rows = got
        got.firstOrNull { it.logo != null }?.let { offerHero(Pick.Channel(it)) }
    }
    val list = rows
    if (list == null) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(s.group.ifBlank { stringResource(R.string.group_none) }, style = LocalTallyType.current.headline, modifier = Modifier.padding(horizontal = 16.dp))
            RowPlaceholder(tileWidth)
        }
        return
    }
    if (list.isEmpty()) return
    ShelfRow(s.group.ifBlank { stringResource(R.string.group_none) }, onAll) {
        items(list, key = { "c:" + it.id }) { ch -> ChannelPoster(ch, Modifier.width(tileWidth)) { open(Pick.Channel(ch)) } }
    }
}

/** First page of a public-domain genre (cached for 30 minutes). */
@Composable
private fun ArchiveRow(
    shelf: VodShelf,
    genre: Genre,
    tileWidth: androidx.compose.ui.unit.Dp,
    offerHero: (Pick) -> Unit,
    open: (Pick) -> Unit,
    onAll: () -> Unit,
) {
    val c = (LocalContext.current.applicationContext as TclApp).container
    var rows by remember(shelf, genre) { mutableStateOf<List<VodItem>?>(null) }
    LaunchedEffect(shelf, genre) {
        val got = try {
            c.vod.archiveShelf(shelf, 1, null, genre)
        } catch (e: IOException) {
            emptyList()
        } catch (e: IllegalArgumentException) {
            emptyList()
        }
        rows = got
        got.firstOrNull { !it.description.isNullOrBlank() }?.let { offerHero(Pick.Archive(it)) }
    }
    val list = rows
    if (list == null) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(genreLabel(genre), style = LocalTallyType.current.headline, modifier = Modifier.padding(horizontal = 16.dp))
            RowPlaceholder(tileWidth)
        }
        return
    }
    if (list.isEmpty()) return
    ShelfRow(genreLabel(genre), onAll) {
        items(list.take(ROW_ITEMS), key = { "a:" + it.key }) { v -> PosterTile(v, Modifier.width(tileWidth)) { open(Pick.Archive(v)) } }
    }
}
