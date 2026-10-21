package fr.ahmad.tclstreamx

import android.view.KeyEvent
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the real UI with remote-control key events only (no touch) and saves screenshots,
 * in French then Arabic (RTL). It proves D-pad navigation end to end and gives visual evidence.
 */
@RunWith(AndroidJUnit4::class)
class UiTourTest {
    private val inst = InstrumentationRegistry.getInstrumentation()

    private fun key(code: Int, times: Int = 1, waitMs: Long = 600) {
        repeat(times) {
            inst.sendKeyDownUpSync(code)
            Thread.sleep(waitMs)
        }
    }

    private fun shot(name: String, waitMs: Long = 1_500) {
        Thread.sleep(waitMs)
        CiReport.screenshot(name)
    }

    @Test
    fun remoteOnlyTour() {
        inst.runOnMainSync { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("fr")) }
        ActivityScenario.launch(MainActivity::class.java).use {
            shot("01-home-fr", 12_000)
            key(KeyEvent.KEYCODE_DPAD_CENTER)                 // first channel tile -> fullscreen player
            Thread.sleep(14_000)
            key(KeyEvent.KEYCODE_INFO)                        // show the info bar (DOWN would zap)
            shot("02-player-fr", 800)
            key(KeyEvent.KEYCODE_DPAD_RIGHT)                  // options panel (quality / audio / subtitles)
            shot("03-player-options-fr", 1_200)
            key(KeyEvent.KEYCODE_BACK)                        // close panel
            key(KeyEvent.KEYCODE_DPAD_LEFT)                   // channel list panel
            shot("04-player-channels-fr", 1_200)
            key(KeyEvent.KEYCODE_BACK)
            key(KeyEvent.KEYCODE_BACK, waitMs = 1_500)        // leave player
            key(KeyEvent.KEYCODE_DPAD_LEFT, times = 3)        // into the rail
            key(KeyEvent.KEYCODE_DPAD_DOWN)                   // 02 Direct
            key(KeyEvent.KEYCODE_DPAD_CENTER, waitMs = 2_000)
            key(KeyEvent.KEYCODE_DPAD_DOWN, times = 2)
            shot("05-live-fr", 9_000)                         // preview starts after the focus dwell
            key(KeyEvent.KEYCODE_DPAD_LEFT, times = 4)
            key(KeyEvent.KEYCODE_DPAD_DOWN)                   // 03 Sport
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            shot("06-sports-fr", 10_000)
            key(KeyEvent.KEYCODE_DPAD_LEFT, times = 4)
            key(KeyEvent.KEYCODE_DPAD_DOWN)                   // 04 Films
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            shot("07-movies-fr", 10_000)
        }
        inst.runOnMainSync { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ar")) }
        ActivityScenario.launch(MainActivity::class.java).use {
            shot("08-home-ar", 8_000)
            key(KeyEvent.KEYCODE_DPAD_RIGHT, times = 3)       // RTL: the rail is on the right
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            key(KeyEvent.KEYCODE_DPAD_CENTER, waitMs = 2_000)
            key(KeyEvent.KEYCODE_DPAD_DOWN, times = 2)
            shot("09-live-ar", 9_000)
            key(KeyEvent.KEYCODE_DPAD_RIGHT, times = 4)
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            shot("10-sports-ar", 10_000)
        }
        inst.runOnMainSync { AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList()) }
    }
}
