package fr.ahmad.tclstreamx

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Results are written to the app's external files dir, which CI pulls with `adb pull`. */
object CiReport {
    val dir: File by lazy {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "ci").apply { mkdirs() }
    }

    fun write(name: String, text: String) = File(dir, name).writeText(text)

    fun screenshot(name: String) {
        val bmp: Bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
