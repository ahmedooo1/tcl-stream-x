package fr.ahmad.tclstreamx

import android.os.SystemClock
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TV geometry (960x540 dp). Drives the real UI with remote keys only and saves screenshots,
 * in French then Arabic (RTL): proves D-pad navigation end to end and gives visual evidence.
 */
@RunWith(AndroidJUnit4::class)
class UiTourTest {
    private val inst = InstrumentationRegistry.getInstrumentation()

    /** Injects remote keys through UiAutomation, like the TV's input service would. */
    private fun key(code: Int, times: Int = 1, waitMs: Long = 600) {
        val ui = inst.uiAutomation
        repeat(times) {
            val now = SystemClock.uptimeMillis()
            ui.injectInputEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0), true)
            ui.injectInputEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0), true)
            Thread.sleep(waitMs)
        }
    }

    private fun shot(name: String, waitMs: Long = 1_500) {
        Thread.sleep(waitMs)
        CiReport.screenshot("tv-$name")
    }

    @Test
    fun remoteOnlyTour() {
        inst.runOnMainSync { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("fr")) }
        // The phone tour before this one taps the screen, which leaves the device in touch mode; a TV
        // never is. One key press first puts the emulator back in remote (non-touch) mode.
        key(KeyEvent.KEYCODE_DPAD_UP)
        ActivityScenario.launch(MainActivity::class.java).use {
            shot("01-home-fr", 12_000)
            key(KeyEvent.KEYCODE_DPAD_DOWN)                    // next row: the stage follows the focus
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            shot("01b-home-focus-fr", 3_000)
            key(KeyEvent.KEYCODE_DPAD_LEFT, times = 4)         // into the rail (lands on Accueil)
            key(KeyEvent.KEYCODE_DPAD_DOWN)                    // Chaînes
            key(KeyEvent.KEYCODE_DPAD_CENTER, waitMs = 2_000)
            key(KeyEvent.KEYCODE_DPAD_RIGHT)                   // into the channels screen
            key(KeyEvent.KEYCODE_DPAD_DOWN, times = 2)
            shot("02-live-fr", 9_000)                          // preview starts after the focus dwell
            key(KeyEvent.KEYCODE_DPAD_CENTER)                  // fullscreen
            Thread.sleep(10_000)
            key(KeyEvent.KEYCODE_INFO)                         // info bar (DOWN would zap)
            shot("03-player-fr", 800)
            key(KeyEvent.KEYCODE_DPAD_RIGHT)                   // options: quality / audio / subtitles
            shot("04-player-options-fr", 1_200)
            key(KeyEvent.KEYCODE_BACK)
            key(KeyEvent.KEYCODE_DPAD_LEFT)                    // channel list panel
            shot("05-player-channels-fr", 1_200)
            key(KeyEvent.KEYCODE_BACK)
            key(KeyEvent.KEYCODE_BACK, waitMs = 1_500)         // back to the live list
            key(KeyEvent.KEYCODE_DPAD_LEFT, times = 4)         // into the rail (lands on Chaînes)
            key(KeyEvent.KEYCODE_DPAD_DOWN)                    // Films
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            shot("06-movies-fr", 9_000)
            key(KeyEvent.KEYCODE_DPAD_LEFT, times = 4)
            key(KeyEvent.KEYCODE_DPAD_DOWN, times = 2)         // Sport
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            shot("07-sports-fr", 10_000)
            key(KeyEvent.KEYCODE_BACK, waitMs = 2_000)         // back to Home
            key(KeyEvent.KEYCODE_BACK)                         // asks before leaving
            shot("10-exit-fr", 1_200)
            key(KeyEvent.KEYCODE_DPAD_CENTER, waitMs = 1_500)  // "Rester" has the focus
            check(UiDevice.getInstance(inst).hasObject(By.text("StreamX"))) { "the app closed instead of staying" }
        }
        inst.runOnMainSync { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ar")) }
        ActivityScenario.launch(MainActivity::class.java).use {
            shot("08-home-ar", 8_000)
            key(KeyEvent.KEYCODE_DPAD_RIGHT, times = 4)        // the rail is on the right in Arabic
            key(KeyEvent.KEYCODE_DPAD_DOWN)                    // القنوات
            key(KeyEvent.KEYCODE_DPAD_CENTER, waitMs = 2_000)
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            key(KeyEvent.KEYCODE_DPAD_DOWN, times = 2)
            shot("09-live-ar", 9_000)
        }
        inst.runOnMainSync { AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList()) }
    }
}
