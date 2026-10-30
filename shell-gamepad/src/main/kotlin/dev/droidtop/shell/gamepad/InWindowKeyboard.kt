package dev.droidtop.shell.gamepad

import android.app.Activity
import android.graphics.Rect
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.widget.FrameLayout
import dev.droidtop.library.credentials.handoff.RemoteInputRouter
import dev.droidtop.library.credentials.handoff.RemoteTarget
import org.pocketworkstation.pckeyboard.KeyboardPanel
import org.pocketworkstation.pckeyboard.WindowKeySink
import java.util.WeakHashMap

/**
 * droidtop's keyboard for every text field in a droidtop activity (docs/SPEC.md 4c, "Typing on the add-on display",
 * Droidtop/tracker#314): store sign-in pages, onboarding, settings, any screen outside the Gaming shell's own fields.
 * Wherever droidtop's keyboard serves the field ([KeyboardTargets.servesField]: a display Android draws no keyboard
 * on, or any display while a companion takes the typing, Droidtop/tracker#612), while the window's focused
 * view is a text editor (`View.onCheckIsTextEditor`: an EditText, a Compose text field, an editable web page field)
 * and the person chose to type in it (a tap on it, A or Enter on it, or a key typed; or at once, on one touch-only
 * screen, [KeyboardTargets.raisesOnOwnFocus]),
 * the shared keyboard ([KeyboardPanel]) is drawn at the bottom of that window and the content above it is padded by
 * its height, so the field stays visible. Keys go into the window's own key path ([WindowKeySink]): no input method,
 * no focus change, no permission. "Keyboard displays on" may put it elsewhere ([KeyboardTargets]: the companion's
 * input controller, the internal screen) or nowhere; a Hide there holds until focus leaves the window's editors.
 * A window whose field already draws [OwnFieldKeyboard] beside it gets no second.
 * One helper for every activity, installed from the application's activity callbacks; main thread only.
 */
object InWindowKeyboard {
    private const val HIDE_DELAY_MS = 150L

    /** Activities already watched. Weak keys, plain values: nothing here keeps an activity alive. */
    private val attached = WeakHashMap<Activity, Boolean>()

    /**
     * The text field of a droidtop window that droidtop's keyboard serves right now ([KeyboardTargets.servesField]),
     * as a target for text typed on another device (docs/SPEC.md, "Type from another device"): the window that has
     * focus first, else any other droidtop window on screen whose focused view is a text editor. Text and keys go
     * into the window's own key path ([WindowKeySink]), as droidtop's keyboards do. Null when there is none.
     * A field whose kind cannot be read (not an `EditText`, and the window gives no accessibility node for it:
     * Compose, a web page) counts as a password, so nothing typed into it is shown. Main thread only.
     */
    fun focusedOwnField(): RemoteTarget? {
        val decors = attached.keys.mapNotNull { it.window?.decorView }
            .filter { it.isAttachedToWindow && it.windowVisibility == View.VISIBLE }
            .sortedByDescending { it.hasWindowFocus() }
        for (decor in decors) {
            val field = decor.findFocus()?.takeIf { it.onCheckIsTextEditor() } ?: continue
            if (!KeyboardTargets.servesField(decor.display?.displayId, KeyboardTargets.assistiveOn(decor.context))) continue
            return WindowTarget(WindowKeySink { decor }, isSecret(field))
        }
        return null
    }

