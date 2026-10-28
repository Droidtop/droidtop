package dev.droidtop.shell.gamepad

import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.ChoiceItem
import dev.droidtop.library.settings.ControlAccess
import dev.droidtop.library.settings.ControlPanel
import dev.droidtop.library.settings.ControlSurface
import dev.droidtop.library.settings.GamingSettingsCatalog
import dev.droidtop.library.settings.NestedScreenItem
import dev.droidtop.library.settings.SliderItem
import dev.droidtop.library.settings.ToggleItem
import dev.droidtop.library.settings.UiMode

/**
 * The Quick Menu's System tab as tiles: the pure model behind it, with
 * no Compose and no Android in it, so every rule here is unit-testable
 * (QuickTilesTest) instead of only visible on a device.
 *
 * The tab is Android's quick-settings shape -- a status header, the two
 * continuous controls (brightness, volume) as sliders at the top, then a
 * grid of large tiles -- rendered over the SAME settings catalog the
 * Settings section renders as a list (docs/SPEC.md settings
 * architecture). Nothing here owns a value or a write path: a tile
 * carries the catalog item itself and hands the press straight back to
 * it. That is what keeps this a VIEW of the catalog: add a System
 * setting to the catalog and a tile for it appears here, with no edit to
 * this file.
 */

/**
 * The Quick Menu's sections: the icon rail (docs/SPEC.md "Quick Menu: a
 * branching panel", Droidtop/tracker#258). Order is rail order. The rail is
 * for quick management and for the two places kept here, Social and
 * Plugins; the other destinations are the left menu's, and Get games leads
 * Downloads and Updates.
 */
enum class QuickSection(val glyph: QuickGlyph, val panel: ControlPanel) {
    APPS(QuickGlyph.APPS, ControlPanel.APPS),
    NOTIFICATIONS(QuickGlyph.BELL, ControlPanel.NOTIFICATIONS),
    // The Social place in the sheet: friends and their games, then conversations, a chat opening in place (Droidtop/tracker#644).
    SOCIAL(QuickGlyph.PEOPLE, ControlPanel.SOCIAL),
    // The radios, out of what was one System wall of about 20 tiles (owner, 2026-10-23, Droidtop/tracker#610).
    CONNECTIONS(QuickGlyph.NETWORK, ControlPanel.CONNECTIONS),
    // Everything else the System tab held: switching mode, power, screenshot, the app in front.
    SYSTEM(QuickGlyph.POWER, ControlPanel.SYSTEM),
    PERFORMANCE(QuickGlyph.GAUGE, ControlPanel.PERFORMANCE),
    AUDIO(QuickGlyph.VOLUME, ControlPanel.AUDIO),
    DISPLAY(QuickGlyph.DISPLAY, ControlPanel.DISPLAY),
    DOWNLOADS(QuickGlyph.DOWNLOAD, ControlPanel.DOWNLOADS),
    PLUGINS(QuickGlyph.PLUGIN, ControlPanel.PLUGINS),
    // The person's own name and picture (Droidtop/tracker#586).
    PROFILE(QuickGlyph.PROFILE, ControlPanel.PROFILE),
    ;

    /** The section's name, kept with the order the person sets ([QuickMenuOrder]), so the two cannot differ. */
    val label: String get() = dev.droidtop.library.settings.QuickMenuOrder.label(panel)
}

/**
 * One tile. [value] is what the setting is set to (a choice's label, a
 * network's state); [on] is set only for the things that genuinely have
 * an on/off state, and is what paints a tile lit rather than dim -- the
 * quick-settings cue. [opens] marks a tile that leads somewhere (a
 * nested screen, a system panel) rather than changing a value in place.
 */
data class QuickTile(
    val item: CatalogItem,
    val label: String,
    val value: String?,
    val on: Boolean?,
    val glyph: QuickGlyph,
    val opens: Boolean,
)

/** The whole tab: sliders first, then the tile grid, in catalog order. */
data class QuickPanel(val sliders: List<SliderItem>, val tiles: List<QuickTile>) {
    val focusCount: Int get() = sliders.size + tiles.size
}

