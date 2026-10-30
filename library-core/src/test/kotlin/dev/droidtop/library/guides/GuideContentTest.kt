package dev.droidtop.library.guides

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Next mark, in every guide reader, goes forward through the marks (Droidtop/tracker#817). */
class GuideContentTest {
    private val marks = listOf(
        GuideBookmark(GuidePosition(5, 0.1f), "Page 6"),
        GuideBookmark(GuidePosition(2, 0.5f), "Page 3"),
        GuideBookmark(GuidePosition(2, 0.2f), "Page 3 top"),
    )

    @Test
    fun `the next mark is the first one after the place, in page order`() {
        assertEquals("Page 3 top", nextMark(marks, GuidePosition(0, 0f))?.label)
        assertEquals("Page 3", nextMark(marks, GuidePosition(2, 0.2f))?.label)
        assertEquals("Page 6", nextMark(marks, GuidePosition(3, 0f))?.label)
    }

    @Test
    fun `past the last mark there is none`() {
        assertNull(nextMark(marks, GuidePosition(5, 0.5f)))
    }

    @Test
    fun `a text guide reads as its paragraphs, blank lines between them`() {
        val file = java.io.File.createTempFile("walkthrough", ".txt")
        try {
            file.writeText("Chapter 1\nGo north.\n\n  \nChapter 2\n\n\nThe end.\n")
            val guide = Guide("folder:walkthrough.txt", "walkthrough.txt", "In the game's folder", GuideFormat.TEXT, path = file.path)
            assertEquals(listOf("Chapter 1\nGo north.", "Chapter 2", "The end."), GuideContent.paragraphs(guide))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a guide that cannot be read has no paragraphs`() {
        assertNull(GuideContent.paragraphs(Guide("folder:gone.txt", "gone.txt", "In the game's folder", GuideFormat.TEXT, path = "/nowhere/gone.txt")))
    }
}
