package dev.estaab.salchang.meta

import java.util.Locale

private const val SECONDS_PER_MINUTE: Long = 60L
private const val SECONDS_PER_HOUR: Long = 60L * SECONDS_PER_MINUTE
private const val SECONDS_PER_DAY: Long = 24L * SECONDS_PER_HOUR
private const val MILLIS_PER_SECOND: Long = 1000L

/** Below this age [formatTimeAgo] says "just now" instead of a second count. */
private const val JUST_NOW_THRESHOLD_SECONDS: Long = 5L

/** Pure text formatters for the Info tab; all take explicit "now" values so they are testable. */
object Formatters {

    /** "just now", "12 s ago", "3 min ago", "2 h ago", "5 d ago"; negative ages count as "just now". */
    fun formatTimeAgo(updatedAtEpochSeconds: Long, nowEpochSeconds: Long): String {
        val age: Long = nowEpochSeconds - updatedAtEpochSeconds
        return when {
            age < JUST_NOW_THRESHOLD_SECONDS -> "just now"
            age < SECONDS_PER_MINUTE -> "$age s ago"
            age < SECONDS_PER_HOUR -> "${age / SECONDS_PER_MINUTE} min ago"
            age < SECONDS_PER_DAY -> "${age / SECONDS_PER_HOUR} h ago"
            else -> "${age / SECONDS_PER_DAY} d ago"
        }
    }

    /** `h:mm:ss` (hours unbounded, no leading zero on hours). Negative values are clamped to 0. */
    fun formatDurationMs(durationMs: Long): String {
        val totalSeconds: Long = maxOf(0L, durationMs) / MILLIS_PER_SECOND
        val hours: Long = totalSeconds / SECONDS_PER_HOUR
        val minutes: Long = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
        val seconds: Long = totalSeconds % SECONDS_PER_MINUTE
        return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    }

    /** "resets in 2h 15m", "resets in 3d 4h", "resets in 45s", or "resets now" once passed. */
    fun formatResetsIn(resetsAtEpochSeconds: Long, nowEpochSeconds: Long): String {
        val remaining: Long = resetsAtEpochSeconds - nowEpochSeconds
        if (remaining <= 0L) return "resets now"
        val days: Long = remaining / SECONDS_PER_DAY
        val hours: Long = (remaining % SECONDS_PER_DAY) / SECONDS_PER_HOUR
        val minutes: Long = (remaining % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
        return when {
            days > 0L -> "resets in ${days}d ${hours}h"
            hours > 0L -> "resets in ${hours}h ${minutes}m"
            minutes > 0L -> "resets in ${minutes}m"
            else -> "resets in ${remaining}s"
        }
    }

    /** `$1.23`; always two decimals. */
    fun formatUsd(amount: Double): String = String.format(Locale.ROOT, "$%.2f", amount)

    /** `12.5%` or `12%` when integral. */
    fun formatPercent(percent: Double): String =
        if (percent == Math.floor(percent)) String.format(Locale.ROOT, "%d%%", percent.toLong())
        else String.format(Locale.ROOT, "%.1f%%", percent)

    /** `1,234,567` with thousands separators. */
    fun formatCount(count: Long): String = String.format(Locale.ROOT, "%,d", count)
}
