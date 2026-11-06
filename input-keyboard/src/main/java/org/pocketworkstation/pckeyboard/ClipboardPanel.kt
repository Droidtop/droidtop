package org.pocketworkstation.pckeyboard

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.TimeZone

/*
 * The clipboard panel (docs/SPEC.md 6a, "Clipboard history", Droidtop/tracker#903 H12d). Ported in part from
 * FlorisBoard, https://github.com/florisboard/florisboard at bac346e8, Apache-2.0, Copyright (C) 2021-2025 The
 * FlorisBoard Contributors (NOTICE.md): from `ime/clipboard/ClipboardInputLayout.kt`, the clip's menu as a sheet over
 * the dimmed history (the list at 12% while it is open, a tap outside it closes it) showing the whole clip and its time
 * with Pin or Unpin, Delete and Paste, and the clear-history question asked the same way; the grouping into pinned,
 * recent and earlier is in ClipboardRules.kt. Licensed under the Apache License, Version 2.0; you may not use this part
 * except in compliance with the License. You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0.
 * Distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND. FlorisBoard draws it with Compose;
 * here it is plain views, as every panel of this keyboard is.
 */

/**
 * The clipboard history panel over the keys ([ToolsDeck]): a header with Search, Clear unpinned and Back; one line
 * of status; the clips in sections (Pinned, Recent, Earlier), each its text clipped to two lines, its age and a pin
 * mark. A tap pastes ([onPaste]); a long press, or X on a pad, opens the clip's menu (Paste, Pin or Unpin, Delete);
 * Clear unpinned asks first. Pad: [padTargets] are the clips, then the header's buttons; while a menu or question is
 * open, only its buttons.
 */
