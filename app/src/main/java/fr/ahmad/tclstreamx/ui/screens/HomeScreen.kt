package fr.ahmad.tclstreamx.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import fr.ahmad.tclstreamx.R
import fr.ahmad.tclstreamx.TclApp
import fr.ahmad.tclstreamx.data.db.ChannelEntity
import fr.ahmad.tclstreamx.data.db.ContentKind
import fr.ahmad.tclstreamx.data.db.StreamStatus
import fr.ahmad.tclstreamx.data.db.VodProgressEntity
import fr.ahmad.tclstreamx.data.xtream.XtreamRepository
import fr.ahmad.tclstreamx.player.PlayItem
import fr.ahmad.tclstreamx.player.PlaybackController
import fr.ahmad.tclstreamx.ui.components.AppIcon
import fr.ahmad.tclstreamx.ui.components.ChannelLogo
import fr.ahmad.tclstreamx.ui.components.Clock
import fr.ahmad.tclstreamx.ui.components.ProgressLine
import fr.ahmad.tclstreamx.ui.components.StatusDot
import fr.ahmad.tclstreamx.ui.components.TallyCard
import fr.ahmad.tclstreamx.ui.components.TallyRow
import fr.ahmad.tclstreamx.ui.nav.Navigator
import fr.ahmad.tclstreamx.ui.nav.Routes
import fr.ahmad.tclstreamx.ui.nav.SECTIONS
import fr.ahmad.tclstreamx.ui.nav.Section
import fr.ahmad.tclstreamx.ui.theme.LocalTallyType
import fr.ahmad.tclstreamx.ui.theme.Palette
import fr.ahmad.tclstreamx.ui.theme.isCompactWidth
import fr.ahmad.tclstreamx.ui.theme.isTv
import kotlinx.coroutines.delay
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * What the stage at the top of Home shows: the item the focus is on (or, on a phone, the featured
 * one). A wide picture fills the stage; a channel logo or a section icon sits in its own light.
 */
@Stable
private data class Spot(
    val key: String,
    val title: String,
    val meta: String?,
    val image: String?,
    /** True for a poster or a backdrop that can be cropped to fill the stage. */
    val picture: Boolean,
    val accent: Color,
    val icon: ImageVector? = null,
    /** Xtream movie or series reference whose summary and backdrop are fetched after a short dwell. */
    val detailsUrl: String? = null,
    val onPlay: () -> Unit,
)

@Stable
private class SpotState {
    var current by mutableStateOf<Spot?>(null)
}

