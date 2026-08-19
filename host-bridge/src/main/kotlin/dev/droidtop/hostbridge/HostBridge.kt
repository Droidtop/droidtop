package dev.droidtop.hostbridge

import android.view.Surface
import dev.droidtop.runtime.DisplayOutput

/**
 * Android's ONLY privileged surface in the whole system, mirroring dom0's
 * narrow GUI-daemon role in Qubes. Does not implement a compositor, window
 * manager, or any Wayland server logic — that all runs inside the primary
 * container as vendor/sway. This class:
 *
 *  1. Connects to the primary container's Wayland socket as a client.
 *  2. Pulls frames from its headless output(s) via wlr-screencopy and
 *     presents them on an Android [Surface] — one per [DisplayOutput].
 *  3. Forwards normalized input from :input-seat in as virtual
 *     pointer/keyboard events.
 */
class HostBridge : HostBridgeInput {
    private var connected = false

    fun connect(waylandSocketPath: String): Boolean {
        connected = nativeConnect(waylandSocketPath)
        return connected
    }

    fun disconnect() {
        if (connected) {
            nativeStopPresenting()
            nativeDisconnect()
            connected = false
        }
    }

    /**
     * Starts a continuous frame-capture loop targeting [surface]. [output]
     * is accepted but currently ignored on the native side — the native
     * capture loop is single-output only for now (always the primary
     * screen), matching the merged-desktop MVP; see host-bridge/README.md
     * for what real multi-output support needs. Passing a non-primary
     * [DisplayOutput] here won't do what its name implies yet.
     */
    fun presentOutput(output: DisplayOutput, surface: Surface): Boolean {
        return nativePresentOutput(surface)
    }

    fun stopPresenting() {
        nativeStopPresenting()
    }

    // ---- Input injection, called from :input-seat's InputSeat ----

    /** Relative pointer motion (trackpad-style delta), in compositor-defined units. */
    override fun injectPointerMotion(dx: Double, dy: Double) {
        nativeInjectPointerMotion(dx, dy)
    }

    /** Absolute pointer position within a [extentWidth] x [extentHeight] logical area. */
    override fun injectPointerMotionAbsolute(x: Double, y: Double, extentWidth: Int, extentHeight: Int) {
        nativeInjectPointerMotionAbsolute(x, y, extentWidth, extentHeight)
    }

    /** [linuxButtonCode]: BTN_LEFT/BTN_RIGHT/BTN_MIDDLE from linux/input-event-codes.h (0x110/0x111/0x112). */
    override fun injectPointerButton(linuxButtonCode: Int, pressed: Boolean) {
        nativeInjectPointerButton(linuxButtonCode, pressed)
    }

    override fun injectPointerAxis(horizontal: Double, vertical: Double) {
        nativeInjectPointerAxis(horizontal, vertical)
    }

    /** [evdevKeyCode]: Linux evdev keycode (KEY_* from linux/input-event-codes.h), not an Android KeyEvent code. */
    override fun injectKey(evdevKeyCode: Int, pressed: Boolean) {
        nativeInjectKey(evdevKeyCode, pressed)
    }

    private external fun nativeConnect(waylandSocketPath: String): Boolean
    private external fun nativeDisconnect()
    private external fun nativePresentOutput(surface: Surface): Boolean
    private external fun nativeStopPresenting()
    private external fun nativeInjectPointerMotion(dx: Double, dy: Double)
    private external fun nativeInjectPointerMotionAbsolute(x: Double, y: Double, extentWidth: Int, extentHeight: Int)
    private external fun nativeInjectPointerButton(linuxButtonCode: Int, pressed: Boolean)
    private external fun nativeInjectPointerAxis(horizontal: Double, vertical: Double)
    private external fun nativeInjectKey(evdevKeyCode: Int, pressed: Boolean)

    companion object {
        init {
            System.loadLibrary("hostbridge")
        }
    }
}