internal class ClipboardPanel(
    context: Context,
    private val colors: KeyboardColors,
    private val onPaste: (String) -> Unit,
    private val onSearch: () -> Unit,
    private val onBack: () -> Unit,
) : LinearLayout(context) {
    private val status = TextView(context)
    private val rows = LinearLayout(context)
    private val scroll = ScrollView(context)
    private val body = FrameLayout(context)
    private val headerTools = ArrayList<View>()
    private val clipViews = ArrayList<View>()

    /** The open menu or question over the list, with its buttons; null while none is. */
    private var sheet: View? = null
    private val sheetTools = ArrayList<View>()

    private val listener: () -> Unit = { post { refresh() } }

    init {
        orientation = VERTICAL
        setBackgroundColor(colors.panel)
        minimumHeight = dp(200)
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(2), dp(4), dp(2))
        }
        header.addView(
            TextView(context).apply {
                text = context.getString(R.string.title_clipboard_history)
                textSize = 15f
                setTextColor(colors.text)
            },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
        )
        listOf(
            button(context.getString(R.string.clip_search)) { onSearch() },
            button(context.getString(R.string.clip_clear_unpinned)) { askClear() },
            button(context.getString(R.string.tool_back)) { onBack() },
        ).forEach {
            headerTools += it
            header.addView(it)
        }
        addView(header)
        status.apply {
            setPadding(dp(12), 0, dp(12), dp(4))
            setTextColor(colors.muted)
            textSize = 12f
        }
        addView(status)
        rows.orientation = VERTICAL
        scroll.isFocusable = false
        scroll.addView(rows)
        body.addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        refresh()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ClipboardHistoryStore.addListener(listener)
        // Clips past "Keep clips for" go now, on the store's thread; the listener redraws when they have.
        ClipboardHistoryStore.prepareForPanel(context)
        refresh()
    }

    override fun onDetachedFromWindow() {
        ClipboardHistoryStore.removeListener(listener)
        super.onDetachedFromWindow()
    }

    /** What the pad steps through: the open sheet's buttons, else the clips then the header's buttons. */
    fun padTargets(): List<View> = (if (sheet != null) sheetTools else clipViews + headerTools).filter { it.isShown }

    /** X on a pad: [target]'s menu, when it is a clip. True when one opened. */
    fun padMenu(target: View?): Boolean {
        if (sheet != null || target == null || target !in clipViews) return false
        return target.performLongClick()
    }

    /** B on a pad: closes the open menu or question; false when none was open (B then closes the panel). */
    fun padBack(): Boolean {
        if (sheet == null) return false
        closeSheet()
        return true
    }

    private fun refresh() {
        rows.removeAllViews()
        clipViews.clear()
        val incognito = ToolsPrefs.incognito(context)
        val now = System.currentTimeMillis()
        val entries = visibleEntries(context)
        val keep = ToolsPrefs.clipboardKeep(context)
        status.text = when {
            incognito -> context.getString(R.string.tool_incognito_recording_nothing)
            keep != ClipKeep.FOREVER ->
                context.getString(R.string.clip_hint) + " " + context.getString(R.string.clip_keep_status, keepLabel(context, keep))
            else -> context.getString(R.string.clip_hint)
        }
        if (entries.isEmpty()) {
            rows.addView(
                TextView(context).apply {
                    text = context.getString(R.string.tool_clipboard_empty)
                    textSize = 14f
                    setTextColor(colors.text)
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                },
            )
            return
        }
        ClipGroups.of(entries, now).forEach { (group, clips) ->
            rows.addView(sectionTitle(group))
            clips.forEach { entry ->
                val row = clipRow(entry, now)
                clipViews += row
                rows.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { setMargins(dp(8), dp(2), dp(8), dp(2)) })
            }
        }
    }

    private fun sectionTitle(group: ClipGroups.Group): View = TextView(context).apply {
        text = context.getString(
            when (group) {
                ClipGroups.Group.PINNED -> R.string.clip_group_pinned
                ClipGroups.Group.RECENT -> R.string.clip_group_recent
                ClipGroups.Group.OLDER -> R.string.clip_group_older
            },
        )
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(colors.muted)
        setPadding(dp(12), dp(8), dp(12), dp(2))
    }

    /** One clip: a plate with its text in two lines, its age under it, and a pin mark when pinned. */
    private fun clipRow(entry: ClipboardHistory.Entry, now: Long): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = plate(colors.key)
            isClickable = true
            isLongClickable = true
            isFocusable = false
            setOnClickListener { onPaste(entry.text) }
            setOnLongClickListener {
                openMenu(entry)
                true
            }
        }
        val words = LinearLayout(context).apply { orientation = VERTICAL }
        words.addView(
            TextView(context).apply {
                text = entry.text.replace('\n', ' ')
                textSize = 14f
                setTextColor(colors.text)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            },
        )
        words.addView(
            TextView(context).apply {
                text = ageLabel(context, entry.at, now)
                textSize = 11f
                setTextColor(colors.muted)
            },
        )
        row.addView(words, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        if (entry.pinned) {
            row.addView(
                TextView(context).apply {
                    text = context.getString(R.string.clip_pin_mark)
                    textSize = 14f
                    contentDescription = context.getString(R.string.clip_group_pinned)
                    setPadding(dp(8), 0, 0, 0)
                },
            )
        }
        return row
    }

    /** The clip's menu over the dimmed list: the whole clip, its age, then Paste, Pin or Unpin, Delete and Cancel. */
    private fun openMenu(entry: ClipboardHistory.Entry) {
        val card = card()
        card.addView(
            ScrollView(context).apply {
                isFocusable = false
                addView(
                    TextView(context).apply {
                        text = entry.text
                        textSize = 14f
                        setTextColor(colors.text)
                    },
                )
            },
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f),
        )
        card.addView(
            TextView(context).apply {
                text = ageLabel(context, entry.at, System.currentTimeMillis())
                textSize = 11f
                setTextColor(colors.muted)
                setPadding(0, dp(2), 0, dp(4))
            },
        )
        card.addView(
            actions(
                context.getString(R.string.clip_paste) to {
                    closeSheet()
                    onPaste(entry.text)
                },
                context.getString(if (entry.pinned) R.string.tool_unpin else R.string.tool_pin) to {
                    closeSheet()
                    ClipboardHistoryStore.setPinned(entry.text, !entry.pinned)
                },
                context.getString(R.string.tool_delete) to {
                    closeSheet()
                    ClipboardHistoryStore.delete(entry.text)
                },
                context.getString(R.string.clip_cancel) to { closeSheet() },
            ),
        )
        openSheet(card)
    }

    /** Clear unpinned asks first; with nothing unpinned there is nothing to ask. */
    private fun askClear() {
        val count = ClipboardHistoryStore.snapshot().count { !it.pinned }
        if (count == 0) return
        val card = card()
        card.addView(
            TextView(context).apply {
                text = context.resources.getQuantityString(R.plurals.clip_clear_ask, count, count)
                textSize = 14f
                setTextColor(colors.text)
                setPadding(0, 0, 0, dp(6))
            },
        )
        card.addView(
            actions(
                context.getString(R.string.clip_clear_unpinned) to {
                    closeSheet()
                    ClipboardHistoryStore.clearUnpinned()
                },
                context.getString(R.string.clip_cancel) to { closeSheet() },
            ),
        )
        openSheet(card)
    }

    private fun card(): LinearLayout = LinearLayout(context).apply {
        orientation = VERTICAL
        background = plate(colors.ground)
        setPadding(dp(12), dp(10), dp(12), dp(6))
        isClickable = true
    }

    private fun actions(vararg items: Pair<String, () -> Unit>): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.END
        }
        sheetTools.clear()
        items.forEach { (label, action) ->
            val tool = button(label, action)
            sheetTools += tool
            row.addView(tool)
        }
        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            isFocusable = false
            addView(row)
        }
    }

    /** [card] over the list, which dims under it; a tap outside the card closes it. It rises in as panels do (H12a). */
    private fun openSheet(card: View) {
        closeSheet()
        val scrim = FrameLayout(context).apply {
            isClickable = true
            setOnClickListener { closeSheet() }
        }
        scrim.addView(
            card,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
                setMargins(dp(16), dp(8), dp(16), dp(8))
            },
        )
        sheet = scrim
        body.addView(scrim, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        PanelMotion.dim(scroll, DIMMED)
        PanelMotion.enter(card, dp(SHEET_RISE_DP).toFloat())
    }

    private fun closeSheet() {
        val open = sheet ?: return
        sheet = null
        sheetTools.clear()
        PanelMotion.exit(body, open, dp(SHEET_RISE_DP).toFloat())
        PanelMotion.dim(scroll, 1f)
    }

    private fun button(label: String, onClick: () -> Unit): Button = flatButton(context, colors, label, onClick = onClick)

    private fun plate(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(PLATE_CORNER_DP).toFloat()
    }

    companion object {
        /** The list under an open menu, FlorisBoard's 12%. */
        private const val DIMMED = 0.12f
        private const val SHEET_RISE_DP = 16

        /** The plates' corners, as the keys' own (KeyPlates). */
        private const val PLATE_CORNER_DP = 6

        /**
         * The clips the panel and its search show: all of them, pinned first then newest first; while incognito only
         * the pinned ones, which are the person's own notes, so what was copied before stays out of sight.
         */
        fun visibleEntries(context: Context): List<ClipboardHistory.Entry> {
            val incognito = ToolsPrefs.incognito(context)
            return ClipboardHistoryStore.snapshot().filter { !incognito || it.pinned }
        }

        /** "now", "2 min", "yesterday" and the like ([ClipAge]). */
        fun ageLabel(context: Context, at: Long, now: Long): String {
            val age = ClipAge.of(at, now, TimeZone.getDefault().getOffset(now).toLong())
            return when (age.unit) {
                ClipAge.Unit.NOW -> context.getString(R.string.clip_age_now)
                ClipAge.Unit.MINUTES -> context.getString(R.string.clip_age_minutes, age.count.toInt())
                ClipAge.Unit.HOURS -> context.getString(R.string.clip_age_hours, age.count.toInt())
                ClipAge.Unit.YESTERDAY -> context.getString(R.string.clip_age_yesterday)
                ClipAge.Unit.DAYS -> context.getString(R.string.clip_age_days, age.count.toInt())
                ClipAge.Unit.WEEKS -> context.getString(R.string.clip_age_weeks, age.count.toInt())
            }
        }

        /** The setting's own words for [keep] ("1 day"), from its list. */
        private fun keepLabel(context: Context, keep: ClipKeep): String {
            val labels = context.resources.getStringArray(R.array.clipboard_keep_entries)
            val values = context.resources.getStringArray(R.array.clipboard_keep_values)
            return labels.getOrNull(values.indexOf(keep.prefValue)) ?: keep.prefValue
        }
    }
}

