package app.murinelauncher.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import dev.droidtop.shell.standard.StandardTaskbar

class MurineAccessibilityService: AccessibilityService() {
    public companion object {
        @JvmField public var INSTANCE: MurineAccessibilityService? = null;
    }

    /** Standard mode's taskbar (docs/SPEC.md 2c "Taskbar on large screens"); asks for events and keys only while it is on. */
    private var taskbar: StandardTaskbar? = null

    override fun onServiceConnected() {
        serviceInfo = AccessibilityServiceInfo().apply {
            // Set the type of events that this service wants to listen to.  Others
            // won't be passed to this service.
            eventTypes = 0

            // If you only want this service to work with specific applications, set their
            // package names here.  Otherwise, when the service is activated, it will listen
            // to events from all applications.
            packageNames = emptyArray()
        }
        INSTANCE = this
        super.onServiceConnected()
        taskbar = StandardTaskbar(this).also { it.attach() }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        INSTANCE = null;
        taskbar?.detach()
        taskbar = null
        return super.onUnbind(intent)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean = taskbar?.onKey(event) ?: false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null) taskbar?.onEvent(event)
    }

    override fun onInterrupt() {}
}
