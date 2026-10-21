package fr.ahmad.tclstreamx

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Imports a 50 000-channel playlist into the real Room database on the device and measures it. */
@RunWith(AndroidJUnit4::class)
class ImportOnDeviceTest {
    @Test
    fun largePlaylistImport() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val app = ctx.applicationContext as TclApp
        val n = 50_000
        val file = File(ctx.cacheDir, "big.m3u")
        file.bufferedWriter().use { w ->
            w.write("#EXTM3U\n")
            for (i in 1..n) {
                w.write("#EXTINF:-1 tvg-id=\"ch$i.test\" tvg-logo=\"https://logo.example/$i.png\" group-title=\"Group ${i % 120}\",Test Channel $i\n")
                w.write("http://stream.example/live/$i.m3u8\n")
            }
        }
        val sizeMb = file.length() / 1_048_576.0
        val rt = Runtime.getRuntime()
        rt.gc()
        val before = rt.totalMemory() - rt.freeMemory()
        val t0 = System.currentTimeMillis()
        val id = app.container.importer.addFromLocalFile("CI big list", file)
        val importMs = System.currentTimeMillis() - t0
        val after = rt.totalMemory() - rt.freeMemory()
        val playlist = app.container.db.playlists().byId(id)!!
        val t1 = System.currentTimeMillis()
        val page = app.container.db.channels().firstOfLineup(id, "Group 7", 0, 0, null, 60)
        val queryMs = System.currentTimeMillis() - t1
        val t2 = System.currentTimeMillis()
        val search = app.container.db.channels().search("test channel 4999", 50)
        val searchMs = System.currentTimeMillis() - t2
        app.container.importer.delete(id)

        CiReport.write(
            "import-report.json",
            """{"channels":$n,"fileMb":${"%.1f".format(java.util.Locale.ROOT, sizeMb)},"importMs":$importMs,""" +
                """"heapDeltaMb":${(after - before) / 1_048_576},"groupPageQueryMs":$queryMs,"searchMs":$searchMs,"searchHits":${search.size}}""",
        )
        assertEquals(n, playlist.channelCount)
        assertEquals(60, page.size)
        assertTrue(search.isNotEmpty())
        assertTrue("import took $importMs ms", importMs < 120_000)
    }
}
