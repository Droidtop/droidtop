package dev.droidtop.library.integrations

import android.content.ContextWrapper
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.NestedScreenItem
import dev.droidtop.library.settings.SettingsSearchIndex
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings search finds a plugin's Approve on its page (Droidtop/tracker#759). */
class SettingsSearchPluginPagesTest {
    private fun page(id: String, label: String) = CatalogScreen(
        id = "plugin_detail_$id",
        title = label,
        groups = { _ -> listOf(CatalogGroup(id = "top_$id", title = null, items = listOf(ActionItem(id = "plugin_${id}_approve", title = "Approve")))) },
    )

    private fun plugins(indexesItsPages: Boolean) = CatalogScreen(
        id = "plugins",
        title = "Plugins",
        indexesItsPages = indexesItsPages,
        groups = { _ ->
            listOf(
                CatalogGroup(
                    id = "installed",
                    title = "Installed",
                    items = listOf(NestedScreenItem(id = "row_repacks", title = "Repacks", inline = page("repacks", "Repacks"))),
                ),
            )
        },
    )

    /** Settings > Accounts and sources > Plugins > Repacks: the plugin's page is three screens down. */
    private fun root(indexesItsPages: Boolean): CatalogScreen {
        val accounts = CatalogScreen(
            id = "accounts",
            title = "Accounts and sources",
            groups = { _ -> listOf(CatalogGroup(id = "a", title = null, items = listOf(NestedScreenItem(id = "row_plugins", title = "Plugins", inline = plugins(indexesItsPages))))) },
        )
        return CatalogScreen(
            id = "root",
            title = "Settings",
            groups = { _ -> listOf(CatalogGroup(id = "r", title = null, items = listOf(NestedScreenItem(id = "row_accounts", title = "Accounts and sources", inline = accounts)))) },
        )
    }

    @Test
    fun `the Plugins screen lets search reach each plugin's page`() = runBlocking {
        val index = SettingsSearchIndex.build(ContextWrapper(null), root(indexesItsPages = true))
        val hit = SettingsSearchIndex.search(index, "approve").singleOrNull()
        assertTrue("Approve on Repacks' page: $index", hit?.itemId == "plugin_repacks_approve" && hit.screenTitle == "Repacks")
    }

    @Test
    fun `without it the index stops two screens down, as before`() = runBlocking {
        val index = SettingsSearchIndex.build(ContextWrapper(null), root(indexesItsPages = false))
        assertFalse(index.any { it.itemId == "plugin_repacks_approve" })
        assertTrue(index.any { it.itemId == "row_repacks" })
    }
}
