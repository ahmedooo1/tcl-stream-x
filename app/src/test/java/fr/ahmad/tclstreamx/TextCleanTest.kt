package fr.ahmad.tclstreamx

import fr.ahmad.tclstreamx.data.xtream.cleanPlot
import fr.ahmad.tclstreamx.ui.screens.initialsOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextCleanTest {
    @Test fun summaryEscapedSeveralTimesReadsAsText() {
        val raw = """Whoever claims the \\\"One Piece\\\" will be King.\\r\\n\\r\\nMonkey D. Luffy"""
        assertEquals("Whoever claims the \"One Piece\" will be King.\n\nMonkey D. Luffy", cleanPlot(raw))
    }

    @Test fun plainSummaryUnchanged() {
        assertEquals("A quiet story.", cleanPlot("  A quiet story. "))
        assertNull(cleanPlot("   "))
    }

    @Test fun initialsSkipTheSourceTag() {
        assertEquals("OP", initialsOf("AR: One Piece"))
        assertEquals("CL", initialsOf("DE-CAM: Clayface"))
        assertEquals("TF", initialsOf("|FR| TF1"))
        assertEquals("BA", initialsOf("Batman"))
    }
}