/**
 * Home, in two layers. On top, a stage: the focused title in large, its summary, and its picture
 * filling the screen behind. Below, rows: continue watching, the sections, then what the user's
 * own sources hold (newest films and series, best rated), and the channels people come back to.
 * On a TV the stage stays in place while the rows scroll under it.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HomeScreen(navigator: Navigator, playback: PlaybackController, live: LiveViewModel) {
    val context = LocalContext.current
    val c = (context.applicationContext as TclApp).container
    val type = LocalTallyType.current
    val compact = isCompactWidth()
    val tv = isTv()
    val dao = c.db.channels()
    val recent by remember { dao.observeRecent(12) }.collectAsState(initial = emptyList())
    val favorites by remember { dao.observeFavorites(30) }.collectAsState(initial = emptyList())
    val news by remember { dao.observeCategory("news") }.collectAsState(initial = emptyList())
    val sports by remember { dao.observeCategory("sports") }.collectAsState(initial = emptyList())
    val resume by remember { c.db.vodProgress().observeContinue(12) }.collectAsState(initial = emptyList())
    val newMovies by remember { dao.observeNewest(ContentKind.MOVIE, 30) }.collectAsState(initial = emptyList())
    val newSeries by remember { dao.observeNewest(ContentKind.SERIES, 30) }.collectAsState(initial = emptyList())
    val topMovies by remember { dao.observeTopRated(ContentKind.MOVIE, 30) }.collectAsState(initial = emptyList())
    val liveCount by remember { dao.observeCount(0, ContentKind.LIVE, null) }.collectAsState(initial = 0)
    val movieCount by remember { dao.observeCount(0, ContentKind.MOVIE, null) }.collectAsState(initial = 0)
    val seriesCount by remember { dao.observeCount(0, ContentKind.SERIES, null) }.collectAsState(initial = 0)
    val playlists by live.playlists.collectAsState()
    val settings by c.settings.settings.collectAsState(initial = null)
    var lastChannel by remember { mutableStateOf<ChannelEntity?>(null) }
    LaunchedEffect(settings?.lastChannelKey) {
        lastChannel = settings?.lastChannelKey?.let { dao.byKey(it) }
    }
    val spot = remember { SpotState() }
    val firstItem = remember { FocusRequester() }
    var askExit by remember { mutableStateOf(false) }
    // Back on Home asks before leaving the app (a panel open on Home closes first: its own
    // back handler is registered later and wins).
    BackHandler { askExit = true }
    if (askExit) ExitDialog(onStay = { askExit = false })

    var series by remember { mutableStateOf<ChannelEntity?>(null) }
    fun play(ch: ChannelEntity) {
        // A series of an account is not playable itself: its seasons and episodes open instead.
        if (XtreamRepository.isSeries(ch.url)) {
            series = ch
            return
        }
        playback.play(PlayItem.fromChannel(ch, null))
        navigator.openPlayer()
    }
    fun resumeVod(r: VodProgressEntity) {
        playback.play(PlayItem(key = r.key, title = r.title, urls = listOf(r.uri), isLive = false, poster = r.poster))
        navigator.openPlayer()
    }

    // Labels for the spots, resolved once per composition of Home (not on every focus move).
    val liveTag = stringResource(R.string.home_live_tag)
    val resumeTag = stringResource(R.string.home_resume)
    val movieTag = stringResource(R.string.nav_movies)
    val seriesTag = stringResource(R.string.nav_series)
    val groupNames = (newMovies + newSeries + topMovies + recent + favorites + news + sports).associate { it.groupTitle to groupLabel(it.groupTitle) }
    fun channelSpot(ch: ChannelEntity): Spot {
        val group = groupNames[ch.groupTitle]?.takeIf { it.isNotBlank() }
        return when (ch.kind) {
            ContentKind.MOVIE, ContentKind.SERIES -> Spot(
                key = ch.channelKey,
                title = ch.name,
                meta = listOfNotNull(
                    if (ch.kind == ContentKind.MOVIE) movieTag else seriesTag,
                    ch.rating.takeIf { it > 0 }?.let { "★ %.1f".format(Locale.ROOT, it) },
                    ch.addedAt.takeIf { it > 0 }?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it * 1000)) },
                    group,
                ).joinToString("  ·  "),
                image = ch.logo,
                picture = true,
                accent = if (ch.kind == ContentKind.MOVIE) Palette.Ember else Palette.Violet,
                detailsUrl = ch.url,
                onPlay = { play(ch) },
            )
            else -> Spot(
                key = ch.channelKey,
                title = ch.name,
                meta = listOfNotNull(liveTag, group).joinToString("  ·  "),
                image = ch.logo,
                picture = false,
                accent = Palette.Tally,
                onPlay = { play(ch) },
            )
        }
    }
    fun resumeSpot(r: VodProgressEntity) = Spot(
        key = "resume:" + r.key,
        title = r.title,
        meta = resumeTag + "  ·  " + (r.positionMs * 100 / r.durationMs.coerceAtLeast(1)) + " %",
        image = r.poster,
        picture = true,
        accent = Palette.Tally,
        onPlay = { resumeVod(r) },
    )

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
    val sectionNames = tiles.associate { it.route to stringResource(it.label) }
    fun sectionSpot(s: Section) = Spot(
        key = "section:" + s.route,
        title = sectionNames.getValue(s.route),
        meta = subtitles[s.route],
        image = null,
        picture = false,
        accent = accentOf(s.route),
        icon = s.icon,
        onPlay = { navigator.go(s.route) },
    )

    // Before any focus (and on a phone, where touch never focuses), the stage shows the most
    // relevant item: what was being watched, else the newest title, else the last channel.
    val featured: Spot? = resume.firstOrNull()?.let(::resumeSpot)
        ?: newMovies.firstOrNull { it.logo != null }?.let(::channelSpot)
        ?: newSeries.firstOrNull { it.logo != null }?.let(::channelSpot)
        ?: lastChannel?.let(::channelSpot)
        ?: recent.firstOrNull()?.let(::channelSpot)

    val posterWidth: Dp = if (compact) 108.dp else 118.dp
    val wideWidth: Dp = if (compact) 200.dp else 236.dp
    val tileWidth: Dp = if (compact) 160.dp else 186.dp

    // Rows in order; the first item of the first row takes the focus on a TV.
    val rows = buildList<HomeRow> {
        if (lastChannel != null || resume.isNotEmpty()) add(HomeRow.Continue)
        add(HomeRow.Sections)
        if (newMovies.isNotEmpty()) add(HomeRow.Posters(R.string.home_new_movies, newMovies))
        if (newSeries.isNotEmpty()) add(HomeRow.Posters(R.string.home_new_series, newSeries))
        if (topMovies.isNotEmpty()) add(HomeRow.Posters(R.string.home_top_rated, topMovies))
        if (recent.isNotEmpty()) add(HomeRow.Channels(R.string.home_recent, recent))
        if (favorites.isNotEmpty()) add(HomeRow.Channels(R.string.home_favorites, favorites))
        if (news.isNotEmpty()) add(HomeRow.Channels(R.string.home_news, news))
        if (sports.isNotEmpty()) add(HomeRow.Channels(R.string.home_free_sports, sports))
    }

    // Remote only: on a phone, requesting focus would scroll rows and crop the first cards.
    // The first frame can come before the cards are attached (cold start while the catalog installs):
    // retry for a moment instead of leaving the focus on the rail.
    LaunchedEffect(tv) {
        if (!tv) return@LaunchedEffect
        repeat(30) {
            withFrameNanos { }
            if (runCatching { firstItem.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(50)
        }
    }

    fun LazyListScope.rowItems() {
        rows.forEachIndexed { rowIndex, row ->
            val first = rowIndex == 0
            fun Modifier.firstFocus(i: Int) = if (first && i == 0) focusRequester(firstItem) else this
            when (row) {
                HomeRow.Continue -> item(key = "continue") {
                    RowShelf(stringResource(R.string.home_continue)) {
                        lastChannel?.let { ch ->
                            item(key = "live:" + ch.channelKey) {
                                LiveResumeCard(ch, Modifier.width(wideWidth).firstFocus(0), onFocus = { spot.current = channelSpot(ch) }) { play(ch) }
                            }
                        }
                        itemsIndexed(resume, key = { _, r -> r.key }) { i, r ->
                            val index = i + if (lastChannel != null) 1 else 0
                            ResumeCard(
                                r, Modifier.width(wideWidth).firstFocus(index),
                                onFocus = { spot.current = resumeSpot(r) },
                                onLongClick = { forgetResume(c, r.key) },
                            ) { resumeVod(r) }
                        }
                    }
                }
                HomeRow.Sections -> item(key = "sections") {
                    RowShelf(stringResource(R.string.home_sections)) {
                        itemsIndexed(tiles, key = { _, s -> s.route }) { i, s ->
                            SectionCard(
                                s, sectionNames.getValue(s.route), subtitles.getValue(s.route),
                                Modifier.width(tileWidth).firstFocus(i),
                                onFocus = { spot.current = sectionSpot(s) },
                            ) { navigator.go(s.route) }
                        }
                    }
                }
                is HomeRow.Posters -> item(key = "posters:" + row.title) {
                    RowShelf(stringResource(row.title)) {
                        itemsIndexed(row.list, key = { _, ch -> ch.channelKey }) { i, ch ->
                            PosterCard(
                                ch, Modifier.width(posterWidth).firstFocus(i),
                                onFocus = { spot.current = channelSpot(ch) },
                                onLongClick = { toggleFavorite(context, c, ch.channelKey) },
                            ) { play(ch) }
                        }
                    }
                }
                is HomeRow.Channels -> item(key = "channels:" + row.title) {
                    RowShelf(stringResource(row.title)) {
                        itemsIndexed(row.list, key = { _, ch -> ch.channelKey }) { i, ch ->
                            ChannelTile(ch, Modifier.width(tileWidth).firstFocus(i), onFocus = { spot.current = channelSpot(ch) }) { play(ch) }
                        }
                    }
                }
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val stageHeight = if (compact) 300.dp else (maxHeight * 0.44f).coerceIn(200.dp, 300.dp)
        // TV: the picture of the focused item stays behind everything while the rows scroll.
        if (!compact) StageBackdrop(spot, featured, Modifier.fillMaxWidth().height(stageHeight + 90.dp))
        if (compact) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                // Phone: the stage is the top of the page and scrolls with it.
                item(key = "stage") {
                    Box(Modifier.fillMaxWidth().height(stageHeight)) {
                        StageBackdrop(spot, featured, Modifier.fillMaxSize())
                        HomeHeader(compact = true, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))
                        StageInfo(spot, featured, compact = true, Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 16.dp))
                    }
                }
                item(key = "update") { Box(Modifier.padding(horizontal = 16.dp)) { UpdateRow(compactBanner = true) } }
                rowItems()
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                HomeHeader(compact = false, Modifier.padding(start = 36.dp, end = 40.dp, top = 20.dp))
                UpdateRow(compactBanner = true)
                StageInfo(spot, featured, compact = false, Modifier.fillMaxWidth().height(stageHeight - 70.dp).padding(start = 36.dp, end = 40.dp))
                LazyColumn(
                    Modifier.fillMaxWidth().weight(1f).focusRestorer(),
                    contentPadding = PaddingValues(top = 6.dp, bottom = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) { rowItems() }
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

private sealed interface HomeRow {
    data object Continue : HomeRow
    data object Sections : HomeRow
    data class Posters(val title: Int, val list: List<ChannelEntity>) : HomeRow
    data class Channels(val title: Int, val list: List<ChannelEntity>) : HomeRow
}

@Composable
private fun HomeHeader(compact: Boolean, modifier: Modifier) {
    val type = LocalTallyType.current
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.logo_mark), contentDescription = null, modifier = Modifier.size(if (compact) 34.dp else 38.dp))
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.app_name), style = type.title, maxLines = 1, modifier = Modifier.weight(1f))
        Clock()
    }
}

/**
 * The stage picture. It follows the focus after a short dwell, so holding a direction key does
 * not load every poster it passes; pictures cross-fade, colours ease.
 */
