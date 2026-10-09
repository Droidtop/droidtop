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
    fun aRetroArchThatHasUsedNoProcessorTimeIsStalled() {
        fun read(elapsedMs: Long, cpuMs: Long?, shellCameBack: Boolean = false) =
            LaunchWatchPolicy.judge(WatchObservation(elapsedMs, shellCameBack, notResponding = false, taskListed = null, cpuMs = cpuMs))
        // Console, build 1649: GBC and N64 launches at 0:00.14 of processor time, black, until Back raised an ANR.
        assertEquals(WatchVerdict.Trouble(LaunchTrouble.STALLED), read(LaunchWatchPolicy.STALL_CHECK_MS, cpuMs = 140))
        // A game that runs, or RetroArch's own menu, has used seconds by then.
        assertEquals(WatchVerdict.Keep, read(LaunchWatchPolicy.STALL_CHECK_MS, cpuMs = 3_900))
        // No reading (no helper, or not a RetroArch launch) claims nothing; nor does a person already back in droidtop.
        assertEquals(WatchVerdict.Keep, read(LaunchWatchPolicy.STALL_CHECK_MS, cpuMs = null))
        assertEquals(WatchVerdict.Stop, read(LaunchWatchPolicy.STALL_CHECK_MS, cpuMs = 140, shellCameBack = true))
    }

    @Test
    fun processorTimeIsReadFromProcStatPastTheProcessName() {
        // utime 9, stime 5 ticks; the name holds a space and a parenthesis, as app names may.
        val stat = "17618 (RetroArch (A) x) S 811 811 0 0 -1 1077952832 9001 0 12 0 9 5 0 0 10 -10 30 0 123 0"
        assertEquals(140L, LaunchWatchPolicy.cpuMsFromStat(stat))
        assertEquals(280L, LaunchWatchPolicy.cpuMsFromStat("$stat\n$stat"))
        assertEquals(null, LaunchWatchPolicy.cpuMsFromStat("cat: /proc/1/stat: No such file or directory"))
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
