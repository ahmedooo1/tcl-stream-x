package fr.ahmad.tclstreamx

import fr.ahmad.tclstreamx.data.epg.XmltvParser
import fr.ahmad.tclstreamx.data.epg.XmltvProgramme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.StringReader

class XmltvParserTest {
    @Test
    fun parsesTimesWithOffsets() {
        // 2026-10-21 20:00 +0200 == 18:00 UTC
        assertEquals(1792605600000L, XmltvParser.parseTime("20261021200000 +0200"))
        assertEquals(1792605600000L, XmltvParser.parseTime("20261021180000 +0000"))
        assertEquals(1792605600000L, XmltvParser.parseTime("202610211800"))
        assertNull(XmltvParser.parseTime("garbage"))
        assertNull(XmltvParser.parseTime("20261341000000"))
    }

    @Test
    fun keepsOnlyWantedChannelsInsideWindow() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="France24.fr"><display-name>France 24</display-name></channel>
              <programme start="20261021180000 +0000" stop="20261021190000 +0000" channel="France24.fr">
                <title lang="fr">Le Journal</title><title lang="en">The News</title>
                <desc lang="fr">Actualité &amp; analyses</desc>
                <category>News</category>
              </programme>
              <programme start="20261021180000 +0000" stop="20261021190000 +0000" channel="Other.xx">
                <title>Ignored</title>
              </programme>
              <programme start="20261030180000 +0000" stop="20261030190000 +0000" channel="France24.fr">
                <title>Too far</title>
              </programme>
            </tv>
        """.trimIndent()
        val parser = KXmlParser()
        parser.setInput(StringReader(xml))
        val out = mutableListOf<XmltvProgramme>()
        val window = XmltvParser.parseTime("20261021000000 +0000")!!
        val n = XmltvParser(setOf("france24.fr"), window, window + 36 * 3600_000L).parse(parser) { out += it }
        assertEquals(1, n)
        assertEquals("Le Journal", out[0].title)
        assertEquals("Actualité & analyses", out[0].description)
        assertEquals("france24.fr", out[0].channel)
    }
}