/** D-pad directions, named so the movement rules can be tested. */
enum class QuickMove { UP, DOWN, LEFT, RIGHT }

object QuickTiles {

    /**
     * What the Audio and Display sections claim from the catalog's quick
     * System group (and, for the display rows, from Settings' own screens
     * group), by id. Everything else in that group stays on System, so an
     * item added to the catalog still appears somewhere with no edit here.
     */
    val AUDIO_IDS = listOf(
        GamingSettingsCatalog.ID_SYSTEM_VOLUME,
        // The interface sounds' own volume, beside the media volume (Droidtop/tracker#836).
        GamingSettingsCatalog.ID_NAVIGATION_SOUNDS,
        GamingSettingsCatalog.ID_AUDIO_OUTPUT,
        GamingSettingsCatalog.ID_SYSTEM_DND,
        GamingSettingsCatalog.ID_SYSTEM_DND_GRANT,
        GamingSettingsCatalog.ID_SYSTEM_MIC_MUTE,
    )

    /** What the Connections section claims: the device's radios and its network. */
    val CONNECTION_IDS = listOf(
        GamingSettingsCatalog.ID_SYSTEM_NETWORK,
        GamingSettingsCatalog.ID_SYSTEM_WIFI,
        GamingSettingsCatalog.ID_SYSTEM_BLUETOOTH,
        GamingSettingsCatalog.ID_SYSTEM_AIRPLANE,
        GamingSettingsCatalog.ID_SYSTEM_VPN,
        GamingSettingsCatalog.ID_SYSTEM_LOCATION,
    )

    val DISPLAY_IDS = listOf(
        GamingSettingsCatalog.ID_SYSTEM_BRIGHTNESS,
        GamingSettingsCatalog.ID_SYSTEM_BRIGHTNESS_GRANT,
        GamingSettingsCatalog.ID_SYSTEM_ADAPTIVE,
        GamingSettingsCatalog.ID_SYSTEM_ROTATE,
        GamingSettingsCatalog.ID_SYSTEM_TIMEOUT,
        GamingSettingsCatalog.ID_ORIENTATION_HERE,
        dev.droidtop.runtime.systemstatus.AccessibilityControls.ID_FONT,
        dev.droidtop.runtime.systemstatus.AccessibilityControls.ID_DISPLAY_SIZE,
        GamingSettingsCatalog.ID_DISPLAY_GAME_LAUNCH_TARGET,
        GamingSettingsCatalog.ID_DISPLAY_SWAP,
        GamingSettingsCatalog.ID_DISPLAY_REINIT,
    )

    /**
     * The sections the rail shows now: those [mode] allows on the Quick Menu ([ControlAccess], the one
     * restriction model), Plugins only when a plugin is installed or plugins can be managed, Social only where its
     * place opens ([hasSocial]: not in Kiosk and Kid, which hide the places). The running game has no section: what
     * can be done with it is L2's (Droidtop/tracker#740, [RunningGameSheet]).
     */
    fun visibleSections(
        hasPlugins: Boolean,
        hasSocial: Boolean = true,
        mode: UiMode = UiMode.FULL,
        // The person's order (Settings > Shell > Quick Menu order); a section it does not name keeps its own place.
        order: List<ControlPanel> = QuickSection.entries.map { it.panel },
    ): List<QuickSection> {
        val allowed = ControlAccess.panels(mode, ControlSurface.QUICK_MENU)
        return QuickSection.entries.filter {
            it.panel in allowed && (it != QuickSection.PLUGINS || hasPlugins) &&
                (it != QuickSection.SOCIAL || hasSocial)
        }.sortedBy { section -> order.indexOf(section.panel).takeIf { it >= 0 } ?: (order.size + section.ordinal) }
    }

    /**
     * Where the menu opens: Notifications once notification access is
     * granted, else System rather than an empty Notifications list
     * (Droidtop/tracker#180).
     */
    fun initialSection(notificationsGranted: Boolean): QuickSection = when {
        notificationsGranted -> QuickSection.NOTIFICATIONS
        else -> QuickSection.SYSTEM
    }

