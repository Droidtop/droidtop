package dev.droidtop.library.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * The shared, renderer-agnostic settings model (docs/SPEC.md settings
 * architecture): a catalog owns the settings DATA and LAYOUT (which
 * settings exist, their grouping and order, their current values, and
 * what changing them does), and every UI surface just chromes it in its
 * own visual context. The Preference-based unified settings screen
 * (:shell-default) and Gaming's own in-shell themed settings section
 * (:shell-gamepad) both render the SAME catalogs -- neither hand-picks
 * its own subset, neither duplicates a write path, and adding a setting
 * to a catalog makes it appear in every surface at once. Per direction,
 * this is total: EVERYTHING that is a droidtop setting lives in this
 * model -- flat preference lists and the management surfaces (console
 * systems, platform CRUD, ROM folders, scraper credentials) alike, the
 * latter as nested [CatalogScreen]s.
 *
 * Item ids are the historical SharedPreferences keys where one exists --
 * they double as the stable identity a renderer may use to substitute a
 * native fulfillment for an item's default [ActionItem.run] (e.g. the
 * in-shell renderer performs "rescan library" by bumping its own scan
 * trigger instead of relaunching MainActivity with an Intent extra, and
 * opens the theme browser inline instead of deep-linking to itself).
 * Every default implementation must still be real and correct on its own
 * so a renderer with no special knowledge gets working behavior for
 * everything.
 */
/**
 * A category's identity, drawn once per row that OPENS something (a
 * [NestedScreenItem]/[SubScreenItem]) rather than on every leaf toggle --
 * the same density a polished console settings list (Switch, Steam Deck)
 * uses: enough to scan the list by shape, not an icon competing with
 * every value column.
 *
 * Wired into the Gaming shell's own row (`MenuRow`/`CatalogIconGlyphs.kt`
 * in `:shell-gamepad`, settings polish pass 2026-09-25) and, since the H4
 * shared-row-language pass, into the Preference/touch surface too
 * (`:shell-default`, `CatalogPreferenceBuilder.applyCatalogIcon`,
 * `CatalogIconDrawables.kt`): the same Material Symbols Outlined choice
 * per icon on both surfaces, fetched as real Android `<vector>` resources
 * (`google/material-design-icons`, Apache-2.0) rather than a second
 * Compose dependency added to this forked launcher3 tree.
 */
enum class CatalogIcon {
    GLOBAL, MODES, DATA, HOME_ROLE,
    GAMING, DESKTOP, STANDARD,
    LIBRARY, SCRAPER, CONSOLE_SYSTEMS, GAME_FOLDERS, PLATFORMS, ORPHANED_MEDIA,
    WINDOWS_GAMES, CONTAINERS, ENGINEHOST,
    APPEARANCE, THEME, SCREENSAVER,
    INPUT, CONTROLLER, KEYBOARD,
    DISPLAY, SYSTEM_UPDATES, ANDROID_SETTINGS,
    INTEGRATIONS, SEARCH,
}

sealed interface CatalogItem {
    val id: String
    val title: String
    val subtitle: String?

    /** A category glyph for rows that open something. Null draws none -- see [CatalogIcon]. */
    val icon: CatalogIcon? get() = null

    /**
     * What this setting is SET TO, for the value column every surface
     * draws (a settings row's right-hand column, a Quick Menu tile's
     * second line). A choice, a toggle and a slider each derive it from
     * their own state; the kinds that have no state of their own carry
     * it here, because state belongs in the value column and not inside
     * the title. The rig read "Network: Wi-Fi, signal 4/4" and
     * "VPN: off" as TITLES while every other row put its state in the
     * column beside it.
     */
    val value: String? get() = null
}

data class ChoiceOption(val value: String, val label: String)

/**
 * A pick-one setting. [current] is the live value at catalog-build time.
 * Renderers choose their own picker shape (in-place left/right cycling
 * for small option sets, a picker list for large ones, a dialog on the
 * Preference surface) -- the model doesn't care.
 */
