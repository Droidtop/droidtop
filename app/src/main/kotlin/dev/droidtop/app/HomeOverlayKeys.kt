package dev.droidtop.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import dev.droidtop.runtime.overlay.HomeChords
import dev.droidtop.runtime.overlay.OverlayRequest
import dev.droidtop.runtime.overlay.OverlayRequests

/**
 * The HOME chords through droidtop's accessibility service (docs/SPEC.md "In-game overlays", Droidtop/tracker#740):
 * HOME + R2, L2 or Start opens an overlay over whatever is on screen, any app, droidtop's own screens and Android's
 * ([OverlayRequests]); HOME alone is HOME. Even the
 * home app never receives HOME itself, so only the service's key filter can see the chord.
 *
 * The filter is asked for only while droidtop is the default home, read again when a window changes (at most every
 * [HOME_CHECK_MS]); otherwise the service sees no keys at all and HOME is Android's, as before. Each key is decided at
 * once ([HomeChords]): nothing but HOME is held, and everything else goes on untouched.
 */
object HomeOverlayKeys {
    private val chords = HomeChords()
    private var filtering = false
    private var checkedAt = 0L

    /** The display of the window that last came to the front, where an overlay is drawn. */
    @Volatile
    private var frontDisplay = Display.DEFAULT_DISPLAY

    /** The app whose window last came to the front, droidtop's own included: what HOME + L2's menu is about. */
    @Volatile
    private var frontPackage: String? = null

    fun connect(s: AccessibilityService) {
        checkedAt = 0L
        refresh(s)
    }

    fun onEvent(s: AccessibilityService, event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        // droidtop's own overlay coming to the front is not what the person was looking at.
        if (!OverlayRequests.open) {
            event.packageName?.toString()?.let { frontPackage = it }
            if (android.os.Build.VERSION.SDK_INT >= 30) event.source?.window?.displayId?.let { frontDisplay = it }
        }
        refresh(s)
    }

    fun onKey(s: AccessibilityService, event: KeyEvent): Boolean {
        if (!filtering) return false
        val fromPad = event.isFromSource(InputDevice.SOURCE_GAMEPAD) || event.isFromSource(InputDevice.SOURCE_JOYSTICK)
        val sink = OverlayRequests.keys
        val decision = chords.onKey(event.keyCode, event.action == KeyEvent.ACTION_DOWN, event.repeatCount, fromPad, sink != null, event.eventTime)
        // Every decision on HOME, a chord button or a key it took, so a console log shows where a chord went
        // (Droidtop/tracker#740); a key passed on untouched is not logged.
        if (decision.consume || event.keyCode == KeyEvent.KEYCODE_HOME || HomeChords.kindOf(event.keyCode) != null) {
            android.util.Log.i(
                "droidtop.input",
                "chord ${KeyEvent.keyCodeToString(event.keyCode)} ${if (event.action == KeyEvent.ACTION_DOWN) "down" else "up"}" +
                    "#${event.repeatCount}: consume=${decision.consume} open=${decision.open} home=${decision.home} " +
                    "overlay=${decision.toOverlay} idle=${chords.idle}",
            )
        }
        decision.open?.let { raise(it) }
        if (decision.home) s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        if (decision.toOverlay) sink?.invoke(event)
        return decision.consume
    }

    /**
     * Opens [kind] over what is in front, as its chord does: the touch route (Android's accessibility button,
     * [TypingAccessibilityService]) asks through here so it lands on the same display and app the chords would.
     */
    fun raise(kind: OverlayRequest.Kind): Boolean = OverlayRequests.raise(OverlayRequest(kind, frontDisplay, frontPackage))

    /** Asks for the key filter while droidtop is the default home, and drops it otherwise. */
    private fun refresh(s: AccessibilityService) {
        val now = SystemClock.elapsedRealtime()
        if (checkedAt != 0L && now - checkedAt < HOME_CHECK_MS) return
        checkedAt = now
        val want = isDefaultHome(s)
        if (want == filtering) return
        dev.droidtop.runtime.keyboard.AccessibilityHost.wantKeyFilter("home chords", want)
        filtering = want
        // No release of a key seen so far will come through a filter that is off.
        if (!want) chords.reset()
        android.util.Log.i("droidtop.input", "chord filter ${if (want) "on" else "off"}")
    }

    private fun isDefaultHome(s: AccessibilityService): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return s.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == s.packageName
    }

    private const val HOME_CHECK_MS = 2_000L
}