    /** L1/R1 on the rail: the neighbouring section, wrapping at the ends; [current] missing from [visible] lands on the first. */
    fun stepSection(visible: List<QuickSection>, current: QuickSection, step: Int): QuickSection {
        if (visible.isEmpty()) return current
        val i = visible.indexOf(current)
        if (i < 0) return visible.first()
        return visible[(i + step + visible.size) % visible.size]
    }

    /**
     * The catalog items one tile-grid section shows (System, Audio or
     * Display), as the single group [panel] takes. Pulled from the live
     * Gaming catalog by id, never copied, after [ControlAccess] has taken
     * out what [mode] hides.
     */
    fun sectionGroups(catalog: List<CatalogGroup>, section: QuickSection, mode: UiMode = UiMode.FULL): List<CatalogGroup> {
        val all = ControlAccess.filter(mode, catalog)
        val system = all.firstOrNull { it.id == GamingSettingsCatalog.GROUP_SYSTEM }?.items.orEmpty()
        val claimed = AUDIO_IDS + DISPLAY_IDS + CONNECTION_IDS
        val byId = (system + all.filter { it.id != GamingSettingsCatalog.GROUP_SYSTEM }.flatMap { it.items })
            .associateBy { it.id }
        val items = when (section) {
            QuickSection.AUDIO -> AUDIO_IDS.mapNotNull { byId[it] }
            QuickSection.DISPLAY -> DISPLAY_IDS.mapNotNull { byId[it] }
            QuickSection.CONNECTIONS -> CONNECTION_IDS.mapNotNull { byId[it] }
            else -> systemGroups(all).flatMap { it.items }.filter { it.id !in claimed }
        }
        return listOf(CatalogGroup(id = "quick_${section.name.lowercase()}", title = null, items = items))
    }

    /**
     * The configuration rows the System tab shows besides the catalog's
     * own quick-only System group: the screen that manages the device. It
     * lives in Settings (it is configuration), so the tab takes a view of
     * it by id, the same item, never a copy. The two display roles are the
     * Display tab's ([DISPLAY_IDS]); they were listed here too and always
     * filtered out again (Droidtop/tracker#571 row 30).
     */
    val CONFIGURATION_IDS = listOf(
        GamingSettingsCatalog.ID_SYSTEM_ANDROID_LINKS,
    )

    /**
     * The System tab's own single group: the catalog's System group
     * followed by [CONFIGURATION_IDS], both pulled out of the live
     * Gaming catalog.
     */
    fun systemGroups(all: List<CatalogGroup>): List<CatalogGroup> {
        val system = all.firstOrNull { it.id == GamingSettingsCatalog.GROUP_SYSTEM }?.items.orEmpty()
        val displays = all
            .filter { it.id != GamingSettingsCatalog.GROUP_SYSTEM }
            .flatMap { it.items }
            .filter { it.id in CONFIGURATION_IDS }
            .sortedBy { CONFIGURATION_IDS.indexOf(it.id) }
        return listOf(CatalogGroup(id = "quick_system", title = null, items = system + displays))
    }

    /** Splits the tab into its slider strip and its tile grid. */
    fun panel(groups: List<CatalogGroup>): QuickPanel {
        val items = groups.flatMap { it.items }
        return QuickPanel(
            sliders = items.filterIsInstance<SliderItem>(),
            tiles = items.filterNot { it is SliderItem }.map(::tile),
        )
    }

    fun tile(item: CatalogItem): QuickTile {
        val value = when (item) {
            is ChoiceItem -> item.currentLabel()
            is ToggleItem -> null
            // The item's own value column, and only that: the network's
            // connection, VPN's on/off, "Needs permission". A tile used
            // to also split a "Name: state" title back apart for the
            // rows that still carried their state inside their title,
            // which meant the same fact was formatted in two places by
            // two rules -- and the rule the LIST used was the one that
            // was wrong (rig, build 546). The catalog says what a row is
            // called and what it is set to; a tile just draws them.
            else -> item.value
        }
        return QuickTile(
            item = item,
            label = item.title,
            value = value,
            // Every on/off state is a switch on the tile, the system's ones included (Airplane mode,
            // Bluetooth, VPN: ActionItem.state), as in Settings' rows.
            on = when (item) {
                is ToggleItem -> item.current
                is ActionItem -> item.state
                else -> null
            },
            glyph = glyphFor(item),
            opens = item is NestedScreenItem,
        )
    }

