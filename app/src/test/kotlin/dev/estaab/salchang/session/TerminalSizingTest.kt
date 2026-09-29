package dev.estaab.salchang.session

import dev.estaab.salchang.session.TerminalSizing.CellMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil

/** A monospace font whose glyphs are 0.6 x text size wide and lines 1.2 x tall, like most terminal fonts. */
private const val CHAR_WIDTH_RATIO: Float = 0.6f
private const val LINE_SPACING_RATIO: Float = 1.2f
private const val ASCENT_RATIO: Float = 0.9f

private const val MIN_SIZE: Int = 5
private const val MAX_SIZE: Int = 40

private fun charWidthAt(textSize: Int): Float = textSize * CHAR_WIDTH_RATIO

private fun metricsAt(textSize: Int): CellMetrics {
    val lineSpacing: Int = ceil(textSize * LINE_SPACING_RATIO).toInt()
    val ascent: Int = ceil(-textSize * ASCENT_RATIO).toInt()
    return CellMetrics(charWidthAt(textSize), lineSpacing, lineSpacing + ascent)
}

/** Fits [cols] x [rows] into a view; `rows = 0` (or `viewHeightPx = 0`) leaves the height unconstrained. */
private fun fit(cols: Int, rows: Int, viewWidthPx: Int, viewHeightPx: Int): Int =
    TerminalSizing.fitTextSize(cols, rows, viewWidthPx, viewHeightPx, MIN_SIZE, MAX_SIZE, ::metricsAt)

class TerminalSizingTest {
    @Test
    fun fitPicksLargestSizeThatFitsWidth() {
        // 80 cols in 1080 px: 1080 / 80 = 13.5 px per cell -> text size 22 (13.2 px) fits, 23 (13.8) does not.
        val size: Int = fit(80, 0, 1080, 0)
        assertEquals(22, size)
        assertTrue(80 * charWidthAt(size) <= 1080f)
        assertTrue(80 * charWidthAt(size + 1) > 1080f)
    }

    @Test
    fun fitIsCappedAtMax() {
        assertEquals(MAX_SIZE, fit(10, 10, 10_000, 10_000))
    }

    @Test
    fun fitReturnsMinWhenNothingFits() {
        // 300 cols in 200 px: even the minimum is 900 px wide -> min, caller scrolls horizontally.
        assertEquals(MIN_SIZE, fit(300, 0, 200, 0))
        // 200 rows in 100 px: same for the height.
        assertEquals(MIN_SIZE, fit(10, 200, 10_000, 100))
    }

    @Test
    fun fitWithUnknownViewportUsesMax() {
        assertEquals(MAX_SIZE, fit(80, 24, 0, 0))
        assertEquals(MAX_SIZE, fit(0, 0, 1080, 1800))
    }

    @Test
    fun fitIsExactAtBoundary() {
        // 100 cols * 6 px = 600 px exactly at size 10.
        assertEquals(10, fit(100, 0, 600, 0))
        // 50 rows * 12 px + (12 - 9) px ascent offset = 603 px exactly at size 10.
        assertEquals(603, metricsAt(10).heightOf(50))
        assertEquals(10, fit(1, 50, 10_000, 603))
        assertEquals(9, fit(1, 50, 10_000, 602))
    }

    @Test
    fun fitHonoursTheTighterDimension() {
        // Width alone allows 22 (see above); 43 rows at 22 need 43 * 27 + 8 = 1169 px, more than the
        // 1000 px view, so the rows decide: 19 -> 43 * 23 + 6 = 995 fits, 20 -> 43 * 24 + 6 = 1038 does not.
        assertEquals(1169, metricsAt(22).heightOf(43))
        assertEquals(995, metricsAt(19).heightOf(43))
        assertEquals(1038, metricsAt(20).heightOf(43))
        assertEquals(19, fit(80, 43, 1080, 1000))
        // Every row of the fitted grid is visible: the view can hold at least paneRows rows.
        assertTrue(metricsAt(19).rowsIn(1000) >= 43)
        assertTrue(metricsAt(20).rowsIn(1000) < 43)
    }

    @Test
    fun fitNeverShrinksBelowWhatFitsBothWays() {
        // A narrow pane (few cols) whose rows exceed the view: the size is set by the rows, not the cols.
        val size: Int = fit(30, 60, 1080, 1200)
        assertTrue(30 * charWidthAt(size) <= 1080f)
        assertTrue(metricsAt(size).heightOf(60) <= 1200)
        assertTrue(metricsAt(size + 1).heightOf(60) > 1200)
    }

    @Test
    fun rowsInInvertsHeightOf() {
        for (size in MIN_SIZE..MAX_SIZE) {
            val metrics: CellMetrics = metricsAt(size)
            for (rows in 1..100) {
                assertEquals(rows, metrics.rowsIn(metrics.heightOf(rows)))
                assertEquals(rows - 1, metrics.rowsIn(metrics.heightOf(rows) - 1))
            }
        }
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
