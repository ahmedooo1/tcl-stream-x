package fr.ahmad.tclstreamx

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream

/**
 * Reports and screenshots leave the device through the instrumentation output itself
 * (`am instrument -r` prints every status bundle), so CI gets them even from the non-debuggable
 * build that is tested and shipped, where `run-as` is refused. tools/ci_emulator_tests.sh decodes
 * the `cireport:<file>` entries into ci-out/.
 */
object CiReport {
    private const val STATUS_CODE = 3 // not one of the codes JUnit results use (1, 0, -1 ... -4)

    private fun send(name: String, bytes: ByteArray) {
        val bundle = Bundle()
        bundle.putString("cireport:$name", Base64.encodeToString(bytes, Base64.NO_WRAP))
        InstrumentationRegistry.getInstrumentation().sendStatus(STATUS_CODE, bundle)
    }

    fun write(name: String, text: String) = send(name, text.toByteArray())

    /** JPEG keeps a full TV screenshot well under the size a status bundle can carry. */
    fun screenshot(name: String) {
        val bmp: Bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
        send("$name.jpg", out.toByteArray())
    }
}
