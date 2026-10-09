package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Desktop's Start menu sections and the taskbar's pins (docs/SPEC.md 2b). */
class StartMenuSectionsTest {

    private fun entry(id: String, title: String, kind: LibraryEntryKind, hidden: Boolean = false) =
        LibraryEntry(id = id, title = title, kind = kind, hidden = hidden)

    @Test
    fun `a Wine profile is Windows, an app is Android apps, everything else is a game`() {
        assertEquals(StartSection.WINDOWS, StartMenuSections.sectionOf(LibraryEntryKind.WINE_PROFILE))
        assertEquals(StartSection.ANDROID_APPS, StartMenuSections.sectionOf(LibraryEntryKind.NATIVE_ANDROID_APP))
        assertEquals(StartSection.GAMES, StartMenuSections.sectionOf(LibraryEntryKind.RENPY))
        assertEquals(StartSection.GAMES, StartMenuSections.sectionOf(LibraryEntryKind.LINUX_CONTAINER_APP))
    }

    @Test
    fun `every kind lands in exactly one section`() {
        LibraryEntryKind.entries.forEach { kind ->
            val inApps = kind in LibraryKinds.APPS
            val section = StartMenuSections.sectionOf(kind)
            if (kind == LibraryEntryKind.WINE_PROFILE) {
                assertEquals(StartSection.WINDOWS, section)
            } else if (inApps) {
                assertEquals(StartSection.ANDROID_APPS, section)
            } else {
                assertEquals(StartSection.GAMES, section)
            }
        }
    }

    @Test
    fun `sections are in name order ignoring case, hidden entries are left out, empty sections are absent`() {
        val grouped = StartMenuSections.group(
            listOf(
                entry("b", "zelda", LibraryEntryKind.RENPY),
                entry("a", "Alpha", LibraryEntryKind.RENPY),
                entry("h", "Hidden game", LibraryEntryKind.RENPY, hidden = true),
                entry("c", "beta", LibraryEntryKind.RENPY),
                entry("p", "Tool", LibraryEntryKind.WINE_PROFILE),
            ),
        )
        assertEquals(listOf("Alpha", "beta", "zelda"), grouped.getValue(StartSection.GAMES).map { it.label })
        assertEquals(listOf("Tool"), grouped.getValue(StartSection.WINDOWS).map { it.label })
        assertFalse(StartSection.ANDROID_APPS in grouped)
    }

    @Test
    fun `an item is listed under the name a person reads`() {
        val grouped = StartMenuSections.group(listOf(entry("x", "Anomalous_Coffee_Machine_2", LibraryEntryKind.RENPY)))
        val item = grouped.getValue(StartSection.GAMES).single()
        assertEquals("Anomalous Coffee Machine 2", item.label)
        assertEquals("x", item.entry.id)
    }

    @Test
    fun `pinning adds at the end and pinning again takes it out`() {
        val a = TaskbarPin(TaskbarPins.entryKey("a"), "A")
        val b = TaskbarPin(TaskbarPins.linuxKey("foot"), "Foot")
        val both = TaskbarPins.toggled(TaskbarPins.toggled(emptyList(), a), b)
        assertEquals(listOf(a, b), both)
        assertTrue(TaskbarPins.isPinned(both, a.key))
        assertEquals(listOf(b), TaskbarPins.toggled(both, a))
    }

    @Test
    fun `pins survive the round trip, including a picture and awkward characters`() {
        val pins = listOf(
            TaskbarPin(TaskbarPins.entryKey("id1"), "Game one", "/data/art/one.png"),
            TaskbarPin(TaskbarPins.linuxKey("org.foot.desktop"), "Foot\tTerminal\nwith breaks"),
        )
        val back = TaskbarPins.decode(TaskbarPins.encode(pins))
        assertEquals(2, back.size)
        assertEquals(pins[0], back[0])
        assertEquals("Foot Terminal with breaks", back[1].title)
        assertNull(back[1].art)
    }

    @Test
    fun `decoding drops broken lines and repeated keys, and an empty store is no pins`() {
        assertEquals(emptyList<TaskbarPin>(), TaskbarPins.decode(null))
        assertEquals(emptyList<TaskbarPin>(), TaskbarPins.decode(""))
        val back = TaskbarPins.decode("entry:a\tA\t\n\tno key\t\nentry:b\t\t\nentry:a\tAgain\t\nentry:c\tC")
        assertEquals(listOf("entry:a", "entry:c"), back.map { it.key })
        assertEquals("A", back[0].title)
    }

    @Test
    fun `a container app answers the search by its name or its generic name`() {
        assertTrue(StartMenuSections.appMatches("Foot", "Terminal", "foo"))
        assertTrue(StartMenuSections.appMatches("Foot", "Terminal", " TERM "))
        assertFalse(StartMenuSections.appMatches("Foot", "Terminal", "browser"))
        assertTrue(StartMenuSections.appMatches("Firefox", null, "fire"))
        assertFalse(StartMenuSections.appMatches("Firefox", null, "term"))
    }

    @Test
    fun `blank search text answers nothing`() {
        assertFalse(StartMenuSections.appMatches("Foot", "Terminal", ""))
        assertFalse(StartMenuSections.appMatches("Foot", "Terminal", "   "))
    }

    @Test
    fun `a key says what it is`() {
        assertTrue(TaskbarPins.isLinux(TaskbarPins.linuxKey("foot")))
        assertFalse(TaskbarPins.isLinux(TaskbarPins.entryKey("foot")))
        assertEquals("abc", TaskbarPins.entryIdOf(TaskbarPins.entryKey("abc")))
        assertNull(TaskbarPins.entryIdOf(TaskbarPins.linuxKey("abc")))
    }
}
