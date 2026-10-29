package dev.droidtop.runtime.windows

import dev.droidtop.runtime.windows.data.TouchGestureConfig

/**
 * A Windows game's touchscreen gestures as settings rows (Droidtop/tracker#618 C8: "`TouchGestureConfig` has twelve
 * slots stored per container and no screen edits it"). One row per gesture: Off, or what it does. Pure: the rows are
 * read from a [TouchGestureConfig] and a choice gives the next one ([apply]); [WineOptions] keeps it per game and lays
 * it over the prefix at launch.
 */
object WineGestures {
    const val ROW_PREFIX = "wine_gesture_"
    const val OFF = "off"
    private const val DOUBLE_CLICK = "double_click"

    private val LABELS = mapOf(
        OFF to "Off",
        TouchGestureConfig.ACTION_LEFT_CLICK to "Left click",
        TouchGestureConfig.ACTION_RIGHT_CLICK to "Right click",
        TouchGestureConfig.ACTION_MIDDLE_CLICK to "Middle click",
        TouchGestureConfig.ACTION_SHOW_KEYBOARD to "Show the keyboard",
        TouchGestureConfig.ACTION_KEY_ESC to "Escape",
        TouchGestureConfig.ACTION_KEY_TILDE to "Tilde (~)",
        TouchGestureConfig.PAN_ARROW_KEYS to "Arrow keys",
        TouchGestureConfig.PAN_INVERTED_ARROW_KEYS to "Arrow keys, reversed",
        TouchGestureConfig.PAN_WASD to "W A S D",
        TouchGestureConfig.PAN_INVERTED_WASD to "W A S D, reversed",
        TouchGestureConfig.PAN_MIDDLE_MOUSE to "Middle-button drag",
        TouchGestureConfig.PAN_INVERTED_MIDDLE_MOUSE to "Middle-button drag, reversed",
        TouchGestureConfig.PAN_LEFT_CLICK_DRAG to "Left-button drag",
        TouchGestureConfig.PAN_RIGHT_CLICK_DRAG to "Right-button drag",
        TouchGestureConfig.ZOOM_SCROLL_WHEEL to "Scroll wheel",
        TouchGestureConfig.ZOOM_PLUS_MINUS to "Plus and minus",
        TouchGestureConfig.ZOOM_PAGE_UP_DOWN to "Page up and down",
        DOUBLE_CLICK to "Double click",
    )

    private val CLICKS = TouchGestureConfig.COMMON_MOUSE_ACTIONS
    private val TAP_ACTIONS = CLICKS + TouchGestureConfig.ACTION_SHOW_KEYBOARD
    private val HOLD_ACTIONS = TAP_ACTIONS + TouchGestureConfig.ACTION_KEY_ESC + TouchGestureConfig.ACTION_KEY_TILDE

    /** One gesture's row: its id, its name, what it can do (Off first), and what it does now. */
    data class Row(val id: String, val title: String, val values: List<String>, val current: String) {
        fun label(value: String): String = LABELS[value] ?: value
    }

    private class Slot(
        val key: String,
        val title: String,
        val values: List<String>,
        val read: (TouchGestureConfig) -> String,
        val write: (TouchGestureConfig, Boolean, String?) -> TouchGestureConfig,
    )

    private fun choice(enabled: Boolean, action: String) = if (enabled) action else OFF

    private val SLOTS = listOf(
        Slot("tap", "Tap", TAP_ACTIONS, { choice(it.tapEnabled, it.tapAction) }) { c, on, a -> c.copy(tapEnabled = on, tapAction = a ?: c.tapAction) },
        Slot("double_tap", "Double tap", listOf(DOUBLE_CLICK), { choice(it.doubleTapEnabled, DOUBLE_CLICK) }) { c, on, _ -> c.copy(doubleTapEnabled = on) },
        Slot("drag", "Drag", TouchGestureConfig.PAN_ACTIONS, { choice(it.dragEnabled, it.dragAction) }) { c, on, a -> c.copy(dragEnabled = on, dragAction = a ?: c.dragAction) },
        Slot("long_press", "Long press", HOLD_ACTIONS, { choice(it.longPressEnabled, it.longPressAction) }) { c, on, a ->
            c.copy(longPressEnabled = on, longPressAction = a ?: c.longPressAction)
        },
        Slot("two_finger_tap", "Two-finger tap", TAP_ACTIONS, { choice(it.twoFingerTapEnabled, it.twoFingerTapAction) }) { c, on, a ->
            c.copy(twoFingerTapEnabled = on, twoFingerTapAction = a ?: c.twoFingerTapAction)
        },
        Slot("two_finger_drag", "Two-finger drag", TouchGestureConfig.PAN_ACTIONS, { choice(it.twoFingerDragEnabled, it.twoFingerDragAction) }) { c, on, a ->
            c.copy(twoFingerDragEnabled = on, twoFingerDragAction = a ?: c.twoFingerDragAction)
        },
        Slot("two_finger_hold", "Two-finger hold", HOLD_ACTIONS, { choice(it.twoFingerHoldEnabled, it.twoFingerHoldAction) }) { c, on, a ->
            c.copy(twoFingerHoldEnabled = on, twoFingerHoldAction = a ?: c.twoFingerHoldAction)
        },
        Slot("pinch", "Pinch", TouchGestureConfig.ZOOM_ACTIONS, { choice(it.pinchEnabled, it.pinchAction) }) { c, on, a ->
            c.copy(pinchEnabled = on, pinchAction = a ?: c.pinchAction)
        },
        Slot("three_finger_drag", "Three-finger drag", TouchGestureConfig.PAN_ACTIONS, { choice(it.threeFingerDragEnabled, it.threeFingerDragAction) }) { c, on, a ->
            c.copy(threeFingerDragEnabled = on, threeFingerDragAction = a ?: c.threeFingerDragAction)
        },
        Slot("three_finger_hold", "Three-finger hold", HOLD_ACTIONS, { choice(it.threeFingerHoldEnabled, it.threeFingerHoldAction) }) { c, on, a ->
            c.copy(threeFingerHoldEnabled = on, threeFingerHoldAction = a ?: c.threeFingerHoldAction)
        },
    )

    /** Every gesture's row for [config], in the order the page lists them. */
    fun rows(config: TouchGestureConfig): List<Row> =
        SLOTS.map { Row(ROW_PREFIX + it.key, it.title, listOf(OFF) + it.values, it.read(config)) }

    /** [config] with row [rowId] set to [value]: Off turns the gesture off, anything it offers turns it on to that. */
    fun apply(config: TouchGestureConfig, rowId: String, value: String): TouchGestureConfig {
        val slot = SLOTS.firstOrNull { ROW_PREFIX + it.key == rowId } ?: return config
        if (value == OFF) return slot.write(config, false, null)
        if (value !in slot.values) return config
        return slot.write(config, true, value.takeIf { it != DOUBLE_CLICK })
    }

    /** One line for the page's entry: how many gestures are on. Pure. */
    fun summary(config: TouchGestureConfig): String {
        val on = rows(config).count { it.current != OFF }
        return "$on of ${SLOTS.size} gestures on"
    }
}
