package dev.droidtop.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * The notification permission, asked for the desktop the first time it
 * starts (docs/SPEC.md §3, "asked when the session is first started";
 * §7b, asked at the feature, once, with the reason first).
 *
 * Android 13+ hides an app's notifications until the person allows them,
 * and droidtop never asked, so the desktop's notification, the one place
 * its Stop is reachable from anywhere, was invisible on the rig's Android
 * 14 (dq-desk2-01). The reason comes first, in droidtop's own dialog, and
 * Android's prompt only after "Allow". Either answer is remembered and the
 * question is not repeated: a refusal costs only the notification, since
 * the desktop still stops when the person leaves Desktop or presses "Stop
 * the desktop" in Containers, and the dialog says so.
 */
object DesktopNotificationPermission {
    private const val PREFS = "desktop_session"
    private const val KEY_ASKED = "notification_permission_asked"

    const val PERMISSION = Manifest.permission.POST_NOTIFICATIONS

    /** Whether to ask now: Android 13+, not yet allowed, and not asked before. */
    fun shouldAsk(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        if (context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED) return false
        return !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ASKED, false)
    }

    fun markAsked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ASKED, true).apply()
    }

    @Composable
    fun Dialog(onAllow: () -> Unit, onDecline: () -> Unit) {
        AlertDialog(
            onDismissRequest = onDecline,
            title = { Text("Show the desktop's notification?") },
            text = {
                Text(
                    "The desktop keeps running while you use other apps. Android shows that with a " +
                        "notification, and its Stop button ends the desktop from anywhere. Without it the " +
                        "desktop still works; it stops when you leave Desktop or press \"Stop the desktop\" " +
                        "in Containers.",
                )
            },
            confirmButton = { TextButton(onClick = onAllow) { Text("Allow") } },
            dismissButton = { TextButton(onClick = onDecline) { Text("Don't allow") } },
        )
    }
}
