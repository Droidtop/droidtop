package dev.droidtop.runtime.windows

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class WineStoreToolsTest {
    @Test
    fun `store probes and registry files use the actual prefix C drive instead of host paths`() {
        val prefix = Files.createTempDirectory("wine-store-prefix-").toFile()
        val drive = File(prefix, "drive_c").apply { mkdir() }
        val probe = File(drive, "mytop-ea/probe.bat")
        assertEquals("C:\\mytop-ea\\probe.bat", WineStoreTools.pathInWine(prefix, emptyList(), probe))
        val games = Files.createTempDirectory("wine-store-games-").toFile()
        val game = File(games, "Game/bin/Game.exe")
        assertEquals("D:\\Game\\bin\\Game.exe", WineStoreTools.pathInWine(prefix, listOf("D" to games.absolutePath), game))
    }
}
