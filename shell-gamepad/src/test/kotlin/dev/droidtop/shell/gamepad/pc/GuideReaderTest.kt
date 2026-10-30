package dev.droidtop.shell.gamepad.pc

import dev.droidtop.library.guides.GuideBookmark
import dev.droidtop.library.guides.GuidePosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** X in the guide reader goes forward through the marks (Droidtop/tracker#817). */
class GuideReaderTest {
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
}
