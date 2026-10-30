package org.pocketworkstation.pckeyboard

import android.content.Context
import android.view.View
import android.widget.FrameLayout

/**
 * droidtop's one keyboard surface: the embedded Hacker's Keyboard grid ([SecondScreenKeyboard.createView], the
 * same `LatinKeyboardView`, layouts and themes droidtop's input method draws) wired to a [KeyboardSink]. Every
 * place droidtop draws a keyboard of its own uses this view: the companion's Input tab and its docked slab, the
 * Social tab's draft, droidtop's own text fields on a screen Android draws no keyboard on, and the keyboard
 * droidtop pops over another app on such a screen. Only the sink differs.
 *
 * [suppressImeView] is for surfaces that type into the input method's editor: while one is on screen, the input
 * method does not also put its own view up ([SecondScreenKeyboard.setAttached]). It follows window visibility,
 * not attachment, because a stopped Activity's views stay attached (Droidtop/tracker#156).
 *
 * [context] must belong to the display the panel is drawn on: the key grid sizes itself from its metrics.
 */
class KeyboardPanel(
    context: Context,
    private val sink: KeyboardSink,
    private val heightPercent: Float = SecondScreenKeyboard.DEFAULT_HEIGHT_PERCENT,
    private val suppressImeView: Boolean = false,
) : FrameLayout(context) {

    private var functionLayer = false
    private val spaceTimer = HandlerKeyTimer()
    private val repeats = HandlerKeyTimer()
    private val repeatTimer = object : KeyTimer {
        override fun start(delayMs: Long, intervalMs: Long, action: () -> Unit) {
            repeats.cancel()
            // Asked at each press: a routed sink changes where it types, and one route cannot afford a repeat.
            if (sink.repeatsAtSource) repeats.start(delayMs, intervalMs, action)
        }

        override fun cancel() = repeats.cancel()
    }
    private val listener = SecondScreenKeyboardListener(
        send = { code, down -> sink.key(code, down) },
        resolver = AndroidCharKeyResolver(),
        commit = if (sink.takesText) ({ chars: CharSequence -> sink.text(chars) }) else null,
        onLayoutToggle = { toggleLayout() },
        deferSpace = ToolsPrefs.spaceDrag(context),
        holdTimer = spaceTimer,
        repeatTimer = repeatTimer,
    )
    private val keyboardView: LatinKeyboardView? = run {
        // The key grid reads the form and space-bar drag from the shared settings, which only the input method loads.
        ToolsPrefs.loadInto(LatinIME.sKeyboardSettings, context)
        logged("key grid not built; the panel stays empty") { SecondScreenKeyboard.createView(context, listener, heightPercent) }
            .getOrNull()
    }

    // The tool strip (clipboard history, macros, incognito) above the grid, or the grid alone when it is switched off.
    private val deck: View? = keyboardView?.let { grid ->
        ToolsDeck.wrap(
            context,
            grid,
            sink,
            if (sink.takesText) null else ({ chars: CharSequence -> listener.onText(chars) }),
            null,
            Runnable { rebuildKeys() },
        )
    }

    init {
        // Never a focus target: a tap on a key must leave focus on the field being typed into (and on the screen
        // the user is typing on, for the companion).
        isFocusable = false
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        keyboardView?.let {
            it.isFocusable = false
            it.isFocusableInTouchMode = false
        }
        deck?.let { addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)) }
    }

    /** Whether the key grid could be built at all (a broken keyboard resource leaves an empty panel). */
    val hasKeys: Boolean get() = keyboardView != null

    /** The key grid again, for a form that changed: the same layer, built with the new shape. */
    private fun rebuildKeys() {
        val view = keyboardView ?: return
        SecondScreenKeyboard.applyLayout(view, context, functionLayer, heightPercent)
    }

    private fun toggleLayout() {
        val view = keyboardView ?: return
        functionLayer = !functionLayer
        SecondScreenKeyboard.applyLayout(view, context, functionLayer, heightPercent)
    }

    private var countedForIme = false

    private fun syncImeSuppression() {
        if (!suppressImeView) return
        val shouldCount = isAttachedToWindow && windowVisibility == View.VISIBLE
        if (shouldCount == countedForIme) return
        countedForIme = shouldCount
        SecondScreenKeyboard.setAttached(shouldCount)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncImeSuppression()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        syncImeSuppression()
    }

    override fun onDetachedFromWindow() {
        if (countedForIme) {
            countedForIme = false
            SecondScreenKeyboard.setAttached(false)
        }
        // A modifier whose release never arrives leaves the far side holding Ctrl.
        listener.releaseEverything()
        super.onDetachedFromWindow()
    }
}