    fun glyphFor(item: CatalogItem): QuickGlyph = when (item.id) {
        GamingSettingsCatalog.ID_SYSTEM_NETWORK -> QuickGlyph.NETWORK
        GamingSettingsCatalog.ID_SYSTEM_VOLUME -> QuickGlyph.VOLUME
        GamingSettingsCatalog.ID_SYSTEM_BRIGHTNESS,
        GamingSettingsCatalog.ID_SYSTEM_BRIGHTNESS_GRANT,
        GamingSettingsCatalog.ID_SYSTEM_ADAPTIVE,
        -> QuickGlyph.BRIGHTNESS
        GamingSettingsCatalog.ID_SYSTEM_DND,
        GamingSettingsCatalog.ID_SYSTEM_DND_GRANT,
        -> QuickGlyph.MOON
        GamingSettingsCatalog.ID_SYSTEM_ROTATE -> QuickGlyph.ROTATE
        GamingSettingsCatalog.ID_SYSTEM_TIMEOUT -> QuickGlyph.TIMER
        GamingSettingsCatalog.ID_SYSTEM_BLUETOOTH -> QuickGlyph.BLUETOOTH
        GamingSettingsCatalog.ID_SYSTEM_VPN -> QuickGlyph.VPN
        GamingSettingsCatalog.ID_SYSTEM_BATTERY -> QuickGlyph.BATTERY
        GamingSettingsCatalog.ID_SYSTEM_AIRPLANE -> QuickGlyph.AIRPLANE
        GamingSettingsCatalog.ID_SYSTEM_WIFI -> QuickGlyph.NETWORK
        GamingSettingsCatalog.ID_SYSTEM_MIC_MUTE -> QuickGlyph.VOLUME
        GamingSettingsCatalog.ID_SYSTEM_SLEEP -> QuickGlyph.MOON
        GamingSettingsCatalog.ID_SYSTEM_POWER_MENU,
        GamingSettingsCatalog.ID_SYSTEM_POWER_OFF,
        GamingSettingsCatalog.ID_SYSTEM_RESTART,
        -> QuickGlyph.POWER
        GamingSettingsCatalog.ID_AUDIO_OUTPUT -> QuickGlyph.VOLUME
        GamingSettingsCatalog.ID_ORIENTATION -> QuickGlyph.ROTATE
        GamingSettingsCatalog.ID_SYSTEM_UPDATES -> QuickGlyph.UPDATE
        GamingSettingsCatalog.ID_SYSTEM_ANDROID_LINKS -> QuickGlyph.ANDROID
        GamingSettingsCatalog.ID_SYSTEM_LEAVE_UI_MODE -> QuickGlyph.EXIT
        GamingSettingsCatalog.ID_SYSTEM_SWITCH_MODE -> QuickGlyph.SWAP
        GamingSettingsCatalog.ID_DISPLAY_GAME_LAUNCH_TARGET -> QuickGlyph.GAMEPAD
        GamingSettingsCatalog.ID_DISPLAY_SWAP -> QuickGlyph.SWAP
        GamingSettingsCatalog.ID_DISPLAY_REINIT -> QuickGlyph.DISPLAY
        GamingSettingsCatalog.ID_SCREENSHOT -> QuickGlyph.CAMERA
        GamingSettingsCatalog.ID_STOP_FOREGROUND -> QuickGlyph.STOP
        GamingSettingsCatalog.ID_COMPANION_WAKE,
        GamingSettingsCatalog.ID_DISPLAY_COMPANION,
        -> QuickGlyph.SECOND_SCREEN
        GamingSettingsCatalog.ID_SYSTEM_LOCATION -> QuickGlyph.LOCATION
        GamingSettingsCatalog.ID_SYSTEM_FLASHLIGHT -> QuickGlyph.FLASHLIGHT
        GamingSettingsCatalog.ID_SYSTEM_SOCIAL -> QuickGlyph.PEOPLE
        GamingSettingsCatalog.ID_PERFORMANCE_MODE -> QuickGlyph.GAUGE
        GamingSettingsCatalog.ID_DOWNLOADS -> QuickGlyph.DOWNLOAD
        GamingSettingsCatalog.ID_CONTROLLER -> QuickGlyph.GAMEPAD
        GamingSettingsCatalog.ID_TYPE_FROM_DEVICE,
        GamingSettingsCatalog.ID_DISPLAY_KEYBOARD,
        GamingSettingsCatalog.ID_DISPLAY_KEYBOARD_ACCESSIBILITY,
        GamingSettingsCatalog.ID_DISPLAY_KEYBOARD_PLACEMENT,
        GamingSettingsCatalog.ID_DISPLAY_KEYBOARD_RESTRICTED,
        GamingSettingsCatalog.ID_KEYBOARD_PICK,
        GamingSettingsCatalog.ID_KEYBOARD_ENABLE,
        -> QuickGlyph.KEYBOARD
        // An item this file has never heard of still gets a real tile --
        // that is the point of rendering the catalog rather than a
        // hand-listed set.
        else -> QuickGlyph.GENERIC
    }

