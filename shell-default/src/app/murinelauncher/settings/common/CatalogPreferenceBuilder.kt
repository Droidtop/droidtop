package app.murinelauncher.settings.common

import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import app.murinelauncher.widget.CustomSeekBarPreference
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.ChoiceItem
import dev.droidtop.library.settings.SliderItem
import dev.droidtop.library.settings.SubScreenItem
import dev.droidtop.library.settings.ToggleItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Preference-surface renderer for a settings catalog (docs/SPEC.md
 * settings architecture): turns the catalog's own layout -- groups, order,
 * items -- into a real androidx PreferenceScreen, so a fragment doesn't
 * declare any settings of its own, it just chromes the shared data the
 * same way Handheld's in-shell renderer does in its own visual language.
 *
 * Every preference is non-persistent by design: the catalog's own
 * onSelect/onToggle/onChange callbacks are the ONE write path for a
 * setting, shared with every other renderer, instead of the preference
 * framework writing the same key a second way.
 */
object CatalogPreferenceBuilder {

    /**
     * Builds a fresh screen. [skipGroupIds] is for groups the surface's
     * own chrome already exposes (e.g. the "global" group, a persistent
     * action-bar item on every SettingsActivity screen). [rebuild] is
     * invoked after any value change so items whose existence depends on
     * other values (theme -> color scheme/variant) stay correct; it must
     * re-run this builder against a fresh catalog and set the result as
     * the fragment's preference screen.
     */
    fun build(
        fragment: PreferenceFragmentCompat,
        groups: List<CatalogGroup>,
        skipGroupIds: Set<String> = emptySet(),
        rebuild: () -> Unit,
    ): PreferenceScreen {
        val context = fragment.preferenceManager.context
        val screen = fragment.preferenceManager.createPreferenceScreen(context)
        for (group in groups) {
            if (group.id in skipGroupIds) continue
            val container = if (group.title != null) {
                PreferenceCategory(context).apply {
                    title = group.title
                    isIconSpaceReserved = false
                    screen.addPreference(this)
                }
            } else {
                screen
            }
            for (item in group.items) {
                container.addPreference(toPreference(fragment, item, rebuild))
            }
        }
        return screen
    }

    private fun toPreference(
        fragment: PreferenceFragmentCompat,
        item: CatalogItem,
        rebuild: () -> Unit,
    ): Preference {
        val context = fragment.preferenceManager.context
        return when (item) {
            is ChoiceItem -> ListPreference(context).apply {
                key = item.id
                title = item.title
                isPersistent = false
                isIconSpaceReserved = false
                entries = item.options.map { it.label }.toTypedArray()
                entryValues = item.options.map { it.value }.toTypedArray()
                value = item.current
                summary = item.subtitle ?: item.currentLabel()
                setOnPreferenceChangeListener { _, newValue ->
                    item.onSelect(context, newValue as String)
                    rebuild()
                    true
                }
            }
            is ToggleItem -> SwitchPreferenceCompat(context).apply {
                key = item.id
                title = item.title
                summary = item.subtitle
                isPersistent = false
                isIconSpaceReserved = false
                isChecked = item.current
                setOnPreferenceChangeListener { _, newValue ->
                    item.onToggle(context, newValue as Boolean)
                    rebuild()
                    true
                }
            }
            is SliderItem -> CustomSeekBarPreference(context).apply {
                key = item.id
                title = item.title
                summary = item.subtitle
                isPersistent = false
                isIconSpaceReserved = false
                setMin(item.min)
                setMax(item.max)
                setValue(item.current)
                setOnPreferenceChangeListener { _, newValue ->
                    item.onChange(context, newValue as Int)
                    // No rebuild: a slider mid-drag fires repeatedly, and no
                    // catalog item's existence depends on a slider value.
                    true
                }
            }
            is ActionItem -> Preference(context).apply {
                key = item.id
                title = item.title
                summary = item.subtitle
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    item.run(context)
                    true
                }
            }
            is AsyncActionItem -> Preference(context).apply {
                key = item.id
                title = item.title
                summary = item.subtitle
                isIconSpaceReserved = false
                setOnPreferenceClickListener { pref ->
                    pref.summary = "Working..."
                    fragment.viewLifecycleOwner.lifecycleScope.launch {
                        val outcome = withContext(Dispatchers.IO) { item.run(context) }
                        pref.summary = outcome
                    }
                    true
                }
            }
            is SubScreenItem -> Preference(context).apply {
                key = item.id
                title = item.title
                summary = item.subtitle
                isIconSpaceReserved = false
                // Native androidx preference-fragment navigation -- the
                // same mechanism the previous XML android:fragment
                // attribute used, via SettingsActivity's own
                // onPreferenceStartFragment.
                this.fragment = item.fragmentClassName
            }
        }
    }
}
