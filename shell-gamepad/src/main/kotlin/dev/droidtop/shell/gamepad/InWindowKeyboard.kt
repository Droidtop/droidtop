package dev.droidtop.shell.gamepad

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import dev.droidtop.runtime.keyboard.AddonKeyboard
import dev.droidtop.runtime.keyboard.AddonKeyboardRules
import org.pocketworkstation.pckeyboard.KeyboardPanel
import org.pocketworkstation.pckeyboard.WindowKeySink
import java.util.WeakHashMap

/**
 * droidtop's keyboard for every text field in a droidtop activity (docs/SPEC.md 4c, "Typing on the add-on display",
 * Droidtop/tracker#314): store sign-in pages, onboarding, settings, any screen outside the Gaming shell's own fields.
 * On a display Android draws no keyboard on ([AddonKeyboardRules.ownFieldNeedsKeyboard]), while the window's focused
 * view is a text editor (`View.onCheckIsTextEditor`: an EditText, a Compose text field, an editable web page field),
 * the shared keyboard ([KeyboardPanel]) is drawn at the bottom of that window and the content above it is padded by
 * its height, so the field stays visible. Keys go into the window's own key path ([WindowKeySink]): no input method,
 * no focus change, no permission. "Keyboard displays on" may put it elsewhere ([KeyboardTargets]: the companion's
 * input controller, the internal screen) or nowhere; a Hide there holds until focus leaves the window's editors.
 * A window whose field already draws [OwnFieldKeyboard] beside it gets no second.
 * One helper for every activity, installed from the application's activity callbacks; main thread only.
 */
object InWindowKeyboard {
    private const val HIDE_DELAY_MS = 150L

    /** Root views with an [OwnFieldKeyboard] composed in them, counted. Weak: a gone window is forgotten. */
    private val ownFieldRoots = WeakHashMap<View, Int>()

    /** Activities already watched. Weak keys, plain values: nothing here keeps an activity alive. */
    private val attached = WeakHashMap<Activity, Boolean>()

    internal fun ownFieldShown(root: View, shown: Boolean) {
        val count = (ownFieldRoots[root] ?: 0) + if (shown) 1 else -1
        if (count <= 0) ownFieldRoots.remove(root) else ownFieldRoots[root] = count
    }

    /** Watches [activity]'s window from now on; calling it again does nothing. */
    fun attach(activity: Activity) {
        if (attached.put(activity, true) != null) return
        val decor = activity.window?.decorView as? ViewGroup ?: return
        decor.viewTreeObserver.addOnPreDrawListener(Watcher(activity, decor))
    }

    /** Decides on every frame of the window, which costs a focus lookup and a flag: no allocation, no I/O. */
    private class Watcher(private val activity: Activity, private val decor: ViewGroup) : ViewTreeObserver.OnPreDrawListener {
        private var panel: KeyboardPanel? = null
        private var elsewhere: KeyboardTargets.Request? = null
        private var dismissed = false
        private var content: View? = null
        private var basePadding = 0
        private var hidePending = false
        private val hide = Runnable {
            hidePending = false
            hideNow()
        }

        override fun onPreDraw(): Boolean {
            val editing = wanted()
            if (!editing) dismissed = false
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
            if (!AddonKeyboardRules.ownFieldNeedsKeyboard(displayId, AddonKeyboard.localDisplays.value)) return false
            if ((ownFieldRoots[decor] ?: 0) > 0) return false
            val focused = decor.findFocus() ?: return false
            return focused.onCheckIsTextEditor()
        }

        private fun show() {
            val sink = WindowKeySink { decor }
            val displayId = decor.display?.displayId ?: return
            when (val opened = KeyboardTargets.open(displayId, sink) { elsewhere = null; dismissed = true }) {
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
