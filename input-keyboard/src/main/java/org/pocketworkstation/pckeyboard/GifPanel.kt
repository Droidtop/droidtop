/*
 * The emoji panel's GIFs and Stickers tabs and their search (docs/SPEC.md 6a, "GIFs and stickers",
 * Droidtop/tracker#903 H5). The grid of first-frame thumbnails, recently used first, follows CleverKeys' GifGridManager
 * (https://github.com/tribixbite/CleverKeys at c8cbb74b, GPL-3.0, Copyright (C) tribixbite and contributors), drawn
 * with the panel's own plain views instead of a RecyclerView and an image loader.
 */
package org.pocketworkstation.pckeyboard

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * What the emoji panel needs for its GIFs and Stickers tabs. Given only by the input method, and only for a field that
 * is not a password or no-learning one; a panel droidtop draws (KeyboardPanel) has no editor to put an image in, so
 * it never gets the tabs.
 */
internal class GifTabs(
    val provider: GifProvider,
    val recent: () -> List<String>,
    val hasFolders: () -> Boolean,
    val onPick: (GifItem) -> Unit,
    val onSearch: (GifKind) -> Unit,
) {
    companion object {
        /** How many images a tab lists at once. */
        const val LIMIT = 300
    }
}

/** The grid's cells: a thumbnail per image, or one line of text while there are none. */
internal class GifGridAdapter(private val context: Context, private val textColor: Int) : BaseAdapter() {
    private var items: List<GifItem> = emptyList()
    private var note: String? = null

    fun show(list: List<GifItem>) {
        items = list
        note = null
        notifyDataSetChanged()
    }

    fun note(text: String) {
        items = emptyList()
        note = text
        notifyDataSetChanged()
    }

    fun itemAt(position: Int): GifItem? = items.getOrNull(position)

    override fun getCount(): Int = if (note != null) 1 else items.size

    override fun getItem(position: Int): Any = note ?: items[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getViewTypeCount(): Int = 2

    override fun getItemViewType(position: Int): Int = if (note != null) 0 else 1

    override fun areAllItemsEnabled(): Boolean = note == null

    override fun isEnabled(position: Int): Boolean = note == null

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val density = context.resources.displayMetrics.density
        val height = (CELL_DP * density).toInt()
        note?.let { text ->
            val view = (convertView as? TextView) ?: TextView(context).apply {
                gravity = Gravity.CENTER
                textSize = 13f
                setTextColor(textColor)
                layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
            }
            view.text = text
            return view
        }
        val item = items[position]
        val view = (convertView as? ImageView) ?: ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            val pad = (4 * density).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
        }
        view.contentDescription = item.name
        GifThumbs.show(view, item, height)
        return view
    }

    companion object {
        const val CELL_DP = 84
    }
}

/**
 * The GIF or sticker search: a line showing the query typed on the keyboard itself and a row of matching thumbnails,
 * like the emoji search ([EmojiSearchBar], KeyboardCapture). A tap inserts; Enter or Done closes it.
 */
internal class GifSearchBar(
    context: Context,
    private val kind: GifKind,
    private val tabs: GifTabs,
    private val onDone: () -> Unit,
) : LinearLayout(context), KeyboardCapture.Target {
    private val colors = KeyboardAppearance.colors(context)
    private val query = StringBuilder()
    private val label = TextView(context)
    private val results = LinearLayout(context)
    private val cells = ArrayList<ImageView>()
    private var asked = 0

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
            LayoutParams(LayoutParams.MATCH_PARENT, dp(CELL_DP)),
        )
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
        val sticker = kind == GifKind.STICKER
        label.text = if (query.isEmpty()) {
            context.getString(if (sticker) R.string.sticker_search_prompt else R.string.gif_search_prompt)
        } else {
            context.getString(if (sticker) R.string.sticker_search_query else R.string.gif_search_query, query)
        }
        // A provider may answer later; only the answer to the latest query is shown.
        val ticket = ++asked
        tabs.provider.search(query.toString(), kind, tabs.recent(), MATCHES) { found -> if (ticket == asked) showMatches(found) }
    }

    private fun showMatches(found: List<GifItem>) {
        // The cells are made once and reused, so a key typed into the search changes images, not views.
        found.forEachIndexed { i, item ->
            val cell = cells.getOrNull(i) ?: ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(4), dp(4), dp(4), dp(4))
                cells += this
                results.addView(this, LayoutParams(dp(CELL_DP), dp(CELL_DP)))
            }
            cell.contentDescription = item.name
            GifThumbs.show(cell, item, dp(CELL_DP))
            cell.visibility = View.VISIBLE
            cell.setOnClickListener { tabs.onPick(item) }
        }
        for (i in found.size until cells.size) cells[i].visibility = View.GONE
    }

    private companion object {
        const val CELL_DP = 64
        const val MATCHES = 30
    }
}
