package org.pocketworkstation.pckeyboard

import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.MotionEvent
import android.os.SystemClock
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.LinearLayout
import androidx.customview.widget.ExploreByTouchHelper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LegacyKeyAccessibilityTest {
    private class Grid(context: Context) : LatinKeyboardBaseView(context, null) {
        fun popup(visible: Boolean) { mMiniKeyboardVisible = visible; invalidateAllKeys() }
    }
    private open class Listener : LatinKeyboardBaseView.OnKeyboardActionListener {
        val events = mutableListOf<String>()
        override fun onPress(code: Int) { events += "down:$code" }
        override fun onRelease(code: Int) { events += "up:$code" }
        override fun onKey(code: Int, nearby: IntArray?, x: Int, y: Int) { events += "key:$code" }
        override fun onText(text: CharSequence?) { events += "text:$text" }
        override fun onCancel() { events += "cancel" }
        override fun swipeLeft() = false
        override fun swipeRight() = false
        override fun swipeUp() = false
        override fun swipeDown() = false
    }
    private fun keyboard(context: Context, codes: List<Int>): Keyboard =
        Keyboard(context, 50, R.xml.kbd_numpad).apply {
            keys.clear()
            codes.forEachIndexed { index, code ->
                keys.add(Keyboard.Key(Keyboard.Row(this)).apply {
                    this.codes = intArrayOf(code)
                    label = if (code > 32) code.toChar().toString() else null
                    x = index * 150; y = 0; width = 100; height = 80
                    modifier = code < 0 // intentionally includes Delete, which is NOT a toggle
                })
            }
        }
    private fun fixture(block: (Grid, EditText, Context) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val manager = activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            shadowOf(manager).setEnabled(true); shadowOf(manager).setTouchExplorationEnabled(true)
            val editor = EditText(activity)
            val grid = Grid(activity)
            val root = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(editor, LinearLayout.LayoutParams(1000, 100))
                addView(grid, LinearLayout.LayoutParams(1000, 300))
            }
            activity.setContentView(root)
            root.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1000, 500)
            assertTrue(editor.requestFocus())
            block(grid, editor, activity)
        } finally { controller.pause().stop().destroy() }
    }
    private fun helper(grid: Grid): LegacyKeyAccessibility {
        val field = LatinKeyboardBaseView::class.java.getDeclaredField("mAccessibleKeys").apply { isAccessible = true }
        return field.get(grid) as LegacyKeyAccessibility
    }
    private fun ids(grid: Grid): List<Int> = helper(grid).visibleIds()
    private fun node(grid: Grid, id: Int) = checkNotNull(grid.accessibilityNodeProvider.createAccessibilityNodeInfo(id))
    private fun click(grid: Grid, id: Int) = grid.accessibilityNodeProvider.performAction(id, AccessibilityNodeInfo.ACTION_CLICK, null)

    @Test fun duplicateCodesHaveDistinctNodesAndSplitGapsDoNotHitKeys() = fixture { grid, editor, context ->
        grid.setPadding(7, 3, 0, 0)
        grid.setKeyboard(keyboard(context, listOf(Keyboard.KEYCODE_SHIFT, 'a'.code, Keyboard.KEYCODE_SHIFT, Keyboard.KEYCODE_DELETE)))
        val ids = ids(grid)
        assertEquals(4, ids.toSet().size)
        assertEquals(ExploreByTouchHelper.INVALID_ID, helper(grid).hitId(120f, 30f))
        assertEquals(ids[0], helper(grid).hitId(8f, 4f))
        val bounds = Rect(); node(grid, ids[0]).getBoundsInParent(bounds)
        assertEquals(Rect(7, 3, 107, 83), bounds)
        assertEquals("Shift", node(grid, ids[0]).text.toString())
        assertEquals("Backspace", node(grid, ids.last()).text.toString())
        assertFalse(node(grid, ids.last()).isCheckable)
        assertFalse(node(grid, ids[1]).isFocusable)
        assertTrue(editor.hasFocus())
    }

    @Test fun clickAndTextUseExistingBalancedDeliveryWithoutTakingEditorFocus() = fixture { grid, editor, context ->
        val keyboard = keyboard(context, listOf('a'.code, 'b'.code))
        keyboard.keys[1].apply { text = "hello"; codes = null }
        grid.setKeyboard(keyboard)
        val listener = Listener(); grid.setOnKeyboardActionListener(listener)
        assertTrue(click(grid, ids(grid)[0])); assertTrue(click(grid, ids(grid)[1]))
        assertEquals(listOf("down:97", "key:97", "up:97", "text:hello", "up:0"), listener.events)
        assertTrue(editor.hasFocus())
    }

    @Test fun replacementInvalidatesOldNodesAndClearsVanishedFocus() = fixture { grid, editor, context ->
        grid.setKeyboard(keyboard(context, listOf('a'.code)))
        val oldId = ids(grid).single()
        grid.accessibilityNodeProvider.performAction(oldId, AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
        grid.setKeyboard(keyboard(context, listOf('b'.code)))
        assertFalse(click(grid, oldId))
        assertFalse(ids(grid).contains(oldId))
        assertEquals(ExploreByTouchHelper.INVALID_ID, helper(grid).accessibilityFocusedVirtualViewId)
        assertTrue(editor.hasFocus())
    }

    @Test fun layoutChangingPressCannotTypeTheNewKeyAtOldCoordinates() = fixture { grid, _, context ->
        grid.setKeyboard(keyboard(context, listOf('a'.code)))
        val listener = object : Listener() {
            override fun onPress(code: Int) { super.onPress(code); grid.setKeyboard(keyboard(context, listOf('z'.code))) }
        }
        grid.setOnKeyboardActionListener(listener)
        assertFalse(click(grid, ids(grid).single()))
        assertEquals(listOf("down:97", "up:97"), listener.events)
    }

    @Test fun modifierPressSurvivesLayoutSwapAndStillReleasesOriginalModifier() = fixture { grid, editor, context ->
        grid.setKeyboard(keyboard(context, listOf(Keyboard.KEYCODE_SHIFT)))
        val listener = object : Listener() {
            override fun onPress(code: Int) { super.onPress(code); grid.setKeyboard(keyboard(context, listOf('z'.code))) }
        }
        grid.setOnKeyboardActionListener(listener)
        assertTrue(click(grid, ids(grid).single()))
        assertEquals(listOf("down:-1", "key:-1", "up:-1"), listener.events)
        assertTrue(editor.hasFocus())
    }

    @Test fun visiblePopupSuppressesUnderlyingNodesAndActionsUntilClosed() = fixture { grid, _, context ->
        grid.setKeyboard(keyboard(context, listOf('a'.code)))
        val id = ids(grid).single()
        val listener = Listener(); grid.setOnKeyboardActionListener(listener)
        grid.popup(true)
        assertTrue(ids(grid).isEmpty()); assertFalse(click(grid, id)); assertTrue(listener.events.isEmpty())
        grid.popup(false)
        assertTrue(click(grid, id))
    }

    @Test fun deadKeysAndMultiTapStillUseTheTouchOutputCore() = fixture { grid, _, context ->
        val keyboard = keyboard(context, listOf(0x301, 'a'.code))
        keyboard.keys[1].codes = intArrayOf('a'.code, 'b'.code)
        grid.setKeyboard(keyboard)
        val listener = Listener(); grid.setOnKeyboardActionListener(listener)
        val ids = ids(grid)
        assertTrue(click(grid, ids[0])); assertTrue(click(grid, ids[1])); assertTrue(click(grid, ids[1]))
        assertEquals(listOf("down:769", "key:769", "up:769", "down:97", "key:97", "up:97",
            "down:97", "key:-5", "key:98", "up:98"), listener.events)
    }

    @Test fun modifierStateAndSizeRefreshWithoutRealKeyboardFocus() = fixture { grid, editor, context ->
        val keyboard = keyboard(context, listOf(Keyboard.KEYCODE_SHIFT))
        grid.setKeyboard(keyboard)
        val id = ids(grid).single()
        keyboard.keys[0].on = true
        grid.invalidateAllKeys()
        assertTrue(node(grid, id).isChecked)
        grid.layout(0, 0, 50, 40)
        val bounds = Rect(); node(grid, id).getBoundsInParent(bounds)
        assertEquals(Rect(0, 0, 50, 40), bounds)
        assertTrue(editor.hasFocus())
    }

    @Test fun semanticClicksWaitForTouchReleaseAndDoNotInheritPhysicalMultiTapHistory() = fixture { grid, _, context ->
        val keyboard = keyboard(context, listOf('a'.code))
        keyboard.keys[0].codes = intArrayOf('a'.code, 'b'.code)
        grid.setKeyboard(keyboard)
        val listener = Listener(); grid.setOnKeyboardActionListener(listener)
        val id = ids(grid).single()
        val time = SystemClock.uptimeMillis()
        fun touch(action: Int) {
            val event = MotionEvent.obtain(time, time, action, 40f, 40f, 0)
            try { grid.onTouchEvent(event) } finally { event.recycle() }
        }
        touch(MotionEvent.ACTION_DOWN)
        assertFalse(click(grid, id))
        touch(MotionEvent.ACTION_UP)
        listener.events.clear()
        assertTrue(click(grid, id))
        assertEquals(listOf("down:97", "key:97", "up:97"), listener.events)
    }
}
