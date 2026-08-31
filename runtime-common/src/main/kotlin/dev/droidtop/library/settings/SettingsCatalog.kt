package dev.droidtop.library.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * The shared, renderer-agnostic settings model (docs/SPEC.md settings
 * architecture): a catalog owns the settings DATA and LAYOUT (which
 * settings exist, their grouping and order, their current values, and
 * what changing them does), and every UI surface just chromes it in its
 * own visual context. The Preference-based unified settings screen
 * (:shell-default's SettingsHandheldFragment) and Handheld's own
 * in-shell themed settings section (:shell-gamepad) both render the SAME
 * catalog -- neither hand-picks its own subset, neither duplicates a
 * write path, and adding a setting to a catalog makes it appear in every
 * surface at once.
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

/** A pick-one setting. [current] is the live value at catalog-build time. */
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

/** A fire-and-done action; [run] is the default fulfillment. */
class ActionItem(
    override val id: String,
    override val title: String,
    override val subtitle: String? = null,
    val run: (Context) -> Unit,
) : CatalogItem

/**
 * An action with a real async result the surface should show (e.g. a
 * network sync). [run] returns the user-facing outcome text.
 */
class AsyncActionItem(
    override val id: String,
    override val title: String,
    override val subtitle: String? = null,
    val run: suspend (Context) -> String,
) : CatalogItem

/**
 * Navigation to another settings surface. The Preference renderer uses
 * [fragmentClassName] natively (androidx preference fragment
 * navigation); any other renderer launches the unified SettingsActivity
 * at that fragment via [launchIntent].
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

/** Shared helpers for catalogs storing into the launcher prefs file. */
object CatalogPrefs {
    // com.android.launcher3.LauncherFiles.SHARED_PREFERENCES_KEY -- by
    // literal name, since :runtime-common cannot depend on the launcher
    // module (same established pattern as GamepadShell's own pref reads).
    const val PREFS_NAME = "com.android.launcher3.prefs"

    fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
