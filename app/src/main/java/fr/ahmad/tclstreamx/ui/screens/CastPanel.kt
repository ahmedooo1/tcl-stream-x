package fr.ahmad.tclstreamx.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import fr.ahmad.tclstreamx.R
import fr.ahmad.tclstreamx.TclApp
import fr.ahmad.tclstreamx.data.remote.RemoteMedia
import fr.ahmad.tclstreamx.data.remote.RemoteTv
import fr.ahmad.tclstreamx.data.remote.SendResult
import fr.ahmad.tclstreamx.data.xtream.XtreamUrls
import fr.ahmad.tclstreamx.player.PlaybackController
import fr.ahmad.tclstreamx.player.cast.CastMedia
import fr.ahmad.tclstreamx.ui.components.AppIcon
import fr.ahmad.tclstreamx.ui.components.AppIcons
import fr.ahmad.tclstreamx.ui.components.InputField
import fr.ahmad.tclstreamx.ui.components.TallyRow
import fr.ahmad.tclstreamx.ui.theme.LocalTallyType
import fr.ahmad.tclstreamx.ui.theme.Palette
import kotlinx.coroutines.launch

private sealed interface CastStatus {
    data class Sent(val device: String) : CastStatus
    data object BadCode : CastStatus
    data object Unreachable : CastStatus
}

/**
 * Three ways to put what is playing on another screen:
 * 1. TCL Stream X on the TV, over Wi-Fi (paired once with a code shown on the TV);
 * 2. Chromecast and Google TV "Chromecast built-in", with Google's default receiver;
 * 3. any other installed app (VLC, a DLNA/UPnP caster…) through the system chooser.
 */
