package dev.droidtop.runtime.systemstatus

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/** The special grants Android 13 and later lock for an app installed outside a store ("restricted settings"). */
enum class RestrictedGrant(val key: String) {
    NOTIFICATIONS("notifications"),
    ACCESSIBILITY("accessibility"),
}

/**
 * The rule, without Android in it. Android 13+ keeps an app op, ACCESS_RESTRICTED_SETTINGS, per app: the package
 * installer sets it to MODE_ERRORED for an app installed from a file, and App info's "Allow restricted settings"
 * sets it to MODE_ALLOWED (frameworks/base PackageInstallerSession, packages/apps/Settings
 * RestrictedPreferenceHelper, android-13.0.0_r1). Android 15 may leave it at MODE_DEFAULT and decide from the
 * install source instead, which an app cannot read; there, a grant screen droidtop opened that did not end in the
 * grant is taken as the sign. The step is shown only while the grant is still missing.
 */
object RestrictedSettingsRules {
    const val MODE_ALLOWED = 0
    const val MODE_ERRORED = 2

    fun stepNeeded(sdk: Int, granted: Boolean, opMode: Int?, attempted: Boolean): Boolean =
        sdk >= 33 && !granted && opMode != MODE_ALLOWED && (opMode == MODE_ERRORED || attempted)
}

/**
 * Android 13+ "restricted settings" (docs/SPEC.md 4c, "Typing on the add-on display"; Droidtop/tracker#314,
 * #329): a sideloaded droidtop cannot be given notification access or turn its accessibility service on until
 * "Allow restricted settings" is chosen in its App info screen. Every place that offers one of those grants also
 * offers this one step when it is needed, as one row: [TITLE] with the value [VALUE], opening App info.
 */
object RestrictedSettings {
    const val TITLE = "Allow restricted settings"
    const val VALUE = "App info"
    private const val OP = "android:access_restricted_settings"
    private const val PREFS = "restricted_settings"

    /** Reads an app op and droidtop's preferences: not for the main thread's hot path. */
    fun stepNeeded(context: Context, grant: RestrictedGrant, granted: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < 33 || granted) return false
        return RestrictedSettingsRules.stepNeeded(Build.VERSION.SDK_INT, granted, opMode(context), attempted(context, grant))
    }

    /** Called when droidtop opens a grant screen Android may lock. */
    fun noteAttempt(context: Context, grant: RestrictedGrant) {
        prefs(context).edit().putBoolean(grant.key, true).apply()
    }

    fun openAppInfo(context: Context) {
        SettingsLaunch.start(
            context,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun attempted(context: Context, grant: RestrictedGrant) = prefs(context).getBoolean(grant.key, false)

    private fun opMode(context: Context): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return null
        return runCatching { ops.unsafeCheckOpNoThrow(OP, context.applicationInfo.uid, context.packageName) }.getOrNull()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
