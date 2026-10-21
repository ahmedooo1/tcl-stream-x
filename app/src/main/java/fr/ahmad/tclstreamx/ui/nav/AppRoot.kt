package fr.ahmad.tclstreamx.ui.nav

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.tv.material3.Text
import fr.ahmad.tclstreamx.R
import fr.ahmad.tclstreamx.TclApp
import fr.ahmad.tclstreamx.player.PlayItem
import fr.ahmad.tclstreamx.data.vod.VodShelf
import fr.ahmad.tclstreamx.player.PlaybackController
import fr.ahmad.tclstreamx.ui.components.AppIcon
import fr.ahmad.tclstreamx.ui.components.AppIcons
import fr.ahmad.tclstreamx.ui.components.TallyRow
import fr.ahmad.tclstreamx.ui.screens.HomeScreen
import fr.ahmad.tclstreamx.ui.screens.LanScreen
import fr.ahmad.tclstreamx.ui.screens.LiveScreen
import fr.ahmad.tclstreamx.ui.screens.LiveViewModel
import fr.ahmad.tclstreamx.ui.screens.LocalScreen
import fr.ahmad.tclstreamx.ui.screens.PlayerScreen
import fr.ahmad.tclstreamx.ui.screens.PlaylistsScreen
import fr.ahmad.tclstreamx.ui.screens.SearchScreen
import fr.ahmad.tclstreamx.ui.screens.SettingsScreen
import fr.ahmad.tclstreamx.ui.screens.SportsScreen
import fr.ahmad.tclstreamx.ui.screens.VodScreen
import fr.ahmad.tclstreamx.ui.theme.LocalTallyType
import fr.ahmad.tclstreamx.ui.theme.Palette
import fr.ahmad.tclstreamx.ui.theme.isCompactWidth

object Routes {
    const val HOME = "home"
    const val LIVE = "live"
    const val PLAYER = "player"
    const val SPORTS = "sports"
    const val MOVIES = "vod/movies"
    const val SERIES = "vod/series"
    const val ANIMATION = "vod/animation"
    const val LOCAL = "local"
    const val SEARCH = "search"
    const val PLAYLISTS = "playlists"
    const val SETTINGS = "settings"
    const val LAN = "lan"
}

data class Section(val route: String, val label: Int, val icon: ImageVector)

/** Every section of the app, in menu order (TV rail, home grid). */
val SECTIONS = listOf(
    Section(Routes.HOME, R.string.nav_home, AppIcons.Home),
    Section(Routes.LIVE, R.string.nav_live, AppIcons.LiveTv),
    Section(Routes.MOVIES, R.string.nav_movies, AppIcons.Movie),
    Section(Routes.SERIES, R.string.nav_series, AppIcons.Series),
    Section(Routes.SPORTS, R.string.nav_sports, AppIcons.Sports),
    Section(Routes.ANIMATION, R.string.nav_animation, AppIcons.Animation),
    Section(Routes.LOCAL, R.string.nav_local, AppIcons.Folder),
    Section(Routes.SEARCH, R.string.nav_search, AppIcons.Search),
    Section(Routes.PLAYLISTS, R.string.nav_playlists, AppIcons.Playlist),
    Section(Routes.SETTINGS, R.string.nav_settings, AppIcons.Settings),
)
private val RAIL = SECTIONS

/** Phone bottom bar: the five main sections; the others are on the home screen grid. */
private val BOTTOM = SECTIONS.take(5)

