package dev.estaab.salchang.meta

import org.junit.Assert.assertEquals
import org.junit.Test

private const val NOW: Long = 1_000_000L

class FormattersTest {
    @Test
    fun timeAgo() {
        assertEquals("just now", Formatters.formatTimeAgo(NOW, NOW))
        assertEquals("just now", Formatters.formatTimeAgo(NOW + 10, NOW))
        assertEquals("just now", Formatters.formatTimeAgo(NOW - 4, NOW))
        assertEquals("5 s ago", Formatters.formatTimeAgo(NOW - 5, NOW))
        assertEquals("59 s ago", Formatters.formatTimeAgo(NOW - 59, NOW))
        assertEquals("1 min ago", Formatters.formatTimeAgo(NOW - 60, NOW))
        assertEquals("59 min ago", Formatters.formatTimeAgo(NOW - 3599, NOW))
        assertEquals("1 h ago", Formatters.formatTimeAgo(NOW - 3600, NOW))
        assertEquals("23 h ago", Formatters.formatTimeAgo(NOW - 86399, NOW))
        assertEquals("1 d ago", Formatters.formatTimeAgo(NOW - 86400, NOW))
        assertEquals("3 d ago", Formatters.formatTimeAgo(NOW - 3 * 86400 - 100, NOW))
    }

    @Test
    fun durationMs() {
        assertEquals("0:00:00", Formatters.formatDurationMs(0L))
        assertEquals("0:00:00", Formatters.formatDurationMs(-5L))
        assertEquals("0:00:59", Formatters.formatDurationMs(59_999L))
        assertEquals("0:01:00", Formatters.formatDurationMs(60_000L))
        assertEquals("1:02:05", Formatters.formatDurationMs(3_725_000L))
        assertEquals("25:00:00", Formatters.formatDurationMs(25L * 3600L * 1000L))
    }

    @Test
    fun resetsIn() {
        assertEquals("resets now", Formatters.formatResetsIn(NOW, NOW))
        assertEquals("resets now", Formatters.formatResetsIn(NOW - 1, NOW))
        assertEquals("resets in 45s", Formatters.formatResetsIn(NOW + 45, NOW))
        assertEquals("resets in 1m", Formatters.formatResetsIn(NOW + 60, NOW))
        assertEquals("resets in 2h 15m", Formatters.formatResetsIn(NOW + 2 * 3600 + 15 * 60 + 9, NOW))
        assertEquals("resets in 3d 4h", Formatters.formatResetsIn(NOW + 3 * 86400 + 4 * 3600 + 59 * 60, NOW))
    }

    @Test
    fun usdPercentCount() {
        assertEquals("$1.23", Formatters.formatUsd(1.2345))
        assertEquals("$0.00", Formatters.formatUsd(0.0))
        assertEquals("12%", Formatters.formatPercent(12.0))
        assertEquals("12.5%", Formatters.formatPercent(12.5))
        assertEquals("30.3%", Formatters.formatPercent(30.25))
        assertEquals("1,234,567", Formatters.formatCount(1_234_567L))
        assertEquals("0", Formatters.formatCount(0L))
    }
}
