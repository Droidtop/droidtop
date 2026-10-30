package dev.droidtop.runtime.keyboard

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo

/**
 * droidtop's one accessibility service, as the parts that use it see it (Droidtop/tracker#740: one mechanism per
 * job, where there were two services). The service (`TypingAccessibilityService` in `:app`) sets [service] while it
 * is bound. Each part that needs Android's key filter says so by name ([wantKeyFilter]): Standard's taskbar while
 * it is on, the HOME chords while droidtop is the default home. The filter is on while any part wants it, so no
 * part turns it off under another.
 */
object AccessibilityHost {
    @Volatile
    var service: AccessibilityService? = null
        set(value) {
            field = value
            apply()
        }

    private val keyFilterWanted = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** [owner] wants Android's key filter ([on]) or no longer does. */
    fun wantKeyFilter(owner: String, on: Boolean) {
        val changed = if (on) keyFilterWanted.add(owner) else keyFilterWanted.remove(owner)
        if (changed) apply()
    }

    /** Runs [action] (an `AccessibilityService.GLOBAL_ACTION_*`); false when the service is not bound. */
    fun performGlobalAction(action: Int): Boolean = service?.performGlobalAction(action) == true

    private fun apply() {
        val s = service ?: return
        val info = s.serviceInfo ?: return
        val want = keyFilterWanted.isNotEmpty()
        val has = info.flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS != 0
        if (want == has) return
        info.flags = if (want) {
            info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        } else {
            info.flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS.inv()
        }
        s.serviceInfo = info
    }
}
