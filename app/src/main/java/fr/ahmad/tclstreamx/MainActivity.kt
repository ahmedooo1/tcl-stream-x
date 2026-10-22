package fr.ahmad.tclstreamx

import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
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
                AppRoot(playback)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // On a TV, phones running the app can send the channel they are watching while the app is open.
        if (isTelevision()) (application as TclApp).container.remote.startReceiver(deviceLabel())
    }

    override fun onStop() {
        (application as TclApp).container.remote.stopReceiver()
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
