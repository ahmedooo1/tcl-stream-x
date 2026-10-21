package fr.ahmad.tclstreamx.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.ahmad.tclstreamx.R
import fr.ahmad.tclstreamx.TclApp
import fr.ahmad.tclstreamx.data.lan.LanUploadServer
import fr.ahmad.tclstreamx.data.m3u.ImportException
import fr.ahmad.tclstreamx.data.m3u.ImportState
import fr.ahmad.tclstreamx.ui.nav.Navigator
import fr.ahmad.tclstreamx.ui.theme.LocalTallyType
import fr.ahmad.tclstreamx.ui.theme.Palette
import kotlinx.coroutines.launch
import java.io.IOException

/** Shows the address and PIN of the temporary upload page; the server stops when leaving. */
@Composable
fun LanScreen(@Suppress("UNUSED_PARAMETER") navigator: Navigator) {
    val context = LocalContext.current
    val c = (context.applicationContext as TclApp).container
    val type = LocalTallyType.current
    val state by c.importer.state.collectAsState()
    var port by remember { mutableIntStateOf(0) }
    var received by remember { mutableStateOf<String?>(null) }
    val server = remember {
        LanUploadServer(
            cacheDir = context.cacheDir,
            onFile = { name, file ->
                received = name
                c.appScope.launch { c.importer.addFromLocalFile(name, file) }
            },
            onUrl = { name, url ->
                received = name
                c.appScope.launch {
                    try {
                        c.importer.addFromUrl(name, url)
                    } catch (e: ImportException) {
                        android.util.Log.i("TclStreamX", "LAN url rejected: ${e.error}")
                    }
                }
            },
        )
    }
    DisposableEffect(server) {
        port = try { server.start() } catch (e: IOException) { 0 }
        onDispose { server.stop() }
    }
    val ip = remember { LanUploadServer.localIpv4() }

    Column(
        Modifier.fillMaxSize().padding(48.dp).focusable(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.lan_title), style = type.display)
        if (ip == null || port == 0) {
            Text(stringResource(R.string.lan_no_network), style = type.body.copy(color = Palette.Caution))
        } else {
            Text("1 · " + stringResource(R.string.lan_step1), style = type.body)
            Text("2 · " + stringResource(R.string.lan_step2), style = type.body)
            Text(
                "http://$ip:$port",
                style = type.numeral.copy(fontSize = 44.sp, lineHeight = 48.sp),
                modifier = Modifier.background(Palette.Graphite).padding(horizontal = 18.dp, vertical = 10.dp),
            )
            Text("3 · " + stringResource(R.string.lan_step3), style = type.body)
            Text(server.pin.chunked(3).joinToString(" "), style = type.display.copy(color = Palette.Tally, fontSize = 64.sp, lineHeight = 64.sp))
        }
        received?.let { Text(stringResource(R.string.lan_received, it), style = type.title.copy(color = Palette.Signal)) }
        when (val s = state) {
            is ImportState.Running -> Text(stringResource(R.string.import_running, s.playlistName, s.parsed), style = type.body)
            is ImportState.Done -> Text(stringResource(R.string.import_done, s.playlistName, s.channels, s.skipped), style = type.body)
            is ImportState.Failed -> Text(stringResource(R.string.import_failed, s.playlistName, importErrorText(s.reason)), style = type.body.copy(color = Palette.Caution))
            ImportState.Idle -> Unit
        }
    }
}
