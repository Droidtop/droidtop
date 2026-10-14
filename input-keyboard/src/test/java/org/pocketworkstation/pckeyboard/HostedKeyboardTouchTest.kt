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

    private fun panel(functionRow: Boolean, sent: MutableList<Pair<Int, Boolean>> = ArrayList()): Pair<KeyboardPanel, LatinKeyboardView> {
        val context: Context = RuntimeEnvironment.getApplication()
        val panel = KeyboardPanel(
            context,
            object : KeyboardSink {
                override fun key(androidKeyCode: Int, down: Boolean) {
                    sent += androidKeyCode to down
                }
            },
            functionRow = functionRow,
        )
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
        return panel to find(panel)!!
    }

    @Test fun theCompanionInputPanelHasHackersFunctionRowAboveTheFiveRowsAndTheDraftPanelDoesNot() {
        val f12 = -142 // key_fkey_f12
        val (_, plain) = panel(functionRow = false)
        assertTrue("the draft keyboard keeps five rows", plain.keyboard.keys.none { it.codes != null && it.codes[0] == f12 })
        val (_, full) = panel(functionRow = true)
        val keys = full.keyboard.keys
        val f1 = keys.first { it.codes != null && it.codes[0] == -131 }
        val f12Key = keys.first { it.codes != null && it.codes[0] == f12 }
        val esc = keys.first { it.codes != null && it.codes[0] == -111 }
        assertTrue("Home and End sit on the same row", keys.any { it.codes != null && it.codes[0] == -122 } && f12Key.y == f1.y)
        assertTrue("the function row is above Esc's row", f1.y < esc.y)
        assertTrue("a sixth row exists: more keys than the five-row layout", keys.size > plain.keyboard.keys.size)
        assertTrue("the plain layout still has Tab, Ctrl, Alt and the arrows",
            listOf(9, -113, -57, -19, -20, -21, -22).all { code -> plain.keyboard.keys.any { it.codes != null && it.codes[0] == code } })
    }

    @Test fun pressingF12OnTheFunctionRowSendsTheKey() {
        val sent = ArrayList<Pair<Int, Boolean>>()
        val (_, view) = panel(functionRow = true, sent = sent)
        val key = view.keyboard.keys.first { it.codes != null && it.codes[0] == -142 }
        val x = (key.x + key.width / 2).toFloat() + view.paddingLeft
        val y = (key.y + key.height / 2).toFloat() + view.paddingTop
        val downTime = SystemClock.uptimeMillis()
        touch(view, MotionEvent.ACTION_DOWN, x, y, downTime)
        touch(view, MotionEvent.ACTION_UP, x, y, downTime)
        assertTrue(sent.toString(), sent.containsAll(listOf(KeyEvent.KEYCODE_F12 to true, KeyEvent.KEYCODE_F12 to false)))
    }
}
