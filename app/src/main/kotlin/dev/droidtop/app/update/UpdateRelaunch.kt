package dev.droidtop.app.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Brings droidtop back after it replaced itself (docs/SPEC.md 10b, "Coming back after an update";
 * Droidtop/tracker#202).
 *
 * Installing the new APK kills the running process and removes its tasks, so on the console (2026-10-14, build
 * 1773 to 1790) neither screen showed droidtop afterwards until Home was pressed. Home is exactly what is
 * replayed here: the same ACTION_MAIN + CATEGORY_HOME intent, addressed to droidtop's own package, which resolves
 * to the enabled home implementation ([dev.droidtop.shell.standard.HomeTrampolineActivity] or the Alternative
 * forwarder). It decides the person's mode and, as on a real Home press, puts droidtop on the second screen too.
 *
 * Only an update the person (or the UPDATE_NOW trigger) asked for relaunches: the pass [arm]s a short-lived
 * record before it installs, and the new process's MY_PACKAGE_REPLACED receiver consumes it. An update that
 * arrives without it (the store, adb, a restored backup) leaves the screen alone, so a game in front is never
 * taken over.
 */
internal object UpdateRelaunch {
    private const val PREFS = "app_update_relaunch"
    private const val KEY_ARMED_AT = "armed_at_ms"

    /** How long an armed record counts: a download plus the person's time at Android's confirmation. */
    const val VALID_MS = 3L * 60 * 60 * 1000

    /** Whether a record armed at [armedAtMs] (0 for none) still calls for a relaunch at [nowMs]. */
    fun shouldRelaunch(armedAtMs: Long, nowMs: Long): Boolean = armedAtMs > 0 && nowMs - armedAtMs in 0..VALID_MS

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun arm(context: Context) {
        prefs(context).edit().putLong(KEY_ARMED_AT, System.currentTimeMillis()).commit()
    }

    fun disarm(context: Context) {
        prefs(context).edit().clear().commit()
    }

    /** True once if an update armed a relaunch that is still valid; clears the record either way. */
    fun consume(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        val armedAt = prefs(context).getLong(KEY_ARMED_AT, 0L)
        disarm(context)
        return shouldRelaunch(armedAt, nowMs)
    }

    /** The Home intent, addressed to droidtop only. */
    fun homeIntent(context: Context): Intent =
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
}

/** The new process's first word after an update: relaunches droidtop when [UpdateRelaunch] was armed. */
class UpdateRelaunchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val application = context.applicationContext
        if (!UpdateRelaunch.consume(application)) {
            Log.i(UpdateNow.TAG, "package replaced without an armed update: not taking the screen")
            return
        }
        Log.i(UpdateNow.TAG, "droidtop was updated: opening Home again, as a Home press would")
        runCatching { application.startActivity(UpdateRelaunch.homeIntent(application)) }
            .onFailure { Log.w(UpdateNow.TAG, "could not open Home after the update", it) }
    }
}