/** Navigation actions shared by screens. */
class Navigator(private val nav: NavHostController) {
    fun go(route: String) {
        nav.navigate(route) {
            popUpTo(Routes.HOME) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun openPlayer() = nav.navigate(Routes.PLAYER) { launchSingleTop = true }
    fun back() = nav.popBackStack()

    /** Route under the current one, e.g. to know whether the player was opened from the live list. */
    fun previousRoute(): String? = nav.previousBackStackEntry?.destination?.route
}

@Composable
fun AppRoot(playback: PlaybackController) {
    val nav = rememberNavController()
    val navigator = remember(nav) { Navigator(nav) }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: Routes.HOME
    val live: LiveViewModel = viewModel()

    val compact = isCompactWidth()
    val host: @Composable (Modifier) -> Unit = { mod ->
        Box(mod) {
            NavHost(navController = nav, startDestination = Routes.HOME) {
                composable(Routes.HOME) { HomeScreen(navigator, playback, live) }
                composable(Routes.LIVE) { LiveScreen(navigator, playback, live) }
                composable(Routes.PLAYER) { PlayerScreen(navigator, playback, live) }
                composable(Routes.SPORTS) { SportsScreen(navigator, playback) }
                composable(Routes.MOVIES) { VodScreen(VodShelf.MOVIES, navigator, playback) }
                composable(Routes.SERIES) { VodScreen(VodShelf.SERIES, navigator, playback) }
                composable(Routes.ANIMATION) { VodScreen(VodShelf.ANIMATION, navigator, playback) }
                composable(Routes.LOCAL) { LocalScreen(navigator, playback) }
                composable(Routes.SEARCH) { SearchScreen(navigator, playback) }
                composable(Routes.PLAYLISTS) { PlaylistsScreen(navigator) }
                composable(Routes.SETTINGS) { SettingsScreen() }
                composable(Routes.LAN) { LanScreen(navigator) }
            }
        }
    }

    // Channels sent from a phone (TV side): play at once, fullscreen.
    val container = (LocalContext.current.applicationContext as TclApp).container
    val pairingCode by container.remote.pairingCode.collectAsState()
    LaunchedEffect(Unit) {
        container.remote.incoming.collect { m ->
            playback.play(
                PlayItem(
                    key = "remote:" + m.urls.first().hashCode().toUInt().toString(16),
                    title = m.title,
                    urls = m.urls,
                    userAgent = m.userAgent,
                    referrer = m.referrer,
                    isLive = m.live,
                    logo = m.logo,
                )
            )
            navigator.openPlayer()
        }
    }

    // Not a flat black: a faint warm light from the top corner, like a studio monitor wall.
    val backdrop = Modifier.drawBehind {
        drawRect(Palette.Ink)
        drawRect(
            Brush.radialGradient(
                listOf(Palette.Tally.copy(alpha = 0.09f), Color.Transparent),
                center = Offset(size.width * 0.12f, 0f),
                radius = size.maxDimension * 0.65f,
            )
        )
    }
    Box(Modifier.fillMaxSize().then(backdrop)) {
        if (compact) {
            // Phone: content on top, a bottom bar with the main sections (thumb reach), no rail.
            Column(Modifier.fillMaxSize()) {
                host(Modifier.weight(1f).fillMaxWidth())
                if (route != Routes.PLAYER) BottomBar(current = route, onSelect = navigator::go)
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                if (route != Routes.PLAYER) Rail(current = route, onSelect = navigator::go)
                host(Modifier.weight(1f).fillMaxHeight())
            }
        }
        pairingCode?.let { code -> PairingCode(code, Modifier.align(Alignment.TopEnd)) }
    }
}

/** Shown on the TV while a phone pairs: the user types this code on the phone. */
@Composable
private fun PairingCode(code: String, modifier: Modifier) {
    val type = LocalTallyType.current
    Column(
        modifier.padding(28.dp).background(Palette.Graphite).border(2.dp, Palette.Tally).padding(horizontal = 28.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.remote_pair_title), style = type.title)
        Text(code.toCharArray().joinToString("  "), style = type.display.copy(color = Palette.Tally))
        Text(stringResource(R.string.remote_pair_hint), style = type.bodySmall)
    }
}

@Composable
private fun BottomBar(current: String, onSelect: (String) -> Unit) {
    val type = LocalTallyType.current
    val selectedRoute = BOTTOM.lastOrNull { current.startsWith(it.route) }?.route ?: Routes.HOME
    Row(
        Modifier
            .fillMaxWidth()
            .background(Palette.Graphite)
            .navigationBarsPadding()
            .height(64.dp),
    ) {
        BOTTOM.forEach { item ->
            val selected = item.route == selectedRoute
            TallyRow(onClick = { onSelect(item.route) }, selected = selected, modifier = Modifier.weight(1f).fillMaxHeight()) { focused ->
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    AppIcon(item.icon, if (selected || focused) Palette.Tally else Palette.TextDim)
                    Text(
                        stringResource(item.label),
                        style = type.label.copy(color = if (selected || focused) Palette.Text else Palette.TextDim),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * TV: collapsed, the rail is a column of icons; it widens with labels only while it holds focus,
 * so content keeps the whole screen the rest of the time.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Rail(current: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val width by animateDpAsState(if (expanded) 240.dp else 72.dp, label = "rail")
    val type = LocalTallyType.current
    val requesters = remember { RAIL.map { FocusRequester() } }
    val selectedIndex = RAIL.indexOfLast { current.startsWith(it.route) }.coerceAtLeast(0)
    Column(
        Modifier
            .width(width)
            .fillMaxHeight()
            .background(Palette.Graphite)
            .onFocusChanged { expanded = it.hasFocus }
            .focusProperties { enter = { requesters[selectedIndex] } }
            .padding(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(Modifier.padding(start = 24.dp, bottom = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(16.dp).background(Palette.Tally))
            if (expanded) {
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.app_name), style = type.title, maxLines = 1)
            }
        }
        RAIL.forEachIndexed { i, item ->
            val selected = i == selectedIndex
            TallyRow(
                onClick = { onSelect(item.route) },
                selected = selected,
                modifier = Modifier.fillMaxWidth().height(48.dp).focusRequester(requesters[i]),
            ) { focused ->
                Row(Modifier.fillMaxSize().padding(start = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(item.icon, if (focused || selected) Palette.Text else Palette.TextFaint)
                    if (expanded) {
                        Spacer(Modifier.width(16.dp))
                        Text(
                            stringResource(item.label),
                            style = type.title.copy(color = if (focused || selected) Palette.Text else Palette.TextDim),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
