package dev.droidtop.runtime.tasks

import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * The grants droidtop's system controls need, taken through the elevated shell when it can (Droidtop/tracker#581;
 * docs/SPEC.md "Privileged actions"): modify system settings (brightness, auto-rotate, screen timeout), Bluetooth
 * access (its state and paired devices, Android 12 and later) and Do Not Disturb access. Each is asked for only
 * while droidtop does not hold it, and checked again after, so a start with Shizuku running leaves the controls
 * working without Android's own screens; a start without it changes nothing, and the controls open those screens.
 *
 * Every grant names droidtop's own package and nothing else, so it is not "Give another app access"
 * ([RiskyClass.GRANT_ACCESS], which gates [PrivilegedShell.grantPermission] and [PrivilegedShell.setAppOp] for
 * emulators): these run as plain commands through [PrivilegedShell.exec].
 */
object ElevatedGrants {
    private const val TAG = "droidtop.ElevatedGrants"

    /** What each grant is called in the log, and whether droidtop holds it now. Pure apart from [held]. */
    data class Grant(val name: String, val held: (Context) -> Boolean, val take: (Context, PrivilegedShell) -> Boolean)

    val GRANTS: List<Grant> = listOfNotNull(
        Grant(
            "modify system settings",
            held = { Settings.System.canWrite(it) },
            take = { context, shell -> shell.exec(listOf("appops", "set", context.packageName, "WRITE_SETTINGS", "allow"))?.exit == 0 },
        ),
        Grant(
            "Bluetooth",
            held = { it.checkSelfPermission("android.permission.BLUETOOTH_CONNECT") == PackageManager.PERMISSION_GRANTED },
            take = { context, shell -> shell.exec(listOf("pm", "grant", context.packageName, "android.permission.BLUETOOTH_CONNECT"))?.exit == 0 },
        ).takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.S },
        Grant(
            "Do Not Disturb",
            held = { (it.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).isNotificationPolicyAccessGranted },
            take = { context, shell -> shell.exec(listOf("cmd", "notification", "allow_dnd", context.packageName))?.exit == 0 },
        ),
    )

    /**
     * Takes every grant droidtop lacks, waiting up to [waitMs] for the shell to come up. The ones now held, by name.
     * Binder and shell calls: on a thread of its own, never the main thread.
     */
    fun takeAll(context: Context, shell: PrivilegedShell, waitMs: Long = 10_000): List<String> {
        val missing = GRANTS.filterNot { runCatching { it.held(context) }.getOrDefault(true) }
        if (missing.isEmpty()) return emptyList()
        runCatching { shell.connect(waitMs) }
        if (!runCatching { shell.capabilities().shell }.getOrDefault(false)) return emptyList()
        return missing.mapNotNull { grant ->
            val asked = runCatching { grant.take(context, shell) }.getOrDefault(false)
            val now = runCatching { grant.held(context) }.getOrDefault(false)
            Log.i(TAG, "${grant.name}: ${if (now) "granted" else if (asked) "asked, not held yet" else "refused"}")
            grant.name.takeIf { now }
        }
    }
}
