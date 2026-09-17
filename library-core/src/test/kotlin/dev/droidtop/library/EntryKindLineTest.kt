package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The line under an entry's name says what the ENTRY is, never the name
 * of the group it is filed under: every Apps tile read "Apps" two lines
 * below a heading that already said so (rig, build 547).
 */
class EntryKindLineTest {

    private fun app(genre: String? = null) = LibraryEntry(
        id = "com.example.app",
        title = "Example",
        kind = LibraryEntryKind.NATIVE_ANDROID_APP,
        genre = genre,
    )

    @Test
    fun `an app that declares no category says what it is`() {
        assertEquals("Android app", app().kindLine())
    }

    @Test
    fun `an app's own declared category is what it says`() {
        assertEquals("Games", app(genre = "Games").kindLine())
    }

    @Test
    fun `a blank category is no category`() {
        assertEquals("Android app", app(genre = "  ").kindLine())
    }

    @Test
    fun `no kind's own name is the name of the group it is filed under`() {
        // Where the two differ they must not be confusable; where a kind
        // is called the same thing either way ("Remote PC") that word is
        // already singular and states what one entry is.
        assertNotEquals(
            LibraryEntryKind.NATIVE_ANDROID_APP.displayName(),
            LibraryEntryKind.NATIVE_ANDROID_APP.itemName(),
        )
        LibraryEntryKind.entries.forEach { kind ->
            assertTrue("$kind has no name of its own", kind.itemName().isNotBlank())
        }
    }
}
