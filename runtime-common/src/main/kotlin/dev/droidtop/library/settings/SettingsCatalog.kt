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
 * (:shell-default) and Handheld's own in-shell themed settings section
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
sealed interface CatalogItem {
    val id: String
    val title: String
    val subtitle: String?
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
 */
class TextInputItem(
    override val id: String,
    override val title: String,
    override val subtitle: String? = null,
    val value: String,
    val secret: Boolean = false,
    val multiline: Boolean = false,
    val onChange: (Context, String) -> Unit,
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
    val confirmTitle: String? = null,
    val run: (Context) -> Unit,
) : CatalogItem

/**
 * An action with a real async lifecycle the surface should show. [run]
 * reports live progress through its `onStatus` callback ("Scraping
 * 3/40...") and returns the final user-facing outcome text.
 */
class AsyncActionItem(
    override val id: String,
    override val title: String,
    override val subtitle: String? = null,
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
 * module this catalog cannot depend on (e.g. HandheldSettingsCatalog in
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
