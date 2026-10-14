package dev.droidtop.app

import dev.droidtop.library.settings.PinnedControls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The companion app picker's list (CompanionAppPicker.kt, Droidtop/tracker#488 item 3). */
class CompanionAppPickerTest {
    private fun app(pkg: String, label: String, game: Boolean = false) = AppPicker.App(pkg, label, game)

    private val apps = listOf(
        app("com.android.chrome", "Chrome"),
        app("org.mozilla.firefox", "Firefox"),
        app("com.example.mines", "Mines of Dust", game = true),
        app("com.example.calc", "Calculator"),
        app("com.example.camera", "Camera"),
        app("dev.example.chess", "Pocket Chess", game = true),
        app("com.zed.notes", "Zed Notes"),
    )

    private fun labels(sections: List<AppPicker.Section>) = sections.map { section -> section.title to section.rows.map { it.app.label } }

    @Test fun `with no search, pinned apps lead in pin order, then recent apps, then the rest by name`() {
        val sections = AppPicker.sections(
            apps,
            pinned = listOf("org.mozilla.firefox", "com.example.calc"),
            recent = listOf("com.example.camera", "org.mozilla.firefox", "dev.example.chess"),
            query = "",
        )
        assertEquals(
            listOf(
                "Pinned" to listOf("Firefox", "Calculator"),
                // Firefox is pinned, so it is not listed twice.
                "Recent" to listOf("Camera", "Pocket Chess"),
                "All other apps" to listOf("Chrome", "Mines of Dust", "Zed Notes"),
            ),
            labels(sections),
        )
        assertTrue(sections[0].rows.all { it.pinned })
        assertTrue(sections.drop(1).flatMap { it.rows }.none { it.pinned })
    }

    @Test fun `with nothing pinned or recent there is one list, by name, called All apps`() {
        val sections = AppPicker.sections(apps, emptyList(), emptyList(), "")
        assertEquals(listOf("All apps"), sections.map { it.title })
        assertEquals(listOf("Calculator", "Camera", "Chrome", "Firefox", "Mines of Dust", "Pocket Chess", "Zed Notes"), sections.single().rows.map { it.app.label })
    }

    @Test fun `pinned and recent apps that are not installed are ignored, and recent is capped`() {
        val many = (1..12).map { app("p.$it", "App $it") }
        val sections = AppPicker.sections(many, pinned = listOf("gone.pkg"), recent = listOf("gone.too") + many.map { it.packageName }, query = "")
        assertEquals("Recent", sections[0].title)
        assertEquals(AppPicker.MAX_RECENT, sections[0].rows.size)
        assertEquals(listOf("All other apps"), sections.drop(1).map { it.title })
    }

    @Test fun `a search lists matches best first, name start before word start before contains before package`() {
        val list = listOf(
            app("a.zero", "Hexcam"),
            app("a.one", "Dual Cam"),
            app("a.two", "Camera"),
            app("com.cam.tools", "Utilities"),
            app("a.three", "Calculator"),
        )
        val result = AppPicker.sections(list, emptyList(), emptyList(), "cam").single()
        assertEquals(null, result.title)
        assertEquals(listOf("Camera", "Dual Cam", "Hexcam", "Utilities"), result.rows.map { it.app.label })
    }

    @Test fun `a search is case and space insensitive and finds nothing when nothing matches`() {
        assertEquals(listOf("Chrome"), AppPicker.sections(apps, emptyList(), emptyList(), "  CHRO ").single().rows.map { it.app.label })
        assertTrue(AppPicker.sections(apps, emptyList(), emptyList(), "zzzz").isEmpty())
    }

    @Test fun `among equal matches, pinned come first, then recent, then by name`() {
        val list = listOf(app("a", "Cat Alpha"), app("b", "Cat Beta"), app("c", "Cat Gamma"), app("d", "Cat Delta"))
        val rows = AppPicker.sections(list, pinned = listOf("c"), recent = listOf("b"), query = "cat").single().rows
        assertEquals(listOf("Cat Gamma", "Cat Beta", "Cat Alpha", "Cat Delta"), rows.map { it.app.label })
        assertTrue(rows.first().pinned && rows.drop(1).none { it.pinned })
    }

    @Test fun `the games filter keeps only games in every view`() {
        val list = AppPicker.sections(apps, pinned = listOf("com.android.chrome", "com.example.mines"), recent = emptyList(), query = "", gamesOnly = true)
        assertEquals(listOf("Pinned" to listOf("Mines of Dust"), "All other apps" to listOf("Pocket Chess")), labels(list))
        assertEquals(listOf("Pocket Chess"), AppPicker.sections(apps, emptyList(), emptyList(), "ch", gamesOnly = true).single().rows.map { it.app.label })
    }

    @Test fun `pinned apps come from the stored pin ids in order, ignoring other pins`() {
        val pins = listOf("system_volume", PinnedControls.appId("b.app"), "stat:clock", PinnedControls.appId("a.app"))
        assertEquals(listOf("b.app", "a.app"), AppPicker.pinnedPackages(pins))
    }

    @Test fun `the result line says where the app went or why it did not open`() {
        assertEquals("Opened Chrome on this screen", AppPicker.outcome("Chrome", here = true, failure = null))
        assertEquals("Opened Chrome on the main screen", AppPicker.outcome("Chrome", here = false, failure = null))
        assertEquals("Chrome did not open: Not installed", AppPicker.outcome("Chrome", here = true, failure = "Not installed"))
    }
}
