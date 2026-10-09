package dev.droidtop.library.settings

/**
 * The one restriction model for every surface that shows controls (docs/SPEC.md "UI modes and
 * ControlAccess", Droidtop/tracker#414). It takes the UI mode as a value and answers, for the
 * companion and for the Quick Menu alike, which panels show, which rows show inside them, and whether
 * the extras (long-press, Open on the companion screen, the provider line) exist. Nothing else gates
 * Kid or Kiosk: [UiMode.hidesSettings] and [UiMode.kidGamesOnly] read from here too.
 *
 * Pure: no Android, no Compose, so every rule is stated and checked in ControlAccessTest. Each mode's
 * rules are written out in [rules]; the `when` there is exhaustive, so a mode added to [UiMode] (Guest
 * is reserved) does not compile until its rules are written, and the test names every mode's sets.
 */
enum class ControlSurface { COMPANION, QUICK_MENU }

/**
 * Every panel a surface can show, named once for both: the companion's tabs (Home, Game, System, Apps,
 * Performance, Input, Social, Plugins) and the Quick Menu's sections (which add Notifications, Audio,
 * Display and Downloads). A surface maps its own tab or section onto one of these.
 */
enum class ControlPanel {
    HOME, GAME, SYSTEM, APPS, PERFORMANCE, INPUT, SOCIAL, PLUGINS, NOTIFICATIONS, AUDIO, DISPLAY, DOWNLOADS,
}

/**
 * Rows inside a panel that are not catalog items (catalog items are filtered by id, see
 * [ControlAccess.shows]). A surface draws such a row only when the mode's [ControlRules.rows] has it.
 */
enum class ControlRow {
    // Home
    PINS, NOW_PLAYING, NOTIFICATIONS, RECENT_APPS, SOCIAL, PLUGIN_PINS, LIBRARY, PROVIDER_LINE,
    // Game
    GAME_RESUME, GAME_QUIT, GAME_RESTART, GAME_KILL, GAME_EMULATOR, GAME_OVERLAY, GAME_PERFORMANCE_MODE,
    GAME_PLUGIN_ROWS, GAME_SCREENSHOT,
    // Performance
    STATS, PERFORMANCE_CONTROLS, LOGS,
}

/** Everything one UI mode allows, written out per mode in [ControlAccess.rules]. */
data class ControlRules(
    val companionPanels: Set<ControlPanel>,
    val quickMenuPanels: Set<ControlPanel>,
    /** Panels shown but not changeable: their controls draw as values only. */
    val readOnlyPanels: Set<ControlPanel>,
    val rows: Set<ControlRow>,
    /** Catalog ids a person may pin on Home; null allows every pinnable item. */
    val pinIds: Set<String>?,
    /** Settings and the device-management places (the left menu's places, the shell's Settings section). */
    val settings: Boolean,
    val kidGamesOnly: Boolean,
    val longPress: Boolean,
    val openOnCompanion: Boolean,
    /** Every row that stops, quits or overwrites asks first, whatever the person's own settings say. */
    val alwaysAsk: Boolean,
    /** Catalog items and groups this mode never shows, on any surface. */
    val hiddenItemIds: Set<String>,
    val hiddenGroupIds: Set<String>,
)

object ControlAccess {

    /** The catalog group of the companion's own preferences (docs/SPEC.md "The companion's tabs"). */
    const val GROUP_COMPANION = "companion"

    /** Kid's optional maximum volume, applied in the one volume write path. */
    const val ID_KID_VOLUME_CAP = "pref_kid_volume_cap"

    /** The optional passkey for leaving Kid and Kiosk. */
    const val ID_UI_MODE_PASSKEY = "pref_ui_mode_passkey"