@Composable
fun CastPanel(playback: PlaybackController, modifier: Modifier) {
    val context = LocalContext.current
    val c = (context.applicationContext as TclApp).container
    val type = LocalTallyType.current
    val scope = rememberCoroutineScope()
    val ui by playback.ui.collectAsState()
    val item = ui.item ?: return
    var castAvailable by remember { mutableStateOf<Boolean?>(null) }
    var pairing by remember { mutableStateOf<RemoteTv?>(null) }
    var status by remember { mutableStateOf<CastStatus?>(null) }
    val first = remember { FocusRequester() }
    var waitedLong by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(12_000)
        waitedLong = true
    }

    DisposableEffect(Unit) {
        castAvailable = c.chromecast.available()
        if (castAvailable == true) c.chromecast.startScan()
        c.remote.startDiscovery()
        onDispose {
            c.chromecast.stopScan()
            c.remote.stopDiscovery()
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    val devices by c.chromecast.devices.collectAsState()
    val connected by c.chromecast.connected.collectAsState()
    val tvs by c.remote.tvs.collectAsState()

    /** Real addresses, resolved in memory (account streams carry the login only at this moment). */
    suspend fun resolvedUrls(): List<String> = item.urls.mapNotNull { c.xtream.resolve(it) }

    fun sendTo(tv: RemoteTv) {
        scope.launch {
            val urls = resolvedUrls()
            if (urls.isEmpty()) return@launch
            val media = RemoteMedia(item.title, urls, item.userAgent, item.referrer, item.isLive, item.logo ?: item.poster)
            when (c.remote.send(tv, media)) {
                SendResult.OK -> {
                    status = CastStatus.Sent(tv.name)
                    playback.player.value?.pause()
                }
                SendResult.NOT_PAIRED -> if (c.remote.requestPairing(tv)) pairing = tv else status = CastStatus.Unreachable
                SendResult.NETWORK -> status = CastStatus.Unreachable
            }
        }
    }

    LazyColumn(
        modifier.widthIn(max = 420.dp).fillMaxWidth(0.92f).fillMaxHeight().background(Palette.Graphite.copy(alpha = 0.97f)),
        contentPadding = PaddingValues(vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Row(Modifier.padding(horizontal = 18.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(AppIcons.Cast, Palette.Tally, size = 26.dp)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.cast_title), style = type.headline)
            }
        }
        status?.let { s ->
            item {
                val text = when (s) {
                    is CastStatus.Sent -> stringResource(R.string.cast_sent, s.device)
                    CastStatus.BadCode -> stringResource(R.string.cast_bad_code)
                    CastStatus.Unreachable -> stringResource(R.string.cast_network)
                }
                Text(text, style = type.body.copy(color = if (s is CastStatus.Sent) Palette.Signal else Palette.Caution), modifier = Modifier.padding(horizontal = 18.dp))
            }
        }

        // ---- 1. the app on the TV ---------------------------------------------------------
        item { PanelTitle(stringResource(R.string.cast_tv_app)) }
        if (tvs.isEmpty()) item { Hint(stringResource(if (waitedLong) R.string.cast_none_found else R.string.cast_tv_app_empty)) }
        items(tvs, key = { "tv:" + it.name }) { tv ->
            DeviceRow(tv.name, if (tv.paired) null else stringResource(R.string.cast_pair_needed), Modifier.focusRequester(first)) { sendTo(tv) }
        }
        // Some routers block discovery (guest Wi-Fi, "AP isolation"): the address shown in the TV settings can be typed here.
        item {
            InputField(
                value = "",
                placeholder = stringResource(R.string.cast_manual_hint),
                keyboardType = KeyboardType.Uri,
                modifier = Modifier.padding(horizontal = 12.dp),
                onSubmit = { text ->
                    if (text.isNotBlank()) scope.launch { if (c.remote.addManual(text) == null) status = CastStatus.Unreachable }
                },
            )
        }
        pairing?.let { tv ->
            item {
                InputField(
                    value = "",
                    placeholder = stringResource(R.string.cast_code_hint),
                    keyboardType = KeyboardType.NumberPassword,
                    modifier = Modifier.padding(horizontal = 12.dp),
                    onSubmit = { code ->
                        scope.launch {
                            if (c.remote.confirmPairing(tv, code)) {
                                pairing = null
                                sendTo(tv.copy(paired = true))
                            } else {
                                status = CastStatus.BadCode
                            }
                        }
                    },
                )
            }
        }

        // ---- 2. Chromecast ----------------------------------------------------------------
        item { PanelTitle(stringResource(R.string.cast_chromecast)) }
        when {
            castAvailable == false -> item { Hint(stringResource(R.string.cast_unavailable)) }
            devices.isEmpty() -> item { Hint(stringResource(if (waitedLong) R.string.cast_none_found else R.string.cast_searching)) }
        }
        items(devices, key = { "cc:" + it.id }) { d ->
            DeviceRow(d.name, d.description, Modifier) {
                scope.launch {
                    val url = resolvedUrls().firstOrNull() ?: return@launch
                    c.chromecast.cast(d.id, CastMedia(url, item.title, item.isLive, item.poster ?: item.logo))
                    playback.player.value?.pause()
                    status = CastStatus.Sent(d.name)
                }
            }
        }
        connected?.let { name ->
            item { DeviceRow(stringResource(R.string.cast_playing_on, name), null, Modifier) { c.chromecast.stop() } }
        }
        if (castAvailable == true) item { Hint(stringResource(R.string.cast_note)) }

        // ---- 3. other apps ----------------------------------------------------------------
        item { PanelTitle(stringResource(R.string.cast_other)) }
        item {
            DeviceRow(stringResource(R.string.cast_open_with), null, Modifier) {
                scope.launch {
                    val url = resolvedUrls().firstOrNull() ?: return@launch
                    val view = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), "video/*").putExtra("title", item.title)
                    try {
                        context.startActivity(Intent.createChooser(view, item.title))
                    } catch (e: ActivityNotFoundException) {
                        status = CastStatus.Unreachable
                    }
                }
            }
        }
        // A link is only shared for public streams: an account address would hand over the login.
        if (item.urls.none { XtreamUrls.isInternal(it) }) {
            item {
                DeviceRow(stringResource(R.string.cast_share), null, Modifier) {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, item.title)
                        .putExtra(Intent.EXTRA_TEXT, item.urls.first())
                    runCatching { context.startActivity(Intent.createChooser(send, item.title)) }
                }
            }
        }
    }
}

@Composable
private fun PanelTitle(text: String) {
    Text(text, style = LocalTallyType.current.title.copy(color = Palette.Tally), modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 2.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, style = LocalTallyType.current.bodySmall, modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
}

@Composable
private fun DeviceRow(name: String, detail: String?, modifier: Modifier, onClick: () -> Unit) {
    val type = LocalTallyType.current
    TallyRow(onClick = onClick, modifier = modifier.fillMaxWidth().height(54.dp)) { focused ->
        Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                name,
                style = type.body.copy(color = if (focused) Palette.Text else Palette.TextDim),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            detail?.let { Text(it, style = type.mono, maxLines = 1) }
        }
    }
}
