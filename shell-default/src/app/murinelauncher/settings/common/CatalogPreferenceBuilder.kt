package app.murinelauncher.settings.common

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.SwitchPreferenceCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.murinelauncher.widget.CustomSeekBarPreference
import com.android.launcher3.R
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogIcon
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.ChoiceItem
import dev.droidtop.library.settings.DocumentPickItem
import dev.droidtop.library.settings.FolderPickItem
import dev.droidtop.library.settings.NestedScreenItem
import dev.droidtop.library.settings.SettingsSearchIndex
import dev.droidtop.library.settings.SettingsSearchResult
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
 * just chromes the same data Gaming's in-shell renderer chromes in its
 * own visual language. Nested screens swap the fragment's
 * PreferenceScreen in place (a real back stack this navigator owns, wired
 * into the activity's back dispatcher) -- no per-screen fragment classes,
 * which inline-built screens couldn't be routed to anyway.
 *
 * Every preference is non-persistent by design: the catalog's own
 * onSelect/onToggle/onChange callbacks are the ONE write path for a
 * setting, shared with every other renderer.
 *
 * Shared row language with the Gaming shell's [CatalogNavigator]
 * (docs/SPEC.md 7k, settings polish pass): a category icon on rows that
 * open something ([CatalogIconDrawables], the same Material Symbols
 * Outlined choice per [CatalogIcon] as the shell's own
 * `CatalogIconGlyphs.kt`), a pad/keyboard focus ring on every row
 * (`catalog_row_focus_ring`, the same accent and 3dp width as the shell's
 * `selectionFrame`), and search over the same [SettingsSearchIndex] the
 * shell's own "Search settings" row uses. Section grouping was already
 * shared: both renderers walk the same [CatalogGroup] list from the same
 * catalog, so a titled/untitled group here is a titled/untitled group
 * there. The touch surface's own always-visible Up arrow
 * (`SettingsActivity`, wired to the same [backCallback] this class adds)
 * is its "what B does" tell -- the Gaming shell's hint row exists because
 * its dark chrome draws no toolbar at all; this surface already has one.
 */
class CatalogPreferenceNavigator(
    private val fragment: PreferenceFragmentCompat,
    private val rootGroups: suspend (Context) -> List<CatalogGroup>,
    private val skipGroupIds: Set<String> = emptySet(),
    /**
     * Search (docs/SPEC.md 7k) is built from a synthetic root [CatalogScreen]
     * wrapping [rootGroups] -- these two only name it for that index's own
     * "In <screen>" result labels and depth-0 identity; they don't have to
     * match a real [dev.droidtop.library.settings.SettingsScreenRegistry] id.
     * Search is off by default so a nested management screen (Console
     * systems, Containers -- hosted by their own navigator instance) never
     * offers a second entry point, matching the shell's own `showSearch`.
     */
    private val enableSearch: Boolean = false,
    private val rootScreenId: String = "root",
    private val rootTitle: String = "",
) {
    private val stack = ArrayDeque<CatalogScreen>()
    private var focusRingAttached = false

    // Last outcome per async item id, so the rebuild that follows an
    // AsyncActionItem keeps its result on the row (the gamepad renderer's
    // statusById does the same).
    private val statusById = HashMap<String, String>()
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

    private var pendingDocumentPick: DocumentPickItem? = null
    private val documentPickLauncher: ActivityResultLauncher<Intent> =
        fragment.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val item = pendingDocumentPick
            pendingDocumentPick = null
            val uri = result.data?.data
            if (uri == null || item == null) return@registerForActivityResult
            val context = fragment.requireContext()
            // onPicked reads or writes the document: never on the main thread.
            fragment.lifecycleScope.launch {
                val outcome = withContext(Dispatchers.IO) { item.onPicked(context, uri) }
                android.widget.Toast.makeText(context, outcome, android.widget.Toast.LENGTH_LONG).show()
                rebuild()
            }
        }

    private val backCallback = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            pop()
        }
    }

    init {
        fragment.requireActivity().onBackPressedDispatcher.addCallback(fragment, backCallback)
    }

    fun rebuild(focusKey: String? = null) {
        val context = fragment.preferenceManager.context
        fragment.lifecycleScope.launch {
            val screen = stack.lastOrNull()
            val groups = screen?.groups?.invoke(context) ?: rootGroups(context)
            val prefScreen = fragment.preferenceManager.createPreferenceScreen(context)
            if (screen == null && enableSearch) {
                prefScreen.addPreference(
                    Preference(context).apply {
                        key = SEARCH_PREFERENCE_KEY
                        title = "Search settings"
                        summary = "Find any setting by name"
                        icon = ContextCompat.getDrawable(context, CatalogIcon.SEARCH.drawableRes())
                        isIconSpaceReserved = true
                        setOnPreferenceClickListener { openSearch(); true }
                    },
                )
            }
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
            ensureFocusRing()
            if (focusKey != null) focusOn(focusKey)
        }
    }

    private fun push(screen: CatalogScreen, focusKey: String? = null) {
        stack.addLast(screen)
        rebuild(focusKey)
    }

    private fun pop() {
        stack.removeLastOrNull()
        rebuild()
    }

    /**
     * Search across settings (docs/SPEC.md 7k), on the Preference surface:
     * the same [SettingsSearchIndex] the Gaming shell's "Search settings"
     * row builds, over a synthetic root wrapping [rootGroups] so this
     * fragment's own catalog is indexed the same shallow way (its own
     * groups, one level into whatever [NestedScreenItem] it opens).  A
     * plain [AlertDialog] rather than a second screen: the whole feature
     * is "type, see matches, pick one", which a dialog does without a
     * fragment transaction of its own.
     */
    private fun openSearch() {
        val appContext = fragment.requireContext()
        // The dialog's OWN themed context, not the fragment's: a manually
        // built child view constructed from the fragment's context (which
        // ThemeOverride may have pushed dark) rendered near-white text on
        // the platform AlertDialog's light background -- illegible, unlike
        // every setMessage()-based dialog elsewhere in this file, which
        // pulls its TextView from the dialog's own theme instead.
        val builder = AlertDialog.Builder(appContext)
        val context = builder.context
        val density = context.resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val input = EditText(context).apply {
            hint = "Search settings"
            setSingleLine(true)
        }
        val recycler = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(recycler, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (360 * density).toInt()))
        }

        lateinit var dialog: AlertDialog
        val adapter = SearchResultAdapter { result ->
            dialog.dismiss()
            navigateToResult(result)
        }
        recycler.adapter = adapter

        dialog = builder
            .setTitle("Search settings")
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.show()
        input.requestFocus()

        var index: List<SettingsSearchResult> = emptyList()
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                adapter.submit(SettingsSearchIndex.search(index, s?.toString().orEmpty()))
            }
        })

        fragment.lifecycleScope.launch {
            index = withContext(Dispatchers.IO) {
                SettingsSearchIndex.build(appContext, CatalogScreen(id = rootScreenId, title = rootTitle, groups = rootGroups))
            }
            adapter.submit(SettingsSearchIndex.search(index, input.text?.toString().orEmpty()))
        }
    }

    /**
     * Picking a search result (docs/SPEC.md 7k, "picking a search result
     * scrolls its screen ... and gives it initial focus"): the result's
     * own screen is shown -- the root if it IS the synthetic search root,
     * or the one real nested [CatalogScreen] the index found it in,
     * matching the index's own "root, or one level in" depth -- and the
     * matching row is scrolled into view and focused once that screen's
     * preferences are built, rather than defaulting to wherever the list
     * already was (the rig bug this pass fixes for both renderers).
     */
    private fun navigateToResult(result: SettingsSearchResult) {
        if (result.target.id == rootScreenId) {
            stack.clear()
            rebuild(focusKey = result.itemId)
        } else {
            stack.clear()
            push(result.target, focusKey = result.itemId)
        }
    }

    /** Scrolls the preference list to [key] and gives that row real focus. */
    private fun focusOn(key: String) {
        val list = try { fragment.listView } catch (_: RuntimeException) { null } ?: return
        val adapter = list.adapter as? PreferenceGroup.PreferencePositionCallback ?: return
        val position = adapter.getPreferenceAdapterPosition(key)
        if (position < 0) return
        list.scrollToPosition(position)
        // A second post: scrollToPosition only schedules the layout pass
        // that creates the view holder for a row not already on screen,
        // so requesting focus in the SAME frame finds nothing there yet.
        list.post {
            list.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
        }
    }

    /**
     * A pad/keyboard focus ring on every row (docs/SPEC.md 7k), the touch
     * surface's half of the Gaming shell's `selectionFrame`: every row
     * the RecyclerView attaches becomes a real focus target with the
     * shared ring as its `foreground`, drawn over the row's own icon/
     * switch/ripple rather than replacing them. Attached once per
     * fragment -- the listener outlives every `rebuild()`'s
     * `setPreferenceScreen` call, so later screens need no re-attachment.
     */
    private fun ensureFocusRing() {
        if (focusRingAttached) return
        val list = try { fragment.listView } catch (_: RuntimeException) { null } ?: return
        focusRingAttached = true
        list.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                view.isFocusable = true
                // Also focusable IN touch mode: a plain requestFocus() (the
                // search-result focus above, and any future caller) is
                // silently dropped by the framework in touch mode
                // otherwise -- the same "requestFocus() in onCreate is not
                // enough" trap DESIGN-LANGUAGE.md already names, here on a
                // row reached by a tap rather than at screen-open time.
                view.isFocusableInTouchMode = true
                view.foreground = ContextCompat.getDrawable(view.context, R.drawable.catalog_row_focus_ring)
            }
            override fun onChildViewDetachedFromWindow(view: View) {}
        })
    }

    /** Runs [action] at once, or after an OK when [confirmTitle] asks for one. */
    private fun confirmThen(context: Context, confirmTitle: String?, action: () -> Unit) {
        if (confirmTitle == null) {
            action()
            return
        }
        AlertDialog.Builder(context)
            .setMessage(confirmTitle)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> action() }
            .show()
    }

    /**
     * A category icon on rows that open something (docs/SPEC.md 7k) --
     * never on a leaf toggle/choice/slider, the same rule
     * `CatalogRowView` (`:shell-gamepad`) applies, so the two surfaces
     * agree on what "carries an icon" means. No icon means no reserved
     * space, matching the Compose renderer drawing nothing at all (not
     * even a blank slot) for a leaf row.
     */
    private fun Preference.applyCatalogIcon(catalogIcon: CatalogIcon?) {
        if (catalogIcon != null) {
            icon = ContextCompat.getDrawable(context, catalogIcon.drawableRes())
            isIconSpaceReserved = true
        } else {
            isIconSpaceReserved = false
        }
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
            // What it is set to, then what it is: the row used to show only
            // its description, so "Default mode" never said which mode
            // (rig, dq-onboard-01).
            summary = listOfNotNull(item.currentLabel()?.takeIf { it.isNotBlank() }, item.subtitle)
                .joinToString("  ·  ")
                .ifBlank { null }
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
                // Rebuild after the write returns, so the re-read sees it.
                this@CatalogPreferenceNavigator.fragment.lifecycleScope.launch {
                    item.onChange(context, newValue as String)
                    rebuild()
                }
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
        is DocumentPickItem -> Preference(context).apply {
            key = item.id
            title = item.title
            summary = item.subtitle
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                pendingDocumentPick = item
                documentPickLauncher.launch(item.pickerIntent())
                true
            }
        }
        is ActionItem -> Preference(context).apply {
            key = item.id
            title = item.title
            summary = item.subtitle
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                confirmThen(context, item.confirmTitle) {
                    item.run(context)
                    rebuild()
                }
                true
            }
        }
        is AsyncActionItem -> Preference(context).apply {
            key = item.id
            title = item.title
            summary = statusById[item.id] ?: item.subtitle
            isIconSpaceReserved = false
            setOnPreferenceClickListener { pref ->
                confirmThen(context, item.confirmTitle) {
                    pref.summary = "Working..."
                    // this@... qualification: inside Preference.apply {},
                    // a bare `fragment` is the Preference's own String
                    // fragment-route property, not the hosting fragment.
                    val host = this@CatalogPreferenceNavigator.fragment
                    host.viewLifecycleOwner.lifecycleScope.launch {
                        val outcome = withContext(Dispatchers.IO) {
                            runCatching {
                                item.run(context) { status ->
                                    host.activity?.runOnUiThread { pref.summary = status }
                                }
                            }.getOrElse { "Failed: ${it.message}" }
                        }
                        statusById[item.id] = outcome
                        // The action may have changed what the screen lists.
                        rebuild()
                    }
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
            applyCatalogIcon(item.icon)
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
            applyCatalogIcon(item.icon)
            // Native androidx preference-fragment navigation -- the same
            // mechanism the previous XML android:fragment attribute used,
            // via SettingsActivity's own onPreferenceStartFragment.
            this.fragment = item.fragmentClassName
        }
    }
}

/** One line per [SettingsSearchResult]: its name, and which screen it lives on. */
private class SearchResultAdapter(
    private val onPick: (SettingsSearchResult) -> Unit,
) : RecyclerView.Adapter<SearchResultAdapter.Holder>() {
    private var results: List<SettingsSearchResult> = emptyList()

    fun submit(results: List<SettingsSearchResult>) {
        this.results = results
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_2, parent, false)
        return Holder(view)
    }

    override fun getItemCount() = results.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val result = results[position]
        holder.title.text = result.itemTitle
        holder.subtitle.text = "In ${result.screenTitle}" + (result.itemSubtitle?.let { " · $it" } ?: "")
        holder.itemView.setOnClickListener { onPick(result) }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(android.R.id.text1)
        val subtitle: TextView = view.findViewById(android.R.id.text2)
    }
}

/** The synthetic settings-home row that opens search -- never a real catalog id. */
private const val SEARCH_PREFERENCE_KEY = "__settings_search__"
