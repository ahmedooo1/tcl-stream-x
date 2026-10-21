package fr.ahmad.tclstreamx.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import fr.ahmad.tclstreamx.data.db.ChannelEntity
import fr.ahmad.tclstreamx.data.db.StreamStatus
import fr.ahmad.tclstreamx.data.db.VodProgressEntity
import fr.ahmad.tclstreamx.player.PlayItem
import fr.ahmad.tclstreamx.player.PlaybackController
import fr.ahmad.tclstreamx.ui.components.ChannelLogo
import fr.ahmad.tclstreamx.ui.components.Clock
import fr.ahmad.tclstreamx.ui.components.EmptyState
import fr.ahmad.tclstreamx.ui.components.ProgressLine
import fr.ahmad.tclstreamx.ui.components.StatusDot
import fr.ahmad.tclstreamx.ui.components.TallyCard
import fr.ahmad.tclstreamx.ui.nav.Navigator
import fr.ahmad.tclstreamx.ui.theme.LocalTallyType
import fr.ahmad.tclstreamx.ui.theme.screenPadding
import fr.ahmad.tclstreamx.ui.theme.Palette
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/**
 * Home reads like the front page of a TV guide: a masthead with the time, then shelves ordered
 * by intent (resume, favorites, news, sport) instead of a promotional hero.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HomeScreen(navigator: Navigator, playback: PlaybackController, live: LiveViewModel) {
    val c = (LocalContext.current.applicationContext as TclApp).container
    val type = LocalTallyType.current
    val recent by remember { c.db.channels().observeRecent(12) }.collectAsState(initial = emptyList())
    val favorites by remember { c.db.channels().observeFavorites(30) }.collectAsState(initial = emptyList())
    val news by remember { c.db.channels().observeCategory("news") }.collectAsState(initial = emptyList())
    val sports by remember { c.db.channels().observeCategory("sports") }.collectAsState(initial = emptyList())
    val resume by remember { c.db.vodProgress().observeContinue(12) }.collectAsState(initial = emptyList())
    val total by live.totalCount.collectAsState()
    val firstFocus = remember { FocusRequester() }
    val onlineCount = (news + sports).count { it.status == StreamStatus.ONLINE.code }

    fun play(ch: ChannelEntity) {
        playback.play(PlayItem.fromChannel(ch, null))
        navigator.openPlayer()
    }

    LaunchedEffect(recent.isNotEmpty() || news.isNotEmpty()) { runCatching { firstFocus.requestFocus() } }

    LazyColumn(
        Modifier.fillMaxSize().focusRestorer(),
        contentPadding = screenPadding(end = 0.dp),
        verticalArrangement = Arrangement.spacedBy(30.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(end = 40.dp), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                    Text(
                        stringResource(if (hour in 5..17) R.string.home_greeting_morning else R.string.home_greeting_evening),
                        style = type.display,
                    )
                    Text(
                        DateFormat.getDateInstance(DateFormat.FULL).format(Date()),
                        style = type.body.copy(color = Palette.TextDim),
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Clock()
                    Text(stringResource(R.string.home_channels_count, total, onlineCount), style = type.mono)
                }
            }
            Box(Modifier.padding(top = 18.dp, end = 40.dp).fillMaxWidth().height(1.dp).background(Palette.Hairline))
        }

        val shelves = listOf(
            R.string.home_recent to recent,
            R.string.home_favorites to favorites,
            R.string.home_news to news,
            R.string.home_free_sports to sports,
        ).filter { it.second.isNotEmpty() }
        if (shelves.isEmpty() && resume.isEmpty()) {
            item { EmptyState(stringResource(R.string.home_empty_title), stringResource(R.string.home_empty_body)) }
        }
        shelves.forEachIndexed { shelfIndex, (title, list) ->
            item(key = "shelf$title") {
                Shelf(stringResource(title)) {
                    LazyRow(
                        Modifier.focusRestorer(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(end = 40.dp),
                    ) {
                        items(list, key = { it.channelKey + title }) { ch ->
                            val mod = if (shelfIndex == 0 && ch == list.first()) Modifier.focusRequester(firstFocus) else Modifier
                            ChannelTile(ch, mod) { play(ch) }
                        }
                    }
                }
            }
        }
        if (resume.isNotEmpty()) {
            item(key = "resume") {
                Shelf(stringResource(R.string.home_continue)) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(end = 40.dp)) {
                        items(resume, key = { it.key }) { r ->
                            ResumeTile(r) {
                                playback.play(PlayItem(key = r.key, title = r.title, urls = listOf(r.uri), isLive = false, poster = r.poster))
                                navigator.openPlayer()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Shelf(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = LocalTallyType.current.headline)
        content()
    }
}

@Composable
fun ChannelTile(ch: ChannelEntity, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val type = LocalTallyType.current
    TallyCard(onClick = onClick, modifier = modifier.width(216.dp)) { focused ->
        Column {
            Box(Modifier.fillMaxWidth().height(108.dp)) {
                ChannelLogo(ch.logo, ch.name, Modifier.fillMaxSize().padding(18.dp))
                Text(
                    ch.position.toString(),
                    style = type.numeralSmall.copy(color = if (focused) Palette.Tally else Palette.TextFaint),
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                )
                StatusDot(StreamStatus.of(ch.status), Modifier.align(Alignment.TopEnd).padding(10.dp))
            }
            Text(
                ch.name,
                style = type.body.copy(color = if (focused) Palette.Text else Palette.TextDim),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun ResumeTile(r: VodProgressEntity, onClick: () -> Unit) {
    val type = LocalTallyType.current
    TallyCard(onClick = onClick, modifier = Modifier.width(260.dp)) {
        Column {
            Box(Modifier.fillMaxWidth().height(146.dp).background(Palette.Raised)) {
                if (r.poster != null) {
                    AsyncImage(model = r.poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
            ProgressLine(r.positionMs.toFloat() / r.durationMs.coerceAtLeast(1), Modifier.fillMaxWidth())
            Text(r.title, style = type.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(12.dp))
        }
    }
}
