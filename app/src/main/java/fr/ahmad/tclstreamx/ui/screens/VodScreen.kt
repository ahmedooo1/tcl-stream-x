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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import fr.ahmad.tclstreamx.R
import fr.ahmad.tclstreamx.TclApp
import fr.ahmad.tclstreamx.data.vod.VodFile
import fr.ahmad.tclstreamx.data.vod.VodItem
import fr.ahmad.tclstreamx.data.vod.VodShelf
import fr.ahmad.tclstreamx.player.PlayItem
import fr.ahmad.tclstreamx.player.PlaybackController
import fr.ahmad.tclstreamx.ui.components.InputField
import fr.ahmad.tclstreamx.ui.components.TallyCard
import fr.ahmad.tclstreamx.ui.components.TallyRow
import fr.ahmad.tclstreamx.ui.nav.Navigator
import fr.ahmad.tclstreamx.ui.theme.LocalTallyType
import fr.ahmad.tclstreamx.ui.theme.Palette
import kotlinx.coroutines.launch
import java.io.IOException

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun VodScreen(shelf: VodShelf, navigator: Navigator, playback: PlaybackController) {
    val context = LocalContext.current
    val c = (context.applicationContext as TclApp).container
    val type = LocalTallyType.current
    val scope = rememberCoroutineScope()
    val results = remember(shelf) { mutableStateListOf<VodItem>() }
    var page by remember(shelf) { mutableIntStateOf(1) }
    var query by remember(shelf) { mutableStateOf("") }
    var loading by remember(shelf) { mutableStateOf(false) }
    var failed by remember(shelf) { mutableStateOf(false) }
    var detail by remember { mutableStateOf<VodItem?>(null) }
    val firstFocus = remember { FocusRequester() }

    val open = c.vod.openMedia.films.filter {
        when (shelf) {
            VodShelf.MOVIES -> it.kind == "film" || it.kind == "demo"
            VodShelf.ANIMATION -> it.kind == "animation"
            VodShelf.SERIES -> false
        }
    }.map { VodItem(it.key, it.title, it.year.toString(), it.poster, null, it.license, directUrl = it.url) }

    fun load(reset: Boolean) {
        if (loading) return
        loading = true
        failed = false
        scope.launch {
            try {
                val next = if (reset) 1 else page + 1
                val got = c.vod.archiveShelf(shelf, next, query)
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

    fun playDirect(item: VodItem) {
        playback.play(PlayItem(key = item.key, title = item.title, urls = listOf(item.directUrl!!), isLive = false, poster = item.poster))
        navigator.openPlayer()
    }

    LaunchedEffect(shelf) { load(reset = true) }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.fillMaxSize().focusRestorer(),
            contentPadding = PaddingValues(start = 40.dp, end = 40.dp, top = 30.dp, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(
                                when (shelf) {
                                    VodShelf.MOVIES -> R.string.nav_movies
                                    VodShelf.SERIES -> R.string.nav_series
                                    VodShelf.ANIMATION -> R.string.nav_animation
                                }
                            ),
                            style = type.display,
                        )
                        Text(
                            when (shelf) {
                                VodShelf.SERIES -> stringResource(R.string.vod_series_note)
                                VodShelf.ANIMATION -> stringResource(R.string.vod_animation_note)
                                VodShelf.MOVIES -> stringResource(R.string.vod_archive)
                            },
                            style = type.bodySmall,
                        )
                    }
                    InputField(
                        value = query,
                        placeholder = stringResource(R.string.vod_search_hint),
                        onSubmit = { q -> if (q != query) { query = q; load(reset = true) } },
                        modifier = Modifier.width(340.dp),
                        rowModifier = Modifier.focusRequester(firstFocus),
                    )
                }
            }
            if (open.isNotEmpty() && query.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(stringResource(R.string.vod_open_films), style = type.headline, modifier = Modifier.padding(top = 10.dp))
                }
                items(open, key = { it.key }) { v -> PosterTile(v) { playDirect(v) } }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(stringResource(R.string.vod_archive), style = type.headline, modifier = Modifier.padding(top = 10.dp))
                }
            }
            items(results, key = { it.key }) { v -> PosterTile(v) { detail = v } }
            item(span = { GridItemSpan(maxLineSpan) }) {
                when {
                    failed -> Text(stringResource(R.string.vod_error), style = type.body.copy(color = Palette.Caution))
                    loading -> Text(stringResource(R.string.vod_loading), style = type.body)
                    results.size >= page * fr.ahmad.tclstreamx.data.vod.VodRepository.PAGE_SIZE ->
                        TallyRow(onClick = { load(reset = false) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                            Text(stringResource(R.string.vod_load_more), style = type.title, modifier = Modifier.align(Alignment.Center))
                        }
                    else -> Unit
                }
            }
            if (shelf != VodShelf.SERIES) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(stringResource(R.string.vod_services), style = type.headline, modifier = Modifier.padding(top = 18.dp))
                }
                items(c.vod.openMedia.services, key = { "svc:" + it.name }) { s ->
                    TallyRow(onClick = { openStoreSearch(context, s.query) }, modifier = Modifier.height(56.dp)) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(s.name, style = type.body, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("↗", style = type.mono)
                        }
                    }
                }
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
    }
}

@Composable
private fun PosterTile(item: VodItem, onClick: () -> Unit) {
    val type = LocalTallyType.current
    TallyCard(onClick = onClick) { focused ->
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).background(Palette.Raised)) {
                if (item.poster != null) {
                    AsyncImage(model = item.poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(item.title, style = type.body.copy(color = if (focused) Palette.Text else Palette.TextDim), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        modifier.width(460.dp).fillMaxHeight().background(Palette.Graphite).padding(28.dp),
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
