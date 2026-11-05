package org.pocketworkstation.pckeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Kaomoji in the emoji panel (Droidtop/tracker#903 H4). */
class KaomojiTest {
    private val sample = """
        # a comment
        [Joy]
        (* ^ ω ^)
        (;・ω・)

        [Love]
        (*♡∀♡)
    """.trimIndent()

    @Test fun groupsBecomeNamesSearchCanFind() {
        val group = Kaomoji.parse(sample)
        assertEquals(Kaomoji.GROUP, group.id)
        assertEquals(listOf("(* ^ ω ^)", "(;・ω・)", "(*♡∀♡)"), group.entries.map { it.emoji })
        assertEquals("kaomoji love", group.entries[2].name)
        val catalog = EmojiCatalog(listOf(group))
        assertEquals(listOf("(*♡∀♡)"), catalog.search("love").map { it.emoji })
        assertEquals(3, catalog.search("kaomoji").size)
    }

    @Test fun aSemicolonIsPartOfTheKaomoji() {
        assertEquals("(;・ω・)", Kaomoji.parse(sample).entries[1].emoji)
    }

    @Test fun theBundledListParses() {
        val file = listOf("src/main/assets/kaomoji/kaomoji.txt", "input-keyboard/src/main/assets/kaomoji/kaomoji.txt")
            .map(::File).firstOrNull { it.exists() } ?: return
        val group = Kaomoji.parse(file.readText())
        assertEquals(104, group.entries.size)
        assertTrue(group.entries.all { it.emoji.isNotBlank() && it.name.startsWith("kaomoji ") })
    }
}
