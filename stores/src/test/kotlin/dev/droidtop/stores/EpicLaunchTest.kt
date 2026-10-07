package dev.droidtop.stores

import dev.droidtop.stores.epic.EpicGameLauncher
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/** How Epic's command lines are split and how a host file is named to Wine (docs/SPEC.md 7g, "Stores"). */
class EpicLaunchTest {

    @Test
    fun `a command line splits like CommandLineToArgvW`() {
        assertEquals(listOf("-arg=value with spaces", "-x"), EpicGameLauncher.tokenizeArgs("-arg=\"value with spaces\" -x"))
        assertEquals(listOf("-name=Don't"), EpicGameLauncher.tokenizeArgs("-name=Don't"))
        assertEquals(listOf("a", "b c d"), EpicGameLauncher.tokenizeArgs("a \"b c d"))
        assertEquals(emptyList<String>(), EpicGameLauncher.tokenizeArgs(""))
    }

    @Test
    fun `a host file is reached through the Z drive`() {
        val path = EpicGameLauncher.zDrivePath(File("/data/user/0/dev.droidtop.app/files/epic/ovt/ns.ovt"))
        assertEquals("Z:\\data\\user\\0\\dev.droidtop.app\\files\\epic\\ovt\\ns.ovt", path)
    }
}
