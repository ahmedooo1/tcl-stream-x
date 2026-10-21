package fr.ahmad.tclstreamx.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import fr.ahmad.tclstreamx.R
import fr.ahmad.tclstreamx.TclApp
import fr.ahmad.tclstreamx.data.db.ChannelEntity
import fr.ahmad.tclstreamx.data.db.ContentKind
import fr.ahmad.tclstreamx.data.db.StreamStatus
import fr.ahmad.tclstreamx.data.db.VodProgressEntity
import fr.ahmad.tclstreamx.player.PlayItem
import fr.ahmad.tclstreamx.player.PlaybackController
import fr.ahmad.tclstreamx.ui.components.AppIcon
import fr.ahmad.tclstreamx.ui.components.ChannelLogo
import fr.ahmad.tclstreamx.ui.components.Clock
import fr.ahmad.tclstreamx.ui.components.ProgressLine
import fr.ahmad.tclstreamx.ui.components.StatusDot
import fr.ahmad.tclstreamx.ui.components.TallyCard
import fr.ahmad.tclstreamx.ui.nav.Navigator
import fr.ahmad.tclstreamx.ui.nav.Routes
import fr.ahmad.tclstreamx.ui.nav.SECTIONS
import fr.ahmad.tclstreamx.ui.nav.Section
import fr.ahmad.tclstreamx.ui.theme.LocalTallyType
import fr.ahmad.tclstreamx.ui.theme.Palette
import fr.ahmad.tclstreamx.ui.theme.isCompactWidth
import fr.ahmad.tclstreamx.ui.theme.isTv
import fr.ahmad.tclstreamx.ui.theme.screenPadding
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/**
 * Home is a launcher: first a grid of big section buttons (each with its icon and what is inside),
 * then the shelves people come back for (resume, recent, favorites, news, free sport).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HomeScreen(navigator: Navigator, playback: PlaybackController, live: LiveViewModel) {
    val c = (LocalContext.current.applicationContext as TclApp).container
    val type = LocalTallyType.current
    val compact = isCompactWidth()
    val tv = isTv()
    val dao = c.db.channels()
    val recent by remember { dao.observeRecent(12) }.collectAsState(initial = emptyList())
    val favorites by remember { dao.observeFavorites(30) }.collectAsState(initial = emptyList())
    val news by remember { dao.observeCategory("news") }.collectAsState(initial = emptyList())
    val sports by remember { dao.observeCategory("sports") }.collectAsState(initial = emptyList())
    val resume by remember { c.db.vodProgress().observeContinue(12) }.collectAsState(initial = emptyList())
    val liveCount by remember { dao.observeCount(0, ContentKind.LIVE, null) }.collectAsState(initial = 0)
    val movieCount by remember { dao.observeCount(0, ContentKind.MOVIE, null) }.collectAsState(initial = 0)
    val seriesCount by remember { dao.observeCount(0, ContentKind.SERIES, null) }.collectAsState(initial = 0)
    val playlists by live.playlists.collectAsState()
    val firstTile = remember { FocusRequester() }

    fun play(ch: ChannelEntity) {
        playback.play(PlayItem.fromChannel(ch, null))
        navigator.openPlayer()
    }

    // Remote only: on a phone, requesting focus would scroll rows and crop the first cards.
    // The first frame can come before the tiles are attached (cold start while the catalog installs):
    // retry for a moment instead of leaving the focus on the rail.
    LaunchedEffect(tv) {
        if (!tv) return@LaunchedEffect
        repeat(20) {
            withFrameNanos { }
            if (runCatching { firstTile.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(50)
        }
    }

    val tiles = SECTIONS.filter { it.route != Routes.HOME }
    val subtitles = tiles.associate { s ->
        s.route to when (s.route) {
            Routes.LIVE -> stringResource(R.string.home_tile_channels, liveCount)
            Routes.MOVIES -> if (movieCount > 0) stringResource(R.string.home_tile_titles, movieCount) else stringResource(R.string.home_tile_public)
            Routes.SERIES -> if (seriesCount > 0) stringResource(R.string.home_tile_titles, seriesCount) else stringResource(R.string.home_tile_public)
            Routes.SPORTS -> stringResource(R.string.home_tile_matches)
            Routes.ANIMATION -> stringResource(R.string.home_tile_public)
            Routes.LOCAL -> stringResource(R.string.home_tile_local)
            Routes.SEARCH -> stringResource(R.string.home_tile_search)
            Routes.PLAYLISTS -> stringResource(R.string.home_tile_playlists, playlists.size)
            else -> stringResource(R.string.home_tile_settings)
        }
    }
    val columns = if (compact) 2 else 5
    val tileWidth: Dp = if (compact) 168.dp else 210.dp
    val shelves = listOf(
        stringResource(R.string.home_recent) to recent,
        stringResource(R.string.home_favorites) to favorites,
        stringResource(R.string.home_news) to news,
        stringResource(R.string.home_free_sports) to sports,
    ).filter { it.second.isNotEmpty() }
    val continueTitle = stringResource(R.string.home_continue)

    LazyColumn(
        Modifier.fillMaxSize().focusRestorer(),
        contentPadding = screenPadding(),
        verticalArrangement = Arrangement.spacedBy(if (compact) 18.dp else 26.dp),
    ) {
        item(key = "header") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(18.dp).background(Palette.Tally))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.app_name), style = type.headline, maxLines = 1)
                    Text(DateFormat.getDateInstance(DateFormat.FULL).format(Date()), style = type.bodySmall, maxLines = 1)
                }
                Clock()
            }
        }

        // Section buttons, in rows of 2 (phone) or 5 (TV).
        items(tiles.chunked(columns), key = { row -> "tiles:" + row.first().route }) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { s ->
                    SectionTile(
                        section = s,
                        subtitle = subtitles.getValue(s.route),
                        modifier = Modifier.weight(1f).let { if (s == tiles.first()) it.focusRequester(firstTile) else it },
                        onClick = { navigator.go(s.route) },
                    )
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }

        if (resume.isNotEmpty()) {
            item(key = "resume") {
                Shelf(continueTitle) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(resume, key = { it.key }) { r ->
                            ResumeTile(r, tileWidth) {
                                playback.play(PlayItem(key = r.key, title = r.title, urls = listOf(r.uri), isLive = false, poster = r.poster))
                                navigator.openPlayer()
                            }
                        }
                    }
                }
            }
        }
        shelves.forEach { (title, list) ->
            item(key = "shelf:$title") {
                Shelf(title) {
                    LazyRow(Modifier.focusRestorer(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(list, key = { it.channelKey + title }) { ch -> ChannelTile(ch, Modifier.width(tileWidth)) { play(ch) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTile(section: Section, subtitle: String, modifier: Modifier, onClick: () -> Unit) {
    val type = LocalTallyType.current
    TallyCard(onClick = onClick, modifier = modifier.height(112.dp)) { focused ->
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            AppIcon(section.icon, if (focused) Palette.Tally else Palette.Text, size = 30.dp)
            Column {
                Text(stringResource(section.label), style = type.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = type.mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Shelf(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = LocalTallyType.current.headline)
        content()
    }
}

@Composable
fun ChannelTile(ch: ChannelEntity, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val type = LocalTallyType.current
    TallyCard(onClick = onClick, modifier = modifier) { focused ->
        Column {
            Box(Modifier.fillMaxWidth().height(96.dp)) {
                ChannelLogo(ch.logo, ch.name, Modifier.fillMaxSize().padding(14.dp))
                StatusDot(StreamStatus.of(ch.status), Modifier.align(Alignment.TopEnd).padding(8.dp))
            }
            Text(
                ch.name,
                style = type.body.copy(color = if (focused) Palette.Text else Palette.TextDim),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun ResumeTile(r: VodProgressEntity, width: Dp, onClick: () -> Unit) {
    val type = LocalTallyType.current
    TallyCard(onClick = onClick, modifier = Modifier.width(width)) {
        Column {
            Box(Modifier.fillMaxWidth().height(96.dp).background(Palette.Raised)) {
                if (r.poster != null) {
                    AsyncImage(model = r.poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
            ProgressLine(r.positionMs.toFloat() / r.durationMs.coerceAtLeast(1), Modifier.fillMaxWidth())
            Text(r.title, style = type.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(10.dp))
        }
    }
}
