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
    fun injectPointerButton(linuxButtonCode: Int, pressed: Boolean)
    fun injectPointerAxis(horizontal: Double, vertical: Double)
    fun injectKey(evdevKeyCode: Int, pressed: Boolean)
}