    private val FULL = ControlRules(
        companionPanels = setOf(
            ControlPanel.HOME, ControlPanel.GAME, ControlPanel.SYSTEM, ControlPanel.APPS, ControlPanel.PERFORMANCE,
            ControlPanel.INPUT, ControlPanel.SOCIAL, ControlPanel.PLUGINS,
        ),
        quickMenuPanels = ControlPanel.entries.toSet(),
        readOnlyPanels = emptySet(),
        rows = ControlRow.entries.toSet(),
        pinIds = null,
        settings = true,
        kidGamesOnly = false,
        longPress = true,
        openOnCompanion = true,
        alwaysAsk = false,
        hiddenItemIds = emptySet(),
        hiddenGroupIds = emptySet(),
    )

    /**
     * Kiosk and Kid: a handheld handed to somebody else. The companion keeps Home (volume and
     * brightness only), Game (Resume and Quit, both asking) and Performance as figures only. The Quick
     * Menu keeps its sections as before. The items that control the restriction itself (the Companion
     * group, the volume cap, the passkey, the UI mode) are hidden on every surface, so the person it
     * restricts cannot undo it from inside.
     */
    private val RESTRICTED = ControlRules(
        companionPanels = setOf(ControlPanel.HOME, ControlPanel.GAME, ControlPanel.PERFORMANCE),
        quickMenuPanels = ControlPanel.entries.toSet(),
        readOnlyPanels = setOf(ControlPanel.PERFORMANCE),
        rows = setOf(ControlRow.PINS, ControlRow.GAME_RESUME, ControlRow.GAME_QUIT, ControlRow.STATS),
        pinIds = setOf(GamingSettingsCatalog.ID_SYSTEM_VOLUME, GamingSettingsCatalog.ID_SYSTEM_BRIGHTNESS),
        settings = false,
        kidGamesOnly = false,
        longPress = false,
        openOnCompanion = false,
        alwaysAsk = true,
        hiddenItemIds = setOf(
            GamingSettingsCatalog.ID_UI_MODE, GamingSettingsCatalog.ID_DISPLAY_COMPANION, ID_KID_VOLUME_CAP, ID_UI_MODE_PASSKEY,
        ),
        hiddenGroupIds = setOf(GROUP_COMPANION),
    )

    fun rules(mode: UiMode): ControlRules = when (mode) {
        UiMode.FULL -> FULL
        UiMode.KIOSK -> RESTRICTED
        UiMode.KID -> RESTRICTED.copy(kidGamesOnly = true)
    }

    fun panels(mode: UiMode, surface: ControlSurface): Set<ControlPanel> = when (surface) {
        ControlSurface.COMPANION -> rules(mode).companionPanels
        ControlSurface.QUICK_MENU -> rules(mode).quickMenuPanels
    }

    fun readOnly(mode: UiMode, panel: ControlPanel): Boolean = panel in rules(mode).readOnlyPanels

    fun shows(mode: UiMode, row: ControlRow): Boolean = row in rules(mode).rows

    /** Whether a catalog item shows in [mode]; [groupId] is the catalog group it came from, when known. */
    fun shows(mode: UiMode, itemId: String, groupId: String? = null): Boolean {
        val rules = rules(mode)
        return itemId !in rules.hiddenItemIds && (groupId == null || groupId !in rules.hiddenGroupIds)
    }

    /** Whether [itemId] may be pinned on Home in [mode]. */
    fun pinnable(mode: UiMode, itemId: String): Boolean =
        shows(mode, itemId) && rules(mode).pinIds?.contains(itemId) != false

    /** [groups] without what [mode] hides: whole groups, then single items; a group left empty goes too. */
    fun filter(mode: UiMode, groups: List<CatalogGroup>): List<CatalogGroup> {
        val rules = rules(mode)
        if (rules.hiddenGroupIds.isEmpty() && rules.hiddenItemIds.isEmpty()) return groups
        return groups
            .filter { it.id !in rules.hiddenGroupIds }
            .map { group -> group.copy(items = group.items.filter { it.id !in rules.hiddenItemIds }) }
            .filter { it.items.isNotEmpty() }
    }
}
