package app.murinelauncher.settings.common

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.SwitchPreferenceCompat
import app.murinelauncher.widget.CustomSeekBarPreference
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.ChoiceItem
import dev.droidtop.library.settings.FolderPickItem
import dev.droidtop.library.settings.NestedScreenItem
import dev.droidtop.library.settings.SliderItem
import dev.droidtop.library.settings.SubScreenItem
import dev.droidtop.library.settings.TextInputItem
import dev.droidtop.library.settings.ToggleItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Preference-surface renderer for the shared settings catalogs
 * (docs/SPEC.md settings architecture): turns catalog layout -- groups,
 * order, items, nested [CatalogScreen]s -- into real androidx
 * PreferenceScreens, so a fragment declares no settings of its own; it
 * just chromes the same data Handheld's in-shell renderer chromes in its
 * own visual language. Nested screens swap the fragment's
 * PreferenceScreen in place (a real back stack this navigator owns, wired
 * into the activity's back dispatcher) -- no per-screen fragment classes,
 * which inline-built screens couldn't be routed to anyway.
 *
 * Every preference is non-persistent by design: the catalog's own
 * onSelect/onToggle/onChange callbacks are the ONE write path for a
 * setting, shared with every other renderer.
 */
class CatalogPreferenceNavigator(
    private val fragment: PreferenceFragmentCompat,
    private val rootGroups: suspend (Context) -> List<CatalogGroup>,
    private val skipGroupIds: Set<String> = emptySet(),
) {
    private val stack = ArrayDeque<CatalogScreen>()
    private var pendingFolderPick: FolderPickItem? = null
    private val folderPickLauncher: ActivityResultLauncher<android.net.Uri?> =
        fragment.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val item = pendingFolderPick
            pendingFolderPick = null
            if (uri == null || item == null) return@registerForActivityResult
            val context = fragment.requireContext()
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val error = item.onPicked(context, uri)
            if (error != null) {
                AlertDialog.Builder(context).setMessage(error).setPositiveButton(android.R.string.ok, null).show()
            }
            rebuild()
        }

    private val backCallback = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            pop()
        }
    }

    init {
        fragment.requireActivity().onBackPressedDispatcher.addCallback(fragment, backCallback)
    }

    fun rebuild() {
        val context = fragment.preferenceManager.context
        fragment.lifecycleScope.launch {
            val screen = stack.lastOrNull()
            val groups = screen?.groups?.invoke(context) ?: rootGroups(context)
            val prefScreen = fragment.preferenceManager.createPreferenceScreen(context)
            for (group in groups) {
                if (screen == null && group.id in skipGroupIds) continue
                val container: PreferenceGroup = if (group.title != null) {
                    PreferenceCategory(context).apply {
                        title = group.title
                        isIconSpaceReserved = false
                        prefScreen.addPreference(this)
                    }
                } else {
                    prefScreen
                }
                for (item in group.items) {
                    container.addPreference(toPreference(context, item))
                }
            }
            fragment.preferenceScreen = prefScreen
            screen?.title?.let { fragment.activity?.title = it }
            backCallback.isEnabled = stack.isNotEmpty()
        }
    }

    private fun push(screen: CatalogScreen) {
        stack.addLast(screen)
        rebuild()
    }

    private fun pop() {
        stack.removeLastOrNull()
        rebuild()
    }

    private fun toPreference(context: Context, item: CatalogItem): Preference = when (item) {
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
        is TextInputItem -> EditTextPreference(context).apply {
            key = item.id
            title = item.title
            isPersistent = false
            isIconSpaceReserved = false
            dialogTitle = item.title
            text = item.value
            summary = item.subtitle ?: when {
                item.secret && item.value.isNotEmpty() -> "••••"
                item.value.isNotEmpty() -> item.value
                else -> "(not set)"
            }
            setOnPreferenceChangeListener { _, newValue ->
                item.onChange(context, newValue as String)
                rebuild()
                true
            }
        }
        is FolderPickItem -> Preference(context).apply {
            key = item.id
            title = item.title
            summary = item.subtitle
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                pendingFolderPick = item
                folderPickLauncher.launch(null)
                true
            }
        }
        is ActionItem -> Preference(context).apply {
            key = item.id
            title = item.title
            summary = item.subtitle
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                if (item.confirmTitle != null) {
                    AlertDialog.Builder(context)
                        .setMessage(item.confirmTitle)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            item.run(context)
                            rebuild()
                        }
                        .show()
                } else {
                    item.run(context)
                    rebuild()
                }
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
                    val outcome = withContext(Dispatchers.IO) {
                        runCatching {
                            item.run(context) { status ->
                                fragment.activity?.runOnUiThread { pref.summary = status }
                            }
                        }.getOrElse { "Failed: ${it.message}" }
                    }
                    pref.summary = outcome
                }
                true
            }
        }
        is NestedScreenItem -> Preference(context).apply {
            key = item.id
            title = item.title
            summary = listOfNotNull(item.subtitle, item.valueLabel?.invoke(context)?.takeIf { it.isNotBlank() })
                .joinToString("  ·  ")
                .ifBlank { null }
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                val child = item.resolve()
                if (child != null) push(child)
                true
            }
        }
        is SubScreenItem -> Preference(context).apply {
            key = item.id
            title = item.title
            summary = item.subtitle
            isIconSpaceReserved = false
            // Native androidx preference-fragment navigation -- the same
            // mechanism the previous XML android:fragment attribute used,
            // via SettingsActivity's own onPreferenceStartFragment.
            this.fragment = item.fragmentClassName
        }
    }
}
