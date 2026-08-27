package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GameLaunchStrategyResolverTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `RenPy offers nothing with no engineVersion, no exe, and no lib folder`() {
        val strategies = GameLaunchStrategyResolver.resolve(GameEngine.RENPY, tmp.root)
        assertTrue(strategies.isEmpty())
    }

    @Test
    fun `a Windows exe adds WINE_PREFIX regardless of engine`() {
        File(tmp.root, "Game.exe").createNewFile()
        val strategies = GameLaunchStrategyResolver.resolve(GameEngine.KIRIKIRI, tmp.root)
        assertEquals(listOf(GameLaunchStrategy.WINE_PREFIX), strategies)
    }

    @Test
    fun `RenPy with a linux lib folder adds LINUX_CONTAINER`() {
        File(tmp.root, "lib/py3-linux-x86_64").mkdirs()
        val strategies = GameLaunchStrategyResolver.resolve(GameEngine.RENPY, tmp.root)
        assertEquals(listOf(GameLaunchStrategy.LINUX_CONTAINER), strategies)
    }

    @Test
    fun `Kirikiri never offers LINUX_CONTAINER even with a lib-linux folder present`() {
        // Real architectural fact, not a guess: Kirikiri is Windows-native
        // with no official Linux port -- a lib/*linux* folder existing
        // (unusual, but not impossible for a game bundling unrelated
        // assets) must never be read as "this can run in a Linux container."
        File(tmp.root, "lib/linux-x86_64").mkdirs()
        val strategies = GameLaunchStrategyResolver.resolve(GameEngine.KIRIKIRI, tmp.root)
        assertFalse(GameLaunchStrategy.LINUX_CONTAINER in strategies)
    }

    @Test
    fun `Kirikiri offers KIRIKIROID2 when it's installed, other engines never do`() {
        val kirikiriStrategies = GameLaunchStrategyResolver.resolve(GameEngine.KIRIKIRI, tmp.root, kirikiroid2Installed = true)
        assertTrue(GameLaunchStrategy.KIRIKIROID2 in kirikiriStrategies)

        val renPyStrategies = GameLaunchStrategyResolver.resolve(GameEngine.RENPY, tmp.root, kirikiroid2Installed = true)
        assertFalse(GameLaunchStrategy.KIRIKIROID2 in renPyStrategies)
    }

    @Test
    fun `a Ren'Py game can offer ENGINEHOST, WINE_PREFIX, and LINUX_CONTAINER at once`() {
        File(tmp.root, "Game.exe").createNewFile()
        File(tmp.root, "lib/py3-linux-x86_64").mkdirs()

        val strategies = GameLaunchStrategyResolver.resolve(
            GameEngine.RENPY, tmp.root, engineHostInstalled = true, engineHostEngineVersion = "8.2.0",
        )

        assertEquals(
            setOf(GameLaunchStrategy.ENGINEHOST, GameLaunchStrategy.WINE_PREFIX, GameLaunchStrategy.LINUX_CONTAINER),
            strategies.toSet(),
        )
    }

    @Test
    fun `ENGINEHOST is offered first when installed and a version is known`() {
        val strategies = GameLaunchStrategyResolver.resolve(
            GameEngine.KIRIKIRI, tmp.root,
            engineHostInstalled = true, engineHostEngineVersion = "2.32",
        )
        assertEquals(GameLaunchStrategy.ENGINEHOST, strategies.first())
    }

    @Test
    fun `ENGINEHOST is not offered when installed but no version is known and no enginehost json exists`() {
        val strategies = GameLaunchStrategyResolver.resolve(
            GameEngine.KIRIKIRI, tmp.root,
            engineHostInstalled = true, engineHostEngineVersion = null,
        )
        assertFalse(GameLaunchStrategy.ENGINEHOST in strategies)
    }

    @Test
    fun `ENGINEHOST is offered with no known version when the folder has its own enginehost json`() {
        File(tmp.root, "enginehost.json").writeText("""{"engine":"kirikiri2","engineVersion":"2.32"}""")
        val strategies = GameLaunchStrategyResolver.resolve(
            GameEngine.KIRIKIRI, tmp.root,
            engineHostInstalled = true, engineHostEngineVersion = null,
        )
        assertTrue(GameLaunchStrategy.ENGINEHOST in strategies)
    }

    @Test
    fun `ENGINEHOST is never offered for an engine it doesn't cover`() {
        val strategies = GameLaunchStrategyResolver.resolve(
            GameEngine.UNITY, tmp.root,
            engineHostInstalled = true, engineHostEngineVersion = "1.0.0",
        )
        assertFalse(GameLaunchStrategy.ENGINEHOST in strategies)
    }

    @Test
    fun `ENGINEHOST is not offered when enginehost isn't installed even with a known version`() {
        val strategies = GameLaunchStrategyResolver.resolve(
            GameEngine.KIRIKIRI, tmp.root,
            engineHostInstalled = false, engineHostEngineVersion = "2.32",
        )
        assertFalse(GameLaunchStrategy.ENGINEHOST in strategies)
    }
}
