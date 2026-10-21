package fr.ahmad.tclstreamx.ui.nav

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import fr.ahmad.tclstreamx.data.vod.VodShelf
import fr.ahmad.tclstreamx.player.PlaybackController
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

private data class RailEntry(val route: String, val label: Int)

private val RAIL = listOf(
    RailEntry(Routes.HOME, R.string.nav_home),
    RailEntry(Routes.LIVE, R.string.nav_live),
    RailEntry(Routes.SPORTS, R.string.nav_sports),
    RailEntry(Routes.MOVIES, R.string.nav_movies),
    RailEntry(Routes.SERIES, R.string.nav_series),
    RailEntry(Routes.ANIMATION, R.string.nav_animation),
    RailEntry(Routes.LOCAL, R.string.nav_local),
    RailEntry(Routes.SEARCH, R.string.nav_search),
    RailEntry(Routes.PLAYLISTS, R.string.nav_playlists),
    RailEntry(Routes.SETTINGS, R.string.nav_settings),
)

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

    Row(Modifier.fillMaxSize().background(Palette.Ink)) {
        if (route != Routes.PLAYER) {
            Rail(current = route, onSelect = navigator::go)
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
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
}

/**
 * Collapsed, the rail is a strip of section numbers like a channel list; it widens with labels
 * only while it holds focus, so content keeps the whole screen the rest of the time.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Rail(current: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val width by animateDpAsState(if (expanded) 236.dp else 76.dp, label = "rail")
    val type = LocalTallyType.current
    val requesters = remember { RAIL.map { FocusRequester() } }
    val selectedIndex = RAIL.indexOfFirst { current.startsWith(it.route) }.coerceAtLeast(0)
    Column(
        Modifier
            .width(width)
            .fillMaxHeight()
            .background(Palette.Graphite)
            .onFocusChanged { expanded = it.hasFocus }
            .focusProperties { enter = { requesters[selectedIndex] } }
            .padding(vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(Modifier.padding(start = 22.dp, bottom = 26.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(14.dp).background(Palette.Tally))
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
                Row(Modifier.fillMaxSize().padding(start = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "%02d".format(java.util.Locale.ROOT, i + 1),
                        style = type.numeralSmall.copy(color = if (focused || selected) Palette.Text else Palette.TextFaint),
                        modifier = Modifier.width(30.dp),
                    )
                    if (expanded) {
                        Spacer(Modifier.width(14.dp))
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
