package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Test

/** The deep link a Linux game's "Linux tools" row opens (docs/SPEC.md 7c). */
class LinuxToolsScreenTest {

    @Test
    fun `the argument carries the game, its title and its folder`() {
        val target = LinuxToolsScreen.parse(LinuxToolsScreen.argument("folder:CUSTOM_GAME_7", "Some Game", "/data/user/0/dev.droidtop.app/files/games/Some Game"))
        assertEquals(LinuxToolsScreen.Target("folder:CUSTOM_GAME_7", "Some Game", "/data/user/0/dev.droidtop.app/files/games/Some Game"), target)
    }

    @Test
    fun `an argument cut short still names the game`() {
        assertEquals(LinuxToolsScreen.Target("x", "x", ""), LinuxToolsScreen.parse("x"))
    }
}
