package dev.droidtop.library.stores

import org.junit.Assert.assertEquals
import org.junit.Test

/** A store's hidden list carried into droidtop's Hidden by its changes only (Droidtop/tracker#402). */
class StoreHiddenTest {
    @Test
    fun `the first read hides every game the store hides`() {
        val plan = StoreHidden.plan("steam", null, setOf("440", "570"))
        assertEquals(setOf("steam:440", "steam:570"), plan.hide)
        assertEquals(emptySet<String>(), plan.show)
    }

    @Test
    fun `a game that joins the list is hidden and one that leaves it is shown again`() {
        val plan = StoreHidden.plan("steam", setOf("440", "570"), setOf("570", "730"))
        assertEquals(setOf("steam:730"), plan.hide)
        assertEquals(setOf("steam:440"), plan.show)
    }

    @Test
    fun `an unchanged list touches nothing, so the person's own Hidden choice stands`() {
        val plan = StoreHidden.plan("steam", setOf("440"), setOf("440"))
        assertEquals(emptySet<String>(), plan.hide)
        assertEquals(emptySet<String>(), plan.show)
    }
}