@Composable
private fun StageBackdrop(state: SpotState, featured: Spot?, modifier: Modifier) {
    val target = state.current ?: featured
    var shown by remember { mutableStateOf(target) }
    var wide by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(target?.key) {
        delay(if (shown == null) 0L else 260L)
        shown = target
    }
    // A panel's wide picture (16:9) is better than a cropped poster; fetched after a longer dwell.
    val c = (LocalContext.current.applicationContext as TclApp).container
    LaunchedEffect(shown?.key) {
        wide = null
        val url = shown?.detailsUrl ?: return@LaunchedEffect
        delay(500)
        wide = runCatching { stageDetails(c, url)?.backdrop }.getOrNull()
    }
    val s = shown
    val accent by animateColorAsState(s?.accent ?: Palette.Tally, animationSpec = tween(400), label = "stageAccent")
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val context = LocalContext.current
    Box(modifier) {
        // A light of the item's colour from the far corner, under the picture.
        Box(
            Modifier.fillMaxSize().drawBehind {
                drawRect(
                    Brush.radialGradient(
                        listOf(accent.copy(alpha = 0.30f), Color.Transparent),
                        center = Offset(if (rtl) size.width * 0.15f else size.width * 0.85f, size.height * 0.25f),
                        radius = size.maxDimension * 0.55f,
                    )
                )
            }
        )
        val image = wide ?: s?.image
        if (s != null && image != null && s.picture) {
            val request = remember(image) {
                ImageRequest.Builder(context).data(image).crossfade(350).size(1280, 720).build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.align(Alignment.TopEnd).fillMaxWidth(0.68f).fillMaxHeight(),
            )
        } else if (s != null && image != null) {
            // A channel logo is never stretched: it sits large in the light.
            ChannelLogo(
                image, s.title,
                Modifier.align(Alignment.CenterEnd).padding(end = 90.dp, bottom = 40.dp).size(width = 260.dp, height = 150.dp),
            )
        } else if (s?.icon != null) {
            AppIcon(s.icon, accent.copy(alpha = 0.22f), size = 240.dp, modifier = Modifier.align(Alignment.CenterEnd).offset(x = if (rtl) (-40).dp else 40.dp))
        }
        // Legibility of the text side and a soft landing into the rows.
        val side = listOf(Palette.Ink, Palette.Ink.copy(alpha = 0.85f), Color.Transparent)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(if (rtl) side.reversed() else side, startX = 0f)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Palette.Ink.copy(alpha = 0.6f), Palette.Ink))))
    }
}

