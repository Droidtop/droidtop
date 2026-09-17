package dev.droidtop.shell.gamepad

import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.ChoiceItem
import dev.droidtop.library.settings.ChoiceOption
import dev.droidtop.library.settings.GamingSettingsCatalog
import dev.droidtop.library.settings.NestedScreenItem
import dev.droidtop.library.settings.SliderItem
import dev.droidtop.library.settings.ToggleItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Quick Menu's tile model (see QuickTiles.kt). */
class QuickTilesTest {

    private fun toggle(id: String, on: Boolean) =
        ToggleItem(id = id, title = "Toggle $id", current = on, onToggle = { _, _ -> })

    private fun choice(id: String, options: Int) = ChoiceItem(
        id = id,
        title = "Choice $id",
        options = (1..options).map { ChoiceOption("v$it", "Value $it") },
        current = "v1",
        onSelect = { _, _ -> },
    )

    private fun slider(id: String, min: Int, max: Int, current: Int) =
        SliderItem(id = id, title = "Slider $id", min = min, max = max, current = current, onChange = { _, _ -> })

    @Test
    fun `sliders leave the grid and keep catalog order`() {
        val panel = QuickTiles.panel(
            listOf(
                CatalogGroup(
                    "g", null,
                    listOf(
                        ActionItem(id = "a", title = "Network", value = "Wi-Fi", run = {}),
                        slider(GamingSettingsCatalog.ID_SYSTEM_VOLUME, 0, 15, 7),
                        slider(GamingSettingsCatalog.ID_SYSTEM_BRIGHTNESS, 0, 255, 128),
                        toggle(GamingSettingsCatalog.ID_SYSTEM_DND, true),
                    ),
                ),
            ),
        )
        assertEquals(
            listOf(GamingSettingsCatalog.ID_SYSTEM_VOLUME, GamingSettingsCatalog.ID_SYSTEM_BRIGHTNESS),
            panel.sliders.map { it.id },
        )
        assertEquals(listOf("a", GamingSettingsCatalog.ID_SYSTEM_DND), panel.tiles.map { it.item.id })
        assertEquals(4, panel.focusCount)
    }

    @Test
    fun `a tile draws the catalog's own label and value, and never splits a title`() {
        // The catalog says what a row is CALLED and what it is SET TO;
        // there is no second rule here that reads state back out of a
        // title. A title that still contains a colon is a title.
        val stateful = QuickTiles.tile(
            ActionItem(
                id = GamingSettingsCatalog.ID_SYSTEM_NETWORK,
                title = "Network",
                value = "Wi-Fi, signal 3/4",
                run = {},
            ),
        )
        assertEquals("Network", stateful.label)
        assertEquals("Wi-Fi, signal 3/4", stateful.value)

        val plain = QuickTiles.tile(ActionItem(id = GamingSettingsCatalog.ID_SYSTEM_BLUETOOTH, title = "Bluetooth", run = {}))
        assertEquals("Bluetooth", plain.label)
        assertNull(plain.value)

        val colonInName = QuickTiles.tile(ActionItem(id = "x", title = "Wine: a compatibility layer", run = {}))
        assertEquals("Wine: a compatibility layer", colonInName.label)
        assertNull(colonInName.value)
    }

    @Test
    fun `toggles carry an on state, choices carry their current label`() {
        val on = QuickTiles.tile(toggle(GamingSettingsCatalog.ID_SYSTEM_DND, true))
        assertEquals(true, on.on)
        assertNull(on.value)
        assertEquals(QuickGlyph.MOON, on.glyph)

        val picked = QuickTiles.tile(choice(GamingSettingsCatalog.ID_SYSTEM_TIMEOUT, 3))
        assertNull(picked.on)
        assertEquals("Value 1", picked.value)
        assertEquals(QuickGlyph.TIMER, picked.glyph)
        assertFalse(picked.opens)

        val opens = QuickTiles.tile(
            NestedScreenItem(
                id = GamingSettingsCatalog.ID_SYSTEM_ANDROID_LINKS,
                title = "Android settings",
                registryId = "android_settings",
            ),
        )
        assertTrue(opens.opens)
        assertEquals(QuickGlyph.ANDROID, opens.glyph)
    }

    @Test
    fun `an item this view has never heard of still gets a tile`() {
        val tile = QuickTiles.tile(toggle("pref_something_new", false))
        assertEquals(QuickGlyph.GENERIC, tile.glyph)
        assertEquals(false, tile.on)
    }

