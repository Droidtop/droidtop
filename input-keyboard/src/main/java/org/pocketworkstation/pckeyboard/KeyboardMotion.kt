package org.pocketworkstation.pckeyboard

import android.animation.ValueAnimator
import android.content.Context
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator

/**
 * Hacker Keyboard Reborn's motion on the device (Droidtop/tracker#903 H12a; docs/SPEC.md 6a, "Motion and haptics"):
 * the duration scale read from Android's settings, and the helpers that move the keyboard's views. The rules are
 * [KeyMotion]'s.
 *
 * The scale is read once each time the keyboard is shown ([refresh]: the input method's onStartInputView, an embedded
 * [KeyboardPanel] coming on screen), never per frame. 0 means no motion anywhere: keys, popups, panels and the
 * suggestion strip all go straight to their end state. Everything moves by translation, scale and alpha, or by what a
 * view draws, so no movement asks for a layout pass.
 */
object KeyboardMotion {
    /** The duration scale last read; 0 is no motion. Written on the main thread, read by the views drawing. */
    @JvmStatic
    @Volatile
    var scale: Float = 1f
        private set

    /** Whether anything moves at all. */
    @JvmStatic
    fun enabled(): Boolean = scale > 0f

    /** Reads the animator duration scale (Android's "Remove animations" sets it to 0) and the animator switch. */
    @JvmStatic
    fun refresh(context: Context) {
        val animator = runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)
        scale = KeyMotion.durationScale(animator, ValueAnimator.areAnimatorsEnabled())
    }
}

/**
 * The pressed key's scale and tint (Droidtop/tracker#903 H12a): read by `LatinKeyboardBaseView` while it draws each
 * key. A key's change of [Keyboard.Key.pressed] is noticed when the key is drawn (every press and release invalidates
 * it), stamped on the key itself, and the key is kept in a fixed list until its movement settles, so each frame only
 * does arithmetic: nothing is allocated. The pressed colour is drawn at once on touch-down; only the shrink, and the
 * return after release, are eased.
 */
class KeyPressMotion {
    private val moving = arrayOfNulls<Keyboard.Key>(MAX_MOVING)
    private var count = 0

    /** Notes [key] changing its pressed state since it was last drawn, at [now] (uptime ms). */
    fun track(key: Keyboard.Key, now: Long, motion: Float) {
        if (key.pressed == key.motionPressed) return
        val elapsed = now - key.motionAt
        key.motionScaleFrom = KeyMotion.keyScale(key.motionPressed, key.motionScaleFrom, elapsed, motion)
        key.motionTintFrom = KeyMotion.keyTint(key.motionPressed, key.motionTintFrom, elapsed, motion)
        key.motionPressed = key.pressed
        key.motionAt = now
        if (motion > 0f) add(key)
    }

    fun scaleOf(key: Keyboard.Key, now: Long, motion: Float): Float =
        KeyMotion.keyScale(key.motionPressed, key.motionScaleFrom, now - key.motionAt, motion)

    fun tintOf(key: Keyboard.Key, now: Long, motion: Float): Float =
        KeyMotion.keyTint(key.motionPressed, key.motionTintFrom, now - key.motionAt, motion)

    /**
     * Asks [view] for another frame over each key still moving, and forgets the ones that settled. [padLeft] and
     * [padTop] are the view's padding, where key coordinates start.
     */
    fun schedule(view: View, now: Long, motion: Float, padLeft: Int, padTop: Int) {
        var kept = 0
        for (i in 0 until count) {
            val key = moving[i] ?: continue
            if (KeyMotion.keySettled(key.motionPressed, now - key.motionAt, motion)) continue
            moving[kept++] = key
            view.postInvalidateOnAnimation(key.x + padLeft, key.y + padTop, key.x + key.width + padLeft, key.y + key.height + padTop)
        }
        for (i in kept until count) moving[i] = null
        count = kept
    }

    /** Forgets every key (a new keyboard was set). */
    fun clear() {
        for (i in 0 until count) moving[i] = null
        count = 0
    }

    private fun add(key: Keyboard.Key) {
        for (i in 0 until count) if (moving[i] === key) return
        if (count < MAX_MOVING) moving[count++] = key
    }

    private companion object {
        /** More fingers than a hand has, and the keys a slide passes over. */
        const val MAX_MOVING = 16
    }
}

/**
 * The key preview and the long-press popup growing out of the key and shrinking back into it (Droidtop/tracker#903
 * H12a). The popup windows themselves have no window animation; their content moves, so the movement follows
 * [KeyboardMotion] like everything else.
 */
object PopupMotion {
    private val grow = DecelerateInterpolator()
    private val shrink = AccelerateInterpolator()

