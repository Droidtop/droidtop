package org.pocketworkstation.pckeyboard

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The emoji panel (docs/SPEC.md 6a, "Emoji panel and search", Droidtop/tracker#342): a row of group buttons (Recent
 * first), a grid of the group's emoji, and Search and Back. Takes the key grid's place like the other tool panels.
 * Emoji are listed from [EmojiAssets] once loaded; a tap calls [onPick]. With [gifs], GIFs and Stickers tabs follow
 * Kaomoji (Droidtop/tracker#903 H5, GifPanel.kt).
 */
internal class EmojiBrowser(
    context: Context,
    private val recent: () -> List<String>,
    private val onPick: (String) -> Unit,
    private val onSearch: () -> Unit,
    private val onBack: () -> Unit,
    private val gifs: GifTabs? = null,
) : LinearLayout(context) {
    // Light beside a light keyboard, dark beside every other theme (KeyboardAppearance, Droidtop/tracker#562).
    private val colors = KeyboardAppearance.colors(context)
    private val adapter = EmojiAdapter(context, colors.text)
    private val grid = GridView(context)
    private val groupRow = LinearLayout(context)
    private var catalog: EmojiCatalog? = null
    private var selected: String = RECENT
    // An emoji's skin tones, shown above the grid on a long press (Droidtop/tracker#903 H4).
    private val toneRow = LinearLayout(context)
    private val toneScroll = HorizontalScrollView(context)

    // The GIFs or Stickers tab while one is shown (Droidtop/tracker#903 H5); null on an emoji tab.
    private var gifKind: GifKind? = null
    private val gifAdapter by lazy { GifGridAdapter(context, colors.text) }

    init {
        orientation = VERTICAL
        setBackgroundColor(colors.panel)
        val header = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        header.addView(
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                isFocusable = false
                addView(groupRow)
            },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
        )
        header.addView(
            button(context.getString(R.string.emoji_search)) {
                val kind = gifKind
                if (kind != null && gifs != null) gifs.onSearch(kind) else onSearch()
            },
        )
        header.addView(button(context.getString(R.string.tool_back)) { onBack() })
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        toneScroll.apply {
            isHorizontalScrollBarEnabled = false
            isFocusable = false
            visibility = GONE
            addView(toneRow)
        }
        addView(toneScroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        grid.apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = dp(46)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            isFocusable = false
            selector = ColorDrawable(Color.TRANSPARENT)
            this.adapter = this@EmojiBrowser.adapter
            onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
                if (gifKind != null) gifAdapter.itemAt(position)?.let { gifs?.onPick(it) } else onPick(this@EmojiBrowser.adapter.items[position])
            }
            // A long press on an emoji with skin tones offers them all; the one picked is typed and its tone kept.
            onItemLongClickListener = AdapterView.OnItemLongClickListener { _, _, position, _ ->
                if (gifKind != null) return@OnItemLongClickListener false
                val entry = this@EmojiBrowser.adapter.items.getOrNull(position)?.let { catalog?.entryOf(it) }
                if (entry == null || entry.variants.isEmpty()) false else { showTones(entry); true }
            }
        }
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        adapter.show(listOf(context.getString(R.string.emoji_loading)), loading = true)
        EmojiAssets.load(context) { loaded ->
            catalog = loaded
            buildGroups()
            select(if (recent().isEmpty()) loaded.groups.firstOrNull()?.id ?: RECENT else RECENT, animate = false)
        }
    }

    private fun button(label: String, onClick: () -> Unit): Button = flatButton(context, colors, label, paddingDp = 10, onClick = onClick)

    private fun buildGroups() {
        groupRow.removeAllViews()
        groupRow.addView(button(context.getString(R.string.emoji_recent)) { select(RECENT) })
        catalog?.groups?.forEach { group -> groupRow.addView(button(groupLabel(context, group.id)) { select(group.id) }) }
        if (gifs != null) {
            groupRow.addView(button(context.getString(R.string.emoji_group_gifs)) { selectGifs(GifKind.GIF) })
            groupRow.addView(button(context.getString(R.string.emoji_group_stickers)) { selectGifs(GifKind.STICKER) })
        }
    }

    /**
     * The GIFs or Stickers tab: the images of the picked folders, recently used first, as first-frame thumbnails. The
     * index is read off the main thread the first time ([GifIndex]); until then the grid says it is loading.
     */
    private fun selectGifs(kind: GifKind) {
        val tabs = gifs ?: return
        gifKind = kind
        toneScroll.visibility = GONE
        grid.columnWidth = dp(GifGridAdapter.CELL_DP)
        if (grid.adapter !== gifAdapter) grid.adapter = gifAdapter
        gifAdapter.note(context.getString(R.string.gif_loading))
        tabs.provider.search("", kind, tabs.recent(), GifTabs.LIMIT) { found ->
            if (gifKind != kind) return@search
            when {
                found.isNotEmpty() -> gifAdapter.show(found)
                !tabs.hasFolders() -> gifAdapter.note(context.getString(R.string.gif_no_folders))
                else -> gifAdapter.note(context.getString(if (kind == GifKind.STICKER) R.string.gif_no_stickers else R.string.gif_no_gifs))
            }
        }
    }

    private fun showTones(entry: EmojiEntry) {
        toneRow.removeAllViews()
        (listOf(entry.emoji) + entry.variants).forEach { choice ->
            toneRow.addView(
                TextView(context).apply {
                    text = choice
                    textSize = 26f
                    setTextColor(colors.text)
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                    setOnClickListener {
                        EmojiTones.toneOfPick(entry, choice)?.let { tone ->
                            ToolsPrefs.prefs(context).edit().putInt(EmojiTones.PREF, tone).apply()
                        }
                        toneScroll.visibility = GONE
                        onPick(choice)
                    }
                },
            )
        }
        toneScroll.visibility = VISIBLE
    }

    private fun select(id: String, animate: Boolean = true) {
        // The new group slides in from its side of the row (Droidtop/tracker#903 H12a): Recent first, Kaomoji last.
        val from = order(selected)
        val to = order(id)
        selected = id
        gifKind = null
        if (grid.adapter !== adapter) grid.adapter = adapter
        toneScroll.visibility = GONE
        // Kaomoji are wide: fewer, wider cells in smaller type (Droidtop/tracker#903 H4).
        val wide = id == Kaomoji.GROUP
        grid.columnWidth = dp(if (wide) 128 else 46)
        adapter.wide = wide
        val tone = ToolsPrefs.prefs(context).getInt(EmojiTones.PREF, 0)
        val items = if (id == RECENT) recent() else catalog?.groups?.firstOrNull { it.id == id }?.entries?.map { EmojiTones.shown(it, tone) } ?: emptyList()
        adapter.show(if (items.isEmpty() && id == RECENT) listOf(context.getString(R.string.emoji_recent_empty)) else items, loading = items.isEmpty())
        if (animate && from != to) PanelMotion.swap(grid, (if (to > from) 1 else -1) * dp(GROUP_SLIDE_DP).toFloat())
    }

    /** A group's place in the row: Recent first, then the data's groups in order (Kaomoji last). */
    private fun order(id: String): Int = if (id == RECENT) -1 else catalog?.groups?.indexOfFirst { it.id == id } ?: -1

    private class EmojiAdapter(private val context: Context, private val textColor: Int) : BaseAdapter() {
        var items: List<String> = emptyList()
            private set
        private var note = false

        /** Kaomoji cells: smaller type, so a whole face fits a cell. */
        var wide = false

        fun show(list: List<String>, loading: Boolean) {
            items = list
            note = loading
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size

        override fun getItem(position: Int): Any = items[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun areAllItemsEnabled(): Boolean = !note

        override fun isEnabled(position: Int): Boolean = !note

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val density = context.resources.displayMetrics.density
            val view = (convertView as? TextView) ?: TextView(context).apply {
                gravity = Gravity.CENTER
                setTextColor(textColor)
                layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (44 * density).toInt())
            }
            view.textSize = if (note) 13f else if (wide) 15f else 26f
            view.maxLines = 1
            view.text = items[position]
            return view
        }
    }

    companion object {
        private const val RECENT = "recent"

        /** How far a group's emoji slide in when it is picked. */
        private const val GROUP_SLIDE_DP = 16

        /** The button text for a group id of the emoji data. */
        fun groupLabel(context: Context, id: String): String {
            val name = when (id) {
                "smileys_emotion" -> R.string.emoji_group_smileys
                "people_body" -> R.string.emoji_group_people
                "animals_nature" -> R.string.emoji_group_nature
                "food_drink" -> R.string.emoji_group_food
                "travel_places" -> R.string.emoji_group_travel
                "activities" -> R.string.emoji_group_activities
                "objects" -> R.string.emoji_group_objects
                "symbols" -> R.string.emoji_group_symbols
                "flags" -> R.string.emoji_group_flags
                Kaomoji.GROUP -> R.string.emoji_group_kaomoji
                else -> return id.replace('_', ' ')
            }
            return context.getString(name)
        }
    }
}

