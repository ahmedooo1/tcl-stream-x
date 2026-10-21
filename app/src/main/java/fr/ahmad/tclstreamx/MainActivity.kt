package fr.ahmad.tclstreamx

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import fr.ahmad.tclstreamx.player.PlaybackController
import fr.ahmad.tclstreamx.ui.nav.AppRoot
import fr.ahmad.tclstreamx.ui.theme.TallyTheme

class MainActivity : AppCompatActivity() {
    private val playback: PlaybackController by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TallyTheme {
                AppRoot(playback)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Release the decoder when the TV goes to standby or another app/input takes over.
        playback.onBackground()
    }
}
