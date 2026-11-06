package org.pocketworkstation.pckeyboard

import android.content.Context
import android.view.Display
import android.view.View
import android.view.WindowManager
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
 * [context] must belong to the display the panel is drawn on: the key grid sizes itself from its metrics. On the add-on
 * screen of a handheld (any display but the device's own, whose short side is a handheld's) the panel is compact
 * (Droidtop/tracker#903 H12c): Hacker's Compact 5-row layout, at [KeyboardModes.compactHeightPercent] of the height
 * asked for, never a row shorter than a finger's press target.
 */
class KeyboardPanel(
    context: Context,
    private val sink: KeyboardSink,
    private val heightPercent: Float = SecondScreenKeyboard.DEFAULT_HEIGHT_PERCENT,
    private val suppressImeView: Boolean = false,
) : FrameLayout(context) {

    private var functionLayer = false

    // Compact on the add-on screen (Droidtop/tracker#903 H12c), decided once from the display the panel is built for.
    private val compact: Boolean = context.resources.displayMetrics.let { metrics ->
        KeyboardModes.compactPanel(
            onDefaultDisplay(context),
            KeyboardForm.handheld(metrics.widthPixels, metrics.heightPixels, metrics.xdpi, metrics.ydpi, context.resources.configuration.smallestScreenWidthDp),
        )
    }
    private val keysHeightPercent: Float = if (compact) {
        context.resources.displayMetrics.let { KeyboardModes.compactHeightPercent(heightPercent, it.heightPixels, it.ydpi) }
    } else {
        heightPercent
    }
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
        logged("key grid not built; the panel stays empty") { SecondScreenKeyboard.createView(context, FeedbackListener(listener), keysHeightPercent, compact) }
            .getOrNull()
    }

    /**
     * The panel's keys with a haptic per key class (Droidtop/tracker#903 H12a). droidtop has no vibration setting of
     * its own, and its text-entry slab follows Android's touch feedback ([SlabView]), so these keys do too
     * ([KeyHapticsPlayer.viewFeedback]); the input method's "Vibration strength" is the input method's.
     */
    private inner class FeedbackListener(private val keys: SecondScreenKeyboardListener) : LatinKeyboardBaseView.OnKeyboardActionListener {
        override fun onPress(primaryCode: Int) {
            keyboardView?.let { KeyHapticsPlayer.viewFeedback(it, primaryCode) }
            keys.onPress(primaryCode)
        }

        override fun onRelease(primaryCode: Int) = keys.onRelease(primaryCode)
        override fun onKey(primaryCode: Int, keyCodes: IntArray?, x: Int, y: Int) = keys.onKey(primaryCode, keyCodes, x, y)
        override fun onText(text: CharSequence?) = keys.onText(text)
        override fun onCancel() = keys.onCancel()
        override fun swipeLeft(): Boolean = keys.swipeLeft()
        override fun swipeRight(): Boolean = keys.swipeRight()
        override fun swipeDown(): Boolean = keys.swipeDown()
        override fun swipeUp(): Boolean = keys.swipeUp()
        override fun onCursorDrag(steps: Int) = keys.onCursorDrag(steps)
    }

    // The tool strip (clipboard history, macros, incognito) above the grid, or the grid alone when it is switched off.
    // No MediaTarget: this panel has no InputConnection, so its emoji panel has no GIFs or Stickers tabs (SPEC 6a).
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
        SecondScreenKeyboard.applyLayout(view, context, functionLayer, keysHeightPercent, compact)
    }

    private fun toggleLayout() {
        val view = keyboardView ?: return
        functionLayer = !functionLayer
        SecondScreenKeyboard.applyLayout(view, context, functionLayer, keysHeightPercent, compact)
    }

    private var countedForIme = false

    /** The animation settings, read each time the panel comes on screen (Droidtop/tracker#903 H12a). */
    private fun refreshMotion() {
        if (isAttachedToWindow && windowVisibility == View.VISIBLE) KeyboardMotion.refresh(context)
    }

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
        refreshMotion()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        syncImeSuppression()
        refreshMotion()
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

    private companion object {
        /** Whether [context] draws on the device's own screen (any context that names no other display does). */
        @Suppress("DEPRECATION") // Context.getDisplay is Android 11's; a display context's WindowManager answers on 8 too.
        fun onDefaultDisplay(context: Context): Boolean =
            (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay?.displayId?.let { it == Display.DEFAULT_DISPLAY } ?: true
    }
}
