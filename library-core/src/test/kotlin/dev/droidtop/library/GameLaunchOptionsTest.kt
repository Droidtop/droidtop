package dev.droidtop.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a person types as a game's launch options and environment (docs/SPEC.md 7i, "Game properties"; Droidtop/tracker#228). */
class GameLaunchOptionsTest {

    @Test
    fun `launch options split at spaces and keep quoted words whole`() {
        assertEquals(listOf("-windowed", "-w", "1280"), GameLaunchOptions.tokenize("-windowed -w 1280"))
        assertEquals(listOf("-name", "Big Bad", "x"), GameLaunchOptions.tokenize("-name \"Big Bad\" x"))
        assertEquals(listOf("a b", "c"), GameLaunchOptions.tokenize("'a b' c"))
        assertEquals(listOf("say \"hi\""), GameLaunchOptions.tokenize("\"say \\\"hi\\\"\""))
        assertEquals(listOf("a b"), GameLaunchOptions.tokenize("a\\ b"))
    }

    @Test
    fun `an empty quoted word is kept, extra spaces and an open quote are not an error`() {
        assertEquals(listOf("-x", "", "y"), GameLaunchOptions.tokenize("-x \"\"   y"))
        assertEquals(emptyList<String>(), GameLaunchOptions.tokenize("   "))
        assertEquals(listOf("open quote"), GameLaunchOptions.tokenize("\"open quote"))
    }

    @Test
    fun `a shell is never involved, so its characters are only text`() {
        assertEquals(listOf("a;b", "\$HOME", "&&", "rm"), GameLaunchOptions.tokenize("a;b \$HOME && rm"))
    }

    @Test
    fun `the environment keeps the tuning variables and says why it refused the rest`() {
        val parsed = GameLaunchOptions.parseEnvironment("DXVK_HUD=fps MESA_GLTHREAD=true LD_PRELOAD=x PATH=/bin DXVK_LOG=a/b NOEQUALS DXVK_HUD=full")
        assertEquals(mapOf("DXVK_HUD" to "fps", "MESA_GLTHREAD" to "true"), parsed.variables)
        assertEquals(5, parsed.refused.size)
        assertTrue(parsed.refused.any { it.startsWith("LD_PRELOAD") })
        assertTrue(parsed.refused.any { it.startsWith("PATH") })
        assertTrue(parsed.refused.any { it.startsWith("DXVK_LOG") })
        assertTrue(parsed.refused.any { it.startsWith("NOEQUALS") })
        assertTrue(parsed.refused.any { it.startsWith("DXVK_HUD") })
    }

    @Test
    fun `the environment text round-trips and new lines separate like spaces`() {
        val parsed = GameLaunchOptions.parseEnvironment("DXVK_HUD=fps\nVKD3D_CONFIG=dxr")
        assertEquals("DXVK_HUD=fps VKD3D_CONFIG=dxr", GameLaunchOptions.formatEnvironment(parsed.variables))
        assertEquals(parsed.variables, GameLaunchOptions.parseEnvironment(GameLaunchOptions.formatEnvironment(parsed.variables)).variables)
    }

    @Test
    fun `a launch asks the rules again, so stored loader variables are dropped`() {
        val stored = mapOf("DXVK_HUD" to "fps", "LD_PRELOAD" to "evil.so", "MESA_X" to "a b")
        assertEquals("DXVK_HUD=fps", GameLaunchOptions.launchEnvironment(stored))
    }

    @Test
    fun `the person's launch options go after the program's own arguments, whatever the program`() {
        val base = WindowsLaunch(File("/g/game.exe"), File("/g"), listOf("-store"))
        val settings = WineGameSettings(launchOptions = "-windowed -w \"1280 720\"")
        assertEquals(listOf("-store", "-windowed", "-w", "1280 720"), WindowsLaunchResolver.withUserOptions(base, settings).arguments)
        assertEquals(base, WindowsLaunchResolver.withUserOptions(base, null))
        assertEquals(base, WindowsLaunchResolver.withUserOptions(base, WineGameSettings(environment = mapOf("DXVK_HUD" to "1"))))
    }

    @Test
    fun `settings with nothing in them are empty, and an option alone is not`() {
        assertTrue(WineGameSettings().isEmpty)
        assertTrue(WineGameSettings(launchOptions = "  ").isEmpty)
        assertFalse(WineGameSettings(launchOptions = "-x").isEmpty)
        assertFalse(WineGameSettings(environment = mapOf("DXVK_HUD" to "1")).isEmpty)
        assertFalse(WineGameSettings(executable = "a.exe").isEmpty)
    }
}
