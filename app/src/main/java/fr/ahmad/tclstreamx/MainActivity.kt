package fr.ahmad.tclstreamx

import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import fr.ahmad.tclstreamx.player.PlaybackController
import fr.ahmad.tclstreamx.ui.nav.AppRoot
import fr.ahmad.tclstreamx.ui.theme.TallyTheme

class MainActivity : AppCompatActivity() {
    private val playback: PlaybackController by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Same drawing behind the system bars on every Android version; screens add the insets they need.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            TallyTheme {
                KeepScreenOn(playback)
                AppRoot(playback)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as TclApp).container.onForeground()
        // On a TV, phones running the app can send the channel they are watching while the app is open.
        if (isTelevision()) (application as TclApp).container.remote.startReceiver(deviceLabel())
    }

    override fun onStop() {
        (application as TclApp).container.remote.stopReceiver()
        (application as TclApp).container.onBackground()
        super.onStop()
        // Release the decoder when the TV goes to standby or another app/input takes over.
        playback.onBackground()
    }

    private fun isTelevision(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION

    private fun deviceLabel(): String {
        val name = Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL
        return name.take(40)
    }
}

/**
 * A TV treats a few minutes without a key press as "nobody is watching" and starts the screen saver or
 * goes to sleep, even in the middle of a live channel. While something plays (or is loading) the
 * window asks to stay awake; it goes back to normal as soon as playback stops or is paused.
 */
@Composable
private fun KeepScreenOn(playback: PlaybackController) {
    val ui by playback.ui.collectAsState()
    val keepOn = ui.playing || ui.buffering
    val view = LocalView.current
    DisposableEffect(keepOn, view) {
        view.keepScreenOn = keepOn
        onDispose { view.keepScreenOn = false }
    }
}
