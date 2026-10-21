package fr.ahmad.tclstreamx.ui.components

import android.graphics.Color
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * Hosts Media3's PlayerView without its controller: every control is drawn in Compose so the
 * remote navigation stays in one focus system. Subtitles are still rendered by PlayerView.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun VideoSurface(player: Player?, resizeMode: Int, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                isFocusable = false
                isFocusableInTouchMode = false
                descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                setShutterBackgroundColor(Color.BLACK)
                setKeepContentOnPlayerReset(false)
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        },
        update = { view ->
            if (view.player !== player) view.player = player
            view.resizeMode = when (resizeMode) {
                1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        },
        onRelease = { view -> view.player = null },
        modifier = modifier,
    )
}