    /**
     * One column when the side panel leaves little room, two on a wider
     * one, three when there is plenty -- the same call Android's quick
     * settings makes. [sheetWidthDp] is the pane beside the rail.
     */
    fun columnsFor(sheetWidthDp: Int): Int = when {
        sheetWidthDp >= 620 -> 3
        sheetWidthDp >= 300 -> 2
        else -> 1
    }

    /**
     * A slider's D-pad step: about a twentieth of its range, so
     * brightness (0..255) moves in visible jumps while volume (0..15)
     * still moves one notch at a time.
     */
    fun sliderStep(item: SliderItem): Int = ((item.max - item.min) / 20).coerceAtLeast(1)

    /**
     * Focus across the whole tab as one index: the sliders first (one
     * per row), then the tiles in grid order. Left/Right inside the
     * slider strip is an ADJUST, not a move, so it returns the same
     * index and the caller adjusts instead.
     */
    fun move(index: Int, sliderCount: Int, tileCount: Int, columns: Int, direction: QuickMove): Int {
        val total = sliderCount + tileCount
        if (total == 0) return 0
        val current = index.coerceIn(0, total - 1)
        val inSliders = current < sliderCount
        return when (direction) {
            QuickMove.UP -> when {
                inSliders -> (current - 1).coerceAtLeast(0)
                current - sliderCount < columns -> if (sliderCount > 0) sliderCount - 1 else current
                else -> current - columns
            }
            QuickMove.DOWN -> when {
                inSliders && current < sliderCount - 1 -> current + 1
                inSliders -> if (tileCount > 0) sliderCount else current
                else -> (current + columns).coerceAtMost(total - 1)
            }
            QuickMove.LEFT -> if (inSliders) current else (current - 1).coerceAtLeast(sliderCount)
            QuickMove.RIGHT -> if (inSliders) current else (current + 1).coerceAtMost(total - 1)
        }
    }

    /**
     * What a tile press does, as a value the view switches on -- so the
     * rule "small choice sets cycle, big ones open a picker" is one
     * testable statement instead of a branch buried in a click handler.
     * The threshold matches the settings list's own (SettingsCatalogView).
     */
    fun pressKind(item: CatalogItem): QuickPress = when (item) {
        is ToggleItem -> QuickPress.TOGGLE
        is ChoiceItem -> if (item.options.size <= 4) QuickPress.CYCLE else QuickPress.PICK
        is NestedScreenItem -> QuickPress.OPEN
        is AsyncActionItem -> QuickPress.RUN_ASYNC
        is ActionItem -> QuickPress.RUN
        else -> QuickPress.RUN
    }

    /** Controller line for the status header: one name, or the first and a count. */
    fun describeControllers(names: List<String>): String? {
        val clean = names.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return when {
            clean.isEmpty() -> null
            clean.size == 1 -> clean.first()
            else -> "${clean.first()} +${clean.size - 1}"
        }
    }
}

enum class QuickPress { TOGGLE, CYCLE, PICK, OPEN, RUN, RUN_ASYNC }
