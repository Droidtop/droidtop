package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The availability model's own rules, as plain JVM tests -- the reason
 * [RunnerAvailability] takes measured facts instead of a `Context`.
 */
class RunnerAvailabilityTest {

    private fun facts(
        engine: GameEngine? = GameEngine.RENPY,
        hasWindowsExecutable: Boolean = false,
        hasLinuxBuild: Boolean = false,
        enginehostSupported: Boolean = true,
        enginehostInstalled: Boolean = true,
        enginehostCanReachFolder: Boolean = true,
        enginehostEngineVersionKnown: Boolean = true,
        enginehostBundleCovers: Boolean? = null,
        kirikiroid2Installed: Boolean = false,
        windowsEnvironmentReady: Boolean = true,
        wineRendererWired: Boolean = true,
        linuxContainerAvailable: Boolean = true,
        x86TranslationRegistered: Boolean = true,
        preferredOrder: List<GameLaunchStrategy> = emptyList(),
    ) = RunnerFacts(
        engine = engine,
        hasWindowsExecutable = hasWindowsExecutable,
        hasLinuxBuild = hasLinuxBuild,
        enginehostSupported = enginehostSupported,
        enginehostInstalled = enginehostInstalled,
        enginehostCanReachFolder = enginehostCanReachFolder,
        enginehostEngineVersionKnown = enginehostEngineVersionKnown,
        enginehostBundleCovers = enginehostBundleCovers,
        kirikiroid2Installed = kirikiroid2Installed,
        windowsEnvironmentReady = windowsEnvironmentReady,
        wineRendererWired = wineRendererWired,
        linuxContainerAvailable = linuxContainerAvailable,
        x86TranslationRegistered = x86TranslationRegistered,
        preferredOrder = preferredOrder,
    )

    private fun List<RunnerOption>.of(strategy: GameLaunchStrategy) = single { it.strategy == strategy }

    @Test
    fun `every runner is reported for every game, never silently absent`() {
        val options = RunnerAvailability.evaluate(facts(engine = null))
        assertEquals(GameLaunchStrategy.entries.toSet(), options.map { it.strategy }.toSet())
    }

    @Test
    fun `a store game with no engine still gets a Wine row`() {
        val options = RunnerAvailability.evaluate(facts(engine = null, hasWindowsExecutable = true))
        assertEquals(RunnerState.READY, options.of(GameLaunchStrategy.WINE_PREFIX).state)
        // ...and no enginehost row it could never use.
        assertEquals(RunnerState.NOT_FOR_THIS_GAME, options.of(GameLaunchStrategy.ENGINEHOST).state)
    }

    @Test
    fun `no exe hides Wine as a fact about the game, not the device`() {
        val option = RunnerAvailability.evaluate(facts()).of(GameLaunchStrategy.WINE_PREFIX)
        assertEquals(RunnerState.NOT_FOR_THIS_GAME, option.state)
        assertNotNull(option.reason)
    }

    @Test
    fun `an unprovisioned Windows environment is one named action away`() {
        val option = RunnerAvailability
            .evaluate(facts(hasWindowsExecutable = true, windowsEnvironmentReady = false))
            .of(GameLaunchStrategy.WINE_PREFIX)
        assertEquals(RunnerState.NEEDS_SETUP, option.state)
        assertEquals(RunnerAction.SET_UP_WINDOWS_GAMES, option.action)
    }

    @Test
    fun `an unwired renderer is needs-setup with the real reason, never Ready`() {
        val option = RunnerAvailability
            .evaluate(facts(hasWindowsExecutable = true, wineRendererWired = false))
            .of(GameLaunchStrategy.WINE_PREFIX)
        assertEquals(RunnerState.NEEDS_SETUP, option.state)
        assertTrue(option.reason!!.contains("renderer"))
        // Nothing the user can press fixes it, so no action is claimed.
        assertNull(option.action)
    }

    @Test
    fun `native Linux without a container is not-on-this-device, and says to start Desktop mode`() {
        val option = RunnerAvailability
            .evaluate(facts(hasLinuxBuild = true, linuxContainerAvailable = false))
            .of(GameLaunchStrategy.LINUX_CONTAINER)
        assertEquals(RunnerState.NOT_ON_THIS_DEVICE, option.state)
        // Desktop mode's container runs with or without root (SPEC 3), so
        // the reason names the mode to start, not root.
        assertTrue(option.reason!!.contains("Desktop mode"))
    }

    @Test
    fun `root never gates a game that has another route`() {
        val options = RunnerAvailability.evaluate(
            facts(
                engine = GameEngine.GODOT,
                enginehostSupported = false,
                hasWindowsExecutable = true,
                hasLinuxBuild = true,
                linuxContainerAvailable = false,
            ),
        )
        val resolved = RunnerAvailability.resolve(options, override = null)
        assertEquals(GameLaunchStrategy.WINE_PREFIX, resolved!!.option.strategy)
        assertEquals(RunnerState.READY, resolved.option.state)
    }

    @Test
    fun `a game whose only route needs the Linux container says so instead of resolving nothing`() {
        val options = RunnerAvailability.evaluate(
            facts(engine = GameEngine.GODOT, hasLinuxBuild = true, linuxContainerAvailable = false, enginehostSupported = false),
        )
        val resolved = RunnerAvailability.resolve(options, override = null)
        assertEquals(RunnerState.NOT_ON_THIS_DEVICE, resolved!!.option.state)
    }

