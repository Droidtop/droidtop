package org.pocketworkstation.pckeyboard

import android.content.Context
import android.view.LayoutInflater
import android.view.KeyEvent

/**
 * droidtop's persistent second-screen keyboard (docs/SPEC.md sections 4,
 * 6 and 6c).
 *
 * ## What Android actually permits, stated before the design
 *
 * An IME's window is placed by the PLATFORM, not by the IME. Android picks
 * the display from `WindowManager#getDisplayImePolicy()` for the display
 * the focused app is on, and the only choices are "the focused app's
 * display", "the default display" and "nowhere"; setting that policy needs
 * a system permission, and the platform additionally refuses to show an
 * IME on displays it does not itself own. There is no arrangement of
 * layouts, window flags or tokens by which an ordinary app puts its IME
 * window on the second screen and leaves it there. Nor is "persistent" an
 * IME concept at all: the soft input window is shown when an editor asks
 * for it and hidden when one stops.
 *
 * So a persistent second-screen keyboard cannot be an IME window, and
 * anything built as though it could be would silently do nothing.
 *
 * ## What it is instead
 *
 * An ORDINARY droidtop window on the second screen, containing a real
 * [LatinKeyboardView] -- the fork's own key grid, its own themes, its own
 * `kbd_full` layout with the function row, Ctrl, Alt, Esc and the arrow
 * cluster. That view is the reason Hacker's Keyboard was forked in rather
 * than a keyboard being drawn from scratch, and it is used here unmodified.
 *
 * What changes is where the keystrokes go, and the model for both
 * destinations is the same one: a HARDWARE keyboard. Keys are pressed and
 * released; the far side decides what they produce.
 *
 * - **Into a container** (Desktop mode): each key becomes an Android
 *   keycode handed to `:input-seat`'s existing `DesktopInputRouter`, which
 *   already knows how to turn one into an evdev key. The compositor's XKB
 *   keymap applies the layout and its own auto-repeat, exactly as it does
 *   for a lapdock's physical keyboard.
 * - **Into an Android app** (Handheld and Standard modes): each key
 *   becomes an `InputConnection.sendKeyEvent`, which is precisely the call
 *   whose contract is "as though a hardware key was pressed". It needs
 *   droidtop's IME to be the selected input method (that is what supplies
 *   the `InputConnection`) and an editor to have focus (that is what the
 *   connection points at). Those two conditions are the platform's, not
 *   droidtop's, and the surface says which one is missing rather than
 *   dropping keystrokes.
 *
 * Both routes share one translation ([HackersKeyCodes]) and one key table
 * (`EvdevKeys`, on the container side only). Nothing here reimplements the
 * IME, and nothing calls into [LatinIME]'s internals -- which is
 * deliberate: with the on-primary input view suppressed those internals
 * have no view to work against, and driving them from here would be
 * reaching into a service configured for a screen that is not showing.
 *
 * ## What is given up, stated rather than hidden
 *
 * The hardware-key model means no autocorrect, no suggestion strip, no
 * dead-key composition and no shift-state relabelling of the keys on this
 * surface. For a keyboard whose entire purpose is driving a terminal, Wine
 * and a desktop (section 6a), those are the right things to lose; a user
 * who wants them still has the ordinary on-primary keyboard, by turning
 * this surface off.
 */
object SecondScreenKeyboard {

    /**
     * How many second-screen keyboard surfaces are up.
     *
     * Counted rather than a flag, because there can legitimately be two at
     * once: the idle `SECONDARY_HOME` activity the platform places, and
     * the live `Presentation` the foreground shell owns above it (the
     * split docs/SPEC.md section 4c exists to explain). With a flag, the
     * first of them to go away would tell the IME the second screen was
     * free and the on-primary keyboard would come back underneath a
     * keyboard that is still on screen.
     */
    @Volatile
    private var attachCount: Int = 0

    /**
     * True while any second-screen keyboard surface is up. Read from Java
     * as `SecondScreenKeyboard.INSTANCE.getAttached()`.
     *
     * [LatinIME.onEvaluateInputViewShown] reads it, which is the platform's
     * own mechanism for "there is a real keyboard elsewhere, do not cover
     * the screen with a soft one" -- the same answer it gives when a
     * hardware keyboard is attached, which is materially what the user has
     * arranged. Suppressing the on-primary keyboard is the entire point of
     * putting one on the second screen; without this the user gets both.
     */
    val attached: Boolean get() = attachCount > 0

