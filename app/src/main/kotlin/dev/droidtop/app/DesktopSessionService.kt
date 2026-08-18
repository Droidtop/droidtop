package dev.droidtop.app

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Foreground service owning the primary container's lifecycle and the
 * host-bridge connection to it — kept alive independent of whether
 * MainActivity (or any other Activity presenting a DisplayOutput) is in the
 * foreground, since the "desktop" should keep running when, e.g., the user
 * is only interacting via the second-screen trackpad.
 */
class DesktopSessionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    // TODO: create/start the primary Container (runtime-linux-root or
    // -noroot, chosen by root availability), connect HostBridge to its
    // Wayland socket, register DisplayOutputs as they appear/disappear.
}