/** Title, facts and summary of the focused item, in the reading side of the stage. */
@Composable
private fun StageInfo(state: SpotState, featured: Spot?, compact: Boolean, modifier: Modifier) {
    val type = LocalTallyType.current
    val c = (LocalContext.current.applicationContext as TclApp).container
    val s = state.current ?: featured
    var plot by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(s?.key) {
        plot = null
        val url = s?.detailsUrl ?: return@LaunchedEffect
        delay(500)
        plot = runCatching { stageDetails(c, url)?.plot }.getOrNull()
    }
    Column(modifier, verticalArrangement = Arrangement.Bottom) {
        if (s == null) return@Column
        Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(s.title, style = if (compact) type.headline else type.display, maxLines = 2, overflow = TextOverflow.Ellipsis)
            s.meta?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = type.label.copy(color = s.accent), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            plot?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = type.bodySmall.copy(color = Palette.Text), maxLines = if (compact) 3 else 3, overflow = TextOverflow.Ellipsis)
            }
            if (compact) {
                // Touch: the stage itself is a button.
                TallyRow(onClick = s.onPlay, modifier = Modifier.padding(top = 4.dp).width(170.dp).height(44.dp)) {
                    Text("▶  " + stringResource(R.string.vod_play), style = type.title, modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 14.dp))
                }
            }
        }
    }
}

