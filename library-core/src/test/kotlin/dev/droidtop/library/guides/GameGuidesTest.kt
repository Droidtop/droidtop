package dev.droidtop.library.guides

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The guides a game's folder carries, and the place kept in them (Droidtop/tracker#817). */
class GameGuidesTest {
    @Test
    fun `a folder's walkthrough, guide and readme are found, the walkthrough first, and nothing else`() {
        val dir = Files.createTempDirectory("guides").toFile()
        File(dir, "README.txt").writeText("r")
        File(dir, "Walkthrough v2.pdf").writeText("w")
        File(dir, "game.exe").writeText("x")
        File(dir, "guidebook.txt").writeText("not a guide name")
        File(dir, "docs").mkdirs()
        File(dir, "docs/guide.html").writeText("<p>g</p>")
        val guides = GameGuides.inFolder(dir)
        assertEquals(listOf("Walkthrough v2.pdf", "guide.html", "README.txt"), guides.map { it.title })
        assertEquals(listOf(GuideFormat.PDF, GuideFormat.WEB, GuideFormat.TEXT), guides.map { it.format })
        assertEquals("folder:docs/guide.html", guides[1].id)
    }

    @Test
    fun `a missing folder has no guides`() {
        assertTrue(GameGuides.inFolder(File("/nonexistent/droidtop/guides")).isEmpty())
    }

    @Test
    fun `the place remembers the last guide, where each was left, and bookmarks once each`() {
        val mark = GuideBookmark(GuidePosition(3, 0.5f), "Chapter 2")
        val place = GuidePlace()
            .leftAt("folder:a.txt", GuidePosition(0, 0.25f))
            .leftAt("folder:b.pdf", GuidePosition(4, 0f))
            .marked("folder:b.pdf", mark)
            .marked("folder:b.pdf", mark)
        assertEquals("folder:b.pdf", place.last)
        assertEquals(0.25f, place.at("folder:a.txt").fraction, 0.0001f)
        assertEquals(GuidePosition(), place.at("never-opened"))
        assertEquals(listOf(mark), place.bookmarks["folder:b.pdf"])
    }
}
