package dev.droidtop.library.settings

import android.content.Context
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Play-time limits (docs/SPEC.md 7f "Play-time limits", Droidtop/tracker#421): a limit per day and one per
 * week, on all play together rather than per game, in minutes; 0 is no limit. They belong to Kid mode, the
 * mode that is handed to a child: play counts, and the limits hold, while Kid is on ([PlayTimeLimiter] in
 * `:app` is the one place that watches).
 */
data class PlayLimits(val dailyMinutes: Int = 0, val weeklyMinutes: Int = 0) {
    val any: Boolean get() = dailyMinutes > 0 || weeklyMinutes > 0
}

/** Everything about play time that needs no Android: the ledger of seconds played per local day, the remaining time and the wording. */
object PlayTime {
    /** The warning comes this long before the limit. */
    const val WARN_BEFORE_SECONDS = 5 * 60L

    /** After the limit the game is told, and closed this long later: time to save. */
    const val GRACE_SECONDS = 60L

    /** The choices the Settings page offers, in minutes; 0 is no limit. */
    val DAILY_CHOICES: List<Int> = listOf(0, 30, 60, 90, 120, 180, 240)
    val WEEKLY_CHOICES: List<Int> = listOf(0, 180, 300, 420, 600, 840)

    /** How many days the ledger keeps: a week and a day of slack. */
    private const val KEEP_DAYS = 14L

    /** "No limit", "30 min", "2 h", "1 h 30 min". */
    fun limitLabel(minutes: Int): String = if (minutes <= 0) "No limit" else duration(minutes * 60L)

    /** "45 min", "2 h", "1 h 05 min"; "0 min" for nothing. */
    fun duration(seconds: Long): String {
        val minutes = (seconds.coerceAtLeast(0L) / 60L)
        return when {
            minutes < 60 -> "$minutes min"
            minutes % 60 == 0L -> "${minutes / 60} h"
            else -> "${minutes / 60} h %02d min".format(minutes % 60)
        }
    }

    // ---- The ledger: epoch day (local) -> seconds played ----

    fun parse(raw: String?): Map<Long, Long> {
        if (raw.isNullOrBlank()) return emptyMap()
        val days = HashMap<Long, Long>()
        for (part in raw.split(',')) {
            val (day, seconds) = part.split(':').takeIf { it.size == 2 } ?: continue
            val d = day.toLongOrNull() ?: continue
            val s = seconds.toLongOrNull()?.takeIf { it > 0 } ?: continue
            days[d] = s
        }
        return days
    }

    fun format(days: Map<Long, Long>): String =
        days.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}" }

    private fun dayOf(epochMs: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()

    /** [days] with the play from [startMs] to [endMs] added, split at local midnights; days older than the ledger keeps are dropped. */
    fun add(days: Map<Long, Long>, startMs: Long, endMs: Long, zone: ZoneId): Map<Long, Long> {
        if (endMs <= startMs) return days
        val result = HashMap(days)
        var from = startMs
        while (from < endMs) {
            val day = dayOf(from, zone)
            val nextMidnight = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val to = minOf(endMs, nextMidnight)
            result[day.toEpochDay()] = (result[day.toEpochDay()] ?: 0L) + (to - from) / 1000L
            from = to
        }
        val oldest = dayOf(endMs, zone).toEpochDay() - KEEP_DAYS
        return result.filterKeys { it >= oldest }.filterValues { it > 0 }
    }

    fun usedToday(days: Map<Long, Long>, nowMs: Long, zone: ZoneId): Long =
        days[dayOf(nowMs, zone).toEpochDay()] ?: 0L

    /** Seconds since the Monday of [nowMs]'s week. */
    fun usedThisWeek(days: Map<Long, Long>, nowMs: Long, zone: ZoneId): Long {
        val today = dayOf(nowMs, zone)
        val monday = today.minusDays((today.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong()).toEpochDay()
        return days.filterKeys { it in monday..today.toEpochDay() }.values.sum()
    }

    /** Seconds left under the tighter of the limits that are set; null when none is. Negative is not returned: over is 0. */
    fun remainingSeconds(limits: PlayLimits, usedToday: Long, usedWeek: Long): Long? {
        val left = listOfNotNull(
            limits.dailyMinutes.takeIf { it > 0 }?.let { it * 60L - usedToday },
            limits.weeklyMinutes.takeIf { it > 0 }?.let { it * 60L - usedWeek },
        )
        return left.minOrNull()?.coerceAtLeast(0L)
    }

    /** The companion's one line: "Today 45 min of 2 h · This week 3 h 10 min of 10 h"; a limit that is not set is left out. */
    fun summary(limits: PlayLimits, usedToday: Long, usedWeek: Long): String = listOfNotNull(
        "Today ${duration(usedToday)}" + (limits.dailyMinutes.takeIf { it > 0 }?.let { " of ${limitLabel(it)}" } ?: ""),
        "This week ${duration(usedWeek)}" + (limits.weeklyMinutes.takeIf { it > 0 }?.let { " of ${limitLabel(it)}" } ?: ""),
    ).joinToString(" · ")
}

/** What the enforcer asks its caller to do on one tick. */
sealed interface PlayStep {
    data object None : PlayStep

    /** The limit is [minutesLeft] minutes away: say so, once. */
    data class Warn(val minutesLeft: Int) : PlayStep

    /** The limit is reached: say the game closes in [graceSeconds] seconds, once. */
    data class TimeUp(val graceSeconds: Long) : PlayStep

    /** The grace is over: close the game. Asked again on every tick until the game is gone. */
    data object Quit : PlayStep
}

/**
 * The limit's state machine for one running game: a warning before the limit, a notice at it, the quit after
 * the grace. Pure, so the order and the once-only rule are tested (PlayTimeTest). A new instance per game
 * session; [step] is given the clock and the seconds remaining ([PlayTime.remainingSeconds], null for no limit).
 */
class PlayEnforcer(
    private val warnBeforeSeconds: Long = PlayTime.WARN_BEFORE_SECONDS,
    private val graceSeconds: Long = PlayTime.GRACE_SECONDS,
) {
    private var warned = false
    private var overSinceMs: Long? = null
    private var toldOver = false

    fun step(nowMs: Long, remainingSeconds: Long?): PlayStep {
        if (remainingSeconds == null || remainingSeconds > warnBeforeSeconds) {
            // No limit, or time was added: start over.
            warned = false
            overSinceMs = null
            toldOver = false
            return PlayStep.None
        }
        if (remainingSeconds > 0) {
            overSinceMs = null
            toldOver = false
            if (warned) return PlayStep.None
            warned = true
            return PlayStep.Warn(((remainingSeconds + 59) / 60).toInt())
        }
        val since = overSinceMs ?: nowMs.also { overSinceMs = it }
        if (!toldOver) {
            toldOver = true
            return PlayStep.TimeUp(graceSeconds)
        }
        return if (nowMs - since >= graceSeconds * 1000L) PlayStep.Quit else PlayStep.None
    }
}

/** The limits and the ledger as stored, in the shell's own prefs file. Reads and writes are small; call them off the main thread. */
object PlayTimePrefs {
    private const val PREFS_NAME = LAUNCHER_PREFS_FILE_NAME
    const val KEY_DAILY = "droidtop_play_limit_daily_min"
    const val KEY_WEEKLY = "droidtop_play_limit_weekly_min"
    private const val KEY_LEDGER = "droidtop_play_ledger"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun limits(context: Context): PlayLimits {
        val p = prefs(context)
        return PlayLimits(p.getInt(KEY_DAILY, 0).coerceAtLeast(0), p.getInt(KEY_WEEKLY, 0).coerceAtLeast(0))
    }

    fun setDaily(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_DAILY, minutes.coerceAtLeast(0)).apply()
    }

    fun setWeekly(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_WEEKLY, minutes.coerceAtLeast(0)).apply()
    }

    fun ledger(context: Context): Map<Long, Long> = PlayTime.parse(prefs(context).getString(KEY_LEDGER, null))

    /** Adds the play from [startMs] to [endMs] to the ledger. */
    @Synchronized
    fun record(context: Context, startMs: Long, endMs: Long, zone: ZoneId = ZoneId.systemDefault()) {
        val updated = PlayTime.add(ledger(context), startMs, endMs, zone)
        prefs(context).edit().putString(KEY_LEDGER, PlayTime.format(updated)).apply()
    }

    /** Today's and this week's seconds played, with the limits. */
    fun snapshot(context: Context, nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Triple<PlayLimits, Long, Long> {
        val days = ledger(context)
        return Triple(limits(context), PlayTime.usedToday(days, nowMs, zone), PlayTime.usedThisWeek(days, nowMs, zone))
    }
}
