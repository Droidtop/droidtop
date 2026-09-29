package dev.droidtop.shell.gamepad

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.SaverScope
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.SettingsScreenRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The settings navigator's place -- its screen stack and each depth's
 * selected row -- as saveable data: what lets the Activity recreate the
 * Text size setting triggers put the user back on the row they changed
 * instead of the settings root (Droidtop/tracker#87).
 */
class SettingsCatalogRestoreTest {

    // listSaver's save asks its SaverScope what may be stored; this one
    // stores anything, which is all the round-trip tests need.
    private val saverScope = object : SaverScope {
        override fun canBeSaved(value: Any) = true
    }

    private fun screen(id: String) = CatalogScreen(id = id, title = id, groups = { emptyList() })

    @Test
    fun `a pushed stack round-trips through the registry ids`() {
        val root = screen("restore-test-root")
        val first = screen("restore-test-first")
        val second = screen("restore-test-second")
        SettingsScreenRegistry.register(first)
        SettingsScreenRegistry.register(second)
        val stack = mutableStateListOf(root, first, second)

        val saved = with(saverScope) { catalogStackSaver(root).save(stack) }
        val restored = catalogStackSaver(root).restore(saved!!)

        assertEquals(listOf("restore-test-root", "restore-test-first", "restore-test-second"), restored!!.map { it.id })
    }

    @Test
    fun `an id nothing registers ends the restore where it still resolves`() {
        val root = screen("restore-test-root")
        val registered = screen("restore-test-registered")
        SettingsScreenRegistry.register(registered)
        val stack = mutableStateListOf(root, registered, screen("restore-test-never-registered"))

        val saved = with(saverScope) { catalogStackSaver(root).save(stack) }
        val restored = catalogStackSaver(root).restore(saved!!)

        assertEquals(listOf("restore-test-root", "restore-test-registered"), restored!!.map { it.id })
    }

    @Test
    fun `a saved stack for another root is not restored`() {
        val root = screen("restore-test-root")
        val saved = with(saverScope) { catalogStackSaver(root).save(mutableStateListOf(root, screen("pushed"))) }

        assertNull(catalogStackSaver(screen("restore-test-changed-root")).restore(saved!!))
    }

    @Test
    fun `each depth keeps the row it had selected`() {
        val byDepth = mutableStateMapOf(0 to 3, 1 to 7)

        val saved = with(saverScope) { selectionByDepthSaver.save(byDepth) }
        val restored = selectionByDepthSaver.restore(saved!!)

        assertEquals(3, restored[0])
        assertEquals(7, restored[1])
    }
}
