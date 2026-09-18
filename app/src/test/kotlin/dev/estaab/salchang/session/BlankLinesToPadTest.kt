package dev.estaab.salchang.session

import dev.estaab.salchang.tmuxctl.DEFAULT_HISTORY_LINES
import org.junit.Assert.assertEquals
import org.junit.Test

class BlankLinesToPadTest {
    private fun capture(lines: Int): ByteArray = List(lines) { "line $it" }.joinToString("\n").toByteArray()

    @Test
    fun emptyPaneNeedsFullScreenOfPadding() {
        assertEquals(24, SessionController.blankLinesToPad(ByteArray(0), 0, 24))
    }

    @Test
    fun partiallyFilledScreenWithoutHistory() {
        assertEquals(20, SessionController.blankLinesToPad(capture(4), 0, 24))
    }

    @Test
    fun fullScreenNeedsNoPadding() {
        assertEquals(0, SessionController.blankLinesToPad(capture(24), 0, 24))
    }

    @Test
    fun historyIsAddedToExpectedLineCount() {
        // 100 history lines + 24 rows expected; capture has 110 lines (trailing blanks dropped).
        assertEquals(14, SessionController.blankLinesToPad(capture(110), 100, 24))
    }

    @Test
    fun historyIsCappedAtCaptureLimit() {
        val expected: Int = DEFAULT_HISTORY_LINES + 24
        assertEquals(expected - 10, SessionController.blankLinesToPad(capture(10), DEFAULT_HISTORY_LINES * 5, 24))
    }

    @Test
    fun neverNegative() {
        assertEquals(0, SessionController.blankLinesToPad(capture(50), 0, 24))
        assertEquals(24, SessionController.blankLinesToPad(ByteArray(0), -3, 24))
    }
}
