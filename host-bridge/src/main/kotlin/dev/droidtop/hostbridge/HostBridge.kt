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
class HostBridge {
    private var connected = false

    fun connect(waylandSocketPath: String): Boolean {
        connected = nativeConnect(waylandSocketPath)
        return connected
    }

    fun presentOutput(output: DisplayOutput, surface: Surface) {
        TODO("Bind a wlr-screencopy capture loop for this output to the given Surface")
    }

    fun disconnect() {
        if (connected) {
            nativeDisconnect()
            connected = false
        }
    }

    private external fun nativeConnect(waylandSocketPath: String): Boolean
    private external fun nativeDisconnect()

    companion object {
        init {
            System.loadLibrary("hostbridge")
        }
    }
}