class ChoiceItem(
    override val id: String,
    override val title: String,
    override val subtitle: String? = null,
    val options: List<ChoiceOption>,
    val current: String?,
    val onSelect: (Context, String) -> Unit,
) : CatalogItem {
    fun currentLabel(): String? = options.firstOrNull { it.value == current }?.label ?: current
}

class ToggleItem(
    override val id: String,
    override val title: String,
    override val subtitle: String? = null,
    val current: Boolean,
    val onToggle: (Context, Boolean) -> Unit,
) : CatalogItem

/** An integer range setting (rendered as a seekbar or left/right stepper). */
class SliderItem(
    override val id: String,
    override val title: String,
    override val subtitle: String? = null,
    val min: Int,
    val max: Int,
    val current: Int,
    val onChange: (Context, Int) -> Unit,
) : CatalogItem

/**
 * A free-text setting (names, credentials, am-start argument templates).
 * [secret] asks the renderer for password-style masking; [multiline] for
 * a taller editor. Write-through: [onChange] fires when the surface
 * commits an edit (dialog OK / field defocus), and the catalog persists
 * it -- there is no separate save step unless the owning screen adds an
 * explicit [ActionItem] for one (e.g. a create-new form whose fields
 * buffer into the catalog object until "Save" commits them atomically).
 *
 * [onChange] is suspend and main-safe: renderers call it from a coroutine
 * on the main thread and re-read the screen once it returns, so a handler
 * writing Room (a suspend DAO call dispatches itself) never blocks the UI
 * and the refreshed list already shows the write. Main, not IO, because
 * some handlers start activities through LaunchDisplay, whose screen
 * chooser is UI (audit 2026-09-24, C4).
 */
class TextInputItem(
    override val id: String,
    override val title: String,
    override val subtitle: String? = null,
    // The text IS this setting's value column (CatalogItem.value), so it
    // overrides rather than shadowing it under the same name.
    override val value: String,
    val secret: Boolean = false,
    val multiline: Boolean = false,
    val onChange: suspend (Context, String) -> Unit,
) : CatalogItem

/**
 * A fire-and-done action; [run] is the default fulfillment. A non-null
 * [confirmTitle] asks the surface to get an explicit yes first
 * (destructive actions: delete a platform, remove a folder).
 */
class ActionItem(
    override val id: String,
    override val title: String,
    override val subtitle: String? = null,
    override val value: String? = null,
    val confirmTitle: String? = null,
    override val icon: CatalogIcon? = null,
    val run: (Context) -> Unit,
) : CatalogItem

/**
 * An action with a real async lifecycle the surface should show. [run]
 * reports live progress through its `onStatus` callback ("Scraping
 * 3/40...") and returns the final user-facing outcome text. Renderers run
 * it off the main thread and re-read the screen when it returns, so this
 * is also the kind for any action that writes a database. [confirmTitle]
 * works as on [ActionItem].
 */
class AsyncActionItem(
    override val id: String,
    override val title: String,
    override val subtitle: String? = null,
    override val value: String? = null,
    val confirmTitle: String? = null,
    val run: suspend (Context, onStatus: (String) -> Unit) -> String,
) : CatalogItem

/**
 * A system folder pick (SAF OpenDocumentTree). The renderer owns
 * launching the real picker; [onPicked] receives the granted tree URI
 * (persistable permission already taken by the renderer) and returns
 * null on success or a user-facing error ("couldn't resolve that folder
 * to a real path").
 */
class FolderPickItem(
    override val id: String,
    override val title: String,
    override val subtitle: String? = null,
    val onPicked: (Context, Uri) -> String?,
) : CatalogItem

/**
 * A document to write or read through the system's own file picker
 * (ACTION_CREATE_DOCUMENT with [createName], ACTION_OPEN_DOCUMENT
 * without): a settings backup and its restore. The renderer owns the
 * picker; [onPicked] receives the chosen document and returns what
 * happened, success or failure, for the renderer to show.
 */