/**
 * The emoji search (Droidtop/tracker#342): a line showing the query typed on the keyboard itself and a row of
 * matches. While it is open it takes the keyboard's keys ([KeyboardCapture]); a match is picked with a tap, Enter or
 * Done closes it. Sits between the tool strip and the key grid, so the grid stays usable.
 */
internal class EmojiSearchBar(
    context: Context,
    private val onPick: (String) -> Unit,
    private val onDone: () -> Unit,
) : LinearLayout(context), KeyboardCapture.Target {
    private val colors = KeyboardAppearance.colors(context)
    private val query = StringBuilder()
    private val label = TextView(context)
    private val results = LinearLayout(context)
    private val cells = ArrayList<TextView>()
    private var catalog: EmojiCatalog? = EmojiAssets.current()

    init {
        orientation = VERTICAL
        setBackgroundColor(colors.panel)
        val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        label.apply {
            textSize = 15f
            setTextColor(colors.text)
            setPadding(dp(12), dp(6), dp(8), dp(6))
        }
        top.addView(label, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        top.addView(flatButton(context, colors, context.getString(R.string.label_done_key), onClick = onDone))
        addView(top)
        addView(
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                isFocusable = false
                addView(results)
            },
        )
        if (catalog == null) EmojiAssets.load(context) { loaded -> catalog = loaded; refresh() }
        refresh()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        KeyboardCapture.set(this)
    }

    override fun onDetachedFromWindow() {
        KeyboardCapture.clear(this)
        super.onDetachedFromWindow()
    }

    override fun key(code: Int): Boolean = when {
        code == Keyboard.KEYCODE_DELETE || code == '\b'.code -> {
            if (query.isNotEmpty()) query.setLength(query.length - 1)
            refresh()
            true
        }
        code == '\n'.code -> {
            onDone()
            true
        }
        code >= 32 -> {
            query.append(code.toChar())
            refresh()
            true
        }
        else -> false
    }

    override fun text(chars: CharSequence): Boolean {
        query.append(chars)
        refresh()
        return true
    }

    private fun refresh() {
        label.text = if (query.isEmpty()) context.getString(R.string.emoji_search_prompt) else context.getString(R.string.emoji_search_query, query)
        val found = catalog?.search(query.toString()).orEmpty()
        // The result cells are made once and reused, so a key typed into the search changes text, not views.
        found.forEachIndexed { i, entry ->
            val cell = cells.getOrNull(i) ?: TextView(context).apply {
                textSize = 26f
                setTextColor(colors.text)
                setPadding(dp(8), dp(2), dp(8), dp(2))
                cells += this
                results.addView(this)
            }
            // In the skin tone last picked (Droidtop/tracker#903 H4).
            val shown = EmojiTones.shown(entry, ToolsPrefs.prefs(context).getInt(EmojiTones.PREF, 0))
            cell.text = shown
            cell.visibility = View.VISIBLE
            cell.setOnClickListener { onPick(shown) }
        }
        for (i in found.size until cells.size) cells[i].visibility = View.GONE
    }
}
