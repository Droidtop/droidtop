package dev.droidtop.library.integrations

import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A game's older version folders and their saves before one is removed (docs/SPEC.md 7i; Droidtop/tracker#624). */
class OldVersionsTest {
    private val dir: File = Files.createTempDirectory("old-versions").toFile()

    private fun folder(path: String, missing: Boolean = false) =
        LibraryEntry(id = path, title = path.substringAfterLast('/'), kind = LibraryEntryKind.RENPY, missing = missing)

    @Test
    fun onlyOtherVersionsOfTheSamePartAreOld() {
        val kept = folder("/g/Game v0.38")
        val siblings = listOf(
            kept,
            folder("/g/Game v0.37"),
            folder("/g/Game v0.36", missing = true),
            folder("/g/Game"),
            LibraryEntry(id = "steam:123", title = "Game", kind = LibraryEntryKind.RENPY),
            folder("/g/Game v0.38/Game v0.1"),
        )
        assertEquals(listOf("0.37"), OldVersions.of(kept, siblings).map { it.version })
        assertEquals("a store row has no old versions of its own", emptyList<OldVersions.Old>(), OldVersions.of(siblings[4], siblings))
    }

    @Test
    fun anotherPartIsNotAnOldVersion() {
        val part3 = folder("/g/Thief of Hearts/ThiefofHeartsPart3-0.0.9-pc")
        val part2 = folder("/g/Thief of Hearts/ThiefofHeartsPart2-0.0.8-pc")
        val part3Old = folder("/g/Thief of Hearts/ThiefofHeartsPart3-0.0.8-pc")
        assertEquals(listOf("0.0.8"), OldVersions.of(part3, listOf(part3, part2, part3Old)).map { it.version })
        assertEquals(part3Old.id, OldVersions.of(part3, listOf(part3, part2, part3Old)).single().entry.id)
    }

    @Test
    fun savesTheKeptVersionLacksAreCopiedAndANewerOneStopsTheRemoval() {
        val old = File(dir, "Game v0.37").apply { File(this, "game/saves").mkdirs() }
        val kept = File(dir, "Game v0.38").apply { File(this, "game/saves").mkdirs() }
        File(old, "game/saves/1-1.save").writeText("only here")
        File(old, "game/saves/persistent").writeText("old").also { it.setLastModified(1_000_000L) }
        File(kept, "game/saves/persistent").writeText("new").also { it.setLastModified(2_000_000L) }
        File(old, "game/script.rpy").writeText("not a save")

        val plan = OldVersions.savesPlan(old, kept, listOf("game/saves/**"))
        assertEquals(listOf(File(old, "game/saves/1-1.save") to File(kept, "game/saves/1-1.save")), plan.copy)
        assertEquals(emptyList<String>(), plan.newer)

        File(old, "game/saves/persistent").setLastModified(3_000_000L)
        assertEquals(listOf("game/saves/persistent"), OldVersions.savesPlan(old, kept, listOf("game/saves/**")).newer)
    }

    @Test
    fun anEngineThatKeepsNoSavesInTheFolderHasNothingToMove() {
        val old = File(dir, "G v1").apply { mkdirs() }
        File(old, "save.dat").writeText("x")
        val plan = OldVersions.savesPlan(old, File(dir, "G v2"), emptyList())
        assertTrue(plan.copy.isEmpty() && plan.newer.isEmpty())
    }

    @Test
    fun aVersionIsNamedTheWayTheFolderNamesIt() {
        assertEquals("v0.37", OldVersions.label("0.37"))
        assertEquals("Final", OldVersions.label("Final"))
    }
}
