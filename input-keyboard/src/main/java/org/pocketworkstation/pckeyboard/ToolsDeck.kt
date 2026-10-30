package org.pocketworkstation.pckeyboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The keyboard with its editing helpers (docs/SPEC.md 6a, "Editing helpers", Droidtop/tracker#340): a strip of tool
 * buttons above the key grid, and panels (clipboard history, macros) that take the grid's place while open. The one
 * wrapper both surfaces use: droidtop's input method wraps its input view in it ([wrap], from
 * `KeyboardSwitcher`), and [KeyboardPanel] (the companion, the overlays, droidtop's own fields) wraps its grid. What a
 * tool types goes to the [sink] the surface already types into, so the helpers work wherever the keys do.
 * Built from plain views, no layout resources; never a focus target.
 */
class ToolsDeck private constructor(
    context: Context,
    private val keyboard: View,
    private val sink: KeyboardSink,
    private val typeText: (CharSequence) -> Unit,
    private val beforeInsert: Runnable?,
    private val relayout: Runnable?,
) : LinearLayout(context) {
    // Light beside a light keyboard, dark beside every other theme (KeyboardAppearance, Droidtop/tracker#562).
    private val colors = KeyboardAppearance.colors(context)
    private val stack = FrameLayout(context)
    private var overlay: View? = null
    private var overlayId: String? = null
    private lateinit var incognitoButton: Button
    private var searchBar: EmojiSearchBar? = null
    private val notice = TextView(context)
    private val inlineRow = LinearLayout(context)
    private val inlineScroll = HorizontalScrollView(context)

    // Autofill chips are surfaces of another process; on Android 11 and later they are clipped to this host.
    private val inlineHost: ViewGroup = if (Build.VERSION.SDK_INT >= 30) InlineClipView(context) else FrameLayout(context)

    init {
        orientation = VERTICAL
        setBackgroundColor(colors.ground)
        isFocusable = false
        addView(buildStrip(), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        inlineScroll.apply {
            isHorizontalScrollBarEnabled = false
            isFocusable = false
            addView(inlineRow)
        }
        inlineHost.addView(inlineScroll)
        inlineHost.visibility = GONE
        addView(inlineHost, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        notice.apply {
            textSize = 12f
            setTextColor(colors.muted)
            setPadding(dp(12), 0, dp(12), dp(2))
            visibility = GONE
        }
        addView(notice)
        stack.addView(keyboard, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, formGravity()))
        addView(stack, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    /** A grid narrower than the screen sits at the edge the form names. */
    private fun formGravity(): Int = if (ToolsPrefs.form(context).atEnd) Gravity.END else Gravity.START

    private fun buildStrip(): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (ToolsPrefs.clipboardHistory(context)) row.addView(tool(context.getString(R.string.tool_clipboard)) { toggle("clipboard") { clipboardPanel() } })
        if (ToolsPrefs.emoji(context)) row.addView(tool(context.getString(R.string.tool_emoji)) { toggle("emoji") { emojiPanel() } })
        row.addView(tool(context.getString(R.string.title_macros)) { toggle("macros") { macroPanel() } })
        row.addView(formButton())
        incognitoButton = tool("") { toggleIncognito() }
        row.addView(incognitoButton)
        updateIncognito()
        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            isFocusable = false
            addView(row)
        }
    }

    private fun tool(label: String, onClick: () -> Unit): Button = flatButton(context, colors, label, onClick = onClick)

    /** Cycles the key grid between full width, split, and one-handed left and right. */
    private fun formButton(): Button {
        lateinit var button: Button
        fun label() = context.getString(R.string.tool_layout, ToolsPrefs.form(context).id)
        button = tool(label()) {
            val form = KeyboardForm.next(ToolsPrefs.form(context))
            ToolsPrefs.prefs(context).edit().putString(KeyboardForm.PREF, form.id).apply()
            LatinIME.sKeyboardSettings.form = form
            button.text = label()
            (keyboard.layoutParams as? FrameLayout.LayoutParams)?.let { it.gravity = formGravity() }
            // The input method rebuilds itself when the setting changes; a panel is told to.
            relayout?.run()
            keyboard.requestLayout()
        }
        return button
    }

    private fun toggle(id: String, build: () -> View) {
        val open = overlayId == id
        hidePanel()
        hideSearch()
        if (open) return
        val panel = build()
        overlay = panel
        overlayId = id
        keyboard.visibility = INVISIBLE
        stack.addView(panel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    /** Closes the open panel and shows the keys again. */
    fun hidePanel() {
        overlay?.let { stack.removeView(it) }
        overlay = null
        overlayId = null
        keyboard.visibility = VISIBLE
    }

    /**
     * The autofill suggestions ([InlineAutofill], Android 11+) in a row above the keys; an empty list hides the row.
     * The views belong to the autofill service's process and are only moved here.
     */
    fun showInlineSuggestions(views: List<View>) {
        inlineRow.removeAllViews()
        views.forEach { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            inlineRow.addView(view)
        }
        inlineHost.visibility = if (views.isEmpty()) GONE else VISIBLE
    }

    fun clearInlineSuggestions() = showInlineSuggestions(emptyList())

    private fun showNotice(text: String) {
        notice.text = text
        notice.visibility = VISIBLE
        notice.removeCallbacks(hideNotice)
        notice.postDelayed(hideNotice, NOTICE_MS)
    }

    private val hideNotice = Runnable { notice.visibility = GONE }

    /**
     * Into an editor the text is typed where the cursor is. A container has no text channel, so the text goes to the
     * Android clipboard, which the clipboard bridge hands to the container (SPEC 6d); the container pastes it.
     * True when it was typed.
     */
    private fun insertText(text: String): Boolean {
        if (sink.takesText) {
            beforeInsert?.run()
            sink.text(text)
            return true
        }
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("", text))
        showNotice(context.getString(R.string.tool_copied_for_terminal))
        return false
    }

    private fun emojiPanel(): View = EmojiBrowser(
        context,
        recent = { EmojiRecents.decode(ToolsPrefs.prefs(context).getString(ToolsPrefs.RECENT_EMOJI, null)) },
        onPick = { emoji ->
            pickEmoji(emoji)
            hidePanel()
        },
        onSearch = {
            hidePanel()
            showSearch()
        },
        onBack = { hidePanel() },
    )

    private fun pickEmoji(emoji: String) {
        val prefs = ToolsPrefs.prefs(context)
        val recent = EmojiRecents.push(EmojiRecents.decode(prefs.getString(ToolsPrefs.RECENT_EMOJI, null)), emoji)
        prefs.edit().putString(ToolsPrefs.RECENT_EMOJI, EmojiRecents.encode(recent)).apply()
        insertText(emoji)
    }

    /** The search line and its matches above the keys; the keys type its query while it is open. */
    private fun showSearch() {
        hideSearch()
        val bar = EmojiSearchBar(
            context,
            onPick = { emoji ->
                pickEmoji(emoji)
                hideSearch()
            },
            onDone = { hideSearch() },
        )
        searchBar = bar
        addView(bar, indexOfChild(stack))
    }

    private fun hideSearch() {
        searchBar?.let { removeView(it) }
        searchBar = null
    }

    override fun onDetachedFromWindow() {
        hideSearch()
        super.onDetachedFromWindow()
    }

    private fun toggleIncognito() {
        val on = !ToolsPrefs.incognito(context)
        ToolsPrefs.prefs(context).edit().putBoolean(ToolsPrefs.INCOGNITO, on).apply()
        updateIncognito(on)
    }

    private fun updateIncognito(on: Boolean = ToolsPrefs.incognito(context)) {
        incognitoButton.text = context.getString(if (on) R.string.tool_incognito_on else R.string.tool_incognito)
        incognitoButton.setBackgroundColor(if (on) colors.accent else Color.TRANSPARENT)
    }

    private fun label(text: String, size: Float = 14f): TextView = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(colors.text)
    }

    private fun panelFrame(title: String, status: TextView, vararg actions: Button): LinearLayout {
        val root = LinearLayout(context).apply {
            orientation = VERTICAL
            setBackgroundColor(colors.panel)
            minimumHeight = dp(200)
        }
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(2), dp(4), dp(2))
        }
        header.addView(label(title, 15f), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        actions.forEach { header.addView(it) }
        header.addView(tool(context.getString(R.string.tool_back)) { hidePanel() })
        root.addView(header)
        status.setPadding(dp(12), 0, dp(12), dp(4))
        status.setTextColor(colors.muted)
        status.textSize = 12f
        root.addView(status)
        return root
    }

    private fun clipboardPanel(): View {
        val status = TextView(context)
        val rows = LinearLayout(context).apply { orientation = VERTICAL }
        val root = panelFrame(context.getString(R.string.title_clipboard_history), status, tool(context.getString(R.string.tool_clear)) { ClipboardHistoryStore.clear() })
        root.addView(
            ScrollView(context).apply {
                isFocusable = false
                addView(rows)
            },
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f),
        )
        fun refresh() {
            rows.removeAllViews()
            val incognito = ToolsPrefs.incognito(context)
            // Incognito keeps what was copied before out of sight; pinned entries are the user's own notes.
            val entries = ClipboardHistoryStore.snapshot().filter { !incognito || it.pinned }
            status.text = if (incognito) context.getString(R.string.tool_incognito_recording_nothing) else ""
            if (entries.isEmpty()) rows.addView(label(context.getString(R.string.tool_clipboard_empty)).apply { setPadding(dp(12), dp(8), dp(12), dp(8)) })
            entries.forEach { rows.addView(clipboardRow(it)) }
        }
        val listener: () -> Unit = { root.post { refresh() } }
        root.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    ClipboardHistoryStore.addListener(listener)
                    refresh()
                }

                override fun onViewDetachedFromWindow(v: View) {
                    ClipboardHistoryStore.removeListener(listener)
                }
            },
        )
        refresh()
        return root
    }

    private fun clipboardRow(entry: ClipboardHistory.Entry): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(4), 0)
        }
        row.addView(
            label(entry.text.replace('\n', ' ')).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(8), dp(8), dp(8))
                setOnClickListener {
                    if (insertText(entry.text)) hidePanel()
                }
            },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
        )
        row.addView(tool(context.getString(if (entry.pinned) R.string.tool_unpin else R.string.tool_pin)) { ClipboardHistoryStore.setPinned(entry.text, !entry.pinned) })
        row.addView(tool(context.getString(R.string.tool_delete)) { ClipboardHistoryStore.delete(entry.text) })
        return row
    }

    private fun macroPanel(): View {
        val status = TextView(context)
        val root = panelFrame(context.getString(R.string.title_macros), status)
        val macros = ToolsPrefs.macros(context)
        val rows = LinearLayout(context).apply { orientation = VERTICAL }
        val problems = MacroParser.problems(ToolsPrefs.macroSource(context))
        status.text = when {
            problems.isNotEmpty() -> problems.first() + if (problems.size > 1) context.getString(R.string.tool_macro_problems_more, problems.size - 1) else ""
            macros.isEmpty() -> context.getString(R.string.tool_macros_empty)
            else -> ""
        }
        val player = MacroPlayer(sink::key, typeText)
        macros.forEach { macro ->
            rows.addView(
                tool(macro.name) {
                    beforeInsert?.run()
                    player.play(macro)
                },
            )
        }
        root.addView(
            ScrollView(context).apply {
                isFocusable = false
                addView(rows)
            },
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f),
        )
        return root
    }

    companion object {
        private const val NOTICE_MS = 3000L

        /**
         * [keyboard] wrapped with the tool strip, or [keyboard] itself when the strip is switched off. [typeText]
         * enters text a key cannot produce (default: the sink's own); [beforeInsert] runs before anything is typed
         * for a tool, so an input method can finish the word it is composing; [relayout] rebuilds the key grid
         * after the layout form changed, for a surface that is not the input method (which rebuilds itself).
         */
        @JvmStatic
        @JvmOverloads
        fun wrap(
            context: Context,
            keyboard: View,
            sink: KeyboardSink,
            typeText: ((CharSequence) -> Unit)? = null,
            beforeInsert: Runnable? = null,
            relayout: Runnable? = null,
        ): View {
            if (!ToolsPrefs.strip(context)) return keyboard
            (keyboard.parent as? ViewGroup)?.removeView(keyboard)
            ClipboardHistoryStore.start(context)
            return ToolsDeck(context, keyboard, sink, typeText ?: { chars -> sink.text(chars) }, beforeInsert, relayout)
        }
    }
}
