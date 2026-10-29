package dev.droidtop.app

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Build
import app.murinelauncher.service.MurineAccessibilityService
import dev.droidtop.library.settings.NoticeTone
import dev.droidtop.library.settings.Notices

/**
 * Start > System > Lock screen in Desktop (Droidtop/tracker#687): the same accessibility-service lock Standard's
 * double tap uses (GestureAction.lockScreen), the one public way an app locks the screen on Android 9 and later.
 * Without the service turned on there is nothing to call, so it says where to turn it on, rather than opening
 * Android's accessibility settings past the disclosure Standard shows first.
 */
internal fun lockScreen(context: Context) {
    val service = MurineAccessibilityService.INSTANCE
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && service != null) {
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
        return
    }
    Notices.flash(
        "Locking the screen needs droidtop's accessibility service. Turn it on in Android's Accessibility settings",
        tone = NoticeTone.Caution,
        context = context,
    )
}
