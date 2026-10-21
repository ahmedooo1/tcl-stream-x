package fr.ahmad.tclstreamx.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import fr.ahmad.tclstreamx.R
import fr.ahmad.tclstreamx.TclApp
import fr.ahmad.tclstreamx.data.db.PlaylistKind
import fr.ahmad.tclstreamx.data.m3u.ImportException
import fr.ahmad.tclstreamx.data.m3u.ImportState
import fr.ahmad.tclstreamx.ui.components.InputField
import fr.ahmad.tclstreamx.ui.components.TallyRow
import fr.ahmad.tclstreamx.ui.nav.Navigator
import fr.ahmad.tclstreamx.ui.nav.Routes
import fr.ahmad.tclstreamx.ui.theme.LocalTallyType
import fr.ahmad.tclstreamx.ui.theme.Palette
import kotlinx.coroutines.launch

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PlaylistsScreen(navigator: Navigator) {
    val context = LocalContext.current
    val c = (context.applicationContext as TclApp).container
    val type = LocalTallyType.current
    val playlists by c.db.playlists().observeAll().collectAsState(initial = emptyList())
    val state by c.importer.state.collectAsState()
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var noPicker by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Long?>(null) }
    val first = remember { FocusRequester() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            c.appScope.launch { c.importer.addFromUri(uri.lastPathSegment?.substringAfterLast('/') ?: "Playlist", uri) }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    LazyColumn(
        Modifier.fillMaxSize().focusRestorer(),
        contentPadding = PaddingValues(start = 40.dp, end = 40.dp, top = 30.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item { Text(stringResource(R.string.playlists_title), style = type.display) }
        item { Text(stringResource(R.string.playlists_legal), style = type.bodySmall, modifier = Modifier.padding(bottom = 14.dp)) }

        item {
            val text = when (val s = state) {
                is ImportState.Running -> stringResource(R.string.import_running, s.playlistName, s.parsed)
                is ImportState.Done -> stringResource(R.string.import_done, s.playlistName, s.channels, s.skipped)
                is ImportState.Failed -> stringResource(R.string.import_failed, s.playlistName, importErrorText(s.reason))
                ImportState.Idle -> null
            }
            if (text != null) {
                Box(Modifier.fillMaxWidth().background(Palette.Raised).padding(14.dp)) {
                    Text(text, style = type.body.copy(color = if (state is ImportState.Failed) Palette.Caution else Palette.Text))
                }
            }
        }

        item { Text(stringResource(R.string.playlists_add_url), style = type.headline, modifier = Modifier.padding(top = 10.dp)) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InputField(name, stringResource(R.string.playlists_name_hint), onSubmit = { name = it }, modifier = Modifier.width(260.dp), rowModifier = Modifier.focusRequester(first))
                InputField(url, stringResource(R.string.playlists_url_hint), onSubmit = { url = it }, modifier = Modifier.weight(1f), keyboardType = KeyboardType.Uri)
                TallyRow(
                    onClick = {
                        val u = url
                        val n = name
                        c.appScope.launch {
                            try {
                                c.importer.addFromUrl(n, u)
                            } catch (e: ImportException) {
                                android.util.Log.i("TclStreamX", "import rejected: ${e.error}")
                            }
                        }
                        url = ""
                        name = ""
                    },
                    modifier = Modifier.width(150.dp).height(52.dp),
                ) {
                    Text(stringResource(R.string.playlists_add), style = type.title.copy(color = Palette.Tally), modifier = Modifier.align(Alignment.Center))
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                TallyRow(
                    onClick = {
                        try {
                            picker.launch(arrayOf("*/*"))
                        } catch (e: ActivityNotFoundException) {
                            noPicker = true
                        }
                    },
                    modifier = Modifier.weight(1f).height(52.dp),
                ) {
                    Text(stringResource(R.string.playlists_add_file), style = type.title, modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 14.dp))
                }
                TallyRow(onClick = { navigator.go(Routes.LAN) }, modifier = Modifier.weight(1f).height(52.dp)) {
                    Text(stringResource(R.string.playlists_lan), style = type.title, modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 14.dp))
                }
            }
        }
        if (noPicker) item { Text(stringResource(R.string.local_no_picker), style = type.bodySmall.copy(color = Palette.Caution)) }

        item { Spacer(Modifier.height(18.dp)) }
        items(playlists, key = { it.id }) { p ->
            Column(Modifier.fillMaxWidth().background(Palette.Graphite).padding(vertical = 10.dp)) {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = type.title)
                        Text(
                            listOfNotNull(
                                stringResource(R.string.playlists_channels, p.channelCount),
                                if (p.updatedAt > 0) stringResource(R.string.playlists_updated, relativeTime(p.updatedAt)) else null,
                                p.epgUrl?.let { "EPG" },
                            ).joinToString(" · "),
                            style = type.mono,
                        )
                        if (p.kind == PlaylistKind.BUILTIN) Text(stringResource(R.string.playlists_builtin_note), style = type.bodySmall)
                        p.lastError?.let { Text("⚠ $it", style = type.mono.copy(color = Palette.Caution)) }
                    }
                    if (p.kind == PlaylistKind.URL || p.kind == PlaylistKind.FILE) {
                        TallyRow(onClick = { c.appScope.launch { c.importer.refresh(p.id) } }, modifier = Modifier.width(150.dp).height(44.dp)) {
                            Text(stringResource(R.string.playlists_refresh), style = type.body, modifier = Modifier.align(Alignment.Center))
                        }
                    }
                    if (p.kind != PlaylistKind.BUILTIN) {
                        Spacer(Modifier.width(8.dp))
                        // Two presses: the first arms, the second deletes.
                        TallyRow(
                            onClick = {
                                if (confirmDelete == p.id) {
                                    confirmDelete = null
                                    c.appScope.launch { c.importer.delete(p.id) }
                                } else {
                                    confirmDelete = p.id
                                }
                            },
                            modifier = Modifier.width(150.dp).height(44.dp),
                        ) {
                            Text(
                                stringResource(R.string.playlists_delete) + if (confirmDelete == p.id) " ?" else "",
                                style = type.body.copy(color = Palette.Tally),
                                modifier = Modifier.align(Alignment.Center),
                            )
                        }
                    }
                }
            }
        }
    }
}