private class StageDetails(val plot: String?, val backdrop: String?)

private suspend fun stageDetails(c: fr.ahmad.tclstreamx.AppContainer, url: String): StageDetails? = try {
    if (XtreamRepository.isSeries(url)) c.xtream.seriesInfo(url).let { StageDetails(it.plot, it.backdrop) }
    else c.xtream.movieInfo(url)?.let { StageDetails(it.plot, it.backdrop) }
} catch (e: IOException) {
    null
}

/** Each section has its own light. */
private fun accentOf(route: String): Color = when (route) {
    Routes.LIVE -> Palette.Tally
    Routes.MOVIES -> Palette.Ember
    Routes.SERIES -> Palette.Violet
    Routes.SPORTS -> Palette.Signal
    Routes.ANIMATION -> Palette.Cyan
    Routes.LOCAL -> Palette.Caution
    Routes.SEARCH -> Palette.Rose
    else -> Palette.TextDim
}

/** Title over one horizontal row of cards; the row remembers its focused card. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun RowShelf(title: String, content: LazyListScope.() -> Unit) {
    val compact = isCompactWidth()
    val side = if (compact) 16.dp else 36.dp
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = LocalTallyType.current.title, modifier = Modifier.padding(horizontal = side))
        LazyRow(
            Modifier.fillMaxWidth().focusRestorer(),
            contentPadding = PaddingValues(horizontal = side, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun SectionCard(section: Section, label: String, subtitle: String, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit) {
    val type = LocalTallyType.current
    val accent = accentOf(section.route)
    TallyCard(onClick = onClick, onFocus = onFocus, modifier = modifier.height(92.dp)) { focused ->
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(accent.copy(alpha = if (focused) 0.34f else 0.18f), Color.Transparent))))
        AppIcon(section.icon, accent.copy(alpha = if (focused) 0.30f else 0.12f), size = 74.dp, modifier = Modifier.align(Alignment.BottomEnd).offset(x = 14.dp, y = 16.dp))
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
            AppIcon(section.icon, accent, size = 22.dp)
            Column {
                Text(label, style = type.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = type.mono.copy(color = if (focused) Palette.Text else Palette.TextDim), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Portrait poster (2:3) of a film or a series. */
