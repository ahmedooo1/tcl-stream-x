package fr.ahmad.tclstreamx

import fr.ahmad.tclstreamx.data.check.ManifestInspector
import fr.ahmad.tclstreamx.data.db.StreamStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestInspectorTest {
    @Test
    fun masterPlaylistVariants() {
        val info = ManifestInspector.inspect(
            """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            low/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
            hi/index.m3u8
            """.trimIndent()
        )
        assertTrue(info.isHls)
        assertEquals(2, info.variants.size)
        assertEquals(1080, info.variants[1].height)
        assertEquals(800_000L, info.variants[0].bandwidth)
        assertFalse(info.drm)
    }

    @Test
    fun mediaPlaylistSegmentsAndClearAesIsNotDrm() {
        val info = ManifestInspector.inspect(
            """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-KEY:METHOD=AES-128,URI="https://k.example/key"
            #EXTINF:6.0,
            seg1.ts
            #EXTINF:6.0,
            seg2.ts
            """.trimIndent()
        )
        assertEquals(listOf("seg1.ts", "seg2.ts"), info.segments)
        assertFalse(info.drm)
    }

    @Test
    fun sampleAesAndWidevineAreDrm() {
        assertTrue(ManifestInspector.inspect("#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://x\"\n#EXTINF:6,\na.ts\n").drm)
        assertTrue(
            ManifestInspector.inspect(
                "#EXTM3U\n#EXT-X-SESSION-KEY:METHOD=SAMPLE-CTR,KEYFORMAT=\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed\",URI=\"data:x\"\n"
            ).drm
        )
        val dash = ManifestInspector.inspect("<?xml version=\"1.0\"?><MPD><ContentProtection schemeIdUri=\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed\"/></MPD>")
        assertTrue(dash.isDash && dash.drm)
    }

    @Test
    fun rejectsNonManifests() {
        val info = ManifestInspector.inspect("<html>blocked</html>")
        assertFalse(info.isHls || info.isDash)
    }

    @Test
    fun httpCodesMapToStatuses() {
        assertEquals(StreamStatus.FORBIDDEN, ManifestInspector.statusForHttp(403))
        assertEquals(StreamStatus.FORBIDDEN, ManifestInspector.statusForHttp(451))
        assertEquals(StreamStatus.AUTH_REQUIRED, ManifestInspector.statusForHttp(401))
        assertEquals(StreamStatus.OFFLINE, ManifestInspector.statusForHttp(404))
    }
}
