package dev.droidtop.app

import android.os.SystemClock

/**
 * Whether the Gaming shell coming back to the front is the person returning from a running game (docs/SPEC.md 7f,
 * "Sleep and return to game"; Droidtop/tracker#86). HOME does not run droidtop's home activity again when the shell's
 * task is already there: Android brings that task to the front and the shell is only resumed, with no new intent
 * (rig, BlueStacks, 2026-10-28: no trampoline, no deep link, the Quick Menu never asked for). So the return is decided
 * on resume: the shell stopped while a game ran, and nothing of droidtop's own but its game screens came up since. A
 * screen going off stops the shell too, so a wake with the shell in front is the same return.
 */
object ShellReturn {
    @Volatile
    private var otherScreenAtMs = 0L

    /** A droidtop screen that is neither the shell, a parked surface nor a game screen came to the front. */
    fun otherScreenResumed() {
        otherScreenAtMs = SystemClock.elapsedRealtime()
    }

    /** The time of the last [otherScreenResumed], for [returnsToGame]. */
    val otherScreenAt: Long get() = otherScreenAtMs

    /**
     * True when the shell, stopped at [stoppedAtMs] (null: it did not stop) with a game running then, resumes while
     * that game still runs ([gameRunning]) and no other droidtop screen came up after it stopped ([otherScreenAtMs]):
     * the person is coming back from the game, by HOME, Recents or a wake. Pure.
     */
    fun returnsToGame(stoppedAtMs: Long?, gameRunning: Boolean, otherScreenAtMs: Long): Boolean =
        stoppedAtMs != null && gameRunning && otherScreenAtMs < stoppedAtMs
}
