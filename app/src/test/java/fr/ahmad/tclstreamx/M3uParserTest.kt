package fr.ahmad.tclstreamx

import fr.ahmad.tclstreamx.data.m3u.M3uEntry
import fr.ahmad.tclstreamx.data.m3u.M3uHeader
import fr.ahmad.tclstreamx.data.m3u.M3uParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Reader
import java.io.StringReader

class M3uParserTest {

    private fun parse(text: String): Triple<List<M3uEntry>, List<M3uHeader>, fr.ahmad.tclstreamx.data.m3u.M3uStats> {
        val entries = mutableListOf<M3uEntry>()
        val headers = mutableListOf<M3uHeader>()
        val stats = M3uParser().parse(StringReader(text), onHeader = { headers += it }) { entries += it }
        return Triple(entries, headers, stats)
    }

    @Test
    fun parsesAttributesWithCommasInsideQuotes() {
        val (e, _, _) = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="France24.fr@French" tvg-logo="https://x/logo.png" group-title="News, FR",France 24 (1080p)
            https://live.france24.com/hls/live/master.m3u8
            """.trimIndent()
        )
        assertEquals(1, e.size)
        assertEquals("France 24 (1080p)", e[0].name)
        assertEquals("News, FR", e[0].group)
        assertEquals("France24.fr@French", e[0].tvgId)
        assertEquals("https://x/logo.png", e[0].logo)
    }

    @Test
    fun readsHeaderGuideUrls() {
        val (_, h, _) = parse("#EXTM3U url-tvg=\"https://a/guide.xml.gz,https://b/g.xml\" x-tvg-url=\"https://a/guide.xml.gz\"\n")
        assertEquals(listOf("https://a/guide.xml.gz", "https://b/g.xml"), h.single().epgUrls)
    }

    @Test
    fun appliesVlcOptionsExtGrpAndPipeHeaders() {
        val (e, _, _) = parse(
            """
            #EXTM3U
            #EXTINF:-1,Channel A
            #EXTGRP:Sport
            #EXTVLCOPT:http-user-agent=MyUA/1.0
            #EXTVLCOPT:http-referrer=https://ref.example/
            https://a.example/live.m3u8
            #EXTINF:-1,Channel B
            https://b.example/live.m3u8|User-Agent=Other&Referer=https://r.example/
            """.trimIndent()
        )
        assertEquals("Sport", e[0].group)
        assertEquals("MyUA/1.0", e[0].userAgent)
        assertEquals("https://ref.example/", e[0].referrer)
        assertEquals("https://b.example/live.m3u8", e[1].url)
        assertEquals("Other", e[1].userAgent)
        assertEquals("https://r.example/", e[1].referrer)
    }

    @Test
    fun flagsDrmDeclaredByKodiProps() {
        val (e, _, _) = parse(
            """
            #EXTINF:-1,Protected
            #KODIPROP:inputstream.adaptive.license_type=com.widevine.alpha
            #KODIPROP:inputstream.adaptive.license_key=https://lic.example/
            https://p.example/manifest.mpd
            """.trimIndent()
        )
        assertTrue(e.single().drm)
    }

    @Test
    fun skipsUnsupportedSchemesAndCountsMalformed() {
        val (e, _, stats) = parse(
            """
            #EXTM3U
            #EXTINF:-1,Multicast
            udp://@239.0.0.1:1234
            #EXTINF:-1,Rtmp
            rtmp://x/live
            #EXTINF:-1,Dangling
            #EXTINF:-1,Good
            http://ok.example/stream.ts
            """.trimIndent()
        )
        assertEquals(listOf("Good"), e.map { it.name })
        assertEquals(2, stats.skippedUnsupported)
        assertEquals(1, stats.skippedMalformed)
    }

    @Test
    fun handlesBomPlainUrlsAndMissingTitle() {
        val (e, _, _) = parse("﻿#EXTM3U\nhttps://cdn.example/path/channel.m3u8?token=1\n#EXTINF:-1 tvg-name=\"Named\",\nhttps://x.example/a.m3u8\n")
        assertEquals("channel.m3u8", e[0].name)
        assertEquals("Named", e[1].name)
        assertNull(e[0].tvgId)
    }

    @Test
    fun capsHostileLengths() {
        val longName = "A".repeat(10_000)
        val (e, _, _) = parse("#EXTINF:-1,$longName\nhttps://x.example/a.m3u8\n")
        assertEquals(M3uParser.MAX_NAME, e.single().name.length)
    }

    @Test
    fun normalizesAccentsAndArabicDiacritics() {
        assertEquals("tele liban", M3uParser.normalize("  Télé   Liban "))
        assertEquals("الجزيرة", M3uParser.normalize("الجَزِيرَة"))
        assertEquals(M3uParser.channelKey(null, "Télé Liban", "News"), M3uParser.channelKey(null, "tele liban", "news"))
        assertFalse(M3uParser.channelKey("A.fr", "x", "") == M3uParser.channelKey("B.fr", "x", ""))
    }

    /** 200k entries streamed without keeping them in memory: must stay fast on a TV-class CPU. */
    @Test
    fun streamsVeryLargePlaylists() {
        val count = 200_000
        val reader = object : Reader() {
            private var i = 0
            private var buf = ""
            private var pos = 0
            override fun read(cbuf: CharArray, off: Int, len: Int): Int {
                if (pos >= buf.length) {
                    if (i >= count) return -1
                    buf = "#EXTINF:-1 tvg-id=\"c$i.xx\" group-title=\"G${i % 50}\",Channel $i\nhttp://h.example/live/$i.ts\n"
                    pos = 0
                    i++
                }
                val n = minOf(len, buf.length - pos)
                buf.toCharArray(cbuf, off, pos, pos + n)
                pos += n
                return n
            }
            override fun close() = Unit
        }
        var seen = 0
        val start = System.nanoTime()
        val stats = M3uParser().parse(reader) { seen++ }
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals(count, seen)
        assertEquals(count, stats.entries)
        assertTrue("parsing took $ms ms", ms < 10_000)
    }

    private fun entry(name: String, url: String, group: String = "") =
        M3uEntry(name, url, null, group, null, null, null, null, null, false)

    @Test
    fun splitsMixedPlaylistsIntoSections() {
        val k = fr.ahmad.tclstreamx.data.m3u.ContentKindCodes
        assertEquals(k.SERIES, M3uParser.classify(entry("Breaking Bad S02 E05", "http://h/x/1.mkv", "US")))
        assertEquals(k.SERIES, M3uParser.classify(entry("باب الحارة الحلقة 12", "http://h/v/9.mp4")))
        assertEquals(k.SERIES, M3uParser.classify(entry("Anything", "http://h:8080/series/u/p/77.mkv")))
        assertEquals(k.MOVIE, M3uParser.classify(entry("Dune", "http://h:8080/movie/u/p/123.mkv")))
        assertEquals(k.MOVIE, M3uParser.classify(entry("Inception", "http://h/m/2.ts", "Films Action")))
        assertEquals(k.MOVIE, M3uParser.classify(entry("Clip", "http://h/m/2.mp4")))
        assertEquals(k.LIVE, M3uParser.classify(entry("beIN Serie A", "http://h/live/1.ts", "Serie A")))
        assertEquals(k.LIVE, M3uParser.classify(entry("Al Jazeera", "https://x/live.m3u8", "News")))
    }

    @Test
    fun groupsEpisodesByShow() {
        assertEquals("Breaking Bad", M3uParser.seriesShow("Breaking Bad S02 E05 - Peekaboo"))
        assertEquals("The Office", M3uParser.seriesShow("The Office 3x07"))
        assertEquals("باب الحارة", M3uParser.seriesShow("باب الحارة الحلقة 12"))
        assertEquals("Movie Title", M3uParser.seriesShow("Movie Title"))
    }
}
