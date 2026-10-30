package dev.droidtop.app.settings

import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.NestedScreenItem
import dev.droidtop.shell.gamepad.pc.SourceRecordScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A folder game's update row shows the update in place (docs/SPEC.md 7g, "Facets in place"; Droidtop/tracker#639): on
 * the console its A opened the game's page and Android fell to the Standard launcher. The row is a nested screen, so A
 * pushes the update's own screen wherever Updates is drawn and has nothing of its own to run: no activity can start.
 */
class UpdateRowInPlaceTest {
    private val row = UpdatesPage.sourceUpdateRow(
        library = { error("the library is asked for only when the update's screen is opened") },
        entryId = "/games/30 Year Old Virgin 0.37",
        title = "30 Year Old Virgin",
        step = "v0.37 → v0.38",
    )

    @Test
    fun `A on a game's update row pushes the update's own screen and runs nothing`() {
        assertTrue(row is NestedScreenItem)
        assertFalse(row is ActionItem || row is AsyncActionItem)
        val screen = (row as NestedScreenItem).resolve()
        assertEquals(SourceRecordScreen.ID, screen?.id)
        assertEquals(null, row.registryId)
    }

    @Test
    fun `the row says both versions, installed first, in the one row line`() {
        val nested = row as NestedScreenItem
        assertTrue(nested.subtitle!!.startsWith("v0.37 → v0.38 · from its source"))
        // The value column's line; the label needs no context, but a JVM test has no Android to give it one.
        assertTrue(nested.valueLabel != null)
    }
}
