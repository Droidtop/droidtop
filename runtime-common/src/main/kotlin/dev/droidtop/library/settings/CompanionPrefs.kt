package dev.droidtop.library.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The companion's navigation preferences (docs/SPEC.md "The companion's tabs", Droidtop/tracker#414): which tabs
 * sit on the bar in each mode, which tab it opens on, and what it does when a game starts. They are rows of the
 * Companion group of the catalog; there is no companion-only settings screen. Read once with [load] off the main
 * thread, then kept in [settings] so the companion and Settings follow each other live.
 *
 * A tab is named by its id: a [ControlPanel]'s name in lower case ("home", "apps", ...) or "plugin:<id>" for one
 * plugin's panel. Modes are the second screen's mode names (GAMING, STANDARD, DESKTOP).
 */
data class CompanionSettings(
    /** Tab ids on the bar per mode, in bar order, at most [CompanionPrefs.MAX_CHOSEN]. */
    val chosen: Map<String, List<String>> = emptyMap(),
    /** Per mode: [CompanionPrefs.OPEN_DEFAULT], [CompanionPrefs.OPEN_LAST] or a tab id. */
    val opening: Map<String, String> = emptyMap(),
    /** The tab last shown per mode, for [CompanionPrefs.OPEN_LAST]. */
    val last: Map<String, String> = emptyMap(),
    /** [CompanionPrefs.GAME_START_RUNNER], [CompanionPrefs.GAME_START_NONE] or a tab id. */
    val onGameStart: String = CompanionPrefs.GAME_START_RUNNER,
    /** The bar's first-run tip was dismissed. */
    val barTipSeen: Boolean = false,
    /** "Show message text" ([dev.droidtop.runtime.systemstatus.NotificationRows.MessageText] key). */
    val messageText: String = "unlocked",
    /** Home's low-battery line shows at or under this percent; 0 is off (the default). */
    val lowBattery: Int = 0,
    /** Ask before stopping (Quit, Restart, Kill, Stop, Clear all); on by default. */
    val askBeforeStopping: Boolean = true,
    /** Ask before load and overwrite (plugin rows flagged confirm); on by default. */
    val askBeforeLoad: Boolean = true,
    /** Move picture-in-picture video to the companion (with the helper app); on by default. */
    val pipToCompanion: Boolean = true,
) {
    /** The two ask-first rules as [GameControls] takes them. */
    val ask: AskFirst get() = AskFirst(askBeforeStopping, askBeforeLoad)

    fun chosen(mode: String): List<String> = chosen[mode] ?: CompanionPrefs.defaultChosen(mode)
    fun opening(mode: String): String = opening[mode] ?: CompanionPrefs.OPEN_DEFAULT
}

object CompanionPrefs {
    private const val PREFS = "companion"
    private const val KEY_CHOSEN = "chosen_"
    private const val KEY_OPENING = "opening_"
    private const val KEY_LAST = "last_"
    private const val KEY_GAME_START = "game_start"
    private const val KEY_BAR_TIP = "bar_tip_seen"
    private const val KEY_MIGRATED = "migrated_roles"
    private const val KEY_MESSAGE_TEXT = "message_text"
    private const val KEY_LOW_BATTERY = "low_battery"
    private const val KEY_ASK_STOP = "ask_before_stopping"
    private const val KEY_ASK_LOAD = "ask_before_load"
    private const val KEY_PIP = "pip_to_companion"

    const val MAX_CHOSEN = 4
    const val OPEN_DEFAULT = "default"
    const val OPEN_LAST = "last"
    const val GAME_START_RUNNER = "runner"
    const val GAME_START_NONE = "none"
    const val PLUGIN_PREFIX = "plugin:"

    val MODES = listOf("GAMING", "STANDARD", "DESKTOP")

    /** The id of a built-in tab. */
    fun id(panel: ControlPanel): String = panel.name.lowercase()

