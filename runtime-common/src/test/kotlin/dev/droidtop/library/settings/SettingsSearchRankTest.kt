package dev.droidtop.library.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** Settings search ranks titles above descriptions, as Android Settings does (Droidtop/tracker#505). */
class SettingsSearchRankTest {
    private val screen = CatalogScreen(id = "rank_test", title = "Settings", groups = { _ -> emptyList() })

    private fun row(id: String, title: String, subtitle: String? = null, screenTitle: String = "Other") =
        SettingsSearchResult(screen, screenTitle, id, title, subtitle, null)

    private val index = listOf(
        row("build", "Build channel", "Which builds droidtop installs for them"),
        row("sounds", "Navigation sounds", "Plays them as you move"),
        row("picker", "Theme", "Pick how droidtop looks"),
        row("inside", "Bathemed", null),
        row("word", "Choose a theme"),
        row("stem", "Themes everywhere", "unrelated"),
        row("screen", "Colours", screenTitle = "Theme browser"),
    )

    @Test
    fun `a title that starts with the text leads, then a title word, then the text inside a title`() {
        val ids = SettingsSearchIndex.search(index, "them").map { it.itemId }

        assertEquals(listOf("picker", "stem", "word", "inside"), ids.take(4))
    }

    @Test
    fun `the screen's own name comes after titles and a description counts only as a whole word`() {
        val ids = SettingsSearchIndex.search(index, "them").map { it.itemId }

        assertEquals(listOf("picker", "stem", "word", "inside", "screen", "build", "sounds"), ids)
        // "them" is not a whole word of "themselves", and "droid" is only part of "droidtop".
        assertEquals(emptyList<String>(), SettingsSearchIndex.search(listOf(row("a", "Other", "by themselves")), "them").map { it.itemId })
        assertEquals(emptyList<String>(), SettingsSearchIndex.search(listOf(row("a", "Other", "droidtop")), "droid").map { it.itemId })
    }

    @Test
    fun `rows of one rank keep the tree's order and a short query finds nothing`() {
        val ids = SettingsSearchIndex.search(listOf(row("b", "Sound"), row("a", "Sounds")), "sou").map { it.itemId }

        assertEquals(listOf("b", "a"), ids)
        assertEquals(emptyList<SettingsSearchResult>(), SettingsSearchIndex.search(index, "t"))
    }
}
