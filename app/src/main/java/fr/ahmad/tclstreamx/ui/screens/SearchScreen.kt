package fr.ahmad.tclstreamx.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import fr.ahmad.tclstreamx.data.db.StreamStatus
import fr.ahmad.tclstreamx.data.m3u.M3uParser
import fr.ahmad.tclstreamx.player.PlayItem
import fr.ahmad.tclstreamx.player.PlaybackController
import fr.ahmad.tclstreamx.ui.components.ChannelLogo
import fr.ahmad.tclstreamx.ui.components.InputField
import fr.ahmad.tclstreamx.ui.components.StatusDot
import fr.ahmad.tclstreamx.ui.components.TallyRow
import fr.ahmad.tclstreamx.ui.nav.Navigator
import fr.ahmad.tclstreamx.ui.theme.LocalTallyType

@Composable
fun SearchScreen(navigator: Navigator, playback: PlaybackController) {
    val c = (LocalContext.current.applicationContext as TclApp).container
    val type = LocalTallyType.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ChannelEntity>>(emptyList()) }
    val first = remember { FocusRequester() }

    LaunchedEffect(query) {
        val q = M3uParser.normalize(query)
        results = if (q.length < 2) emptyList() else c.db.channels().search(q, 200)
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 40.dp, end = 40.dp, top = 30.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item { Text(stringResource(R.string.nav_search), style = type.display, modifier = Modifier.padding(bottom = 12.dp)) }
        item {
            InputField(query, stringResource(R.string.search_hint), onSubmit = { query = it }, rowModifier = Modifier.focusRequester(first))
            Spacer(Modifier.height(12.dp))
        }
        if (query.length >= 2 && results.isEmpty()) {
            item { Text(stringResource(R.string.search_empty), style = type.body) }
        }
        items(results, key = { it.id }) { ch ->
            TallyRow(
                onClick = {
                    playback.play(PlayItem.fromChannel(ch, null))
                    navigator.openPlayer()
                },
                modifier = Modifier.fillMaxWidth().height(58.dp),
            ) {
                Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    ChannelLogo(ch.logo, ch.name, Modifier.size(width = 62.dp, height = 38.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(ch.name, style = type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(groupLabel(ch.groupTitle), style = type.mono, maxLines = 1)
                    }
                    StatusDot(StreamStatus.of(ch.status))
                }
            }
        }
    }
}
