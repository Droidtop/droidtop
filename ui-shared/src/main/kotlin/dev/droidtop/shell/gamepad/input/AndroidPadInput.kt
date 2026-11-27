package dev.droidtop.shell.gamepad.input

/** Android's raw event adapter for hosts which dispatch before Compose sees the event. The shared edge state
 * and repeat cadence are the same ones the Compose onPad modifier uses. */
fun PadEdges.dispatch(event: android.view.KeyEvent, preview: Boolean, handle: (PadPress) -> Boolean): Boolean {
    val keyCode = event.keyCode
    if (keyCode == android.view.KeyEvent.KEYCODE_BACK) return false
    if ((event.flags and android.view.KeyEvent.FLAG_FALLBACK) != 0) return false
    if (preview && GamepadKeyMap.isTextKey(keyCode)) return false
    val action = GamepadKeyMap.actionFor(keyCode, event.isShiftPressed) ?: return false
    return when (event.action) {
        android.view.KeyEvent.ACTION_DOWN -> down(keyCode, action, event.repeatCount, event.downTime, event.eventTime, handle)
        android.view.KeyEvent.ACTION_UP -> up(keyCode)
        else -> false
    }
}
