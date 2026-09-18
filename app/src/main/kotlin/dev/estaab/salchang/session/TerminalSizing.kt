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
     * Largest text size in `[minTextSize, maxTextSize]` whose glyph width (from [charWidthAt],
     * monotonic in the text size) lets [paneCols] columns fit into [viewWidthPx]; [minTextSize]
     * when even that does not fit (the view then scrolls horizontally). Units are whatever
     * [charWidthAt] expects (the view uses pixels).
     */
    fun fitTextSize(
        paneCols: Int,
        viewWidthPx: Int,
        minTextSize: Int,
        maxTextSize: Int,
        charWidthAt: (textSize: Int) -> Float,
    ): Int {
        require(minTextSize in 1..maxTextSize) { "bad text size range $minTextSize..$maxTextSize" }
        if (paneCols <= 0 || viewWidthPx <= 0) return maxTextSize
        var low: Int = minTextSize
        var high: Int = maxTextSize
        var best: Int = minTextSize
        while (low <= high) {
            val mid: Int = (low + high) / 2
            if (paneCols * charWidthAt(mid) <= viewWidthPx) {
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
        require(charWidthPx > 0f && lineSpacingPx > 0) { "bad font metrics $charWidthPx / $lineSpacingPx" }
        val cols: Int = (viewWidthPx / charWidthPx).toInt()
        val rows: Int = (viewHeightPx - lineSpacingAndAscentPx) / lineSpacingPx
        return Grid(maxOf(MIN_CLIENT_COLS, cols), maxOf(MIN_CLIENT_ROWS, rows))
    }
}