/**
 * The clipboard search (Droidtop/tracker#903 H12d), like the emoji search ([EmojiSearchBar]): a line showing the query
 * typed on the keyboard itself, which it takes while open ([KeyboardCapture]), and the matching clips in a row
 * ([ClipSearch]: every word, any order, ignoring case and accents). A tap pastes ([onPick]); Enter or Done closes it.
 */
internal class ClipSearchBar(
    context: Context,
    private val onPick: (String) -> Unit,
    private val onDone: () -> Unit,
) : LinearLayout(context), KeyboardCapture.Target {
    private val colors = KeyboardAppearance.colors(context)

    // Read outside the drawable's apply, where `colors` would be the drawable's own gradient colours.
    private val keyColor = colors.key
    private val query = StringBuilder()
    private val label = TextView(context)
    private val results = LinearLayout(context)
    private val cells = ArrayList<TextView>()
    private val none = TextView(context)
    private val listener: () -> Unit = { post { refresh() } }

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
        none.apply {
            text = context.getString(R.string.clip_no_match)
            textSize = 13f
            setTextColor(colors.muted)
            setPadding(dp(12), dp(4), dp(12), dp(6))
        }
        results.gravity = Gravity.CENTER_VERTICAL
        results.addView(none)
        addView(
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                isFocusable = false
                addView(results)
            },
        )
        refresh()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        KeyboardCapture.set(this)
        ClipboardHistoryStore.addListener(listener)
        refresh()
    }

    override fun onDetachedFromWindow() {
        KeyboardCapture.clear(this)
        ClipboardHistoryStore.removeListener(listener)
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
        label.text = if (query.isEmpty()) context.getString(R.string.clip_search_prompt) else context.getString(R.string.clip_search_query, query)
        val found = ClipSearch.filter(ClipboardPanel.visibleEntries(context), query.toString())
        // The cells are made once and reused, so a key typed into the search changes text, not views.
        found.forEachIndexed { i, entry ->
            val cell = cells.getOrNull(i) ?: TextView(context).apply {
                textSize = 13f
                setTextColor(colors.text)
                maxLines = 2
                maxWidth = dp(CELL_MAX_DP)
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = GradientDrawable().apply {
                    setColor(keyColor)
                    cornerRadius = dp(CHIP_CORNER_DP).toFloat()
                }
                isFocusable = false
                cells += this
                results.addView(this, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { setMargins(dp(4), dp(2), dp(4), dp(6)) })
            }
            val mark = if (entry.pinned) context.getString(R.string.clip_pin_mark) + " " else ""
            cell.text = mark + entry.text.replace('\n', ' ')
            cell.visibility = View.VISIBLE
            cell.setOnClickListener { onPick(entry.text) }
        }
        for (i in found.size until cells.size) cells[i].visibility = View.GONE
        none.visibility = if (found.isEmpty()) View.VISIBLE else View.GONE
    }

    private companion object {
        const val CELL_MAX_DP = 220
        const val CHIP_CORNER_DP = 6
    }
}