    @Synchronized
    fun setAttached(value: Boolean) {
        val before = attachCount > 0
        attachCount = if (value) attachCount + 1 else (attachCount - 1).coerceAtLeast(0)
        if (before == (attachCount > 0)) return
        // The IME re-evaluates only when asked; without this the change
        // takes effect at the next focus event rather than immediately.
        LatinIME.sInstance?.let { ime -> runCatching { ime.updateInputViewShown() } }
    }

    /**
     * Whether the Android route currently has anywhere to type. Both halves
     * are real preconditions: an IME that is installed but not selected is
     * never bound, and a bound IME with no focused editor has a null
     * `InputConnection`.
     */
    fun androidTargetAvailable(): Boolean =
        LatinIME.sInstance?.currentInputConnection != null

    /**
     * A real [LatinKeyboardView], inflated from the fork's own themed
     * layout.
     *
     * [context] must be a context for the display the keyboard will appear
     * on (a `Presentation`'s own context, or one from
     * `createDisplayContext`): [Keyboard] sizes itself from
     * `resources.displayMetrics`, so a context for the wrong display lays
     * the keyboard out for the wrong panel.
     */
    fun createView(
        context: Context,
        listener: LatinKeyboardBaseView.OnKeyboardActionListener,
        heightPercent: Float = DEFAULT_HEIGHT_PERCENT,
    ): LatinKeyboardView {
        val view = LayoutInflater.from(context)
            .inflate(R.layout.input_material_dark, null) as LatinKeyboardView
        applyLayout(view, context, functionLayer = false, heightPercent = heightPercent)
        view.setOnKeyboardActionListener(listener)
        // No key-preview bubbles: they pop ABOVE the touched key, so the
        // top row's would be clipped by the display edge, and a keyboard
        // that is permanently present should not flash what was pressed.
        view.setPreviewEnabled(false)
        view.setPadding(0, 0, 0, 0)
        return view
    }

    /**
     * Swaps between the fork's full layout and its Fn layer -- the fork's
     * own `kbd_full` and `kbd_full_fn` resources, which is where its
     * symbols and secondary keys live. Driven by the Fn and layout-switch
     * keys, which otherwise would do nothing at all on this surface,
     * because the [LatinIME] that normally handles them is not in the loop.
     */
    fun applyLayout(
        view: LatinKeyboardView,
        context: Context,
        functionLayer: Boolean,
        heightPercent: Float = DEFAULT_HEIGHT_PERCENT,
    ) {
        val xml = if (functionLayer) R.xml.kbd_full_fn else R.xml.kbd_full
        val mode = if (functionLayer) {
            KeyboardSwitcher.KEYBOARDMODE_SYMBOLS
        } else {
            KeyboardSwitcher.KEYBOARDMODE_NORMAL
        }
        view.setKeyboard(LatinKeyboard(context, xml, mode, heightPercent))
    }

    /**
     * Share of the second screen the keyboard occupies, leaving the rest
     * for the trackpad. The addon panel is 1080x1920 used in portrait
     * below the main screen, so a little under half of it is a usable key
     * grid with a usable trackpad beneath.
     */
    const val DEFAULT_HEIGHT_PERCENT = 45f
}

/**
 * Turns Hacker's Keyboard key events into hardware-style key press and
 * release pairs.
 *
 * Deliberately driven by `onPress`/`onRelease` rather than `onKey`: both
 * destinations want a physical key model, where a key is held down and the
 * far side owns layout and auto-repeat. `onKey` is the character-oriented
 * callback and would make droidtop decide those things a second time.
 *
 * Modifiers latch, because a touch keyboard cannot expect a user to hold
 * Ctrl with one hand and press C with the other on a screen they are also
 * using as a trackpad. Tapping Ctrl holds it; the next ordinary key
 * releases it; tapping it again lets it go. That is Hacker's Keyboard's
 * own sticky-modifier behaviour, kept rather than replaced.
 */
