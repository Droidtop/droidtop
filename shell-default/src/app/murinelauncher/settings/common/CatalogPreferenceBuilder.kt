package app.murinelauncher.settings.common

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
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
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.PreferenceViewHolder
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
import dev.droidtop.library.settings.SettingsScreenRegistry
import dev.droidtop.library.settings.SettingsSearchIndex
import dev.droidtop.library.settings.SettingsSearchResult
import dev.droidtop.library.settings.SliderItem
import dev.droidtop.library.settings.SubScreenItem
import dev.droidtop.library.settings.TextBlockItem
import dev.droidtop.library.settings.TextInputItem
import dev.droidtop.library.settings.ToggleItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    /**
     * The fragment's saved instance state, so the recreate a Text size
     * change triggers (AccessibilityPrefs, Droidtop/tracker#139)
     * restores this navigator's place instead of rebuilding it from the
     * root: the pushed screen stack, saved as its screens' registry ids,
     * and the focused row's key, refocused on the rebuilt screen.
     */
    savedState: Bundle? = null,
) {
    private val stack = ArrayDeque<CatalogScreen>()
    private var focusRingAttached = false

    /**
     * The row to refocus after the restore, consumed by the rows' attach
     * listener (see [applyRestoreFocus]): the first rebuild runs before
     * the fragment view exists, so there is no list to scroll or focus
     * until the rebuilt screen's rows start attaching.
     */
    private var restoreFocusKey: String? = null

    /**
     * The row the user last activated. In touch mode a tapped row holds
     * no focus, so the focused view alone cannot name the row a
     * value-pick recreated from; this is the fallback [saveState] saves.
     */
    private var activatedKey: String? = null

    // Last outcome per async item id, so the rebuild that follows an
    // AsyncActionItem keeps its result on the row (the gamepad renderer's
    // statusById does the same).
    private val statusById = HashMap<String, String>()

    // Read-gates (TextBlockItem.gate): the gated row is greyed until READ_GATE_MS after its text was first drawn. Opened
    // in place, not by a rebuild, which would put the list back at its top while the person is reading.
    private val gateStarted = HashSet<String>()
    private val gateOpen = HashSet<String>()
    private val gatedRows = HashMap<String, MutableList<Pair<Preference, CharSequence?>>>()

    private fun startGate(gate: String) {
        if (!gateStarted.add(gate)) return
        fragment.lifecycleScope.launch {
            delay(READ_GATE_MS)
            gateOpen += gate
            gatedRows.remove(gate)?.forEach { (row, summary) ->
                row.isEnabled = true
                row.summary = summary
            }
        }
    }

    /** [title] with a small tag after it (CatalogItem.chip), so the fact is on the row and not in a tooltip. */
    private fun titleWithChip(title: String, chip: String?): CharSequence {
        if (chip == null) return title
        val builder = SpannableStringBuilder(title).append("  ")
        val start = builder.length
        builder.append(" ").append(chip.uppercase()).append(" ")
        builder.setSpan(BackgroundColorSpan(0x44888888), start, builder.length, 0)
        builder.setSpan(StyleSpan(Typeface.BOLD), start, builder.length, 0)
        builder.setSpan(RelativeSizeSpan(0.75f), start, builder.length, 0)
        return builder
    }
    private var pendingFolderPick: FolderPickItem? = null
    private val folderPickLauncher: ActivityResultLauncher<android.net.Uri?> =
        fragment.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val item = pendingFolderPick
            pendingFolderPick = null
            if (uri == null || item == null) return@registerForActivityResult
            val context = fragment.requireContext()
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            statusById[item.id] = "Working..."
            rebuild()
            fragment.viewLifecycleOwner.lifecycleScope.launch {
                val error = runCatching { item.onPicked(context, uri) }
                    .getOrElse { "Couldn't share that folder: ${it.message ?: "an unknown error"}" }
                if (error != null) statusById[item.id] = error else statusById.remove(item.id)
                if (error != null) {
                    AlertDialog.Builder(context).setMessage(error).setPositiveButton(android.R.string.ok, null).show()
                }
                rebuild()
            }
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
        // The recreate restore: pushed screens come back as their registry
        // ids, resolved through the same registry that resolves pushes,
        // stopping at the first id nothing registers (the same rule the
        // Gaming shell's saver applies, Droidtop/tracker#87) so the
        // restore ends at the last screen that still resolves rather
        // than dropping the stack.
        savedState?.getStringArray(KEY_STACK)?.let { ids ->
            stack.addAll(SettingsScreenRegistry.resolveStack(ids.toList()))
        }
        savedState?.getString(KEY_FOCUS_ROW)?.takeIf { it.isNotEmpty() }?.let { key ->
            restoreFocusKey = key
            // Applied once the view exists; see [applyRestoreFocus].
            fragment.viewLifecycleOwnerLiveData.observe(fragment) { owner ->
                if (owner != null) applyRestoreFocus()
            }
        }
    }

    /**
     * Saves this navigator's place for the recreate the Text size
     * setting triggers (Droidtop/tracker#139): the pushed screens as
     * their registry ids, and the row to refocus -- the focused row, or
     * in touch mode (where a tapped row holds no focus) the row last
     * activated. Colour vision needs none of this: it recolours the live
     * window without a recreate.
     */
    fun saveState(outState: Bundle) {
        outState.putStringArray(KEY_STACK, stack.map { it.id }.toTypedArray())
        (focusedRowKey() ?: activatedKey)?.let { key -> outState.putString(KEY_FOCUS_ROW, key) }
    }

    /**
     * The focused row's preference key, or null when no row holds focus
     * (a list nobody drove with a pad or keyboard -- touch mode).
     */
    private fun focusedRowKey(): String? {
        val list = try { fragment.listView } catch (_: RuntimeException) { null } ?: return null
        val focused = list.focusedChild ?: return null
        val position = list.getChildAdapterPosition(focused)
        if (position < 0) return null
        val adapter = list.adapter as? PreferenceGroupAdapter ?: return null
        return adapter.getItem(position)?.key
    }

    /**
     * The restore's focus half, once the fragment view exists: attaches
     * the rows' focus listener BEFORE the first layout, so the rows take
     * focusability from it as they attach (the first rebuild ran too
     * early to), and schedules the scroll that brings the saved row in.
     * The focus itself is taken by [focusRowIfRestored] as that row
     * attaches -- a posted requestFocus would race the layout pass that
     * has to create the row's view holder first. Idempotent and called
     * both when the view appears (the observer [init] registers) and at
     * the end of every rebuild: a screen whose groups suspend (a pushed
     * management screen reading Room or the filesystem) is only built
     * once the view already existed, so the first call found no adapter
     * and left the key pending.
     */
    private fun applyRestoreFocus() {
        val key = restoreFocusKey ?: return
        val list = try { fragment.listView } catch (_: RuntimeException) { null } ?: return
        // No adapter yet: the restored screen is not built. Leave the key
        // pending for the rebuild that is building it.
        val adapter = list.adapter as? PreferenceGroup.PreferencePositionCallback ?: return
        val position = adapter.getPreferenceAdapterPosition(key)
        if (position < 0) {
            // The restored screen no longer has the saved row: no focus
            // to restore, and nothing to wait for either.
            restoreFocusKey = null
            return
        }
        ensureFocusRing()
        list.scrollToPosition(position)
    }

    /**
     * Gives the restored row focus the moment it attaches, in touch mode
     * too: the attach listener has just made the row
     * focusable-in-touch-mode, without which requestFocus is dropped.
     */
    private fun focusRowIfRestored(view: View) {
        val key = restoreFocusKey ?: return
        val list = try { fragment.listView } catch (_: RuntimeException) { null } ?: return
        val position = list.getChildAdapterPosition(view)
        if (position < 0) return
        val adapter = list.adapter as? PreferenceGroupAdapter ?: return
        if (adapter.getItem(position)?.key != key) return
        restoreFocusKey = null
        view.requestFocus()
    }

    fun rebuild(focusKey: String? = null) {
        // An explicit focus target (search navigation) supersedes any
        // restore still pending.
        if (focusKey != null) restoreFocusKey = null
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
                // A group's status chips (a store page's "Signed in", "128 games") are its facts: one
                // line here, where the Gaming renderer draws a row of chips. Not selectable, never a control.
                if (group.chips.isNotEmpty() && group.items.isNotEmpty()) {
                    container.addPreference(
                        Preference(context).apply {
                            key = "${group.id}_chips"
                            title = group.chips.joinToString(" · ") { it.label }
                            isSelectable = false
                            isIconSpaceReserved = false
                        },
                    )
                }
                for (item in group.items) {
                    container.addPreference(toPreference(context, item))
                }
            }
            fragment.preferenceScreen = prefScreen
            screen?.title?.let { fragment.activity?.title = it }
            backCallback.isEnabled = stack.isNotEmpty()
            ensureFocusRing()
            applyRestoreFocus()
            if (focusKey != null) focusOn(focusKey)
        }
    }

    private fun push(screen: CatalogScreen, focusKey: String? = null) {
        stack.addLast(screen)
        rebuild(focusKey)
    }

    private fun pop() {
        stack.removeLastOrNull()?.onLeave?.invoke()
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
                focusRowIfRestored(view)
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
            // Remember the row was used: picking a value from this row's
            // dialog is the one catalog action that recreates the
            // foreground activity (Text size, Droidtop/tracker#139), and
            // in touch mode the row holds no focus for the save to read,
            // so the activated row is what the restore refocuses.
            // Returning false lets the framework open the dialog.
            setOnPreferenceClickListener {
                activatedKey = item.id
                false
            }
            setOnPreferenceChangeListener { _, newValue ->
                item.onSelect(context, newValue as String)
                rebuild()
                true
            }
        }
        is ToggleItem -> SwitchPreferenceCompat(context).apply {
            key = item.id
            title = item.title
            summary = statusById[item.id] ?: item.subtitle
            isPersistent = false
            isIconSpaceReserved = false
            isChecked = item.current
            setOnPreferenceChangeListener { _, newValue ->
                val requestedValue = newValue as Boolean
                statusById[item.id] = "Working..."
                summary = "Working..."
                this@CatalogPreferenceNavigator.fragment.viewLifecycleOwner.lifecycleScope.launch {
                    runCatching { item.onToggle(context, requestedValue) }
                        .onFailure { statusById[item.id] = "Failed: ${it.message ?: "an unknown error"}" }
                    if (statusById[item.id] == "Working...") statusById.remove(item.id)
                    rebuild()
                }
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
            summary = statusById[item.id] ?: item.subtitle
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
                confirmThen(context, item.confirmTitle) {
                    pendingDocumentPick = item
                    documentPickLauncher.launch(item.pickerIntent())
                }
                true
            }
        }
        is ActionItem -> Preference(context).apply {
            key = item.id
            title = titleWithChip(item.title, item.chip)
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
        is TextBlockItem -> object : Preference(context) {
            override fun onBindViewHolder(holder: PreferenceViewHolder) {
                super.onBindViewHolder(holder)
                // The default row stops at ten lines; a text the person must read is shown whole.
                (holder.findViewById(android.R.id.summary) as? TextView)?.maxLines = Int.MAX_VALUE
            }
        }.apply {
            key = item.id
            title = item.title.ifBlank { null }
            summary = item.text
            isSelectable = false
            isIconSpaceReserved = false
            item.gate?.let { startGate(it) }
        }
        is AsyncActionItem -> Preference(context).apply {
            key = item.id
            title = titleWithChip(item.title, item.chip)
            summary = statusById[item.id] ?: item.subtitle
            isIconSpaceReserved = false
            val gate = item.gate
            if (gate != null && gate !in gateOpen) {
                isEnabled = false
                gatedRows.getOrPut(gate) { mutableListOf() }.add(this to summary)
                summary = "Available once you have read the text above"
            }
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
                            }.getOrElse { "Failed: ${it.message ?: "an unknown error"}" }
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
            title = titleWithChip(item.title, item.chip)
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

    private companion object {
        /** Saved-state keys: the pushed screens' registry ids, and the row to refocus. */
        /** How long after a gated text is first drawn its Accept opens (the Gaming renderer also opens it at the end of the text). */
        const val READ_GATE_MS = 3000L
        const val KEY_STACK = "catalog_nav_stack"
        const val KEY_FOCUS_ROW = "catalog_nav_focus_row"
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
        holder.subtitle.text = result.screenTitle
        holder.itemView.setOnClickListener { onPick(result) }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(android.R.id.text1)
        val subtitle: TextView = view.findViewById(android.R.id.text2)
    }
}

/** The synthetic settings-home row that opens search -- never a real catalog id. */
private const val SEARCH_PREFERENCE_KEY = "__settings_search__"
