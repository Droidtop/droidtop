package dev.droidtop.library.format

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * The one formatter for values a person reads (docs/SPEC.md 7k, "Values"; Droidtop/tracker#485 slice F2a):
 * sizes, play and remaining times, percentages, counts, clock times, "how long ago" and the plain state
 * words. Every surface (Gaming, Desktop, the companion, Standard's Preference rows, a plugin's typed
 * values) formats through here, so "1.2 GB" and "2 h 5 min" read the same everywhere and a change to
 * one rule reaches every screen. Pure and locale-stable (digits use [Locale.ROOT], so a decimal point
 * never turns into a comma in the middle of English copy), for the tests.
 *
 * It lives in :runtime-common rather than :ui-shared because :library-core formats too (store sizes,
 * free space) and cannot depend on a Compose module.
 */
object ValueFormat {

    private const val KB = 1_000L
    private const val MB = 1_000_000L
    private const val GB = 1_000_000_000L
    private const val TB = 1_000_000_000_000L
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /**
     * A size in decimal units, as storage is sold and as Android's own Settings counts it: whole KB and MB
     * (never "0 MB" for something that exists), one decimal for GB and TB. "12 KB", "420 MB", "1.5 GB".
     */
    fun size(bytes: Long): String = when {
        bytes <= 0L -> "0 KB"
        bytes >= TB -> String.format(Locale.ROOT, "%.1f TB", bytes / TB.toDouble())
        bytes >= GB -> String.format(Locale.ROOT, "%.1f GB", bytes / GB.toDouble())
        bytes >= MB -> "${bytes / MB} MB"
        else -> "${(bytes / KB).coerceAtLeast(1)} KB"
    }

    /** A size, or blank when nothing is known (a store that names no size). */
    fun sizeOrBlank(bytes: Long): String = if (bytes <= 0L) "" else size(bytes)

    /** "12.3 GB free of 128.0 GB": the room on a volume. */
    fun freeOf(freeBytes: Long, totalBytes: Long): String = "${size(freeBytes)} free of ${size(totalBytes)}"

    /**
     * A duration as a value column shows it: "2 h 5 min", "2 h", "45 min", "Under a minute". Zero or
     * less is [none] (the caller says what absence means: "Not played yet", or nothing).
     */
    fun duration(seconds: Long, none: String = ""): String {
        if (seconds <= 0) return none
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return when {
            hours > 0 && minutes > 0 -> "$hours h $minutes min"
            hours > 0 -> "$hours h"
            minutes > 0 -> "$minutes min"
            else -> "Under a minute"
        }
    }

    /** The same duration read aloud: a screen reader says "2 hours 5 minutes", never "2 h 5 min". */
    fun durationSpoken(seconds: Long, none: String = ""): String {
        if (seconds <= 0) return none
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        fun unit(n: Long, one: String) = if (n == 1L) "1 $one" else "$n ${one}s"
        return when {
            hours > 0 && minutes > 0 -> "${unit(hours, "hour")} ${unit(minutes, "minute")}"
            hours > 0 -> unit(hours, "hour")
            minutes > 0 -> unit(minutes, "minute")
            else -> "Under a minute"
        }
    }

    /** A download's remaining time: "About 8 min left", "About 2 h 15 min left", "Under a minute left"; blank when unknown. */
    fun remaining(seconds: Long): String = when {
        seconds <= 0 -> ""
        seconds < 60 -> "Under a minute left"
        else -> "About ${duration(roundRemaining(seconds))} left"
    }

    // An estimate is never more exact than the rate behind it: whole minutes under an hour, quarter hours above.
    private fun roundRemaining(seconds: Long): Long =
        if (seconds < 3600) (seconds + 30) / 60 * 60 else (seconds + 450) / 900 * 900

    /** "45%": a fraction 0 to 1, clamped. */
    fun percent(fraction: Float): String = "${(fraction.coerceIn(0f, 1f) * 100).toInt()}%"

    /** "1,234": a count with grouping. */
    fun count(n: Long): String = String.format(Locale.US, "%,d", n)

    /** "14:02" or "2:02 PM": a clock time in the device's 12 or 24 hour setting ([is24Hour], from DateFormat). */
    fun time(epochMs: Long, is24Hour: Boolean, zone: TimeZone = TimeZone.getDefault()): String {
        val cal = Calendar.getInstance(zone).apply { timeInMillis = epochMs }
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val m = cal.get(Calendar.MINUTE)
        return if (is24Hour) {
            String.format(Locale.ROOT, "%02d:%02d", h, m)
        } else {
            val h12 = if (h % 12 == 0) 12 else h % 12
            String.format(Locale.ROOT, "%d:%02d %s", h12, m, if (h < 12) "AM" else "PM")
        }
    }

    /** "today", "yesterday", "5 days ago", "3 weeks ago", "4 months ago", "over a year ago": how long ago, by days. */
    fun daysAgo(now: Long, then: Long): String {
        val days = ((now - then) / DAY_MS).coerceAtLeast(0)
        return when {
            days < 1 -> "today"
            days == 1L -> "yesterday"
            days < 14 -> "$days days ago"
            days < 60 -> "${days / 7} weeks ago"
            days < 365 -> "${days / 30} months ago"
            else -> "over a year ago"
        }
    }

    /** "Just now", "5 min ago", "2 h ago", then [daysAgo] past a day: a refresh or check time. */
    fun ago(now: Long, then: Long): String {
        val seconds = ((now - then) / 1000).coerceAtLeast(0)
        return when {
            seconds < 60 -> "Just now"
            seconds < 3600 -> "${seconds / 60} min ago"
            seconds < 86_400 -> "${seconds / 3600} h ago"
            else -> daysAgo(now, then).replaceFirstChar { it.uppercase() }
        }
    }

    /**
     * The plain words for a state value (docs/plugin-api.md, the `typed` value kind `state`): one set
     * every surface and every plugin uses, so a plugin's "running" reads as droidtop's own "Running".
     */
    enum class State(val label: String) {
        ON("On"),
        OFF("Off"),
        RUNNING("Running"),
        STOPPED("Stopped"),
        INSTALLED("Installed"),
        NOT_INSTALLED("Not installed"),
        CONNECTED("Connected"),
        DISCONNECTED("Disconnected"),
        WAITING("Waiting"),
        FAILED("Failed"),
        UNKNOWN("Unknown"),
        ;

        companion object {
            /** A wire name ("not_installed") to its state; anything unknown reads [UNKNOWN]. */
            fun of(wire: String?): State = entries.firstOrNull { it.name.equals(wire?.trim(), ignoreCase = true) } ?: UNKNOWN
        }
    }
}
