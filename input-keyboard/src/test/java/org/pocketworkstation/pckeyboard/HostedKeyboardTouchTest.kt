package org.pocketworkstation.pckeyboard

import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A touch on a keyboard droidtop hosts itself (the companion's, the in-window panel). Only the input method sets a
 * language switcher and the other fields of `LatinIME`; touching a hosted key crashed droidtop with a null language
 * switcher (rig-5566 on 1702, `LatinKeyboard.getLanguageChangeDirection`). Here a real key is pressed and released
 * on a [KeyboardPanel] with no input method in the process.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HostedKeyboardTouchTest {
    private fun find(view: View): LatinKeyboardView? {
        if (view is LatinKeyboardView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun touch(view: View, action: Int, x: Float, y: Float, downTime: Long) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        view.dispatchTouchEvent(event)
        event.recycle()
    }

    @Test fun pressingAKeyOnAHostedKeyboardTypesItAndDoesNotCrash() {
        val context: Context = RuntimeEnvironment.getApplication()
        val sent = ArrayList<Pair<Int, Boolean>>()
        val panel = KeyboardPanel(
            context,
            object : KeyboardSink {
                override fun key(androidKeyCode: Int, down: Boolean) {
                    sent += androidKeyCode to down
                }
            },
        )
        assertTrue(panel.hasKeys)
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
        val view = find(panel)
        assertNotNull(view)
        view!!
        val key = view.keyboard.keys.first { it.codes != null && it.codes[0] == 'a'.code }
        val x = (key.x + key.width / 2).toFloat() + view.paddingLeft
        val y = (key.y + key.height / 2).toFloat() + view.paddingTop
        val downTime = SystemClock.uptimeMillis()
        touch(view, MotionEvent.ACTION_DOWN, x, y, downTime)
        touch(view, MotionEvent.ACTION_UP, x, y, downTime)
        assertTrue(sent.toString(), sent.any { it.first == KeyEvent.KEYCODE_A && it.second })
    }
}
