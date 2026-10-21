package fr.ahmad.tclstreamx.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import fr.ahmad.tclstreamx.data.db.StreamStatus
import fr.ahmad.tclstreamx.data.sports.Match
import fr.ahmad.tclstreamx.data.sports.MatchState
import fr.ahmad.tclstreamx.data.sports.SportsResult
import fr.ahmad.tclstreamx.player.PlayItem
import fr.ahmad.tclstreamx.player.PlaybackController
import fr.ahmad.tclstreamx.ui.components.LiveBadge
import fr.ahmad.tclstreamx.ui.components.StatusDot
import fr.ahmad.tclstreamx.ui.components.TallyRow
import fr.ahmad.tclstreamx.ui.nav.Navigator
import fr.ahmad.tclstreamx.ui.theme.LocalTallyType
import fr.ahmad.tclstreamx.ui.theme.Palette
import fr.ahmad.tclstreamx.ui.theme.isCompactWidth
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

/** Opens the official app's page in the Play Store; nothing is ever played from here. */
fun openStoreSearch(context: Context, query: String) {
    val q = Uri.encode(query)
    val intents = listOf(
        Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=$q&c=apps")),
        Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=$q&c=apps")),
    )
    for (i in intents) {
        try {
            context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: ActivityNotFoundException) {
            continue
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SportsScreen(navigator: Navigator, playback: PlaybackController) {
    val context = LocalContext.current
    val c = (context.applicationContext as TclApp).container
    val type = LocalTallyType.current
    val scope = rememberCoroutineScope()
    val settings by c.settings.settings.collectAsState(initial = null)
    val region = settings?.sportsRegion ?: "FR"
    val freeChannels by remember { c.db.channels().observeCategory("sports") }.collectAsState(initial = emptyList())
    var result by remember { mutableStateOf<SportsResult?>(null) }
    var selected by remember { mutableStateOf<Match?>(null) }

    LaunchedEffect(Unit) { result = c.sports.schedule() }

    val compact = isCompactWidth()
    if (compact) {
        // Portrait phone: region switch and free channels as a strip, then the schedule full width.
        Column(Modifier.fillMaxSize().padding(top = 10.dp)) {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.sports_title), style = type.headline, modifier = Modifier.weight(1f))
                TallyRow(
                    onClick = { scope.launch { c.settings.setSportsRegion(if (region == "FR") "MENA" else "FR") } },
                    modifier = Modifier.height(44.dp),
                ) {
                    Text(
                        stringResource(if (region == "FR") R.string.sports_region_fr else R.string.sports_region_mena) + "  ⇄",
                        style = type.body,
                        modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 12.dp),
                    )
                }
            }
            LazyRow(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(freeChannels, key = { it.channelKey }) { ch ->
                    TallyRow(
                        onClick = {
                            playback.play(PlayItem.fromChannel(ch, null))
                            navigator.openPlayer()
                        },
                        modifier = Modifier.height(44.dp),
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp).align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(StreamStatus.of(ch.status))
                            Text(ch.name, style = type.body, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
                val r = result
                if (r == null) {
                    Text(stringResource(R.string.sports_loading), style = type.body, modifier = Modifier.padding(top = 24.dp))
                } else {
                    Schedule(r, region, compact = true, onSelect = { selected = it })
                }
                selected?.let { m -> WhereToWatch(m, region, Modifier.align(Alignment.Center)) { selected = null } }
            }
        }
        return
    }

    Row(Modifier.fillMaxSize().padding(top = 30.dp)) {
        LazyColumn(
            Modifier.width(360.dp).fillMaxHeight().padding(start = 28.dp).focusRestorer(),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item { Text(stringResource(R.string.sports_title), style = type.display, modifier = Modifier.padding(bottom = 14.dp)) }
            item {
                TallyRow(
                    onClick = { scope.launch { c.settings.setSportsRegion(if (region == "FR") "MENA" else "FR") } },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.sports_region), style = type.body, modifier = Modifier.weight(1f))
                        Text(
                            stringResource(if (region == "FR") R.string.sports_region_fr else R.string.sports_region_mena) + "  ⇄",
                            style = type.mono.copy(color = Palette.Text),
                        )
                    }
                }
            }
            item { Text(stringResource(R.string.sports_free_channels), style = type.headline, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp)) }
            items(freeChannels, key = { it.channelKey }) { ch ->
                TallyRow(
                    onClick = {
                        playback.play(PlayItem.fromChannel(ch, null))
                        navigator.openPlayer()
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(ch.name, style = type.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        ch.geo?.let { Text(it, style = type.mono.copy(color = Palette.Caution), modifier = Modifier.padding(end = 8.dp)) }
                        StatusDot(StreamStatus.of(ch.status))
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.sports_source, c.sports.guide.reviewedAt),
                    style = type.bodySmall,
                    modifier = Modifier.padding(top = 18.dp),
                )
            }
        }

        Box(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 28.dp)) {
            val r = result
            if (r == null) {
                Text(stringResource(R.string.sports_loading), style = type.body, modifier = Modifier.padding(top = 80.dp))
            } else {
                Schedule(r, region, compact = false, onSelect = { selected = it })
            }
            selected?.let { m ->
                WhereToWatch(m, region, Modifier.align(Alignment.CenterEnd)) { selected = null }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Schedule(r: SportsResult, region: String, compact: Boolean, onSelect: (Match) -> Unit) {
    val type = LocalTallyType.current
    val c = (LocalContext.current.applicationContext as TclApp).container
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val byDay = r.matches.groupBy { if (it.state == MatchState.LIVE) today else it.start.atZone(zone).toLocalDate() }
    LazyColumn(Modifier.fillMaxSize().focusRestorer(), contentPadding = PaddingValues(bottom = 40.dp)) {
        item { Text(stringResource(R.string.sports_schedule), style = type.headline, modifier = Modifier.padding(top = if (compact) 4.dp else 70.dp, bottom = 8.dp)) }
        if (r.failedLeagues.isNotEmpty()) {
            item { Text(stringResource(R.string.sports_failed, r.failedLeagues.joinToString()), style = type.bodySmall.copy(color = Palette.Caution)) }
        }
        byDay.keys.sorted().forEach { day ->
            item(key = "d$day") {
                val label = when (day) {
                    today -> stringResource(R.string.sports_today)
                    today.plusDays(1) -> stringResource(R.string.sports_tomorrow)
                    else -> DateFormat.getDateInstance(DateFormat.FULL).format(Date.from(day.atStartOfDay(zone).toInstant()))
                }
                Text(label, style = type.title.copy(color = Palette.Tally), modifier = Modifier.padding(top = 20.dp, bottom = 6.dp))
            }
            items(byDay.getValue(day), key = { it.leagueId + it.id }) { m ->
                val rights = c.sports.rightsFor(region, m.leagueId)
                TallyRow(onClick = { onSelect(m) }, modifier = Modifier.fillMaxWidth().height(if (compact) 84.dp else 64.dp)) {
                    Column(Modifier.fillMaxSize().padding(horizontal = if (compact) 8.dp else 14.dp), verticalArrangement = Arrangement.Center) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(if (compact) 52.dp else 72.dp)) {
                                when (m.state) {
                                    MatchState.LIVE -> LiveBadge()
                                    MatchState.FINISHED -> Text(stringResource(R.string.sports_finished), style = type.mono)
                                    MatchState.SCHEDULED -> Text(clockTime(m.start.toEpochMilli()), style = type.numeralSmall.copy(color = Palette.Text))
                                }
                            }
                            TeamCell(m.home.name, m.home.logo, Modifier.weight(1f), alignEnd = true)
                            Text(
                                if (m.state == MatchState.SCHEDULED) "vs" else "${m.home.score ?: "0"} : ${m.away.score ?: "0"}",
                                style = type.numeralSmall.copy(color = Palette.Text),
                                modifier = Modifier.padding(horizontal = if (compact) 6.dp else 14.dp),
                            )
                            TeamCell(m.away.name, m.away.logo, Modifier.weight(1f), alignEnd = false)
                            if (!compact) {
                                Column(Modifier.width(190.dp).padding(start = 12.dp)) {
                                    Text(m.leagueName, style = type.mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        rights?.destinations?.joinToString(" · ") ?: stringResource(R.string.sports_unknown_rights),
                                        style = type.bodySmall.copy(color = if (rights?.verified == true) Palette.Text else Palette.TextDim),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        if (compact) {
                            Text(
                                m.leagueName + " · " + (rights?.destinations?.joinToString(" · ") ?: stringResource(R.string.sports_unknown_rights)),
                                style = type.mono,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 52.dp, top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TeamCell(name: String, logo: String?, modifier: Modifier, alignEnd: Boolean) {
    val type = LocalTallyType.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start) {
        if (!alignEnd) TeamLogo(logo)
        Text(name, style = type.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp).weight(1f, fill = false))
        if (alignEnd) TeamLogo(logo)
    }
}

@Composable
private fun TeamLogo(url: String?) {
    Box(Modifier.size(28.dp)) {
        if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun WhereToWatch(m: Match, region: String, modifier: Modifier, onClose: () -> Unit) {
    val type = LocalTallyType.current
    val context = LocalContext.current
    val c = (context.applicationContext as TclApp).container
    val rights = c.sports.rightsFor(region, m.leagueId)
    val first = remember { FocusRequester() }
    LaunchedEffect(m.id) { runCatching { first.requestFocus() } }
    BackHandler(onBack = onClose)
    Column(
        modifier.padding(12.dp).widthIn(max = 380.dp).fillMaxWidth().background(Palette.Graphite).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("${m.home.name} vs ${m.away.name}", style = type.headline)
        Text(m.leagueName + (m.venue?.let { " · $it" } ?: ""), style = type.bodySmall)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.sports_where), style = type.title.copy(color = Palette.Tally))
        if (rights == null) {
            Text(stringResource(R.string.sports_unknown_rights), style = type.body)
        } else {
            if (!rights.verified) Text(stringResource(R.string.sports_unverified), style = type.bodySmall.copy(color = Palette.Caution))
            rights.destinations.forEachIndexed { i, d ->
                TallyRow(
                    onClick = { openStoreSearch(context, d.substringBefore(" (")) },
                    modifier = Modifier.fillMaxWidth().height(48.dp).let { if (i == 0) it.focusRequester(first) else it },
                ) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(d, style = type.title, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("↗", style = type.mono)
                    }
                }
            }
            if (rights.source.isNotBlank()) Text(rights.source, style = type.mono, maxLines = 2)
        }
        TallyRow(onClick = onClose, modifier = Modifier.fillMaxWidth().height(44.dp).let { if (rights == null) it.focusRequester(first) else it }) {
            Text(stringResource(R.string.player_back), style = type.body, modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 14.dp))
        }
    }
}
