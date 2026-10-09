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

    /**
     * How far a job this row stands for has got, 0 to 1, drawn as a thin bar under its title (a
     * download in the Downloads place, a store's install on its page); a negative value is a job
     * under way whose size is not known yet (an empty track). Null for every row that is not a job.
     */
    val progress: Float? get() = null

    /**
     * A short tag drawn beside the title, on the row itself ("Unofficial" on a plugin from a catalog that is not
     * droidtop's): a fact the person must see without opening anything, never a tooltip. Null draws none.
     */
    val chip: String? get() = null
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
    val onToggle: suspend (Context, Boolean) -> Unit,
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
    // An on/off state the action cannot change itself but opens the place that does (Airplane mode,
    // Bluetooth, VPN: the system owns the radios). Drawn as the one switch every on/off setting has,
    // in a settings row and on a Quick Menu tile alike; null for an action with no such state.
    val state: Boolean? = null,
    override val progress: Float? = null,
    override val chip: String? = null,
    val run: (Context) -> Unit,
) : CatalogItem

/**
 * A paragraph shown whole, wrapped in the row and never cut (a disclaimer the person must read before accepting it).
 * It does nothing when pressed; the pad steps through the paragraphs, which is how a long text scrolls. [title], when
 * not blank, is a heading line above the text. [gate] names a read-gate ([AsyncActionItem.gate]): the renderer opens
 * it when the paragraph marked [last] is shown (the text was scrolled to its end) or three seconds after the first
 * paragraph of the gate was shown, whichever comes first. The text of a screen short enough to need no scrolling is
 * therefore read the moment it is shown.
 */
