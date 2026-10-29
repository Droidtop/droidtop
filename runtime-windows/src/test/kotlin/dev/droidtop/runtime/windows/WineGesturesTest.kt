package dev.droidtop.runtime.windows

import dev.droidtop.runtime.windows.data.TouchGestureConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A Windows game's touchscreen gestures as rows (Droidtop/tracker#618 C8). */
class WineGesturesTest {
    private val defaults = TouchGestureConfig()

    private fun row(config: TouchGestureConfig, key: String) = WineGestures.rows(config).single { it.id == WineGestures.ROW_PREFIX + key }

    @Test
    fun `every gesture is a row, Off first, showing what it does now`() {
        val rows = WineGestures.rows(defaults)
        assertEquals(10, rows.size)
        assertTrue(rows.all { it.values.first() == WineGestures.OFF })
        assertEquals(TouchGestureConfig.ACTION_LEFT_CLICK, row(defaults, "tap").current)
        assertEquals(WineGestures.OFF, row(defaults, "long_press").current)
        assertEquals("Right click", row(defaults, "two_finger_tap").let { it.label(it.current) })
        assertEquals("8 of 10 gestures on", WineGestures.summary(defaults))
    }

    @Test
    fun `a choice turns a gesture on to it, and Off turns it off`() {
        val on = WineGestures.apply(defaults, WineGestures.ROW_PREFIX + "long_press", TouchGestureConfig.ACTION_SHOW_KEYBOARD)
        assertTrue(on.longPressEnabled)
        assertEquals(TouchGestureConfig.ACTION_SHOW_KEYBOARD, on.longPressAction)
        val off = WineGestures.apply(on, WineGestures.ROW_PREFIX + "tap", WineGestures.OFF)
        assertFalse(off.tapEnabled)
        assertEquals(TouchGestureConfig.ACTION_LEFT_CLICK, off.tapAction)
        val pinch = WineGestures.apply(defaults, WineGestures.ROW_PREFIX + "pinch", TouchGestureConfig.ZOOM_PAGE_UP_DOWN)
        assertEquals(TouchGestureConfig.ZOOM_PAGE_UP_DOWN, pinch.pinchAction)
    }

    @Test
    fun `double tap is on or off, and a value a gesture does not offer changes nothing`() {
        val off = WineGestures.apply(defaults, WineGestures.ROW_PREFIX + "double_tap", WineGestures.OFF)
        assertFalse(off.doubleTapEnabled)
        assertTrue(WineGestures.apply(off, WineGestures.ROW_PREFIX + "double_tap", "double_click").doubleTapEnabled)
        assertEquals(defaults, WineGestures.apply(defaults, WineGestures.ROW_PREFIX + "pinch", TouchGestureConfig.ACTION_LEFT_CLICK))
        assertEquals(defaults, WineGestures.apply(defaults, "something_else", WineGestures.OFF))
    }

    @Test
    fun `a changed set survives being kept as text`() {
        val changed = WineGestures.apply(defaults, WineGestures.ROW_PREFIX + "three_finger_drag", TouchGestureConfig.PAN_WASD)
        assertEquals(changed, TouchGestureConfig.fromJson(changed.toJson()))
    }
}
