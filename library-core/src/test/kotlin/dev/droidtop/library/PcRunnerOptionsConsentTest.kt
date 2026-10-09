package dev.droidtop.library

import dev.droidtop.library.lutris.WinePrefixChanges
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The Windows setup consent gate (Droidtop/tracker#140): the one setup
 * action that downloads hundreds of megabytes must not run until the
 * registered shell's ask comes back, and a decline must not be reported
 * as a failure -- nothing was fetched, and the offer going away IS the
 * answer. [PcRunnerOptions.windowsSetupConsent] is the hook GamepadShell
 * installs; these tests stand where the shell would.
 *
 * The null-hook case is the contract of every caller that cannot ask --
 * Settings' setup row and the Steam sign-in's button state the cost
 * before the press and call provision themselves, so the gate stays open
 * and provision runs exactly as it always did.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PcRunnerOptionsConsentTest {

    /** Records provisions instead of downloading; every launch fails. */
    private class FakeRuntime : PcGameRuntime {
        var provisions = 0
        override val isAvailable = true
        override val isProvisioned = false
        override val isLinuxContainerAvailable = false
        override suspend fun provision(gamesRoots: List<File>, onStatus: (String) -> Unit): PcProvisionResult {
            provisions++
            return PcProvisionResult(true, "set up")
        }

        override suspend fun launchWindows(
            executable: File,
            gameRoot: File,
            workingDir: File,
            arguments: List<String>,
            entryId: String?,
        ): PcLaunchResult = PcLaunchResult(false, "not launched")

        override suspend fun launchLinux(executable: File, gameRoot: File, entryId: String?): PcLaunchResult =
            PcLaunchResult(false, "not launched")

        override fun prefixState(entryId: String?): PcPrefixState? = null

        override suspend fun applyPrefixChanges(entryId: String?, changes: WinePrefixChanges): PcProvisionResult =
            PcProvisionResult(true, "applied")
    }

    private val runtime = FakeRuntime()
    private val entry = LibraryEntry(id = "/games/notepad", title = "Notepad", kind = LibraryEntryKind.WINE_PROFILE)

    @After
    fun tearDown() {
        PcGameRuntimeRegistry.runtime = null
        PcRunnerOptions.windowsSetupConsent = null
    }

    @Test
    fun `a declined offer downloads nothing and is not a failure`() = runBlocking {
        PcGameRuntimeRegistry.runtime = runtime
        PcRunnerOptions.windowsSetupConsent = { false }

        val failure = PcRunnerOptions.runAction(
            RuntimeEnvironment.getApplication(), entry, RunnerAction.SET_UP_WINDOWS_GAMES,
        )

        assertNull(failure)
        assertEquals(0, runtime.provisions)
    }

    @Test
    fun `an accepted offer provisions`() = runBlocking {
        PcGameRuntimeRegistry.runtime = runtime
        PcRunnerOptions.windowsSetupConsent = { true }

        val failure = PcRunnerOptions.runAction(
            RuntimeEnvironment.getApplication(), entry, RunnerAction.SET_UP_WINDOWS_GAMES,
        )

        assertNull(failure)
        assertEquals(1, runtime.provisions)
    }

    @Test
    fun `with no shell installed the gate stays open`() = runBlocking {
        PcGameRuntimeRegistry.runtime = runtime
        PcRunnerOptions.windowsSetupConsent = null

        val failure = PcRunnerOptions.runAction(
            RuntimeEnvironment.getApplication(), entry, RunnerAction.SET_UP_WINDOWS_GAMES,
        )

        assertNull(failure)
        assertEquals(1, runtime.provisions)
    }
}
