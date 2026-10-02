package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The launch watchdog's decision (Droidtop/tracker#270, #271, #168): what it claims and what it never does. */
class LaunchWatchdogTest {
    private fun look(
        elapsedMs: Long,
        shellCameBack: Boolean = false,
        notResponding: Boolean = false,
        taskListed: Boolean? = null,
    ) = LaunchWatchPolicy.judge(WatchObservation(elapsedMs, shellCameBack, notResponding, taskListed))

    @Test
    fun aQuietLaunchIsJustKeptUnderWatch() {
        assertEquals(WatchVerdict.Keep, look(3_000))
        assertEquals(WatchVerdict.Keep, look(60_000))
    }

    @Test
    fun anAppAndroidCallsNotRespondingIsReportedAtOnce() {
        assertEquals(WatchVerdict.Trouble(LaunchTrouble.NOT_RESPONDING), look(3_000, notResponding = true))
        assertEquals(WatchVerdict.Trouble(LaunchTrouble.NOT_RESPONDING), look(40_000, notResponding = true, taskListed = true))
    }

    @Test
    fun theShellComingBackWithinSecondsOfALaunchMeansTheAppDidNotStay() {
        assertEquals(WatchVerdict.Trouble(LaunchTrouble.EXITED_AT_ONCE), look(3_000, shellCameBack = true))
        assertEquals(WatchVerdict.Trouble(LaunchTrouble.EXITED_AT_ONCE), look(6_000, shellCameBack = true, taskListed = false))
    }

    @Test
    fun theShellComingBackWhileTheAppIsStillOpenIsAPersonReturningNotAProblem() {
        assertEquals(WatchVerdict.Stop, look(6_000, shellCameBack = true, taskListed = true))
    }

    @Test
    fun theShellComingBackLongAfterTheLaunchIsAPersonReturning() {
        assertEquals(WatchVerdict.Stop, look(LaunchWatchPolicy.EXIT_WINDOW_MS + 1, shellCameBack = true))
    }

    @Test
    fun anAppMissingFromTheTaskListIsGivenTimeToAppearBeforeItCounts() {
        assertEquals(WatchVerdict.Keep, look(LaunchWatchPolicy.APPEAR_GRACE_MS - 1, taskListed = false))
        assertEquals(
            WatchVerdict.Trouble(LaunchTrouble.GONE_WHILE_AWAY),
            look(LaunchWatchPolicy.APPEAR_GRACE_MS, taskListed = false),
        )
    }

    @Test
    fun anAppThatIsListedAndHasSettledIsStillWatchedForNotResponding() {
        assertEquals(WatchVerdict.Keep, look(LaunchWatchPolicy.SETTLED_MS - 1, taskListed = true))
        assertEquals(WatchVerdict.Settled, look(LaunchWatchPolicy.SETTLED_MS, taskListed = true))
        // After settling the task list is no longer read (taskListed null); an ANR a key press causes later still counts.
        assertEquals(WatchVerdict.Keep, look(44_000))
        assertEquals(WatchVerdict.Trouble(LaunchTrouble.NOT_RESPONDING), look(44_000, notResponding = true))
        assertEquals(WatchVerdict.Stop, look(LaunchWatchPolicy.WATCH_MS))
    }

    @Test
    fun theNotRespondingStateIsReadFromTheProcessDump() {
        val stuck = """
            ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)
              *APP* UID 10115 ProcessRecord{8e50827 15378:com.retroarch.aarch64/u0a115}
                user #0 uid=10115 gids={3003}
                 mCrashing=false null mNotResponding=true [] bad=false
        """.trimIndent()
        assertTrue(LaunchWatchPolicy.dumpShowsNotResponding(stuck))
        val fine = stuck.lines().dropLast(1).joinToString("\n")
        assertEquals(false, LaunchWatchPolicy.dumpShowsNotResponding(fine))
        assertEquals(false, LaunchWatchPolicy.dumpShowsNotResponding(" mCrashing=true null mNotResponding=false [] bad=false"))
    }

    @Test
    fun withoutAHelperTheWatchEndsAtTheLimitAndClaimsNothingAboutABlackScreen() {
        assertEquals(WatchVerdict.Stop, look(LaunchWatchPolicy.WATCH_MS, taskListed = null))
    }

    @Test
    fun everyMessageNamesTheAppAndSaysWhatToDo() {
        for (trouble in LaunchTrouble.entries) {
            val text = LaunchWatchPolicy.message("Example Emu", trouble)
            assertTrue(text, text.startsWith("Example Emu"))
            assertTrue(text, "try again" in text.lowercase() || "close it" in text.lowercase())
        }
    }
}