    @Test
    fun `enginehost missing is an install action, not an absence`() {
        val option = RunnerAvailability
            .evaluate(facts(enginehostInstalled = false))
            .of(GameLaunchStrategy.ENGINEHOST)
        assertEquals(RunnerState.NEEDS_SETUP, option.state)
        assertEquals(RunnerAction.INSTALL_ENGINEHOST, option.action)
    }

    @Test
    fun `an unread bundle list stays advisory and leaves the row Ready`() {
        val option = RunnerAvailability
            .evaluate(facts(enginehostBundleCovers = null))
            .of(GameLaunchStrategy.ENGINEHOST)
        assertEquals(RunnerState.READY, option.state)
    }

    @Test
    fun `a bundle list that covers nothing asks enginehost for the plugin`() {
        val option = RunnerAvailability
            .evaluate(facts(enginehostBundleCovers = false))
            .of(GameLaunchStrategy.ENGINEHOST)
        assertEquals(RunnerState.NEEDS_SETUP, option.state)
        assertEquals(RunnerAction.INSTALL_ENGINEHOST_PLUGIN, option.action)
        assertTrue(option.reason!!.contains("Ren'Py"))
    }

    @Test
    fun `an engine the database does not map is not for this game`() {
        val option = RunnerAvailability
            .evaluate(facts(engine = GameEngine.UNITY, enginehostSupported = false))
            .of(GameLaunchStrategy.ENGINEHOST)
        assertEquals(RunnerState.NOT_FOR_THIS_GAME, option.state)
    }

    @Test
    fun `Kirikiroid2 states its real limitation even when Ready`() {
        val option = RunnerAvailability
            .evaluate(facts(engine = GameEngine.KIRIKIRI, kirikiroid2Installed = true))
            .of(GameLaunchStrategy.KIRIKIROID2)
        assertEquals(RunnerState.READY, option.state)
        assertEquals("Opens the app, not this game", option.caveat)
    }

    @Test
    fun `Kirikiroid2 is not for a non-KiriKiri game`() {
        val option = RunnerAvailability
            .evaluate(facts(kirikiroid2Installed = true))
            .of(GameLaunchStrategy.KIRIKIROID2)
        assertEquals(RunnerState.NOT_FOR_THIS_GAME, option.state)
    }

    @Test
    fun `KiriKiri never offers a Linux container`() {
        val option = RunnerAvailability
            .evaluate(facts(engine = GameEngine.KIRIKIRI, hasLinuxBuild = true))
            .of(GameLaunchStrategy.LINUX_CONTAINER)
        assertEquals(RunnerState.NOT_FOR_THIS_GAME, option.state)
    }

    @Test
    fun `availability comes first and the database's priority only ranks what is available`() {
        val options = RunnerAvailability.evaluate(
            facts(
                hasWindowsExecutable = true,
                hasLinuxBuild = true,
                // The database would rather have enginehost, but it needs
                // setup here, so a Ready row still leads.
                enginehostInstalled = false,
                preferredOrder = listOf(GameLaunchStrategy.ENGINEHOST, GameLaunchStrategy.LINUX_CONTAINER, GameLaunchStrategy.WINE_PREFIX),
            ),
        )
        assertEquals(GameLaunchStrategy.LINUX_CONTAINER, options.first().strategy)
        assertEquals(GameLaunchStrategy.WINE_PREFIX, options[1].strategy)
        assertEquals(GameLaunchStrategy.ENGINEHOST, options[2].strategy)
    }

    @Test
    fun `the default is stated, and an override wins over it`() {
        val options = RunnerAvailability.evaluate(facts(hasWindowsExecutable = true, hasLinuxBuild = true))
        val default = RunnerAvailability.resolve(options, override = null, defaultLabel = "Ren'Py")
        assertEquals("default for Ren'Py", default!!.reason)

        val chosen = RunnerAvailability.resolve(options, override = GameLaunchStrategy.WINE_PREFIX.name, defaultLabel = "Ren'Py")
        assertEquals(GameLaunchStrategy.WINE_PREFIX, chosen!!.option.strategy)
        assertEquals("your choice", chosen.reason)
    }

    @Test
    fun `an override naming a runner this game cannot use is ignored, not obeyed`() {
        val options = RunnerAvailability.evaluate(
            facts(engine = GameEngine.GODOT, enginehostSupported = false, hasWindowsExecutable = true),
        )
        val resolved = RunnerAvailability.resolve(options, override = GameLaunchStrategy.LINUX_CONTAINER.name)
        assertEquals(GameLaunchStrategy.WINE_PREFIX, resolved!!.option.strategy)
        assertEquals("the only runner for this game", resolved.reason)
    }

    @Test
    fun `a PC game with both builds runs its native Linux build when this device can`() {
        val options = RunnerAvailability.evaluate(
            facts(engine = null, hasWindowsExecutable = true, hasLinuxBuild = true),
        )
        assertEquals(GameLaunchStrategy.LINUX_CONTAINER, RunnerAvailability.resolve(options, override = null)!!.option.strategy)
    }

    @Test
    fun `a PC game with both builds falls back to Wine where Linux cannot run`() {
        val options = RunnerAvailability.evaluate(
            facts(engine = null, hasWindowsExecutable = true, hasLinuxBuild = true, linuxContainerAvailable = false),
        )
        assertEquals(GameLaunchStrategy.WINE_PREFIX, RunnerAvailability.resolve(options, override = null)!!.option.strategy)
    }

    @Test
    fun `a game with no route at all resolves to nothing`() {
        val resolved = RunnerAvailability.resolve(
            RunnerAvailability.evaluate(facts(engine = null)),
            override = null,
        )
        assertNull(resolved)
    }
}