class SecondScreenKeyboardListener(
    /** [down] false is a release. Keycodes are Android's. */
    private val send: (androidKeyCode: Int, down: Boolean) -> Unit,
    private val resolver: CharKeyResolver,
    /**
     * Text that no key can produce -- an emoji from a popup, an accented
     * character. Null for a destination that has no text channel, which is
     * the container: a compositor takes keys, not strings, and inventing
     * one would mean inventing a layout.
     */
    private val commit: ((CharSequence) -> Unit)? = null,
    /** Fn / layout-switch, which change what THIS keyboard shows and go nowhere else. */
    private val onLayoutToggle: () -> Unit = {},
) : LatinKeyboardBaseView.OnKeyboardActionListener {

    private val latchedModifiers = LinkedHashSet<Int>()
    private val heldKeys = HashMap<Int, KeyStroke>()
    private var transientShift = false

    override fun onPress(primaryCode: Int) {
        if (primaryCode in LAYOUT_KEYS) {
            onLayoutToggle()
            return
        }

        val stroke = strokeFor(primaryCode)
        if (stroke == null) {
            // A printable character with no key that produces it (an
            // emoji, an accent). Committed as text where there is a text
            // channel, dropped where there is not.
            if (HackersKeyCodes.isPrintable(primaryCode)) {
                commit?.invoke(primaryCode.toChar().toString())
            }
            return
        }

        if (stroke.androidKeyCode in MODIFIERS) {
            if (latchedModifiers.remove(stroke.androidKeyCode)) {
                send(stroke.androidKeyCode, false)
            } else {
                latchedModifiers += stroke.androidKeyCode
                send(stroke.androidKeyCode, true)
            }
            return
        }

        if (stroke.shift && KeyEvent.KEYCODE_SHIFT_LEFT !in latchedModifiers) {
            transientShift = true
            send(KeyEvent.KEYCODE_SHIFT_LEFT, true)
        }
        heldKeys[primaryCode] = stroke
        send(stroke.androidKeyCode, true)
    }

    override fun onRelease(primaryCode: Int) {
        val stroke = heldKeys.remove(primaryCode) ?: return
        send(stroke.androidKeyCode, false)
        if (transientShift) {
            transientShift = false
            send(KeyEvent.KEYCODE_SHIFT_LEFT, false)
        }
        releaseLatched()
    }

    /**
     * Nothing: `onPress`/`onRelease` already delivered the key. Auto-repeat
     * arrives here as repeated `onKey` calls with no matching press, and is
     * ignored on purpose -- the key is still held at the far side, which
     * owns the repeat rate. `DesktopInputRouter` made the same call for
     * physical keyboards, for the same reason.
     */
    override fun onKey(primaryCode: Int, keyCodes: IntArray?, x: Int, y: Int) = Unit

    /** Popup keys and multi-character keys arrive whole rather than per key. */
    override fun onText(text: CharSequence?) {
        text ?: return
        val commit = commit
        if (commit != null) {
            commit(text)
            return
        }
        for (character in text) {
            val stroke = resolver.resolve(character) ?: continue
            if (stroke.shift) send(KeyEvent.KEYCODE_SHIFT_LEFT, true)
            send(stroke.androidKeyCode, true)
            send(stroke.androidKeyCode, false)
            if (stroke.shift) send(KeyEvent.KEYCODE_SHIFT_LEFT, false)
        }
    }

    override fun onCancel() = releaseEverything()

    override fun swipeLeft(): Boolean = false
    override fun swipeRight(): Boolean = false
    override fun swipeDown(): Boolean = false
    override fun swipeUp(): Boolean = false

    /**
     * Lets go of everything the far side thinks is held. Called when the
     * surface goes away: a modifier whose release never arrives leaves the
     * container behaving as though Ctrl were permanently down, which is
     * the failure `DesktopInputRouter.releaseHeldInput` also exists for.
     */
    fun releaseEverything() {
        heldKeys.values.forEach { send(it.androidKeyCode, false) }
        heldKeys.clear()
        if (transientShift) {
            transientShift = false
            send(KeyEvent.KEYCODE_SHIFT_LEFT, false)
        }
        releaseLatched()
    }

    private fun releaseLatched() {
        if (latchedModifiers.isEmpty()) return
        latchedModifiers.forEach { send(it, false) }
        latchedModifiers.clear()
    }

    private fun strokeFor(primaryCode: Int): KeyStroke? =
        if (HackersKeyCodes.isPrintable(primaryCode)) {
            resolver.resolve(primaryCode.toChar())
        } else {
            HackersKeyCodes.specialStroke(primaryCode)
        }

    private companion object {
        /**
         * `Keyboard.KEYCODE_MODE_CHANGE` and `LatinKeyboardView.KEYCODE_FN`,
         * by value because both are package-private Java constants.
         */
        val LAYOUT_KEYS = setOf(-2, -119)

        val MODIFIERS = setOf(
            KeyEvent.KEYCODE_SHIFT_LEFT,
            KeyEvent.KEYCODE_SHIFT_RIGHT,
            KeyEvent.KEYCODE_CTRL_LEFT,
            KeyEvent.KEYCODE_CTRL_RIGHT,
            KeyEvent.KEYCODE_ALT_LEFT,
            KeyEvent.KEYCODE_ALT_RIGHT,
            KeyEvent.KEYCODE_META_LEFT,
            KeyEvent.KEYCODE_META_RIGHT,
        )
    }
}
