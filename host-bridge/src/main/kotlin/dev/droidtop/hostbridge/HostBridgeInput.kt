package dev.droidtop.hostbridge

/**
 * The input-injection + frame-presentation surface [HostBridge] exposes,
 * pulled out as an interface so callers (`:input-seat`'s `InputSeat`, in
 * particular) can be unit-tested against a fake instead of the real JNI-
 * backed class — [HostBridge] calls `System.loadLibrary("hostbridge")` in
 * its `init` block, which throws `UnsatisfiedLinkError` in a plain JVM unit
 * test (no `.so` to load), so anything that wants test coverage needs to
 * depend on this interface, not the concrete class, directly.
 */
interface HostBridgeInput {
    fun injectPointerMotion(dx: Double, dy: Double)
    fun injectPointerMotionAbsolute(x: Double, y: Double, extentWidth: Int, extentHeight: Int)

    /**
     * The pointer at ([x], [y]) of a [extentWidth] x [extentHeight] surface showing the compositor output named
     * [outputName], wherever that output sits in the layout (Droidtop/tracker#908, steps D1b and D3). False, and
     * nothing sent, when there is no output of that name.
     */
    fun injectPointerMotionAbsolute(outputName: String, x: Double, y: Double, extentWidth: Int, extentHeight: Int): Boolean
    fun injectPointerButton(linuxButtonCode: Int, pressed: Boolean)
    fun injectPointerAxis(horizontal: Double, vertical: Double)
    /**
     * [typed]: the key was derived from a character by Android (an on-screen keyboard, droidtop's own keyboards),
     * so it goes through the keyboard that keeps the US keymap those keys were chosen against; false for a
     * physical keyboard's keys, which take the person's layout (docs/SPEC.md 6b "Keyboard layout").
     */
    fun injectKey(evdevKeyCode: Int, pressed: Boolean, typed: Boolean = false)
}
