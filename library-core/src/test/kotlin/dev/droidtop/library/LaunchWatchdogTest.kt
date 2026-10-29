package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The launch watchdog's decision (Droidtop/tracker#270, #271, #168): what it claims and what it never does. */
class LaunchWatchdogTest {
    private fun look(
        elapsedMs: Long,
        shellCameBack: Boolean = false,
        notResponding: Boolean = false,
        taskListed: Boolean? = null,
        freshProcess: Boolean? = null,
        missedBefore: Int = 0,
        seenListed: Boolean = true,
    ) = LaunchWatchPolicy.judge(
        WatchObservation(elapsedMs, shellCameBack, notResponding, taskListed, freshProcess = freshProcess, missedBefore = missedBefore, seenListed = seenListed),
    )

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
        assertEquals(WatchVerdict.Keep, look(LaunchWatchPolicy.APPEAR_GRACE_MS - 1, taskListed = false, missedBefore = 1))
        assertEquals(
            WatchVerdict.Trouble(LaunchTrouble.GONE_WHILE_AWAY),
            look(LaunchWatchPolicy.APPEAR_GRACE_MS, taskListed = false, missedBefore = 1),
        )
    }

    @Test
    fun anAppIsGoneOnlyWhenItStaysGoneAndNoProcessOfThisLaunchIsAlive() {
        // One miss is not enough: RetroArch restarts itself for a new core, an exit then a new process (rig, 1923).
        assertEquals(WatchVerdict.Keep, look(LaunchWatchPolicy.APPEAR_GRACE_MS, taskListed = false))
        // A process that started after the launch is alive: the app is there, whatever the task list says this moment.
        assertEquals(WatchVerdict.Keep, look(LaunchWatchPolicy.APPEAR_GRACE_MS, taskListed = false, missedBefore = 1, freshProcess = true))
        // Only processes from before the launch (a sideload's RetroArch) or none at all: gone.
        assertEquals(
            WatchVerdict.Trouble(LaunchTrouble.GONE_WHILE_AWAY),
            look(LaunchWatchPolicy.APPEAR_GRACE_MS, taskListed = false, missedBefore = 1, freshProcess = false),
        )
    }

    @Test
    fun aLaunchNeverSeenOpenThatStaysMissingExitedAtOnceEvenWithTheShellInFront() {
        // Two displays: the shell never left the front, so only the task list tells (rig, 1931: Vita3K).
        assertEquals(
            WatchVerdict.Trouble(LaunchTrouble.EXITED_AT_ONCE),
            look(LaunchWatchPolicy.APPEAR_GRACE_MS + 3_000, taskListed = false, missedBefore = 1, freshProcess = false, seenListed = false),
        )
    }

    @Test
    fun aProcessLeftWithNoWindowCountsAsGoneAfterSomeLooks() {
        // Eden's process stayed with nothing open (rig, 1931); RetroArch's restart is back within a look or two.
        val grace = LaunchWatchPolicy.APPEAR_GRACE_MS
        assertEquals(WatchVerdict.Keep, look(grace + 6_000, taskListed = false, missedBefore = LaunchWatchPolicy.LINGER_LOOKS - 2, freshProcess = true, seenListed = false))
        assertEquals(
            WatchVerdict.Trouble(LaunchTrouble.EXITED_AT_ONCE),
            look(grace + 9_000, taskListed = false, missedBefore = LaunchWatchPolicy.LINGER_LOOKS - 1, freshProcess = true, seenListed = false),
        )
    }

    @Test
    fun aProcessStartTimeIsReadFromItsStatLine() {
        // Field 22 (starttime) is 5000 ticks: 50 s after boot at 100 ticks a second. The name holds a space and a parenthesis.
        val stat = "4242 (Retro (Arch) x) S 1 4242 0 0 -1 4194624 100 0 0 0 7 3 0 0 20 0 30 0 5000 123456 789"
        assertEquals(true, LaunchWatchPolicy.startedSince(stat, sinceBootMs = 49_000))
        assertEquals(false, LaunchWatchPolicy.startedSince(stat, sinceBootMs = 51_000))
        assertEquals(null, LaunchWatchPolicy.startedSince("", sinceBootMs = 0))
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
    fun anEmulatorThatHasUsedNoProcessorTimeIsStalled() {
        fun read(elapsedMs: Long, cpuMs: Long?, shellCameBack: Boolean = false) =
            LaunchWatchPolicy.judge(WatchObservation(elapsedMs, shellCameBack, notResponding = false, taskListed = null, cpuMs = cpuMs))
        // Console, build 1649: GBC and N64 launches at 0:00.14 of processor time, black, until Back raised an ANR;
        // build 1335: NetherSX2 on a PS2 game at 0.2 s after 60 s.
        assertEquals(WatchVerdict.Trouble(LaunchTrouble.STALLED), read(LaunchWatchPolicy.STALL_CHECK_MS, cpuMs = 140))
        // A game that runs, or RetroArch's own menu, has used seconds by then.
        assertEquals(WatchVerdict.Keep, read(LaunchWatchPolicy.STALL_CHECK_MS, cpuMs = 3_900))
        // A reading that only came back at a retry is judged the same way.
        assertEquals(WatchVerdict.Trouble(LaunchTrouble.STALLED), read(2 * LaunchWatchPolicy.STALL_CHECK_MS, cpuMs = 200))
        assertEquals(WatchVerdict.Keep, read(2 * LaunchWatchPolicy.STALL_CHECK_MS, cpuMs = LaunchWatchPolicy.STALL_CPU_MS))
        // No reading (no helper, or not an emulator launch) claims nothing; nor does a person already back in droidtop.
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
    fun theStalledMessageDoesNotClaimWhyOnlyWhatWasSeenAndWhereToLook() {
        val text = LaunchWatchPolicy.message("NetherSX2", LaunchTrouble.STALLED)
        assertTrue(text, "almost nothing" in text)
        assertTrue(text, "BIOS" in text && "file access" in text)
    }

    @Test
    fun everyMessageNamesTheAppAndSaysWhatToDo() {
        for (trouble in LaunchTrouble.entries) {
            val text = LaunchWatchPolicy.message("Example Emu", trouble)
            assertTrue(text, text.startsWith("Example Emu"))
            assertTrue(text, "try again" in text.lowercase() || "close it" in text.lowercase())
        }
    }

    @Test
    fun anAppOpenedByItselfIsNotToldToCheckAGameFile() {
        val app = LaunchWatchPolicy.message("Settings", LaunchTrouble.EXITED_AT_ONCE, opensFile = false)
        assertTrue(app, app.startsWith("Settings closed straight after it started. Try again"))
        assertFalse(app, "game" in app)
        assertTrue("game file" in LaunchWatchPolicy.message("RetroArch", LaunchTrouble.EXITED_AT_ONCE))
    }

    @Test
    fun enginehostReturningAtOnceIsLeavingNotFailing() {
        // Cancel on Enginehost's no-sandbox warning hands straight back (Droidtop/tracker#289): not a crash.
        assertTrue(LaunchWatchPolicy.leftBeforePlaying(EngineHost.PACKAGE_NAME, LaunchTrouble.EXITED_AT_ONCE))
        // Anything else of Enginehost's is still trouble, and an emulator returning at once still is.
        for (trouble in LaunchTrouble.entries - LaunchTrouble.EXITED_AT_ONCE) {
            assertFalse(LaunchWatchPolicy.leftBeforePlaying(EngineHost.PACKAGE_NAME, trouble))
        }
        assertFalse(LaunchWatchPolicy.leftBeforePlaying("com.retroarch.aarch64", LaunchTrouble.EXITED_AT_ONCE))
    }

    @Test
    fun `an Enginehost launch waiting on its own question is open whatever the task list shows`() {
        // Console, 0.2.0-dev.2129: the task list missed the no-sandbox warning and the launch was ended as left.
        assertEquals(true, LaunchWatchPolicy.listed(appSays = true, taskList = false))
        assertEquals(false, LaunchWatchPolicy.listed(appSays = false, taskList = null))
        assertEquals(true, LaunchWatchPolicy.listed(appSays = null, taskList = true))
        assertEquals(null, LaunchWatchPolicy.listed(appSays = null, taskList = null))
    }
}
