package fr.ahmad.tclstreamx

import fr.ahmad.tclstreamx.data.catalog.BuiltInCatalog
import fr.ahmad.tclstreamx.data.db.StreamStatus
import fr.ahmad.tclstreamx.data.sports.SportsRepository
import fr.ahmad.tclstreamx.data.vod.VodRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class MiscTest {
    @Test
    fun espnDatesWithoutSecondsParse() {
        assertEquals(Instant.parse("2026-10-21T19:00:00Z"), SportsRepository.parseInstant("2026-10-21T19:00Z"))
        assertEquals(Instant.parse("2026-10-21T19:00:30Z"), SportsRepository.parseInstant("2026-10-21T19:00:30Z"))
        assertNull(SportsRepository.parseInstant("tomorrow"))
    }

    @Test
    fun catalogStatusesMap() {
        assertEquals(StreamStatus.ONLINE, BuiltInCatalog.statusOf("ok"))
        assertEquals(StreamStatus.FORBIDDEN, BuiltInCatalog.statusOf("forbidden_or_geo"))
        assertEquals(StreamStatus.UNKNOWN, BuiltInCatalog.statusOf("unchecked"))
        assertEquals(StreamStatus.OFFLINE, BuiltInCatalog.statusOf("network_error"))
    }

    @Test
    fun archiveQueriesCannotInjectSyntax() {
        assertEquals("abc OR collection", VodRepository.sanitize("abc) OR (collection:*"))
        assertEquals("*", VodRepository.sanitize("()[]"))
    }
}