    /** The panel a tab id names (a plugin panel names [ControlPanel.PLUGINS]); null for an id nothing knows. */
    fun panelOf(id: String): ControlPanel? =
        if (id.startsWith(PLUGIN_PREFIX)) ControlPanel.PLUGINS else ControlPanel.entries.firstOrNull { id(it) == id }

    /** Defaults (docs/SPEC.md "The companion's tabs"): Desktop leads with Input, the others with Home. */
    fun defaultChosen(mode: String): List<String> = if (mode == "DESKTOP") {
        listOf(ControlPanel.INPUT, ControlPanel.HOME, ControlPanel.SYSTEM, ControlPanel.APPS).map(::id)
    } else {
        listOf(ControlPanel.HOME, ControlPanel.SYSTEM, ControlPanel.APPS, ControlPanel.SOCIAL).map(::id)
    }

    /** The tab [OPEN_DEFAULT] means: Input in Desktop (SPEC 6c), Home elsewhere. */
    fun defaultOpening(mode: String): String = id(if (mode == "DESKTOP") ControlPanel.INPUT else ControlPanel.HOME)

    /**
     * A stored tab id as today's: the Tasks tab became Apps. Anything else unknown is dropped by the caller, so a
     * value from another version never names a tab that is not there.
     */
    fun currentId(stored: String): String = when (stored) {
        "tasks" -> id(ControlPanel.APPS)
        else -> stored
    }

    private val state = MutableStateFlow(CompanionSettings())
    val settings: StateFlow<CompanionSettings> = state

    fun load(context: Context) {
        val prefs = prefs(context)
        if (!prefs.getBoolean(KEY_MIGRATED, false)) {
            val launcher = context.applicationContext.getSharedPreferences(LAUNCHER_PREFS_FILE_NAME, Context.MODE_PRIVATE)
            val migration = migrateRoles(launcher.all)
            val edit = prefs.edit()
            migration.forEach { (mode, tab) -> edit.putString(KEY_OPENING + mode, tab) }
            edit.putBoolean(KEY_MIGRATED, true).apply()
            launcher.edit().apply { MODES.forEach { remove(ROLE_KEY + it) } }.apply()
        }
        state.value = read(prefs.all)
    }

    /** What is stored, as settings; pure so the decoding is tested. */
    fun read(all: Map<String, *>): CompanionSettings = CompanionSettings(
        chosen = MODES.mapNotNull { mode ->
            (all[KEY_CHOSEN + mode] as? String)?.let { mode to decodeList(it) }
        }.toMap(),
        opening = MODES.mapNotNull { mode -> (all[KEY_OPENING + mode] as? String)?.let { mode to currentId(it) } }.toMap(),
        last = MODES.mapNotNull { mode -> (all[KEY_LAST + mode] as? String)?.let { mode to currentId(it) } }.toMap(),
        onGameStart = (all[KEY_GAME_START] as? String)?.let(::currentId) ?: GAME_START_RUNNER,
        barTipSeen = all[KEY_BAR_TIP] as? Boolean ?: false,
        messageText = all[KEY_MESSAGE_TEXT] as? String ?: "unlocked",
        lowBattery = all[KEY_LOW_BATTERY] as? Int ?: 0,
        askBeforeStopping = all[KEY_ASK_STOP] as? Boolean ?: true,
        askBeforeLoad = all[KEY_ASK_LOAD] as? Boolean ?: true,
        pipToCompanion = all[KEY_PIP] as? Boolean ?: true,
    )

    fun setPipToCompanion(context: Context, on: Boolean) {
        update { it.copy(pipToCompanion = on) }
        prefs(context).edit().putBoolean(KEY_PIP, on).apply()
    }

    fun setAskBeforeStopping(context: Context, on: Boolean) {
        update { it.copy(askBeforeStopping = on) }
        prefs(context).edit().putBoolean(KEY_ASK_STOP, on).apply()
    }

