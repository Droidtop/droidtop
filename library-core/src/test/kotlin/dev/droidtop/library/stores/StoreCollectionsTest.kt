package dev.droidtop.library.stores

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A store's collection becomes a droidtop collection of that store's games (docs/SPEC.md 7g, "Collections"). */
class StoreCollectionsTest {
    @Test
    fun `a collection becomes one named after the store whose members are the store's rows`() {
        val (collection, members) = StoreCollections.plan("steam", "Steam", StoreCollection("uc-1", "Backlog", setOf("570", "440")))
        assertEquals("import:steam:uc-1", collection.id)
        assertEquals("Steam: Backlog", collection.name)
        assertEquals(listOf("steam:440", "steam:570"), members)
    }

    @Test
    fun `an imported collection's id never looks like one the person made`() {
        // The person's own collections get a random UUID; imports are found by their prefix alone.
        val (collection, _) = StoreCollections.plan("gog", "GOG", StoreCollection("x", "Y", emptySet()))
        assertTrue(collection.id.startsWith(StoreCollections.prefixFor("gog")))
        assertTrue(!StoreCollections.prefixFor("gog").startsWith(StoreCollections.prefixFor("go")))
    }
}