class DocumentPickItem(
    override val id: String,
    override val title: String,
    override val subtitle: String? = null,
    val mimeType: String,
    val createName: String? = null,
    val onPicked: (Context, Uri) -> String,
) : CatalogItem {
    fun pickerIntent(): Intent =
        Intent(if (createName != null) Intent.ACTION_CREATE_DOCUMENT else Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
            createName?.let { putExtra(Intent.EXTRA_TITLE, it) }
        }
}

/**
 * A dynamically-built nested settings screen, rendered by the SAME
 * surface that showed the item opening it (in-shell pushes it on its nav
 * stack; the Preference surface opens a child fragment). [groups] is
 * re-invoked on every (re)entry and after every value change, so a
 * screen whose content is live data (the console-systems folder list,
 * the platform list) stays current for free. Suspend, because real
 * screens are built from Room queries and filesystem walks -- renderers
 * call it from a coroutine and the builder does its own IO dispatching.
 */
class CatalogScreen(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val groups: suspend (Context) -> List<CatalogGroup>,
)

/**
 * Opens a nested [CatalogScreen] in the same surface. Carry the screen
 * [inline] when the owning catalog builds it itself; reference a
 * [SettingsScreenRegistry] id instead when the screen's DATA lives in a
 * module this catalog cannot depend on (e.g. GamingSettingsCatalog in
 * :runtime-common opening the console-systems screen owned by :app).
 * [valueLabel] optionally summarizes current state on the row.
 */
class NestedScreenItem(
    override val id: String,
    override val title: String,
    override val subtitle: String? = null,
    val inline: CatalogScreen? = null,
    val registryId: String? = null,
    val valueLabel: ((Context) -> String?)? = null,
    // Optional per-row accent (ARGB) -- e.g. the console-systems folder
    // list keeps its real per-system color cue from SystemThemeColors.
    val accent: Int? = null,
    override val icon: CatalogIcon? = null,
) : CatalogItem {
    init {
        require((inline != null) != (registryId != null)) { "Exactly one of inline/registryId must be set" }
    }

    fun resolve(): CatalogScreen? = inline ?: registryId?.let { SettingsScreenRegistry.get(it) }
}

/**
 * Navigation to another settings SURFACE (a different preference
 * fragment) -- unlike [NestedScreenItem] this deliberately switches
 * chrome. The Preference renderer uses [fragmentClassName] natively;
 * any other renderer launches the unified SettingsActivity at that
 * fragment via [launchIntent].
 */
class SubScreenItem(
    override val id: String,
    override val title: String,
    override val subtitle: String? = null,
    val fragmentClassName: String,
    override val icon: CatalogIcon? = null,
) : CatalogItem {
    fun launchIntent(context: Context): Intent = Intent(Intent.ACTION_MAIN).apply {
        component = ComponentName(context.packageName, "com.android.launcher3.settings.SettingsActivity")
        putExtra(":settings:fragment", fragmentClassName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/** An ordered group of items; [title] null means the ungrouped run at the top. */
data class CatalogGroup(
    val id: String,
    val title: String?,
    val items: List<CatalogItem>,
    /**
     * Live device state and one-shot device actions, rendered by the
     * Quick Menu's System tab and skipped by every Settings renderer:
     * Settings is configuration (docs/SPEC.md 7f, "Where things live").
     */
    val quickOnly: Boolean = false,
)

/**
 * Cross-module screen lookup: the module that owns a management screen's
 * DATA registers its [CatalogScreen] here at process start (:app does
 * this from a manifest-declared init provider, so registration happens
 * before ANY surface -- including :shell-default's SettingsActivity,
 * which cannot depend on :app -- could try to render one), and catalogs
 * in lower modules reference it by id through [NestedScreenItem].
 */
object SettingsScreenRegistry {
    private val screens = LinkedHashMap<String, CatalogScreen>()

    fun register(screen: CatalogScreen) {
        screens[screen.id] = screen
    }

    fun get(id: String): CatalogScreen? = screens[id]
}

/** Shared helpers for catalogs storing into the launcher prefs file. */
object CatalogPrefs {
    const val PREFS_NAME = LAUNCHER_PREFS_FILE_NAME

    fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
