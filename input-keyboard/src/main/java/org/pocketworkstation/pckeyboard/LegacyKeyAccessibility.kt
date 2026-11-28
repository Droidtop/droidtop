package org.pocketworkstation.pckeyboard

import android.graphics.Rect
import android.os.Bundle
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper

/** Canvas-key semantics. IDs identify key instances, never duplicate key codes or reused positions. */
internal class LegacyKeyAccessibility(private val view: LatinKeyboardBaseView) : ExploreByTouchHelper(view) {
    private var nextId = 1
    private val keys = linkedMapOf<Int, Keyboard.Key>()

    fun replace(replacement: Array<Keyboard.Key>) {
        getAccessibilityNodeProvider(view)?.performAction(accessibilityFocusedVirtualViewId,
            AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS, null)
        keys.clear()
        replacement.forEach { keys[nextId++] = it }
        invalidateRoot()
    }

    fun changed() {
        if (!view.accessibleKeysAvailable() && accessibilityFocusedVirtualViewId != INVALID_ID) {
            getAccessibilityNodeProvider(view)?.performAction(accessibilityFocusedVirtualViewId,
                AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS, null)
        }
        invalidateRoot()
    }

    fun clearFocus() {
        if (accessibilityFocusedVirtualViewId != INVALID_ID) getAccessibilityNodeProvider(view)?.performAction(
            accessibilityFocusedVirtualViewId, AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS, null)
        invalidateRoot()
    }

    fun focusedKey(): Keyboard.Key? = keys[accessibilityFocusedVirtualViewId]
    internal fun visibleIds(): List<Int> = mutableListOf<Int>().also(::getVisibleVirtualViews)
    internal fun hitId(x: Float, y: Float): Int = getVirtualViewAt(x, y)

    private fun bounds(key: Keyboard.Key): Rect = Rect(
        key.x + view.paddingLeft, key.y + view.paddingTop,
        key.x + view.paddingLeft + key.width, key.y + view.paddingTop + key.height,
    ).apply { if (!intersect(0, 0, view.width, view.height)) setEmpty() }

    private fun actionable(key: Keyboard.Key): Boolean = key.text != null || !key.codes.isNullOrEmpty()
    private fun toggle(key: Keyboard.Key): Boolean = key.codes?.firstOrNull() in setOf(
        Keyboard.KEYCODE_SHIFT, LatinKeyboardView.KEYCODE_CTRL_LEFT, LatinKeyboardView.KEYCODE_ALT_LEFT,
        LatinKeyboardView.KEYCODE_META_LEFT, LatinKeyboardView.KEYCODE_FN,
    ) || key.sticky

    override fun getVirtualViewAt(x: Float, y: Float): Int =
        if (!view.accessibleKeysAvailable()) INVALID_ID else keys.entries.firstOrNull {
            actionable(it.value) && bounds(it.value).contains(x.toInt(), y.toInt())
        }?.key ?: INVALID_ID

    override fun getVisibleVirtualViews(ids: MutableList<Int>) {
        if (view.accessibleKeysAvailable()) keys.forEach { (id, key) ->
            if (actionable(key) && !bounds(key).isEmpty) ids += id
        }
    }

    override fun onPopulateNodeForVirtualView(id: Int, node: AccessibilityNodeInfoCompat) {
        val key = keys[id]
        val live = key != null && actionable(key) && view.accessibleKeysAvailable() && !bounds(key).isEmpty
        node.text = key?.let(::label) ?: ""
        node.className = if (key != null && toggle(key)) "android.widget.ToggleButton" else "android.widget.Button"
        node.isFocusable = false // never move the editor's real input focus
        node.isEnabled = live
        node.setBoundsInParent(key?.let(::bounds) ?: Rect(0, 0, 1, 1))
        if (key != null && toggle(key)) {
            node.isCheckable = true
            node.isChecked = key.on
        }
        if (live) {
            node.isClickable = true
            node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK)
        }
    }

    override fun onPopulateEventForVirtualView(id: Int, event: AccessibilityEvent) {
        keys[id]?.let { event.text.add(label(it)) }
        view.invalidate() // draw accessibility focus without changing pad selection or input focus
    }

    override fun onPerformActionForVirtualView(id: Int, action: Int, args: Bundle?): Boolean {
        val key = keys[id] ?: return false
        return action == AccessibilityNodeInfo.ACTION_CLICK && !bounds(key).isEmpty && view.activateAccessibleKey(key)
    }

    private fun label(key: Keyboard.Key): String {
        val code = key.codes?.firstOrNull()
        if (key.isDeadKey && code != null) return view.context.getString(
            R.string.legacy_key_dead_accent, DeadAccentSequence.getSpacing(code.toChar()))
        val resource = when (code) {
            Keyboard.KEYCODE_SHIFT -> R.string.slab_shift
            Keyboard.KEYCODE_MODE_CHANGE -> if (key.caseLabel?.contains("ABC", ignoreCase = true) == true) R.string.slab_letters else R.string.slab_symbols
            Keyboard.KEYCODE_DELETE -> R.string.slab_backspace
            Keyboard.KEYCODE_CANCEL -> R.string.legacy_key_close
            32 -> R.string.slab_space
            9 -> R.string.legacy_key_tab
            10 -> if (!key.caseLabel.isNullOrBlank()) null else R.string.slab_enter
            LatinKeyboardView.KEYCODE_OPTIONS -> R.string.legacy_key_options
            LatinKeyboardView.KEYCODE_VOICE -> R.string.legacy_key_voice
            LatinKeyboardView.KEYCODE_NEXT_LANGUAGE -> R.string.legacy_key_next_language
            LatinKeyboardView.KEYCODE_PREV_LANGUAGE -> R.string.legacy_key_previous_language
            LatinKeyboardView.KEYCODE_COMPOSE -> R.string.legacy_key_compose
            LatinKeyboardView.KEYCODE_DPAD_LEFT -> R.string.slab_move_left
            LatinKeyboardView.KEYCODE_DPAD_RIGHT -> R.string.slab_move_right
            LatinKeyboardView.KEYCODE_DPAD_UP -> R.string.slab_move_up
            LatinKeyboardView.KEYCODE_DPAD_DOWN -> R.string.slab_move_down
            else -> null
        }
        if (resource != null) return view.context.getString(resource)
        return key.caseLabel?.takeIf { it.isNotBlank() }
            ?: key.text?.toString()?.takeIf { it.isNotBlank() }
            ?: if (code != null && code < -1) KeyEvent.keyCodeToString(-code).removePrefix("KEYCODE_").replace('_', ' ')
            else if (code != null && Character.isValidCodePoint(code)) String(Character.toChars(code))
            else view.context.getString(R.string.legacy_key_unknown)
    }
}
