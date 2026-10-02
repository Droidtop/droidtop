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
        assertEquals(WatchVerdict.Keep, look(LaunchWatchPolicy.SLOW_MS - 1))
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
    fun aLiveResponsiveAppStillInFrontGetsOnlyTheGentleNoticeAfterTheSlowLimit() {
        assertEquals(WatchVerdict.Keep, look(LaunchWatchPolicy.SLOW_MS - 1, taskListed = true))
        assertEquals(WatchVerdict.Trouble(LaunchTrouble.TAKING_LONG), look(LaunchWatchPolicy.SLOW_MS, taskListed = true))
        assertEquals(WatchVerdict.Trouble(LaunchTrouble.TAKING_LONG), look(LaunchWatchPolicy.SLOW_MS, taskListed = null))
    }

    @Test
    fun aPersonWhoReturnedToTheShellIsNeverToldTheLaunchIsSlow() {
        assertEquals(WatchVerdict.Stop, look(LaunchWatchPolicy.SLOW_MS + 5_000, shellCameBack = true, taskListed = true))
    }

    @Test
    fun theSlowNoticeIsGentleAndAnErrorIsNot() {
        fun alert(t: LaunchTrouble) = LaunchAlert("a.b", "Example Emu", t, "m", "p")
        assertTrue(alert(LaunchTrouble.TAKING_LONG).gentle)
        assertTrue(LaunchTrouble.entries.filter { it != LaunchTrouble.TAKING_LONG }.none { alert(it).gentle })
    }

    @Test
    fun afterTheNoticeTheShellComingBackKeepsItButAClosedAppOrTheLimitEndsIt() {
        fun after(elapsed: Long, shellCameBack: Boolean = false, notResponding: Boolean = false, taskListed: Boolean? = true) =
            LaunchWatchPolicy.afterNotice(WatchObservation(elapsed, shellCameBack, notResponding, taskListed))
        assertEquals(WatchVerdict.Keep, after(60_000, shellCameBack = true))
        assertEquals(WatchVerdict.Stop, after(60_000, taskListed = false))
        assertEquals(WatchVerdict.Stop, after(LaunchWatchPolicy.WATCH_MS))
        assertEquals(WatchVerdict.Trouble(LaunchTrouble.NOT_RESPONDING), after(60_000, notResponding = true))
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
