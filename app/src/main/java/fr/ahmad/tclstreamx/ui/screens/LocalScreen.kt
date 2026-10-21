package fr.ahmad.tclstreamx.ui.screens

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.tv.material3.Text
import fr.ahmad.tclstreamx.R
import fr.ahmad.tclstreamx.TclApp
import fr.ahmad.tclstreamx.data.vod.LocalVideo
import fr.ahmad.tclstreamx.player.PlayItem
import fr.ahmad.tclstreamx.player.PlaybackController
import fr.ahmad.tclstreamx.ui.components.TallyRow
import fr.ahmad.tclstreamx.ui.nav.Navigator
import fr.ahmad.tclstreamx.ui.theme.LocalTallyType
import fr.ahmad.tclstreamx.ui.theme.Palette

private val VIDEO_PERMISSION =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LocalScreen(navigator: Navigator, playback: PlaybackController) {
    val context = LocalContext.current
    val c = (context.applicationContext as TclApp).container
    val type = LocalTallyType.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, VIDEO_PERMISSION) == PackageManager.PERMISSION_GRANTED)
    }
    var videos by remember { mutableStateOf<List<LocalVideo>?>(null) }
    var noPicker by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            playback.play(PlayItem(key = "file:$uri", title = uri.lastPathSegment ?: "video", urls = listOf(uri.toString()), isLive = false))
            navigator.openPlayer()
        }
    }

    LaunchedEffect(granted) {
        if (granted) videos = c.vod.localVideos()
        runCatching { first.requestFocus() }
    }

    LazyColumn(
        Modifier.fillMaxSize().focusRestorer(),
        contentPadding = PaddingValues(start = 40.dp, end = 40.dp, top = 30.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item { Text(stringResource(R.string.local_title), style = type.display, modifier = Modifier.padding(bottom = 6.dp)) }
        item { Text(stringResource(R.string.local_permission_body), style = type.bodySmall, modifier = Modifier.padding(bottom = 16.dp)) }
        if (!granted) {
            item {
                TallyRow(onClick = { permission.launch(VIDEO_PERMISSION) }, modifier = Modifier.fillMaxWidth().height(52.dp).focusRequester(first)) {
                    Text(stringResource(R.string.local_permission), style = type.title, modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 14.dp))
                }
            }
        }
        item {
            TallyRow(
                onClick = {
                    try {
                        picker.launch(arrayOf("video/*"))
                    } catch (e: ActivityNotFoundException) {
                        noPicker = true
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp).let { if (granted) it.focusRequester(first) else it },
            ) {
                Text(stringResource(R.string.local_open_picker), style = type.title, modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 14.dp))
            }
        }
        if (noPicker) item { Text(stringResource(R.string.local_no_picker), style = type.bodySmall.copy(color = Palette.Caution)) }
        val list = videos
        if (granted && list != null && list.isEmpty()) {
            item { Text(stringResource(R.string.local_empty), style = type.body, modifier = Modifier.padding(top = 20.dp)) }
        }
        if (list != null) {
            items(list, key = { it.id }) { v ->
                TallyRow(
                    onClick = {
                        playback.play(PlayItem(key = "local:${v.id}", title = v.title, urls = listOf(v.uri), isLive = false))
                        navigator.openPlayer()
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(v.title, style = type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            v.folder?.let { Text(it, style = type.mono) }
                        }
                        if (v.durationMs > 0) Text("${v.durationMs / 60_000} min", style = type.mono)
                    }
                }
            }
        }
    }
}
