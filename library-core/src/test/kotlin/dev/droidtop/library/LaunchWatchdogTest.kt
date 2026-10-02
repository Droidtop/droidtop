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
    fun anAppThatIsListedAndHasSettledIsLeftAlone() {
        assertEquals(WatchVerdict.Keep, look(LaunchWatchPolicy.SETTLED_MS - 1, taskListed = true))
        assertEquals(WatchVerdict.Stop, look(LaunchWatchPolicy.SETTLED_MS, taskListed = true))
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
