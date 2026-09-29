package dev.estaab.salchang.session

/**
 * Pure grid/text-size arithmetic shared by the session controller and the terminal view.
 * Pixel measurements are injected so this is unit-testable without Android.
 */
object TerminalSizing {
    /** Client size reported to tmux before the terminal view has been laid out. */
    const val DEFAULT_CLIENT_COLS: Int = 80
    const val DEFAULT_CLIENT_ROWS: Int = 24

    /** tmux refuses absurdly small clients; also keeps a keyboard-covered view usable. */
    const val MIN_CLIENT_COLS: Int = 20
    const val MIN_CLIENT_ROWS: Int = 4

    /** Auto-fit range for the terminal font, in sp. */
    const val MIN_TEXT_SIZE_SP: Int = 5
    const val MAX_TEXT_SIZE_SP: Int = 22

    /** Font size the phone's own client size (`refresh-client -C`) is computed for, in sp. */
    const val PREFERRED_TEXT_SIZE_SP: Int = 12

    data class Grid(val cols: Int, val rows: Int)

    /**
     * Cell metrics of the monospace font at one text size, as `TerminalRenderer` computes them:
     * [charWidth] is the advance of `X`, [lineSpacing] the (rounded up) font spacing and
     * [lineSpacingAndAscent] that spacing plus the (negative) ascent, i.e. the top offset of the
     * first row. A grid of `rows` rows needs `rows * lineSpacing + lineSpacingAndAscent` pixels.
     */
    data class CellMetrics(val charWidth: Float, val lineSpacing: Int, val lineSpacingAndAscent: Int) {
        init {
            require(charWidth > 0f && lineSpacing > 0) { "bad font metrics $charWidth / $lineSpacing" }
        }

        /** Pixel height of [rows] rows drawn with these metrics, the way `TerminalRenderer` lays them out. */
        fun heightOf(rows: Int): Int = rows * lineSpacing + lineSpacingAndAscent

        /** Rows that fit into [viewHeightPx], the formula `TerminalView.updateSize()` uses. */
        fun rowsIn(viewHeightPx: Int): Int = (viewHeightPx - lineSpacingAndAscent) / lineSpacing
    }

    /**
     * Largest text size in `[minTextSize, maxTextSize]` at which the whole [paneCols] x [paneRows]
     * grid fits into a [viewWidthPx] x [viewHeightPx] view; [minTextSize] when even that does not fit
     * (the view then scrolls horizontally and/or shows the bottom of the pane). [metricsAt] gives
     * the cell metrics at a text size (both dimensions monotonic in the text size); units are
     * whatever it expects (the view uses pixels). A dimension whose pane or view size is unknown
     * (`<= 0`) is not constrained.
     */
    fun fitTextSize(
        paneCols: Int,
        paneRows: Int,
        viewWidthPx: Int,
        viewHeightPx: Int,
        minTextSize: Int,
        maxTextSize: Int,
        metricsAt: (textSize: Int) -> CellMetrics,
    ): Int {
        require(minTextSize in 1..maxTextSize) { "bad text size range $minTextSize..$maxTextSize" }
        val fitCols: Boolean = paneCols > 0 && viewWidthPx > 0
        val fitRows: Boolean = paneRows > 0 && viewHeightPx > 0
        if (!fitCols && !fitRows) return maxTextSize
        var low: Int = minTextSize
        var high: Int = maxTextSize
        var best: Int = minTextSize
        while (low <= high) {
            val mid: Int = (low + high) / 2
            val metrics: CellMetrics = metricsAt(mid)
            val fits: Boolean = (!fitCols || paneCols * metrics.charWidth <= viewWidthPx) &&
                (!fitRows || metrics.heightOf(paneRows) <= viewHeightPx)
            if (fits) {
                best = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return best
    }

    /**
     * Columns/rows that fit a view of the given pixel size, using the same formula as
     * `TerminalView.updateSize()` (rows leave room for the first line's ascent). Clamped to
     * [MIN_CLIENT_COLS] x [MIN_CLIENT_ROWS].
     */
    fun gridFor(
        viewWidthPx: Int,
        viewHeightPx: Int,
        charWidthPx: Float,
        lineSpacingPx: Int,
        lineSpacingAndAscentPx: Int,
    ): Grid {
        val metrics = CellMetrics(charWidthPx, lineSpacingPx, lineSpacingAndAscentPx)
        val cols: Int = (viewWidthPx / metrics.charWidth).toInt()
        val rows: Int = metrics.rowsIn(viewHeightPx)
        return Grid(maxOf(MIN_CLIENT_COLS, cols), maxOf(MIN_CLIENT_ROWS, rows))
    }
}
