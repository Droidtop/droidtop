package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Apps tab's icon-cache naming rule (see [AppIconFiles]). */
class AppIconFilesTest {

    private val state = "en-US,35,themed=0|12345"

    @Test
    fun `the same app in the same state gets the same name`() {
        assertEquals(
            AppIconFiles.fileName("org.videolan.vlc", 1_700_000_000_000, state),
            AppIconFiles.fileName("org.videolan.vlc", 1_700_000_000_000, state),
        )
    }

    @Test
    fun `the name is the package, the update time and a short state hash`() {
        val name = AppIconFiles.fileName("org.videolan.vlc", 42, state)
        assertTrue(name, Regex("""org\.videolan\.vlc-42-[0-9a-f]{12}\.png""").matches(name))
    }

    @Test
    fun `an update gets a new name`() {
        assertNotEquals(
            AppIconFiles.fileName("org.videolan.vlc", 1, state),
            AppIconFiles.fileName("org.videolan.vlc", 2, state),
        )
    }

    @Test
    fun `a changed icon state gets a new name`() {
        assertNotEquals(
            AppIconFiles.fileName("org.videolan.vlc", 1, "en-US,35,themed=0|12345"),
            AppIconFiles.fileName("org.videolan.vlc", 1, "en-US,35,themed=1|12345"),
        )
    }

    @Test
    fun `stale files are the ones no current app names`() {
        val current = AppIconFiles.fileName("org.videolan.vlc", 2, state)
        val existing = listOf(
            current,
            AppIconFiles.fileName("org.videolan.vlc", 1, state),
            "org.videolan.vlc.png",
            ".$current.tmp",
        )
        assertEquals(existing.drop(1), AppIconFiles.stale(existing, setOf(current)))
    }
}