    /**
     * Shows [view] growing from a key: [pivotX], [pivotY] are the key's centre in the view's coordinates, and
     * [fromScaleX], [fromScaleY] the key's size as a share of the view's.
     */
    @JvmStatic
    fun grow(view: View, pivotX: Float, pivotY: Float, fromScaleX: Float, fromScaleY: Float) {
        view.animate().cancel()
        view.pivotX = pivotX
        view.pivotY = pivotY
        if (!KeyboardMotion.enabled()) {
            rest(view)
            return
        }
        view.scaleX = fromScaleX.coerceIn(0.1f, 1f)
        view.scaleY = fromScaleY.coerceIn(0.1f, 1f)
        view.alpha = 0f
        view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(KeyMotion.POPUP_IN_MS).setInterpolator(grow)
    }

    /** Shrinks [view] back toward the pivot [grow] set, then runs [then] (which dismisses the popup). */
    @JvmStatic
    fun shrink(view: View, toScale: Float, then: Runnable) {
        view.animate().cancel()
        if (!KeyboardMotion.enabled() || !view.isAttachedToWindow) {
            rest(view)
            then.run()
            return
        }
        view.animate().scaleX(toScale).scaleY(toScale).alpha(0f).setDuration(KeyMotion.POPUP_OUT_MS).setInterpolator(shrink)
            .withEndAction {
                then.run()
                rest(view)
            }
    }

    /** Stops any movement of [view] (its end action does not run) and puts it at full size. */
    @JvmStatic
    fun rest(view: View) {
        view.animate().cancel()
        view.scaleX = 1f
        view.scaleY = 1f
        view.alpha = 1f
    }
}

/**
 * Tool panels sliding in place over the keys, and the keys fading under them (Droidtop/tracker#903 H12a): emoji with
 * its kaomoji tab, clipboard history and macros, in the input method and in every keyboard droidtop embeds. A panel
 * leaving is drawn in its container's overlay, where it takes no touches, so a tap right after it closes reaches the
 * keys.
 */
object PanelMotion {
    private val ease = DecelerateInterpolator()

    /** [panel], just added, rises [distancePx] into place and fades in. */
    @JvmStatic
    fun enter(panel: View, distancePx: Float) {
        panel.animate().cancel()
        if (!KeyboardMotion.enabled()) return
        panel.translationY = distancePx
        panel.alpha = 0f
        panel.animate().translationY(0f).alpha(1f).setDuration(KeyMotion.PANEL_MS).setInterpolator(ease)
    }

    /**
     * Takes [panel] out of [parent]: it sinks [distancePx] and fades in [parent]'s overlay, then is gone. With no
     * motion it is removed at once.
     */
    @JvmStatic
    fun exit(parent: ViewGroup, panel: View, distancePx: Float) {
        panel.animate().cancel()
        if (!KeyboardMotion.enabled() || !parent.isAttachedToWindow) {
            parent.removeView(panel)
            return
        }
        val overlay = parent.overlay
        overlay.add(panel)
        panel.animate().translationY(distancePx).alpha(0f).setDuration(KeyMotion.PANEL_MS).setInterpolator(ease)
            .withEndAction { overlay.remove(panel) }
    }

    /** The keys under an opening panel fade out, then are [hiddenAs] (INVISIBLE, keeping their size). */
    @JvmStatic
    fun hide(keys: View, hiddenAs: Int) {
        keys.animate().cancel()
        if (!KeyboardMotion.enabled()) {
            keys.alpha = 1f
            keys.visibility = hiddenAs
            return
        }
        keys.animate().alpha(0f).setDuration(KeyMotion.PANEL_MS).setInterpolator(ease).withEndAction {
            keys.visibility = hiddenAs
            keys.alpha = 1f
        }
    }

    /** The keys come back as a panel closes, fading in from where they are. */
    @JvmStatic
    fun show(keys: View) {
        keys.animate().cancel()
        val wasHidden = keys.visibility != View.VISIBLE
        keys.visibility = View.VISIBLE
        if (!KeyboardMotion.enabled()) {
            keys.alpha = 1f
            return
        }
        if (wasHidden) keys.alpha = 0f
        if (keys.alpha >= 1f) return
        keys.animate().alpha(1f).setDuration(KeyMotion.PANEL_MS).setInterpolator(ease)
    }

    /** A panel's content changed (another emoji group, the kaomoji tab): it slides in [distancePx] from that side. */
    @JvmStatic
    fun swap(content: View, distancePx: Float) {
        content.animate().cancel()
        if (!KeyboardMotion.enabled()) {
            content.translationX = 0f
            content.alpha = 1f
            return
        }
        content.translationX = distancePx
        content.alpha = 0f
        content.animate().translationX(0f).alpha(1f).setDuration(KeyMotion.PANEL_MS).setInterpolator(ease)
    }
}
