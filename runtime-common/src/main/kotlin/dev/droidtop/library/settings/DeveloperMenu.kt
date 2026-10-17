package dev.droidtop.library.settings

import android.content.Context

/**
 * The Developer menu (docs/SPEC.md 7, "Developer menu"; Droidtop/tracker#579; the owner, 2026-10-17: "It's probably
 * worth moving a lot of the advanced stuff (like the containment checker) to a dev menu"). Off until the person taps
 * droidtop's version in Settings, About droidtop, [TAPS] times, as Android's own developer options are found; then a
 * Developer row opens [SCREEN] and the rows that are only for finding faults show where they belong (a plugin's
 * Advanced details, Technical details, logs). Turned off again from the menu itself.
 *
 * One catalog, not a second settings system: a builder adds a developer row only when [on].
 */
object DeveloperMenu {
    const val SCREEN = "developer_settings"
    const val TAPS = 7
    private const val KEY_ON = "droidtop_developer_menu"

    /** The most time between two taps that still counts toward [TAPS]. */
    const val TAP_GAP_MS = 3_000L

    fun on(context: Context): Boolean = CatalogPrefs.prefs(context).getBoolean(KEY_ON, false)

    fun set(context: Context, on: Boolean) {
        CatalogPrefs.prefs(context).edit().putBoolean(KEY_ON, on).apply()
    }

    private val taps = TapCount(TAPS, TAP_GAP_MS)

    /** A tap on the version row: the line to show, after it turns the menu on, or while it counts down. */
    fun versionTapped(context: Context, now: Long = System.currentTimeMillis()): String? {
        if (on(context)) return "The Developer menu is already on"
        return when (val left = taps.tap(now)) {
            0 -> {
                set(context, true)
                "The Developer menu is on: Settings, About droidtop, Developer"
            }
            in 1..3 -> if (left == 1) "1 more tap to turn on the Developer menu" else "$left more taps to turn on the Developer menu"
            else -> null
        }
    }

    /** Counts taps toward [needed]; a gap longer than [gapMs] starts again. Pure apart from its own count. */
    class TapCount(private val needed: Int, private val gapMs: Long) {
        private var count = 0
        private var lastAt = Long.MIN_VALUE

        /** Taps still needed after this one; 0 is the one that completes it (and starts the count again). */
        @Synchronized
        fun tap(now: Long): Int {
            count = if (lastAt != Long.MIN_VALUE && now - lastAt <= gapMs) count + 1 else 1
            lastAt = now
            val left = (needed - count).coerceAtLeast(0)
            if (left == 0) {
                count = 0
                lastAt = Long.MIN_VALUE
            }
            return left
        }
    }
}