@Composable
private fun PosterCard(ch: ChannelEntity, modifier: Modifier, onFocus: () -> Unit, onLongClick: () -> Unit, onClick: () -> Unit) {
    val type = LocalTallyType.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TallyCard(onClick = onClick, onFocus = onFocus, onLongClick = onLongClick, modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
            Box(Modifier.fillMaxSize().background(Palette.Raised)) {
                Text(ch.name.take(2).uppercase(), style = type.numeral.copy(color = Palette.TextFaint), modifier = Modifier.align(Alignment.Center))
                if (ch.logo != null) {
                    AsyncImage(model = ch.logo, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                if (ch.rating > 0) {
                    Text(
                        "★ %.1f".format(Locale.ROOT, ch.rating),
                        style = type.mono.copy(color = Palette.Text),
                        modifier = Modifier.align(Alignment.TopStart).padding(6.dp).background(Palette.Ink.copy(alpha = 0.7f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
        Text(ch.name, style = type.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The last live channel, first in "continue watching". */
@Composable
private fun LiveResumeCard(ch: ChannelEntity, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit) {
    val type = LocalTallyType.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TallyCard(onClick = onClick, onFocus = onFocus, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Palette.Tally.copy(alpha = 0.30f), Palette.Graphite))))
            ChannelLogo(ch.logo, ch.name, Modifier.align(Alignment.Center).fillMaxWidth(0.55f).fillMaxHeight(0.55f))
            Row(
                Modifier.align(Alignment.TopStart).padding(8.dp).background(Palette.Tally, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(Palette.Ink))
                Spacer(Modifier.width(5.dp))
                Text(stringResource(R.string.home_live_tag), style = type.mono.copy(color = Palette.Ink))
            }
        }
        Text(ch.name, style = type.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ResumeCard(r: VodProgressEntity, modifier: Modifier, onFocus: () -> Unit, onLongClick: () -> Unit, onClick: () -> Unit) {
    val type = LocalTallyType.current
    // Long press (remote: hold OK): remove from "continue watching".
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TallyCard(onClick = onClick, onFocus = onFocus, onLongClick = onLongClick, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            Box(Modifier.fillMaxSize().background(Palette.Raised)) {
                if (r.poster != null) {
                    AsyncImage(model = r.poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                ProgressLine(r.positionMs.toFloat() / r.durationMs.coerceAtLeast(1), Modifier.align(Alignment.BottomCenter).fillMaxWidth())
            }
        }
        Text(r.title, style = type.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun ChannelTile(ch: ChannelEntity, modifier: Modifier = Modifier, onFocus: (() -> Unit)? = null, onClick: () -> Unit) {
    val type = LocalTallyType.current
    TallyCard(onClick = onClick, onFocus = onFocus, modifier = modifier) { focused ->
        Column {
            Box(Modifier.fillMaxWidth().height(92.dp)) {
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