    @Test
    fun `the tab is the System group plus the display-role rows, in that order`() {
        val groups = listOf(
            CatalogGroup(
                GamingSettingsCatalog.GROUP_GAMING, null,
                listOf(
                    toggle(GamingSettingsCatalog.ID_SHOW_HINTS, true),
                    choice(GamingSettingsCatalog.ID_DISPLAY_GAME_LAUNCH_TARGET, 4),
                    choice(GamingSettingsCatalog.ID_DISPLAY_SHELL_TARGET, 2),
                ),
            ),
            CatalogGroup(
                GamingSettingsCatalog.GROUP_SYSTEM, "System",
                listOf(toggle(GamingSettingsCatalog.ID_SYSTEM_DND, false)),
            ),
        )
        val ids = QuickTiles.systemGroups(groups).single().items.map { it.id }
        assertEquals(
            listOf(
                GamingSettingsCatalog.ID_SYSTEM_DND,
                GamingSettingsCatalog.ID_DISPLAY_SHELL_TARGET,
                GamingSettingsCatalog.ID_DISPLAY_GAME_LAUNCH_TARGET,
            ),
            ids,
        )
    }

    @Test
    fun `the grid is two columns until the sheet is wide enough for three`() {
        assertEquals(2, QuickTiles.columnsFor(480))
        assertEquals(2, QuickTiles.columnsFor(619))
        assertEquals(3, QuickTiles.columnsFor(620))
        assertEquals(3, QuickTiles.columnsFor(760))
    }

    @Test
    fun `slider steps scale with the range`() {
        assertEquals(1, QuickTiles.sliderStep(slider("v", 0, 15, 7)))
        assertEquals(12, QuickTiles.sliderStep(slider("b", 0, 255, 128)))
    }

    @Test
    fun `focus walks the sliders, then the grid, and back up into them`() {
        val sliders = 2
        val tiles = 7
        val columns = 3
        fun move(from: Int, dir: QuickMove) = QuickTiles.move(from, sliders, tiles, columns, dir)

        // Inside the slider strip: up and down step one row, left and
        // right are adjustments, so they do not move.
        assertEquals(1, move(0, QuickMove.DOWN))
        assertEquals(0, move(1, QuickMove.UP))
        assertEquals(0, move(0, QuickMove.UP))
        assertEquals(1, move(1, QuickMove.LEFT))
        assertEquals(1, move(1, QuickMove.RIGHT))

        // Down from the last slider lands on the first tile; up from the
        // first tile row goes back to the last slider.
        assertEquals(2, move(1, QuickMove.DOWN))
        assertEquals(1, move(2, QuickMove.UP))
        assertEquals(1, move(4, QuickMove.UP))

        // Inside the grid: left and right step one tile, up and down a
        // whole row, and neither leaves the grid at its edges.
        assertEquals(3, move(2, QuickMove.RIGHT))
        assertEquals(2, move(2, QuickMove.LEFT))
        assertEquals(5, move(2, QuickMove.DOWN))
        assertEquals(2, move(5, QuickMove.UP))
        // A short last row still catches a Down press instead of eating it.
        assertEquals(8, move(6, QuickMove.DOWN))
        assertEquals(8, move(8, QuickMove.RIGHT))
    }

    @Test
    fun `an empty tab has nowhere to move`() {
        assertEquals(0, QuickTiles.move(0, 0, 0, 2, QuickMove.DOWN))
    }

    @Test
    fun `a press cycles a small choice and opens a big one`() {
        assertEquals(QuickPress.TOGGLE, QuickTiles.pressKind(toggle("t", false)))
        assertEquals(QuickPress.CYCLE, QuickTiles.pressKind(choice("c", 4)))
        assertEquals(QuickPress.PICK, QuickTiles.pressKind(choice("c", 7)))
        assertEquals(QuickPress.RUN, QuickTiles.pressKind(ActionItem(id = "a", title = "Do", run = {})))
        assertEquals(
            QuickPress.OPEN,
            QuickTiles.pressKind(NestedScreenItem(id = "n", title = "More", registryId = "updates")),
        )
    }

    @Test
    fun `the controller line names one pad and counts the rest`() {
        assertNull(QuickTiles.describeControllers(emptyList()))
        assertNull(QuickTiles.describeControllers(listOf("  ")))
        assertEquals("Retroid Pocket 5", QuickTiles.describeControllers(listOf("Retroid Pocket 5")))
        assertEquals(
            "Retroid Pocket 5",
            QuickTiles.describeControllers(listOf("Retroid Pocket 5", "Retroid Pocket 5")),
        )
        assertEquals(
            "Retroid Pocket 5 +1",
            QuickTiles.describeControllers(listOf("Retroid Pocket 5", "8BitDo Ultimate")),
        )
    }
}
