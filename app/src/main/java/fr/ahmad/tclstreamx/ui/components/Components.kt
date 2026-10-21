package fr.ahmad.tclstreamx.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import fr.ahmad.tclstreamx.R
import fr.ahmad.tclstreamx.data.db.StreamStatus
import fr.ahmad.tclstreamx.ui.theme.LocalTallyType
import fr.ahmad.tclstreamx.ui.theme.Palette
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import androidx.compose.ui.res.stringResource

/**
 * The one focus language of the app: the row lifts to a lighter graphite and a tally bar lights
 * up on its leading edge (mirrored automatically in Arabic). No glow, no bounce.
 *
 * Built on foundation's combinedClickable, so the same row answers the remote (OK / long OK)
 * and touch (tap / long press) on a phone or tablet.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TallyRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
    onFocus: (() -> Unit)? = null,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .background(
                when {
                    pressed -> Palette.Hairline
                    focused -> Palette.Lift
                    selected -> Palette.Raised
                    else -> Color.Transparent
                }
            )
            .onFocusChanged { if (it.isFocused) onFocus?.invoke() }
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onLongClick = onLongClick,
                onClick = onClick,
            )
    ) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(if (focused || pressed) Palette.Tally else if (selected) Palette.TallyDeep else Color.Transparent)
            )
            Box(Modifier.weight(1f)) { content(focused) }
        }
    }
}

/** Poster / tile card: hairline frame that turns white on focus, very slight lift. Remote and touch. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TallyCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocus: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (focused) 1.03f else if (pressed) 0.98f else 1f, label = "card")
    val shape = RoundedCornerShape(2.dp)
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(if (focused) Palette.Raised else Palette.Graphite)
            .border(if (focused) 2.dp else 1.dp, if (focused) Palette.Text else Palette.Hairline, shape)
            .onFocusChanged { if (it.isFocused) onFocus?.invoke() }
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onLongClick = onLongClick,
                onClick = onClick,
            )
    ) {
        content(focused)
    }
}

@Composable
fun StatusDot(status: StreamStatus, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    val color = when (status) {
        StreamStatus.ONLINE -> Palette.Signal
        StreamStatus.UNKNOWN -> Palette.TextFaint
        StreamStatus.FORBIDDEN, StreamStatus.TIMEOUT -> Palette.Caution
        StreamStatus.OFFLINE, StreamStatus.INVALID, StreamStatus.AUTH_REQUIRED, StreamStatus.DRM -> Palette.Dead
    }
    Box(modifier.size(size).clip(CircleShape).background(color))
}

@Composable
fun statusLabel(status: StreamStatus): String = stringResource(
    when (status) {
        StreamStatus.ONLINE -> R.string.status_online
        StreamStatus.UNKNOWN -> R.string.status_unknown
        StreamStatus.OFFLINE -> R.string.status_offline
        StreamStatus.FORBIDDEN -> R.string.status_forbidden
        StreamStatus.AUTH_REQUIRED -> R.string.status_auth
        StreamStatus.DRM -> R.string.status_drm
        StreamStatus.TIMEOUT -> R.string.status_timeout
        StreamStatus.INVALID -> R.string.status_invalid
    }
)

/** Channel logo on a dark plate; falls back to the first letters of the name. */
@Composable
fun ChannelLogo(url: String?, name: String, modifier: Modifier = Modifier) {
    Box(modifier.background(Palette.Raised), contentAlignment = Alignment.Center) {
        Text(
            name.trim().split(' ').filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1) }.uppercase(),
            style = LocalTallyType.current.numeralSmall,
        )
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().background(Palette.Raised).padding(6.dp),
            )
        }
    }
}

/** Section heading: large condensed title with an optional count in the numeral face. */
@Composable
fun SectionHeading(title: String, modifier: Modifier = Modifier, count: Int? = null) {
    Row(modifier, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = LocalTallyType.current.headline)
        if (count != null) Text(count.toString(), style = LocalTallyType.current.numeralSmall, modifier = Modifier.padding(bottom = 4.dp))
    }
}

/** Wall clock, refreshed every 15 s. */
@Composable
fun Clock(modifier: Modifier = Modifier) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(15_000)
        }
    }
    Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(now)), style = LocalTallyType.current.numeral, modifier = modifier)
}

/** Thin progress line used for EPG "now" and VOD resume points. */
@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier, color: Color = Palette.Tally) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), label = "progress")
    Box(modifier.height(3.dp).background(Palette.Hairline)) {
        Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(color))
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = LocalTallyType.current.headline)
        Text(body, style = LocalTallyType.current.body.copy(color = Palette.TextDim))
    }
}

@Composable
fun Tag(text: String, modifier: Modifier = Modifier, color: Color = Palette.TextDim) {
    Box(
        modifier
            .background(Palette.Raised)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, style = LocalTallyType.current.mono.copy(color = color), maxLines = 1, overflow = TextOverflow.Clip)
    }
}

@Composable
fun LiveBadge(modifier: Modifier = Modifier) {
    Row(
        modifier.background(Palette.Tally).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(Palette.Ink))
        Spacer(Modifier.width(5.dp))
        Text(stringResource(R.string.live_badge), style = LocalTallyType.current.mono.copy(color = Palette.Ink))
    }
}
