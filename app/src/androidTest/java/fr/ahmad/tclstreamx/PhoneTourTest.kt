package fr.ahmad.tclstreamx

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phone geometry (portrait, ~392 dp wide). Taps on visible labels with UiAutomator, as a person
 * would with a finger, and saves screenshots: proves touch works and shows the phone layout.
 */
@RunWith(AndroidJUnit4::class)
class PhoneTourTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(inst)

    private fun shot(name: String, waitMs: Long = 2_000) {
        Thread.sleep(waitMs)
        CiReport.screenshot("phone-$name")
    }

    /** Taps the first view showing [text] (exact, or prefix); returns false if it is not on screen. */
    private fun tap(text: String, waitMs: Long = 4_000, prefix: Boolean = false): Boolean {
        val selector = if (prefix) By.textStartsWith(text) else By.text(text)
        val obj = device.wait(Until.findObject(selector), 8_000) ?: return false
        obj.click()
        Thread.sleep(waitMs)
        return true
    }

    @Test
    fun touchTour() {
        inst.runOnMainSync { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("fr")) }
        ActivityScenario.launch(MainActivity::class.java).use {
            shot("01-home-fr", 10_000)
            check(tap("Chaînes")) { "bottom bar 'Chaînes' not found" }
            shot("02-channels-fr")
            if (tap("France 24", waitMs = 10_000, prefix = true)) {
                device.click(device.displayWidth / 2, device.displayHeight / 2)  // show controls
                shot("03-player-fr", 800)
                device.pressBack()
                Thread.sleep(1_500)
            }
            check(tap("Films")) { "bottom bar 'Films' not found" }
            shot("04-movies-fr", 6_000)
            check(tap("Séries")) { "bottom bar 'Séries' not found" }
            shot("05-series-fr", 6_000)
            check(tap("Sport")) { "bottom bar 'Sport' not found" }
            shot("06-sports-fr", 8_000)
            check(tap("Accueil")) { "bottom bar 'Accueil' not found" }
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 4, 20)
            shot("07-home-scrolled-fr", 1_500)
        }
        inst.runOnMainSync { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ar")) }
        ActivityScenario.launch(MainActivity::class.java).use {
            shot("08-home-ar", 8_000)
            check(tap("القنوات")) { "bottom bar 'القنوات' not found" }
            shot("09-channels-ar")
        }
        inst.runOnMainSync { AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList()) }
    }
}
