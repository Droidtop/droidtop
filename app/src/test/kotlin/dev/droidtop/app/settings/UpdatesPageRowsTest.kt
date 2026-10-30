package dev.droidtop.app.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Updates page's one row shape, its state and its views (Droidtop/tracker#792). */
class UpdatesPageRowsTest {
    @Test
    fun `a row's line is the version step, the size and where it comes from`() {
        assertEquals("v1.2 → v1.3 · 12 MB · from droidtop's catalog", UpdatesPage.line("v1.2 → v1.3", 12L * 1024 * 1024, "droidtop's catalog"))
        assertEquals("v1.2 → v1.3", UpdatesPage.line("v1.2 → v1.3", null, null))
        assertEquals("v1.2 → v1.3 · from F-Droid", UpdatesPage.line("v1.2 → v1.3", 0L, " F-Droid "))
    }

    @Test
    fun `one state model for every kind, the running one first`() {
        val id = "updates_plugin_x"
        assertEquals(UpdatesPage.State.AVAILABLE, UpdatesPage.stateOf(id, emptySet(), emptySet(), emptySet()))
        assertEquals(UpdatesPage.State.DOWNLOADING, UpdatesPage.stateOf(id, setOf(id), setOf(id), setOf(id)))
        assertEquals(UpdatesPage.State.WAITING, UpdatesPage.stateOf(id, emptySet(), setOf(id), setOf(id)))
        assertEquals(UpdatesPage.State.FAILED, UpdatesPage.stateOf(id, emptySet(), emptySet(), setOf(id)))
        assertEquals("Failed · Retry", UpdatesPage.State.FAILED.label)
    }

    @Test
    fun `the views are All, then each kind with updates, each with its count`() {
        val titles = UpdatesPage.viewTitles(mapOf(UpdatesPage.Kind.PLUGINS to 3, UpdatesPage.Kind.GAMES to 1)).map { it.second }
        assertEquals(listOf("All · 4", "Plugins · 3", "Games · 1"), titles)
        assertEquals(listOf("All · 0"), UpdatesPage.viewTitles(emptyMap()).map { it.second })
    }
}
