package dev.droidtop.library.integrations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where Get games can put a download ([AcquireContentSources.downloadTargets]) and how a result's size reads ([SourceSize]). */
class AcquireContentSourcesTest {

    private fun system(id: String, name: String, ownedBy: String? = null) =
        dev.droidtop.library.consoles.ConsoleSystemDef(id, name, setOf("rom"), null, ownedBy)

    @Test
    fun `with no system folder Get games still offers every system a folder can mean`() {
        val root = java.io.File("/games")
        val (yours, others) = AcquireContentSources.downloadTargets(
            existing = emptyList(),
            all = listOf(system("snes", "Super Nintendo"), system("gba", "Game Boy Advance"), system("steam", "Steam", ownedBy = "store")),
            root = root,
        )
        assertTrue(yours.isEmpty())
        assertEquals(listOf("gba", "snes"), others.map { it.second.id })
        assertEquals(java.io.File("/games/gba"), others.first().first)
    }

    @Test
    fun `a system that has a folder is listed once and first`() {
        val snes = system("snes", "Super Nintendo")
        val (yours, others) = AcquireContentSources.downloadTargets(
            existing = listOf(java.io.File("/games/roms/snes") to snes, java.io.File("/other/snes") to snes),
            all = listOf(snes, system("gba", "Game Boy Advance")),
            root = java.io.File("/games"),
        )
        assertEquals(listOf(java.io.File("/games/roms/snes")), yours.map { it.first })
        assertEquals(listOf("gba"), others.map { it.second.id })
    }

    @Test
    fun `a result's size in bytes is written in decimal units`() {
        assertEquals("512 B", SourceSize.label(512))
        assertEquals("640 KB", SourceSize.label(640_000))
        assertEquals("1.2 GB", SourceSize.label(1_234_567_890))
        assertEquals("2 MB", SourceSize.label(2_000_000))
        assertEquals("15 GB", SourceSize.label(15_400_000_000))
    }
}
