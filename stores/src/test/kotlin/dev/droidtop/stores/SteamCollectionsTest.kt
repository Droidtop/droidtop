package dev.droidtop.stores

import dev.droidtop.stores.steam.SteamCollections
import dev.droidtop.stores.steam.SteamCollections.Raw
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading Steam's collections out of its cloud config store (docs/SPEC.md 7g, "Collections"; Droidtop/tracker#232). */
class SteamCollectionsTest {
    private fun entry(key: String, value: String, deleted: Boolean = false) = Raw(key, value, deleted)

    @Test
    fun `a static collection keeps its name and its app ids`() {
        val parsed = SteamCollections.parse(
            listOf(entry("user-collections.uc-1", """{"id":"uc-1","name":"Handheld friendly","added":[440,570,730]}""")),
        )
        assertEquals(1, parsed.collections.size)
        assertEquals("Handheld friendly", parsed.collections.single().name)
        assertEquals(setOf(440, 570, 730), parsed.collections.single().appIds)
    }

    @Test
    fun `a dynamic collection is counted and left out, and so are deleted and foreign entries`() {
        val parsed = SteamCollections.parse(
            listOf(
                entry("user-collections.dyn", """{"id":"dyn","name":"Cheap","filterSpec":{"nFormatVersion":2}}"""),
                entry("user-collections.gone", """{"id":"gone","name":"Old","added":[1]}""", deleted = true),
                entry("something-else.x", """{"id":"x","name":"Not ours","added":[2]}"""),
                entry("user-collections.keep", """{"id":"keep","name":"Backlog","added":[3]}"""),
            ),
        )
        assertEquals(listOf("Backlog"), parsed.collections.map { it.name })
        assertEquals(1, parsed.skippedDynamic)
    }

    @Test
    fun `a missing name falls back to the id, a null id to the key, and bad json is skipped`() {
        val parsed = SteamCollections.parse(
            listOf(
                entry("user-collections.a", """{"id":"a","name":null,"added":[]}"""),
                entry("user-collections.b", """{"id":null,"name":"B","added":[9]}"""),
                entry("user-collections.c", "not json"),
            ),
        )
        assertEquals(listOf("a", "B"), parsed.collections.map { it.name })
        assertEquals("b", parsed.collections.last().id)
        assertTrue(parsed.collections.first().appIds.isEmpty())
    }
}
