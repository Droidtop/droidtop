package dev.droidtop.shell.standard

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * A swipe up or down that starts on one home-screen icon (docs/SPEC.md, Launcher mode "Folder covers" and "Icon
 * swipes"; Droidtop/tracker#564): a folder's swipe to open, an app icon's own swipes. It is claimed from the workspace
 * (whose swipe up opens the drawer and swipe down runs its gesture) at half a touch slop, before the workspace's
 * controllers take it at one, and only when it goes more up or down than sideways, so a page swipe still pages; it
 * fires at one touch slop. A direction is claimed only when [wants] says the icon does something with it.
 */
class IconSwipe(
    private val view: View,
    private val wants: (up: Boolean) -> Boolean,
    private val onSwipe: (up: Boolean) -> Unit,
) {
    private val touchSlop = ViewConfiguration.get(view.context).scaledTouchSlop.toFloat()
    private var downX = 0f
    private var downY = 0f
    private var claimed: Boolean? = null
    private var fired = false

    /** Feed every touch event the icon gets. True when this touch is used up by the swipe. */
    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                claimed = null
                fired = false
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (fired) return true
                val dy = event.y - downY
                val up = dy < 0
                val distance = abs(dy)
                if (claimed == null && distance > touchSlop / 2f && distance > abs(event.x - downX) && wants(up)) {
                    claimed = up
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (claimed == up && distance > touchSlop) {
                    fired = true
                    view.cancelLongPress()
                    view.isPressed = false
                    onSwipe(up)
                    return true
                }
                return false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val used = fired
                fired = false
                return used
            }
            else -> return fired
        }
    }
}
