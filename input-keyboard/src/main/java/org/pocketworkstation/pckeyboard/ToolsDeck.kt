package org.pocketworkstation.pckeyboard

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
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
import org.pocketworkstation.pckeyboard.remote.RemoteStrip
import org.pocketworkstation.pckeyboard.remote.RemoteTyping

/**
 * The keyboard with its editing helpers (docs/SPEC.md 6a, "Editing helpers", Droidtop/tracker#340): a strip of tool
 * buttons above the key grid, and panels (clipboard history, [ClipboardPanel]; macros) that take the grid's place
 * while open. The one wrapper both surfaces use: droidtop's input method wraps its input view in it ([wrap], from
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
    /** The input method's own move to the next keyboard ([SwitchKeyboard]); null where droidtop is not the IME. */
    private val switchKeyboard: Runnable?,
    /** The editor GIFs and stickers go into ([ImeMediaTarget]); null where droidtop is not the IME (KeyboardPanel). */
    private val media: MediaTarget?,
    /** Whether the strip of tools shows ("Tool strip"); off, the deck is only here for a mode's controls. */
    val stripShown: Boolean,
) : LinearLayout(context) {
    // Light beside a light keyboard, dark beside every other theme (KeyboardAppearance, Droidtop/tracker#562).
    private val colors = KeyboardAppearance.colors(context)
    private val stack = FrameLayout(context)
    private var overlay: View? = null
    private var overlayId: String? = null
    private lateinit var incognitoButton: Button
    private var searchBar: View? = null
    private val notice = TextView(context)
    private val inlineRow = LinearLayout(context)

    // A phone typing into this device through "Type from another device" (Droidtop/tracker#903), above the strip.
    private val remoteRow = LinearLayout(context)
    private val remoteLine = TextView(context)
    private val remoteNotice = TextView(context)
    private val remoteListener: (RemoteStrip) -> Unit = { state -> post { showRemote(state) } }

    // Emoji for the word just typed (Droidtop/tracker#903 H4), above the strip; hidden while there are none.
    private val emojiRow = LinearLayout(context)
    private val emojiScroll = HorizontalScrollView(context)
    private var emojiWord = ""
    private val inlineScroll = HorizontalScrollView(context)

    // Autofill chips are surfaces of another process; on Android 11 and later they are clipped to this host.
    private val inlineHost: ViewGroup = if (Build.VERSION.SDK_INT >= 30) InlineClipView(context) else FrameLayout(context)

    // The input method's mode (Droidtop/tracker#903 H12c): one-handed puts its controls beside the keys. Embedded
    // keyboards have no modes (KeyboardModes.current answers Normal there).
    private val inputMethod = KeyboardAppearance.drawnByInputMethod(context)
    private val mode = KeyboardModes.current(context)
    private var stripTools: List<View> = emptyList()
    private val modeControls = ArrayList<View>()

    init {
        orientation = VERTICAL
        setBackgroundColor(colors.ground)
        isFocusable = false
        emojiScroll.apply {
            isHorizontalScrollBarEnabled = false
            isFocusable = false
            visibility = GONE
            addView(emojiRow)
        }
        addView(emojiScroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        remoteRow.apply {
            orientation = VERTICAL
            isFocusable = false
            setPadding(dp(12), dp(2), dp(12), dp(2))
            visibility = GONE
        }
        remoteLine.apply {
            textSize = 12f
            setTextColor(colors.muted)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        remoteNotice.apply {
            textSize = 12f
            setTextColor(colors.text)
            setTypeface(typeface, Typeface.BOLD)
            visibility = GONE
        }
        remoteRow.addView(remoteLine)
        remoteRow.addView(remoteNotice)
        // The remote-session row shows only where the tool strip does (H13), so it goes with the strip (H12c).
        if (stripShown) {
            addView(remoteRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(buildStrip(), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
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
        if (mode == KeyboardModes.Mode.LEFT || mode == KeyboardModes.Mode.RIGHT) {
            // One-handed: the keys at their edge, switch side, back to full and the resize handle on the free side.
            val frame = OneHandedFrame(context, keyboard, mode == KeyboardModes.Mode.RIGHT, colors)
            modeControls += frame.controls
            stack.addView(frame, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        } else {
            stack.addView(keyboard, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, formGravity()))
        }
        addView(stack, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    /** Controls a mode adds around the deck (the floating frame's Dock), for the pad to reach after the strip's tools. */
    fun addPadControls(controls: List<View>) {
        modeControls += controls
    }

    /** Whether a panel (clipboard, emoji, macros, modes) is open over the keys. */
    val panelOpen: Boolean get() = overlay != null

    /**
     * What a pad steps through above the keys (PadKeys, Droidtop/tracker#903 H12c): the open panel's buttons while one
     * is open, else the strip's tools then the mode's controls. A tool is pressed as a tap presses it.
     */
    fun padTargets(): List<View> {
        overlay?.let { panel -> return if (panel is ClipboardPanel) panel.padTargets() else clickables(panel) }
        // An open search's matches come first: they sit between the strip and the keys.
        val matches = searchBar?.let { clickables(it) }.orEmpty()
        return matches + (stripTools + modeControls).filter { it.isShown }
    }

    /** Whether the clipboard panel is the one open. */
    val clipboardOpen: Boolean get() = overlay is ClipboardPanel

    /** X on a pad over an open panel (Droidtop/tracker#903 H12d): the clip under the ring opens its menu. */
    fun padMenu(target: View?): Boolean = (overlay as? ClipboardPanel)?.padMenu(target) ?: false

    /** B on a pad over an open panel: a menu or question inside it closes first; false when the panel itself should. */
    fun padBack(): Boolean = (overlay as? ClipboardPanel)?.padBack() ?: false

    private fun clickables(root: View): List<View> {
        val out = ArrayList<View>()
        fun walk(view: View) {
            if (view.visibility != VISIBLE) return
            if (view !== root && view.isClickable && view.isEnabled) out += view
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(root)
        return out
    }

    /** A grid narrower than the screen sits at the edge the form names. */
    private fun formGravity(): Int = if (ToolsPrefs.form(context).atEnd) Gravity.END else Gravity.START

    private fun buildStrip(): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // Switch keyboard first, where other keyboards keep their globe key (Droidtop/tracker#717): the space bar's
        // long press does the same, but nothing on screen said so (rig 2936). Only the input method has one to switch.
        switchKeyboard?.let { switch -> row.addView(tool(context.getString(R.string.tool_switch_keyboard)) { switch.run() }) }
        if (ToolsPrefs.clipboardHistory(context)) row.addView(tool(context.getString(R.string.tool_clipboard)) { toggle("clipboard") { clipboardPanel() } })
        if (ToolsPrefs.emoji(context)) row.addView(tool(context.getString(R.string.tool_emoji)) { toggle("emoji") { emojiPanel() } })
        row.addView(tool(context.getString(R.string.title_macros)) { toggle("macros") { macroPanel() } })
        // The input method's modes (H12c) include the shapes the Layout button cycled; a keyboard droidtop embeds has no
        // modes and keeps the Layout button.
        row.addView(if (inputMethod) modesButton() else formButton())
        incognitoButton = tool("") { toggleIncognito() }
        row.addView(incognitoButton)
        updateIncognito()
        stripTools = (0 until row.childCount).map { row.getChildAt(it) }
        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            isFocusable = false
            addView(row)
        }
    }

    private fun tool(label: String, onClick: () -> Unit): Button = flatButton(context, colors, label, onClick = onClick)

    /** Cycles the current orientation's key grid between full width, split, and one-handed left and right. */
    private fun formButton(): Button {
        lateinit var button: Button
        fun label() = context.getString(R.string.tool_layout, ToolsPrefs.form(context).id)
        button = tool(label()) {
            val form = KeyboardForm.next(ToolsPrefs.form(context))
            // The current orientation's shape: landscape and portrait keep their own (Droidtop/tracker#903 H1).
            ToolsPrefs.setForm(context, form)
            button.text = label()
            (keyboard.layoutParams as? FrameLayout.LayoutParams)?.let { it.gravity = formGravity() }
            // The input method rebuilds itself when the setting changes; a panel is told to.
            relayout?.run()
            keyboard.requestLayout()
        }
        return button
    }

    /** "Mode: Normal" and the like: opens the chooser of modes (Droidtop/tracker#903 H12c). */
    private fun modesButton(): Button =
        tool(context.getString(R.string.tool_mode, context.getString(modeName(mode)))) { toggle("modes") { modesPanel() } }

    private fun modeName(mode: KeyboardModes.Mode): Int = when (mode) {
        KeyboardModes.Mode.NORMAL -> R.string.mode_normal
        KeyboardModes.Mode.SPLIT -> R.string.mode_split
        KeyboardModes.Mode.LEFT -> R.string.mode_one_handed_left
        KeyboardModes.Mode.RIGHT -> R.string.mode_one_handed_right
        KeyboardModes.Mode.FLOATING -> R.string.mode_floating
        KeyboardModes.Mode.THUMB -> R.string.mode_thumb_split
        KeyboardModes.Mode.NUMBER_PAD -> R.string.mode_number_pad
    }

    /**
     * The chooser: Normal, one-handed left and right, floating, the thumb split where the screen is wide enough, and the
     * number pad; the current one lit. A choice is kept for this orientation and the input method builds itself again.
     */
    private fun modesPanel(): View {
        val status = TextView(context)
        val root = panelFrame(context.getString(R.string.title_modes), status)
        status.text = context.getString(R.string.modes_status)
        val rows = LinearLayout(context).apply { orientation = VERTICAL }
        KeyboardModes.offered(KeyboardForm.thumbForScreen(context) != null).forEach { choice ->
            rows.addView(
                tool(context.getString(modeName(choice))) {
                    hidePanel()
                    if (choice != mode) KeyboardModes.choose(context, choice)
                }.apply {
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    if (choice == mode) setBackgroundColor(colors.accent)
                },
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
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

    private fun toggle(id: String, build: () -> View) {
        val open = overlayId == id
        hidePanel()
        hideSearch()
        if (open) return
        val panel = build()
        overlay = panel
        overlayId = id
        // The panel takes every tap while it is over the keys, so none reaches a key fading out underneath.
        panel.isClickable = true
        stack.addView(panel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // It slides up into place over the keys, which fade and are then hidden, keeping their size
        // (Droidtop/tracker#903 H12a; PanelMotion, under the keyboard's reduce-motion rule).
        PanelMotion.enter(panel, dp(PANEL_SLIDE_DP).toFloat())
        PanelMotion.hide(keyboard, INVISIBLE)
    }

    /** Closes the open panel and shows the keys again: the panel sinks away as the keys fade back. */
    fun hidePanel() {
        overlay?.let { PanelMotion.exit(stack, it, dp(PANEL_SLIDE_DP).toFloat()) }
        overlay = null
        overlayId = null
        PanelMotion.show(keyboard)
    }

    /**
     * The autofill suggestions ([InlineAutofill], Android 11+) in a row above the keys; an empty list hides the row.
     * The views belong to the autofill service's process and are only moved here.
     */
    fun showInlineSuggestions(views: List<View>) {
        // With the strip off the deck is only a mode's frame, and the keys alone show, as before (H12c).
        if (!stripShown) return
        inlineRow.removeAllViews()
        views.forEach { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            inlineRow.addView(view)
        }
        inlineHost.visibility = if (views.isEmpty()) GONE else VISIBLE
    }

    fun clearInlineSuggestions() = showInlineSuggestions(emptyList())

    /**
     * Offers emoji for [word], the word just before the cursor (Droidtop/tracker#903 H4): up to three, in the tone last
     * picked; a tap replaces the word with the emoji. An empty word, a [sensitive] field (passwords and fields asking
     * for no learning, IncognitoRules), the emoji panel switched off or no match hides the row. Called by the input
     * method as the text before the cursor changes.
     */
    fun offerEmoji(word: String, sensitive: Boolean) {
        if (!stripShown) return
        val catalog = EmojiAssets.current()
        if (catalog == null && word.isNotEmpty() && !sensitive && ToolsPrefs.emoji(context)) EmojiAssets.load(context) { }
        val tone = ToolsPrefs.prefs(context).getInt(EmojiTones.PREF, 0)
        val found = if (sensitive || !ToolsPrefs.emoji(context) || !sink.takesText) emptyList() else catalog?.suggest(word).orEmpty()
        if (word == emojiWord && (found.isEmpty() == (emojiScroll.visibility != VISIBLE))) return
        emojiWord = word
        emojiRow.removeAllViews()
        found.map { EmojiTones.shown(it, tone) }.forEach { emoji ->
            emojiRow.addView(
                TextView(context).apply {
                    text = emoji
                    textSize = 22f
                    setPadding(dp(12), dp(2), dp(12), dp(2))
                    setOnClickListener { replaceWord(word, emoji) }
                },
            )
        }
        emojiScroll.visibility = if (found.isEmpty()) GONE else VISIBLE
    }

    /** [word], just before the cursor, becomes [emoji]: the composing word finished, its letters deleted, the emoji typed. */
    private fun replaceWord(word: String, emoji: String) {
        beforeInsert?.run()
        repeat(word.length) {
            sink.key(android.view.KeyEvent.KEYCODE_DEL, true)
            sink.key(android.view.KeyEvent.KEYCODE_DEL, false)
        }
        sink.text(emoji)
        emojiScroll.visibility = GONE
        emojiWord = ""
    }

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
        ClipboardHistoryStore.putOnClipboard(context, text, typed = false)
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
        gifs = gifTabs(),
    )

    /**
     * The GIFs and Stickers tabs (Droidtop/tracker#903 H5): only in the input method, with an editor that takes text,
     * and never in a password or no-learning field (IncognitoRules.sensitiveField).
     */
    private fun gifTabs(): GifTabs? {
        val target = media ?: return null
        if (!sink.takesText || target.sensitive()) return null
        val prefs = ToolsPrefs.prefs(context)
        return GifTabs(
            provider = LocalGifLibrary(context),
            recent = { EmojiRecents.decode(prefs.getString(ToolsPrefs.RECENT_GIF, null)) },
            hasFolders = { GifFolderList.decode(prefs.getString(ToolsPrefs.GIF_FOLDERS, null)).isNotEmpty() },
            onPick = { item -> pickGif(target, item) },
            onSearch = { kind ->
                hidePanel()
                showGifSearch(kind)
            },
        )
    }

    /**
     * Puts [item] into the editor through its content channel ([GifInsertPolicy], ported from CleverKeys): a copy of
     * that one image is served from the keyboard's cache ([GifShare]) and committed with a read grant. An editor that
     * takes no image of its type gets one line saying so; a file path is never typed.
     */
    private fun pickGif(target: MediaTarget, item: GifItem) {
        val accepts = GifInsertPolicy.editorAcceptsMime(target.contentMimeTypes(), item.mime)
        when (GifInsertPolicy.decide(target.editorPresent(), target.sensitive(), accepts)) {
            GifInsertPolicy.Action.COMMIT_MEDIA -> {
                beforeInsert?.run()
                hidePanel()
                hideSearch()
                GifShare.prepare(context, item) { ready ->
                    when (ready) {
                        is GifShare.Ready.Shared ->
                            if (target.commit(ready.uri, item.mime, item.name)) rememberGif(item) else showNotice(context.getString(R.string.gif_not_taken))
                        GifShare.Ready.TooLarge -> showNotice(context.getString(R.string.gif_too_large))
                        GifShare.Ready.Unreadable -> showNotice(context.getString(R.string.gif_unreadable))
                    }
                }
            }
            GifInsertPolicy.Action.NOT_ACCEPTED -> showNotice(context.getString(R.string.gif_not_accepted))
            GifInsertPolicy.Action.SENSITIVE, GifInsertPolicy.Action.NO_EDITOR -> {
                hidePanel()
                hideSearch()
            }
        }
    }

    /** Recently used GIFs and stickers, newest first, by document URI; nothing is recorded in incognito. */
    private fun rememberGif(item: GifItem) {
        if (ToolsPrefs.incognito(context)) return
        val prefs = ToolsPrefs.prefs(context)
        val recent = EmojiRecents.push(EmojiRecents.decode(prefs.getString(ToolsPrefs.RECENT_GIF, null)), item.uri)
        prefs.edit().putString(ToolsPrefs.RECENT_GIF, EmojiRecents.encode(recent)).apply()
    }

    /** The GIF or sticker search above the keys, like the emoji search; the keys type its query while it is open. */
    private fun showGifSearch(kind: GifKind) {
        hideSearch()
        val tabs = gifTabs() ?: return
        val bar = GifSearchBar(context, kind, tabs, onDone = { hideSearch() })
        searchBar = bar
        addView(bar, indexOfChild(stack))
    }

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

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        RemoteTyping.strip.addListener(remoteListener)
        showRemote(RemoteTyping.strip.state)
    }

    override fun onDetachedFromWindow() {
        RemoteTyping.strip.removeListener(remoteListener)
        hideSearch()
        super.onDetachedFromWindow()
    }

    /**
     * The phone session's own strip (docs/SPEC.md, "Type from another device", "On the keyboard"): "Phone connected ·
     * typing" with what it last typed, never a password field's text, and "Not received" with why, in place, until the
     * next thing lands. Hidden while no phone is paired.
     */
    private fun showRemote(state: RemoteStrip) {
        if (!state.live) {
            remoteRow.visibility = GONE
            return
        }
        remoteLine.text = state.sent?.let { context.getString(R.string.remote_typing_sent, it) }
            ?: context.getString(R.string.remote_typing)
        remoteNotice.text = state.notReceived?.let { context.getString(R.string.remote_not_received, it) }
        remoteNotice.visibility = if (state.notReceived == null) GONE else VISIBLE
        remoteRow.visibility = VISIBLE
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

    private fun panelFrame(title: String, status: TextView): LinearLayout {
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
        header.addView(tool(context.getString(R.string.tool_back)) { hidePanel() })
        root.addView(header)
        status.setPadding(dp(12), 0, dp(12), dp(4))
        status.setTextColor(colors.muted)
        status.textSize = 12f
        root.addView(status)
        return root
    }

    /**
     * The clipboard history (Droidtop/tracker#903 H12d, [ClipboardPanel]): a tap pastes and closes the panel; into a
     * container the clip goes onto the clipboard and the panel stays, saying to paste there.
     */
    private fun clipboardPanel(): View = ClipboardPanel(
        context,
        colors,
        onPaste = { text -> if (insertText(text)) hidePanel() },
        onSearch = {
            hidePanel()
            showClipSearch()
        },
        onBack = { hidePanel() },
    )

    /** The clipboard search above the keys, like the emoji search; the keys type its query while it is open. */
    private fun showClipSearch() {
        hideSearch()
        val bar = ClipSearchBar(
            context,
            onPick = { text -> if (insertText(text)) hideSearch() },
            onDone = { hideSearch() },
        )
        searchBar = bar
        addView(bar, indexOfChild(stack))
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

        /** How far a tool panel slides as it opens or closes. */
        private const val PANEL_SLIDE_DP = 24

        /**
         * [keyboard] wrapped with the tool strip, or [keyboard] itself when the strip is switched off. [typeText]
         * enters text a key cannot produce (default: the sink's own); [beforeInsert] runs before anything is typed
         * for a tool, so an input method can finish the word it is composing; [relayout] rebuilds the key grid
         * after the layout form changed, for a surface that is not the input method (which rebuilds itself); [media]
         * is the input method's editor for GIFs and stickers (none for a surface droidtop draws).
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
            switchKeyboard: Runnable? = null,
            media: MediaTarget? = null,
        ): View {
            val strip = ToolsPrefs.strip(context)
            // One-handed keeps its controls beside the keys with the strip off (Droidtop/tracker#903 H12c).
            val mode = KeyboardModes.current(context)
            val controls = mode == KeyboardModes.Mode.LEFT || mode == KeyboardModes.Mode.RIGHT
            if (!strip && !controls) return keyboard
            (keyboard.parent as? ViewGroup)?.removeView(keyboard)
            if (strip) ClipboardHistoryStore.start(context)
            return ToolsDeck(context, keyboard, sink, typeText ?: { chars -> sink.text(chars) }, beforeInsert, relayout, switchKeyboard, media, strip)
        }
    }
}
