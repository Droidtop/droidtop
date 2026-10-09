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
 * Emoji are listed from [EmojiAssets] once loaded; a tap calls [onPick].
 */
internal class EmojiBrowser(
    context: Context,
    private val recent: () -> List<String>,
    private val onPick: (String) -> Unit,
    private val onSearch: () -> Unit,
    private val onBack: () -> Unit,
) : LinearLayout(context) {
    private val adapter = EmojiAdapter(context)
    private val grid = GridView(context)
    private val groupRow = LinearLayout(context)
    private var catalog: EmojiCatalog? = null
    private var selected: String = RECENT

    init {
        orientation = VERTICAL
        setBackgroundColor(PANEL)
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
        header.addView(button("Search") { onSearch() })
        header.addView(button("Back") { onBack() })
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        grid.apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = dp(46)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            isFocusable = false
            selector = ColorDrawable(Color.TRANSPARENT)
            this.adapter = this@EmojiBrowser.adapter
            onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ -> onPick(this@EmojiBrowser.adapter.items[position]) }
        }
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        adapter.show(listOf("Loading emoji..."), loading = true)
        EmojiAssets.load(context) { loaded ->
            catalog = loaded
            buildGroups()
            select(if (recent().isEmpty()) loaded.groups.firstOrNull()?.id ?: RECENT else RECENT)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun button(label: String, onClick: () -> Unit): Button = Button(context).apply {
        text = label
        setAllCaps(false)
        textSize = 13f
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.TRANSPARENT)
        minHeight = 0
        minimumHeight = 0
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(10), dp(6), dp(10), dp(6))
        isFocusable = false
        setOnClickListener { onClick() }
    }

    private fun buildGroups() {
        groupRow.removeAllViews()
        groupRow.addView(button("Recent") { select(RECENT) })
        catalog?.groups?.forEach { group -> groupRow.addView(button(groupLabel(group.id)) { select(group.id) }) }
    }

    private fun select(id: String) {
        selected = id
        val items = if (id == RECENT) recent() else catalog?.groups?.firstOrNull { it.id == id }?.entries?.map { it.emoji } ?: emptyList()
        adapter.show(if (items.isEmpty() && id == RECENT) listOf("Nothing used yet") else items, loading = items.isEmpty())
    }

    private class EmojiAdapter(private val context: Context) : BaseAdapter() {
        var items: List<String> = emptyList()
            private set
        private var note = false

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
                setTextColor(Color.WHITE)
                layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (44 * density).toInt())
            }
            view.textSize = if (note) 13f else 26f
            view.text = items[position]
            return view
        }
    }

    companion object {
        private const val RECENT = "recent"
        private val PANEL = Color.rgb(41, 42, 45)

        /** The button text for a group id of the emoji data. */
        fun groupLabel(id: String): String = when (id) {
            "smileys_emotion" -> "Smileys"
            "people_body" -> "People"
            "animals_nature" -> "Nature"
            "food_drink" -> "Food"
            "travel_places" -> "Travel"
            "activities" -> "Activities"
            "objects" -> "Objects"
            "symbols" -> "Symbols"
            "flags" -> "Flags"
            else -> id.replace('_', ' ')
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
    private val query = StringBuilder()
    private val label = TextView(context)
    private val results = LinearLayout(context)
    private var catalog: EmojiCatalog? = EmojiAssets.current()

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.rgb(41, 42, 45))
        val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        label.apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(6), dp(8), dp(6))
        }
        top.addView(label, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        top.addView(
            Button(context).apply {
                text = "Done"
                setAllCaps(false)
                textSize = 13f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.TRANSPARENT)
                isFocusable = false
                setOnClickListener { onDone() }
            },
        )
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        KeyboardCapture.set(this)
    }

    override fun onDetachedFromWindow() {
        KeyboardCapture.clear(this)
        super.onDetachedFromWindow()
    }

    override fun key(code: Int): Boolean = when {
        code == -5 || code == 8 -> {
            if (query.isNotEmpty()) query.setLength(query.length - 1)
            refresh()
            true
        }
        code == 10 -> {
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
        label.text = if (query.isEmpty()) "Search emoji: type on the keyboard" else "Search emoji: $query"
        results.removeAllViews()
        val found = catalog?.search(query.toString()).orEmpty()
        for (entry in found) {
            results.addView(
                TextView(context).apply {
                    text = entry.emoji
                    textSize = 26f
                    setTextColor(Color.WHITE)
                    setPadding(dp(8), dp(2), dp(8), dp(2))
                    setOnClickListener { onPick(entry.emoji) }
                },
            )
        }
    }
}
