package org.pocketworkstation.pckeyboard

import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.view.View
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

/** Real Android node-provider/focus policy in CI; not a TalkBack or physical layout test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SlabAccessibilityTest {
    @Test fun everyLiveLayoutHasUniqueStableActionIdentities() = fixture { slab, _, _ ->
        for (layer in Slab.Layer.entries) for (large in listOf(false, true)) for (extras in listOf(false, true)) {
            val actions = Slab.rows(layer, large, extras).flatten().map { it.action }
            val ids = actions.map(slab::accessibleId)
            assertEquals("$layer large=$large extras=$extras", ids.size, ids.toSet().size)
            assertTrue(ids.all { it > 0 })
        }
    }

    private fun fixture(extras: Boolean = false, test: (SlabView, EditText, MutableList<Slab.Output>) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val manager = activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            shadowOf(manager).setEnabled(true)
            shadowOf(manager).setTouchExplorationEnabled(true)
            val outputs = mutableListOf<Slab.Output>()
            val editor = EditText(activity)
            val slab = SlabView(activity, Slab.Kind.PLAIN, Slab.Form.FIELD, Slab.EnterPurpose.SEARCH,
                KeyboardColors(0, 1, 2, 3, 4, 5), { it.name }, { outputs += it }, extras = extras)
            val root = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(editor, LinearLayout.LayoutParams(1000, 100))
                addView(slab, LinearLayout.LayoutParams(1000, 400))
            }
            activity.setContentView(root)
            root.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1000, 600)
            assertTrue(editor.requestFocus())
            test(slab, editor, outputs)
        } finally { controller.pause().stop().destroy() }
    }

    private fun node(slab: SlabView, action: Slab.Action): AccessibilityNodeInfo =
        checkNotNull(slab.accessibilityNodeProvider?.createAccessibilityNodeInfo(slab.accessibleId(action)))

    private fun activate(slab: SlabView, action: Slab.Action, operation: Int = AccessibilityNodeInfo.ACTION_CLICK): Boolean =
        checkNotNull(slab.accessibilityNodeProvider).performAction(slab.accessibleId(action), operation, null)

    @Test fun semanticKeysAreNamedAndCanTypeWithoutTakingEditorFocus() = fixture { slab, editor, outputs ->
        val provider = checkNotNull(slab.accessibilityNodeProvider)
        val host = checkNotNull(provider.createAccessibilityNodeInfo(ExploreByTouchHelper.HOST_ID))
        assertEquals(slab.state.keys.sumOf { it.size }, host.childCount)
        val q = Slab.Action.Char('q')
        val key = node(slab, q)
        assertEquals("q", key.text.toString())
        assertEquals("android.widget.Button", key.className.toString())
        assertTrue(key.isClickable)
        assertFalse(key.isFocusable)
        val bounds = Rect(); key.getBoundsInParent(bounds)
        assertTrue(bounds.width() > 0 && bounds.height() > 0)
        assertFalse(slab.isFocusable)
        assertTrue(activate(slab, q, AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
        assertTrue(editor.hasFocus())
        assertTrue(activate(slab, q))
        assertEquals(listOf(Slab.Output.Type("q")), outputs)
        assertTrue(editor.hasFocus())
        assertEquals(slab.context.getString(R.string.slab_search), node(slab, Slab.Action.Enter).text.toString())
        assertEquals(slab.context.getString(R.string.slab_move_left), node(slab, Slab.Action.CaretLeft).text.toString())
    }

    @Test fun shiftAndCapsExposeTheirStateAndUseTheExistingKeyOutput() = fixture { slab, editor, outputs ->
        assertFalse(node(slab, Slab.Action.Shift).isChecked)
        assertTrue(activate(slab, Slab.Action.Shift))
        assertTrue(node(slab, Slab.Action.Shift).isChecked)
        assertEquals("Q", node(slab, Slab.Action.Char('q')).text.toString())
        assertTrue(activate(slab, Slab.Action.Char('q')))
        assertEquals(Slab.Output.Type("Q"), outputs.last())
        assertFalse(node(slab, Slab.Action.Shift).isChecked)
        assertTrue(activate(slab, Slab.Action.Shift, AccessibilityNodeInfo.ACTION_LONG_CLICK))
        assertEquals(Slab.ShiftState.CAPS, slab.state.shift)
        assertTrue(activate(slab, Slab.Action.Char('q')))
        assertEquals(Slab.ShiftState.CAPS, slab.state.shift)
        assertTrue(activate(slab, Slab.Action.Shift, AccessibilityNodeInfo.ACTION_LONG_CLICK))
        assertEquals(Slab.ShiftState.OFF, slab.state.shift)
        assertTrue(editor.hasFocus())
    }

    @Test fun removedLetterNodesCannotActivateSymbolsAtTheirOldPosition() = fixture { slab, _, outputs ->
        val provider = checkNotNull(slab.accessibilityNodeProvider)
        assertTrue(activate(slab, Slab.Action.Char('q'), AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
        assertTrue(activate(slab, Slab.Action.Symbols))
        assertEquals(Slab.Layer.SYMBOLS, slab.state.layer)
        assertFalse(activate(slab, Slab.Action.Char('q')))
        assertNull(provider.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY))
        assertTrue(activate(slab, Slab.Action.Char('1')))
        assertEquals(listOf(Slab.Output.Type("1")), outputs)
    }

    @Test fun companionModifiersProduceAChordAndReleaseTheirLatchedState() = fixture(extras = true) { slab, editor, outputs ->
        assertTrue(activate(slab, Slab.Action.Ctrl))
        assertTrue(node(slab, Slab.Action.Ctrl).isChecked)
        assertTrue(activate(slab, Slab.Action.Char('q')))
        assertTrue(outputs.single() is Slab.Output.Chord)
        assertFalse(node(slab, Slab.Action.Ctrl).isChecked)
        assertTrue(editor.hasFocus())
    }

    @Test fun resizingKeepsTheSameAccessibleKeyAndEditorFocus() = fixture { slab, editor, _ ->
        val q = Slab.Action.Char('q')
        assertTrue(activate(slab, q, AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
        slab.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(250, View.MeasureSpec.EXACTLY))
        slab.layout(0, 100, 600, 350)
        val focused = slab.accessibilityNodeProvider?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        assertEquals("q", focused?.text.toString())
        assertTrue(editor.hasFocus())
        assertFalse(slab.isFocusable)
    }
}