    fun setAskBeforeLoad(context: Context, on: Boolean) {
        update { it.copy(askBeforeLoad = on) }
        prefs(context).edit().putBoolean(KEY_ASK_LOAD, on).apply()
    }

    /** A stored bar list: comma separated, old ids made current, unknown ids and repeats dropped, at most four. */
    fun decodeList(stored: String): List<String> =
        stored.split(',').map { currentId(it.trim()) }.filter { it.isNotEmpty() && panelOf(it) != null }
            .distinct().take(MAX_CHOSEN)

    private const val ROLE_KEY = "pref_second_screen_role_"

    /**
     * The per-mode second-screen role ("Widgets and game info" or "Keyboard and trackpad", stored in the launcher
     * prefs as pref_second_screen_role_<MODE>) became the opening tab: a role that differs from the mode's
     * default is kept as an explicit opening tab, so nobody's second screen changes what it opens on.
     */
    fun migrateRoles(launcher: Map<String, *>): Map<String, String> = MODES.mapNotNull { mode ->
        val role = launcher[ROLE_KEY + mode] as? String ?: return@mapNotNull null
        val tab = when (role) {
            "INPUT" -> id(ControlPanel.INPUT)
            "COMPANION" -> id(ControlPanel.HOME)
            else -> return@mapNotNull null
        }
        if (tab == defaultOpening(mode)) null else mode to tab
    }.toMap()

    /**
     * The bar after its tab number [slot] + 1 is set to [tab] ("" empties it): the tab there is replaced, a tab
     * already elsewhere on the bar moves here, and the bar closes up so it never has a gap. Pure.
     */
    fun placeTab(chosen: List<String>, slot: Int, tab: String): List<String> {
        val slots = MutableList(maxOf(chosen.size, slot + 1)) { chosen.getOrElse(it) { "" } }
        for (i in slots.indices) if (i != slot && slots[i] == tab) slots[i] = ""
        slots[slot] = tab
        return slots.filter { it.isNotEmpty() }.take(MAX_CHOSEN)
    }

    fun setChosen(context: Context, mode: String, tabs: List<String>) {
        val list = tabs.filter { panelOf(it) != null }.distinct().take(MAX_CHOSEN)
        update { it.copy(chosen = it.chosen + (mode to list)) }
        prefs(context).edit().putString(KEY_CHOSEN + mode, list.joinToString(",")).apply()
    }

    fun setOpening(context: Context, mode: String, value: String) {
        update { it.copy(opening = it.opening + (mode to value)) }
        prefs(context).edit().putString(KEY_OPENING + mode, value).apply()
    }

    fun setLast(context: Context, mode: String, tab: String) {
        if (state.value.last[mode] == tab) return
        update { it.copy(last = it.last + (mode to tab)) }
        prefs(context).edit().putString(KEY_LAST + mode, tab).apply()
    }

    fun setOnGameStart(context: Context, value: String) {
        update { it.copy(onGameStart = value) }
        prefs(context).edit().putString(KEY_GAME_START, value).apply()
    }

    fun setLowBattery(context: Context, percent: Int) {
        update { it.copy(lowBattery = percent) }
        prefs(context).edit().putInt(KEY_LOW_BATTERY, percent).apply()
    }

    fun setMessageText(context: Context, key: String) {
        update { it.copy(messageText = key) }
        prefs(context).edit().putString(KEY_MESSAGE_TEXT, key).apply()
    }

    fun setBarTipSeen(context: Context) {
        update { it.copy(barTipSeen = true) }
        prefs(context).edit().putBoolean(KEY_BAR_TIP, true).apply()
    }

    /** Every companion navigation preference back to its default (the tips show again too). */
    fun reset(context: Context) {
        state.value = CompanionSettings()
        prefs(context).edit().clear().putBoolean(KEY_MIGRATED, true).apply()
    }

    private inline fun update(change: (CompanionSettings) -> CompanionSettings) {
        state.value = change(state.value)
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