class TextBlockItem(
    override val id: String,
    val text: String,
    override val title: String = "",
    val gate: String? = null,
    val last: Boolean = false,
) : CatalogItem {
    override val subtitle: String? get() = null
}

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
    /** A read-gate ([TextBlockItem.gate]): the row is greyed ("Read it first") and does nothing until the renderer has opened that gate. */
    val gate: String? = null,
    override val chip: String? = null,
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
    val onPicked: suspend (Context, Uri) -> String?,
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
    /** A folder of the primary shared storage the picker opens in ("Download"), instead of wherever it last was. */
    val startIn: String? = null,
    val onPicked: suspend (Context, Uri) -> String,
) : CatalogItem {
    fun pickerIntent(): Intent =
        Intent(if (createName != null) Intent.ACTION_CREATE_DOCUMENT else Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
            createName?.let { putExtra(Intent.EXTRA_TITLE, it) }
            startIn?.let {
                putExtra(
                    android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                    android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$it"),
                )
            }
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
    /**
     * This screen re-opened DEEP-LINKED at one argument (Console systems
     * for the single system a gamelist's options menu was opened from,
     * docs/SPEC.md "One consistent way into Settings"): the SAME screen
     * builder, parameterized, never a second implementation of the
     * screen. [SettingsScreenRegistry.get] passes the argument through;
     * a screen that declares none (all but the deep-linkable ones)
     * resolves unchanged whatever the caller asked for.
     */
    val forDeepLink: ((String) -> CatalogScreen)? = null,
    /**
     * The rows the settings SEARCH INDEX reads instead of [groups]
     * (SettingsSearchIndex.build). Set only when the live [groups] does
     * work the index must not pay for: Console systems walks every games
     * root and game-counts every store and engine folder, which made
     * "Indexing settings..." sit there for 10-40 s on a real device
     * (Droidtop/tracker#136). Rows the index can do without -- per-folder,
     * per-platform, per-container instance rows -- belong to the screen,
     * not to settings search. Null = the index reads [groups] itself.
     */
    val indexGroups: (suspend (Context) -> List<CatalogGroup>)? = null,
    /**
     * Runs when the person leaves this screen (B, or backing out of Settings): a screen that started
     * something which waits on them, like a GitHub sign-in showing a code, stops it here.
     */
    val onLeave: (() -> Unit)? = null,
    /**
     * A short screen (a handful of rows, no list of its own) that the Gaming settings renderer draws
     * as sections inside the pane that links it, instead of as a level of its own (docs/SPEC.md
     * "Settings layout": at most two levels, category then row or detail). Its rows keep their ids
     * and write paths; only where they are drawn changes.
     */
    val merged: Boolean = false,
    /** The settings category column's order, for a root whose groups name categories (docs/SPEC.md "Settings layout"). */
    val categoryOrder: List<String> = emptyList(),
    /**
     * Emits whenever this screen's content changes (after the value it was built from) without anything being done on it (a message
     * arrived, a friend came online): the Gaming renderer builds [groups] again then, so a screen
     * of live data stays current without polling. Null for a screen that only changes when it is
     * used, which is every screen but the friends and their conversations.
     */
    val live: kotlinx.coroutines.flow.Flow<Any?>? = null,
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
    override val progress: Float? = null,
    override val chip: String? = null,
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
    /**
     * The glyph this group shows as an entry of the settings category column (docs/SPEC.md
     * "Settings layout"). Null: the first glyph one of its rows carries, else none.
     */
    val icon: CatalogIcon? = null,
    /**
     * The settings category this group belongs to (docs/SPEC.md "Settings layout"). Groups that name
     * the same category are one entry of the category column and their titles become the section
     * labels of its pane; a group with a category is never read as a hub of links. Null: the group
     * is its own category (or a hub, when it holds only links to other screens).
     */
    val category: String? = null,
    /**
     * Short facts about what the group is about, drawn as a row of status chips above its rows
     * (docs/SPEC.md 7j "Places": a store page's "Signed in", "128 games", "Synced 5 min ago"): the
     * page's header when it is the first group. A chip is a fact, never a control; anything that can
     * be pressed is a row. Drawn only above a group that has rows.
     */
    val chips: List<CatalogChip> = emptyList(),
)

/** One status chip ([CatalogGroup.chips]): a short fact, and whether it is a good state (signed in, up to date). */
data class CatalogChip(val label: String, val ok: Boolean = false)

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

    /**
     * [deepLink] opens the screen at one argument (Console systems for
     * one system id): the registered screen's own [CatalogScreen.forDeepLink]
     * re-opens its builder parameterized, so a caller in a module that
     * cannot reach the screen's data (the gamelist options menu in
     * :shell-gamepad, the console-systems screen in :app) deep-links
     * through the same registry it always resolved by id through --
     * no second mechanism for one screen. Without an argument, or for a
     * screen that declares no deep link, the plain registered screen
     * resolves, which is what every existing surface gets.
     */
    fun get(id: String, deepLink: String? = null): CatalogScreen? =
        screens[id]?.let { screen ->
            if (deepLink == null) screen else screen.forDeepLink?.invoke(deepLink) ?: screen
        }

    /**
     * Rebuilds a pushed screen stack from the ids a renderer saved across
     * an Activity recreate (the one the Text size setting triggers,
     * Droidtop/tracker#87 and #139): live [CatalogScreen]s carry builder
     * lambdas and cannot go into saved state, so what is saved is the
     * ids. Resolves them in order and stops at the first id nothing
     * registers -- a screen some surface pushed inline, or one its owner
     * no longer registers -- so the restore ends at the last screen that
     * still resolves rather than dropping the whole stack. The one
     * definition both renderers that save a stack restore through
     * (:shell-gamepad's `catalogStackSaver`, :shell-default's
     * `CatalogPreferenceNavigator`).
     */
    fun resolveStack(ids: List<String>): List<CatalogScreen> = buildList {
        for (id in ids) {
            val screen = get(id) ?: break
            add(screen)
        }
    }
}

/** Shared helpers for catalogs storing into the launcher prefs file. */
object CatalogPrefs {
    const val PREFS_NAME = LAUNCHER_PREFS_FILE_NAME

    fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
