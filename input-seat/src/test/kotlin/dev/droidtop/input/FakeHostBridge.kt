package dev.droidtop.input

import dev.droidtop.hostbridge.HostBridgeInput

/**
 * Records every call instead of touching real Wayland input injection —
 * this is exactly why [HostBridgeInput] was pulled out of the concrete,
 * JNI-backed `HostBridge` class (which would throw `UnsatisfiedLinkError`
 * in a plain JVM test).
 */
internal class FakeHostBridge : HostBridgeInput {
    val pointerMotions = mutableListOf<Pair<Double, Double>>()
    val pointerAbsolutes = mutableListOf<List<Any>>()
    val pointerButtons = mutableListOf<Pair<Int, Boolean>>()
    val pointerAxes = mutableListOf<Pair<Double, Double>>()
    val keys = mutableListOf<Pair<Int, Boolean>>()

    override fun injectPointerMotion(dx: Double, dy: Double) {
        pointerMotions += dx to dy
    }

    override fun injectPointerMotionAbsolute(x: Double, y: Double, extentWidth: Int, extentHeight: Int) {
        pointerAbsolutes += listOf(x, y, extentWidth, extentHeight)
    }

    override fun injectPointerButton(linuxButtonCode: Int, pressed: Boolean) {
        pointerButtons += linuxButtonCode to pressed
    }

    override fun injectPointerAxis(horizontal: Double, vertical: Double) {
        pointerAxes += horizontal to vertical
    }

    /** Which keyboard each key in [keys] went through: true for the typed-text (US) one. */
    val typed = mutableListOf<Boolean>()

    override fun injectKey(evdevKeyCode: Int, pressed: Boolean, typed: Boolean) {
        keys += evdevKeyCode to pressed
        this.typed += typed
    }
}