    private fun isSecret(field: View): Boolean {
        if (field is android.widget.TextView) {
            return RemoteInputRouter.isPasswordInputType(field.inputType) ||
                field.transformationMethod is android.text.method.PasswordTransformationMethod
        }
        val node = runCatching { field.accessibilityNodeProvider?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
        return node == null || node.isPassword
    }

    private class WindowTarget(private val sink: WindowKeySink, override val secret: Boolean) : RemoteTarget {
        override fun commit(text: String) = sink.text(text)

        override fun backspace(count: Int) = repeat(count) { press(android.view.KeyEvent.KEYCODE_DEL) }

        override fun enter() = press(android.view.KeyEvent.KEYCODE_ENTER)

        private fun press(code: Int) {
            sink.key(code, true)
            sink.key(code, false)
        }
    }

    /** Watches [activity]'s window from now on; calling it again does nothing. */
    fun attach(activity: Activity) {
        if (attached.put(activity, true) != null) return
        dev.droidtop.shell.gamepad.input.ControllerLayouts.listen(activity)
        val decor = activity.window?.decorView as? ViewGroup ?: return
        val watcher = Watcher(activity, decor)
        decor.viewTreeObserver.addOnPreDrawListener(watcher)
        decor.viewTreeObserver.addOnGlobalFocusChangeListener(watcher)
        val window = activity.window
        val callback = window.callback ?: return
        window.callback = EditorTaps(callback, watcher::tapped, watcher::keyed)
    }

    /** The window's callback, told of each touch that starts and each key pressed; everything goes on to [base]. */
    private class EditorTaps(
        private val base: Window.Callback,
        private val tapped: (MotionEvent) -> Unit,
        private val keyed: (android.view.KeyEvent) -> Unit,
    ) : Window.Callback by base {
        override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
            if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) keyed(event)
            return base.dispatchKeyEvent(event)
        }

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) tapped(event)
            return base.dispatchTouchEvent(event)
        }
    }

    /** Decides on every frame of the window, which costs a focus lookup and a flag: no allocation, no I/O. */
    private class Watcher(private val activity: Activity, private val decor: ViewGroup) :
        ViewTreeObserver.OnPreDrawListener,
        ViewTreeObserver.OnGlobalFocusChangeListener {
        private val bounds = Rect()

        private var panel: KeyboardPanel? = null
        private var elsewhere: KeyboardTargets.Request? = null
        private var dismissed = false

        // Whether the person chose to type in the focused field; droidtop putting the cursor there is not a choice.
        private var picked = false
        private var content: View? = null
        private var basePadding = 0
        private var hidePending = false
        private val hide = Runnable {
            hidePending = false
            hideNow()
        }

        /** A field took focus: a Hide pressed for the last one does not apply to it (tracker#369). */
        override fun onGlobalFocusChanged(oldFocus: View?, newFocus: View?) {
            if (newFocus != null && newFocus !== oldFocus && newFocus.onCheckIsTextEditor()) {
                dismissed = false
                picked = KeyboardTargets.raisesOnOwnFocus()
            }
        }

        /** A key on the focused field: A, Enter or a typed character is the person choosing to type (Droidtop/tracker#612). */
        fun keyed(event: android.view.KeyEvent) {
            if (picked) return
            val focused = decor.findFocus() ?: return
            if (!focused.onCheckIsTextEditor()) return
            val code = event.keyCode
            if (code == android.view.KeyEvent.KEYCODE_BUTTON_A || code == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                code == android.view.KeyEvent.KEYCODE_ENTER || event.isPrintingKey
            ) {
                picked = true
            }
        }

        /** A touch landed on the focused field: a Hide holds only until the field is tapped again (tracker#369). */
        fun tapped(event: MotionEvent) {
            val focused = decor.findFocus() ?: return
            if (!focused.onCheckIsTextEditor() || !focused.getGlobalVisibleRect(bounds)) return
            if (bounds.contains(event.x.toInt(), event.y.toInt())) {
                dismissed = false
                picked = true
            }
        }

        override fun onPreDraw(): Boolean {
            val editing = wanted()
            if (!editing) dismissed = false
            // The keys are served on another screen: no Android keyboard over this window as well (#523).
            if (editing && elsewhere != null) KeyboardTargets.hideAndroidKeyboard(decor)
            if (editing && !dismissed) {
                if (hidePending) {
                    decor.removeCallbacks(hide)
                    hidePending = false
                }
                if (panel == null && elsewhere == null) show()
            } else if ((panel != null || elsewhere != null) && !hidePending) {
                // Focus passes through nothing for a frame when it moves between fields.
                hidePending = true
                decor.postDelayed(hide, HIDE_DELAY_MS)
            }
            return true
        }

        private fun wanted(): Boolean {
            val displayId = decor.display?.displayId ?: return false
            if (OwnFieldWindows.has(decor)) return false
            val focused = decor.findFocus() ?: return false
            if (!focused.onCheckIsTextEditor()) return false
            if (!KeyboardTargets.servesField(displayId)) return false
            return picked
        }

        private fun show() {
            val sink = WindowKeySink { decor }
            val displayId = decor.display?.displayId ?: return
            when (val opened = KeyboardTargets.open(displayId, sink, onHide = { elsewhere = null; dismissed = true }, assistive = KeyboardTargets.assistiveOn(decor.context))) {
                is KeyboardTargets.Opened.Elsewhere -> {
                    elsewhere = opened.request
                    return
                }
                // droidtop does not control the keyboard: nothing until focus leaves the editors.
                KeyboardTargets.Opened.Nowhere -> {
                    dismissed = true
                    return
                }
                KeyboardTargets.Opened.Here -> Unit
            }
            val keyboard = KeyboardPanel(activity, sink, INLINE_KEYBOARD_HEIGHT_PERCENT)
            if (!keyboard.hasKeys) return
            val body = decor.findViewById<View>(android.R.id.content)
            content = body
            basePadding = body?.paddingBottom ?: 0
            keyboard.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                val target = content ?: return@addOnLayoutChangeListener
                val bottom = basePadding + v.height
                if (target.paddingBottom != bottom) target.setPadding(target.paddingLeft, target.paddingTop, target.paddingRight, bottom)
            }
            decor.addView(keyboard, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            panel = keyboard
        }

        private fun hideNow() {
            elsewhere?.let { KeyboardTargets.close(it) }
            elsewhere = null
            val keyboard = panel ?: return
            decor.removeView(keyboard)
            panel = null
            content?.let { it.setPadding(it.paddingLeft, it.paddingTop, it.paddingRight, basePadding) }
            content = null
        }
    }
}
