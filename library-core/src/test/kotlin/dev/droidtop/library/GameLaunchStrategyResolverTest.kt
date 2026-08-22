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
    fun `RenPy with no exe or lib folder only offers JoiPlay when installed`() {
        val strategies = GameLaunchStrategyResolver.resolve(GameEngine.RENPY, tmp.root, joiPlayInstalled = true)
        assertEquals(listOf(GameLaunchStrategy.JOIPLAY), strategies)
    }

    @Test
    fun `RenPy offers nothing when JoiPlay isn't installed and there's no exe or Linux build`() {
        val strategies = GameLaunchStrategyResolver.resolve(GameEngine.RENPY, tmp.root, joiPlayInstalled = false)
        assertTrue(strategies.isEmpty())
    }

    @Test
    fun `a Windows exe adds WINE_PREFIX regardless of engine`() {
        File(tmp.root, "Game.exe").createNewFile()
        val strategies = GameLaunchStrategyResolver.resolve(GameEngine.KIRIKIRI, tmp.root, joiPlayInstalled = false)
        assertEquals(listOf(GameLaunchStrategy.WINE_PREFIX), strategies)
    }

    @Test
    fun `RenPy with a linux lib folder adds LINUX_CONTAINER`() {
        File(tmp.root, "lib/py3-linux-x86_64").mkdirs()
        val strategies = GameLaunchStrategyResolver.resolve(GameEngine.RENPY, tmp.root, joiPlayInstalled = false)
        assertEquals(listOf(GameLaunchStrategy.LINUX_CONTAINER), strategies)
    }

    @Test
    fun `Kirikiri never offers LINUX_CONTAINER even with a lib-linux folder present`() {
        // Real architectural fact, not a guess: Kirikiri is Windows-native
        // with no official Linux port -- a lib/*linux* folder existing
        // (unusual, but not impossible for a game bundling unrelated
        // assets) must never be read as "this can run in a Linux container."
        File(tmp.root, "lib/linux-x86_64").mkdirs()
        val strategies = GameLaunchStrategyResolver.resolve(GameEngine.KIRIKIRI, tmp.root, joiPlayInstalled = true)
        assertFalse(GameLaunchStrategy.LINUX_CONTAINER in strategies)
    }

    @Test
    fun `Kirikiri never offers JOIPLAY even when JoiPlay is installed`() {
        val strategies = GameLaunchStrategyResolver.resolve(GameEngine.KIRIKIRI, tmp.root, joiPlayInstalled = true)
        assertFalse(GameLaunchStrategy.JOIPLAY in strategies)
    }

    @Test
    fun `Kirikiri offers KIRIKIROID2 when it's installed, other engines never do`() {
        val kirikiriStrategies = GameLaunchStrategyResolver.resolve(
            GameEngine.KIRIKIRI, tmp.root, joiPlayInstalled = false, kirikiroid2Installed = true,
        )
        assertTrue(GameLaunchStrategy.KIRIKIROID2 in kirikiriStrategies)

        val renPyStrategies = GameLaunchStrategyResolver.resolve(
            GameEngine.RENPY, tmp.root, joiPlayInstalled = false, kirikiroid2Installed = true,
        )
        assertFalse(GameLaunchStrategy.KIRIKIROID2 in renPyStrategies)
    }

    @Test
    fun `a Ren'Py game can offer all three strategies at once`() {
        File(tmp.root, "Game.exe").createNewFile()
        File(tmp.root, "lib/py3-linux-x86_64").mkdirs()

        val strategies = GameLaunchStrategyResolver.resolve(GameEngine.RENPY, tmp.root, joiPlayInstalled = true)

        assertEquals(
            setOf(GameLaunchStrategy.JOIPLAY, GameLaunchStrategy.WINE_PREFIX, GameLaunchStrategy.LINUX_CONTAINER),
            strategies.toSet(),
        )
    }
}
