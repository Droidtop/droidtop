package dev.droidtop.shell.gamepad

import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogIcon
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.NestedScreenItem
import dev.droidtop.library.settings.ToggleItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The settings layout's category column and merged screens (docs/SPEC.md
 * "Settings layout"): a hub of links becomes one category per link, any
 * other group one category, and a short screen marked merged is drawn as
 * sections of the pane that links it.
 */
class SettingsCategoriesTest {

    private fun toggle(id: String) = ToggleItem(id = id, title = id, current = false, onToggle = { _, _ -> })

    private fun screen(id: String, merged: Boolean = false, groups: List<CatalogGroup> = emptyList()) =
        CatalogScreen(id = id, title = "Title $id", groups = { groups }, merged = merged)

    private fun link(id: String, target: CatalogScreen, icon: CatalogIcon? = null) =
        NestedScreenItem(id = id, title = "Link $id", inline = target, icon = icon)

    @Test
    fun `a group of only links is a hub, one category per link under its title`() {
        val groups = listOf(
            CatalogGroup(id = "library", title = "Library", items = listOf(link("a", screen("sa"), CatalogIcon.SCRAPER), link("b", screen("sb")))),
        )

        val categories = settingsCategories(groups, untitledLabel = "Settings")

        assertEquals(listOf("link:a", "link:b"), categories.map { it.key })
        assertEquals(listOf("Link a", "Link b"), categories.map { it.label })
        assertEquals(listOf("Library", null), categories.map { it.sectionAbove })
        assertEquals(CatalogIcon.SCRAPER, categories[0].icon)
        assertEquals(emptyList<String>(), categories[0].groupIds)
    }

    @Test
    fun `any other group is one category, named by its title or the untitled label`() {
        val groups = listOf(
            CatalogGroup(id = "top", title = null, items = listOf(toggle("t1"), link("x", screen("sx")))),
            CatalogGroup(id = "shell", title = "Shell", items = listOf(toggle("t2")), icon = CatalogIcon.GAMING),
            CatalogGroup(id = "empty", title = "Empty", items = emptyList()),
        )

        val categories = settingsCategories(groups, untitledLabel = "Settings")

        assertEquals(listOf("group:top", "group:shell"), categories.map { it.key })
        assertEquals(listOf("Settings", "Shell"), categories.map { it.label })
        assertEquals(listOf("top", "shell"), categories.map { it.groupIds.single() })
        assertEquals(CatalogIcon.GAMING, categories[1].icon)
    }

    @Test
    fun `a link the renderer fulfils natively keeps its group a plain category`() {
        val groups = listOf(CatalogGroup(id = "g", title = "G", items = listOf(link("native", screen("sn")))))

        val categories = settingsCategories(groups, untitledLabel = "Settings", nativeIds = setOf("native"))

        assertEquals(listOf("group:g"), categories.map { it.key })
    }

    @Test
    fun `a merged screen's rows become sections after the group that linked it`() = runBlocking {
        val shortGroups = listOf(CatalogGroup(id = "only", title = null, items = listOf(toggle("inner"))))
        val short = screen("short", merged = true, groups = shortGroups)
        val long = screen("long")
        val groups = listOf(
            CatalogGroup(id = "modes", title = "Modes", items = listOf(toggle("outer"), link("toShort", short), link("toLong", long))),
        )

        val merged = mergeShortScreens(groups) { if (it === short) shortGroups else emptyList() }

        assertEquals(listOf("modes", "short/only"), merged.map { it.id })
        assertEquals(listOf("outer", "toLong"), merged[0].items.map { it.id })
        assertEquals("Title short", merged[1].title)
        assertEquals(listOf("inner"), merged[1].items.map { it.id })
    }

    @Test
    fun `groups without a merged link come back unchanged`() = runBlocking {
        val groups = listOf(CatalogGroup(id = "g", title = "G", items = listOf(toggle("t"), link("l", screen("s")))))

        assertSame(groups, mergeShortScreens(groups) { error("nothing to load") })
    }

    @Test
    fun `groups that name one category are one entry, even when they hold only links`() {
        val groups = listOf(
            CatalogGroup(id = "home", title = null, items = listOf(toggle("t1")), category = "Home"),
            CatalogGroup(id = "library", title = "Library", items = listOf(link("a", screen("sa")), link("b", screen("sb"))), category = "Library"),
            CatalogGroup(id = "modes", title = "Modes", items = listOf(toggle("t2")), category = "Home", icon = CatalogIcon.MODES),
            CatalogGroup(id = "plain", title = "Plain", items = listOf(toggle("t3"))),
        )

        val categories = settingsCategories(groups, untitledLabel = "Settings")

        assertEquals(listOf("category:Home", "category:Library", "group:plain"), categories.map { it.key })
        assertEquals(listOf("Home", "Library", "Plain"), categories.map { it.label })
        assertEquals(listOf("home", "modes"), categories[0].groupIds)
        assertEquals(listOf("library"), categories[1].groupIds)
        // The icon is the first one the category's groups name.
        assertEquals(CatalogIcon.MODES, categories[0].icon)
        assertEquals(null, categories[0].link)
    }

    @Test
    fun `the order puts named categories first in that order and keeps the rest after them`() {
        val groups = listOf(
            CatalogGroup(id = "x", title = null, items = listOf(toggle("a")), category = "System"),
            CatalogGroup(id = "y", title = "Unlisted", items = listOf(toggle("b"))),
            CatalogGroup(id = "z", title = null, items = listOf(toggle("c")), category = "Home"),
            CatalogGroup(id = "w", title = null, items = listOf(toggle("d")), category = "System"),
        )

        val categories = settingsCategories(groups, untitledLabel = "Settings", order = listOf("Home", "System"))

        assertEquals(listOf("Home", "System", "Unlisted"), categories.map { it.label })
        assertEquals(listOf("x", "w"), categories[1].groupIds)
    }
}
