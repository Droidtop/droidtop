package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** "Run once with…" (docs/SPEC.md 7i, Droidtop/tracker#636): one launch takes the choice, the saved Runs with stays. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RunOnceTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `a run-once choice applies to the next launch only and leaves the saved choice`() {
        LaunchStrategyOverridePrefs.set(context, "folder:MGQ", GameLaunchStrategy.ENGINEHOST)
        LaunchStrategyOverridePrefs.runOnce("folder:MGQ", GameLaunchStrategy.WINE_PREFIX)

        assertTrue(LaunchStrategyOverridePrefs.isOnce("folder:MGQ"))
        assertEquals("WINE_PREFIX", LaunchStrategyOverridePrefs.forLaunch(context, "folder:MGQ"))
        assertFalse(LaunchStrategyOverridePrefs.isOnce("folder:MGQ"))
        assertEquals("ENGINEHOST", LaunchStrategyOverridePrefs.forLaunch(context, "folder:MGQ"))
        assertEquals("ENGINEHOST", LaunchStrategyOverridePrefs.get(context, "folder:MGQ"))
    }

    @Test
    fun `without a run-once choice a launch takes the saved one, or none`() {
        assertEquals(null, LaunchStrategyOverridePrefs.forLaunch(context, "folder:NEVER_CHOSEN"))
    }
}
