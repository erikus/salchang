package dev.estaab.salchang.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A monospace font whose glyphs are 0.6 x text size wide, like most terminal fonts. */
private const val CHAR_WIDTH_RATIO: Float = 0.6f

private const val MIN_SIZE: Int = 5
private const val MAX_SIZE: Int = 40

private fun charWidthAt(textSize: Int): Float = textSize * CHAR_WIDTH_RATIO

class TerminalSizingTest {
    @Test
    fun fitPicksLargestSizeThatFits() {
        // 80 cols in 1080 px: 1080 / 80 = 13.5 px per cell -> text size 22 (13.2 px) fits, 23 (13.8) does not.
        val size: Int = TerminalSizing.fitTextSize(80, 1080, MIN_SIZE, MAX_SIZE, ::charWidthAt)
        assertEquals(22, size)
        assertTrue(80 * charWidthAt(size) <= 1080f)
        assertTrue(80 * charWidthAt(size + 1) > 1080f)
    }

    @Test
    fun fitIsCappedAtMax() {
        assertEquals(MAX_SIZE, TerminalSizing.fitTextSize(10, 10_000, MIN_SIZE, MAX_SIZE, ::charWidthAt))
    }

    @Test
    fun fitReturnsMinWhenNothingFits() {
        // 300 cols in 200 px: even the minimum is 900 px wide -> min, caller scrolls horizontally.
        assertEquals(MIN_SIZE, TerminalSizing.fitTextSize(300, 200, MIN_SIZE, MAX_SIZE, ::charWidthAt))
    }

    @Test
    fun fitWithUnknownViewportUsesMax() {
        assertEquals(MAX_SIZE, TerminalSizing.fitTextSize(80, 0, MIN_SIZE, MAX_SIZE, ::charWidthAt))
        assertEquals(MAX_SIZE, TerminalSizing.fitTextSize(0, 1080, MIN_SIZE, MAX_SIZE, ::charWidthAt))
    }

    @Test
    fun fitIsExactAtBoundary() {
        // 100 cols * 6 px = 600 px exactly at size 10.
        assertEquals(10, TerminalSizing.fitTextSize(100, 600, MIN_SIZE, MAX_SIZE, ::charWidthAt))
    }

    @Test
    fun gridMatchesTerminalViewFormula() {
        val grid: TerminalSizing.Grid = TerminalSizing.gridFor(
            viewWidthPx = 1080,
            viewHeightPx = 1800,
            charWidthPx = 13.2f,
            lineSpacingPx = 26,
            lineSpacingAndAscentPx = 5,
        )
        assertEquals((1080 / 13.2f).toInt(), grid.cols)
        assertEquals((1800 - 5) / 26, grid.rows)
    }

    @Test
    fun gridIsClampedToMinimums() {
        val grid: TerminalSizing.Grid = TerminalSizing.gridFor(
            viewWidthPx = 10,
            viewHeightPx = 10,
            charWidthPx = 13.2f,
            lineSpacingPx = 26,
            lineSpacingAndAscentPx = 5,
        )
        assertEquals(TerminalSizing.MIN_CLIENT_COLS, grid.cols)
        assertEquals(TerminalSizing.MIN_CLIENT_ROWS, grid.rows)
    }
}
