package dev.droidtop.runtime.windows

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WineGameLogsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun prefixWith(vararg files: String): File {
        val prefix = temp.newFolder("prefix")
        files.forEach { path -> File(prefix, path).apply { parentFile.mkdirs() }.writeText("log $path") }
        return prefix
    }

    @Test
    fun `a Unity game's Player log and the previous one are found through app info`() {
        val prefix = prefixWith(
            "drive_c/users/xuser/AppData/LocalLow/NekoNyanSoft/Clover Days Plus/Player.log",
            "drive_c/users/xuser/AppData/LocalLow/NekoNyanSoft/Clover Days Plus/Player-prev.log",
            "drive_c/users/xuser/AppData/LocalLow/Other/Clover Days Plus/Player.log",
        )
        val game = temp.newFolder("game")
        File(game, "Clover Days Plus_Data").mkdirs()
        File(game, "Clover Days Plus_Data/app.info").writeText("NekoNyanSoft\nClover Days Plus\n")
        val found = WineGameLogs.find(listOf(game), prefix, "Clover Days Plus.exe")
        assertEquals(listOf("Unity Player.log", "Unity Player-prev.log"), found.map { it.label })
        assertTrue(found[0].file.path.contains("NekoNyanSoft"))
    }

    @Test
    fun `without app info any company's folder for the product answers`() {
        val prefix = prefixWith("drive_c/users/xuser/AppData/LocalLow/Someone/Prod/Player.log")
        val game = temp.newFolder("game")
        File(game, "Prod_Data").mkdirs()
        val found = WineGameLogs.find(listOf(game), prefix, "Prod.exe")
        assertEquals(1, found.size)
    }

    @Test
    fun `an old Unity output log and common logs next to the executable are found, whatever their case`() {
        val game = temp.newFolder("game")
        File(game, "G_Data").mkdirs()
        File(game, "G_Data/output_log.txt").writeText("old unity")
        File(game, "Error.LOG").writeText("e")
        File(game, "G.log").writeText("stem")
        File(game, "readme.txt").writeText("not a log")
        val found = WineGameLogs.find(listOf(game), null, "C:\\Games\\G.exe")
        assertEquals(listOf("Unity output_log.txt", "Game log (Error.LOG)", "Game log (G.log)"), found.map { it.label })
    }

    @Test
    fun `nothing is found for a game that writes no log`() {
        val game = temp.newFolder("game")
        File(game, "data.pak").writeText("x")
        assertTrue(WineGameLogs.find(listOf(game), temp.newFolder("p"), "g.exe").isEmpty())
    }

    @Test
    fun `the game's folders are the executable's and the working directory`() {
        val game = temp.newFolder("game")
        val exe = File(game, "g.exe").apply { writeText("MZ") }
        val other = temp.newFolder("other")
        assertEquals(listOf(game, other), WineGameLogs.gameDirs(exe.path, other))
        assertEquals(listOf(game), WineGameLogs.gameDirs(exe.path, game))
        assertEquals(listOf(other), WineGameLogs.gameDirs("C:\\Games\\g.exe", other))
    }
}
