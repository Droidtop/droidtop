package app.murinelauncher.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import dev.droidtop.shell.standard.StandardTaskbar

class MurineAccessibilityService: AccessibilityService() {
    public companion object {
        @JvmField public var INSTANCE: MurineAccessibilityService? = null
    }

    /** Standard mode's taskbar (docs/SPEC.md 2c "Taskbar on large screens"); asks for events and keys only while it is on. */
    private var taskbar: StandardTaskbar? = null

    override fun onServiceConnected() {
        // Starts asking for nothing (no events, no keys); the taskbar asks for what it needs while it is on.
        serviceInfo = AccessibilityServiceInfo()
        INSTANCE = this
        super.onServiceConnected()
        taskbar = StandardTaskbar(this).also { it.attach() }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        INSTANCE = null
        taskbar?.detach()
        taskbar = null
        return super.onUnbind(intent)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean = taskbar?.onKey(event) ?: false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null) taskbar?.onEvent(event)
    }

    override fun onInterrupt() {}
}
