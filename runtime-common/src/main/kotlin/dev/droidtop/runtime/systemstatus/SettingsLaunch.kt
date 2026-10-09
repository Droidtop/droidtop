package dev.droidtop.runtime.systemstatus

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * The one way droidtop starts an Android system settings screen (grants, panels, App info, Home choice). It
 * only adds a debug line, tag [TAG], naming the code that asked and the screen asked for, so a settings screen
 * that opens when nobody pressed anything can be traced to its caller from logcat (Droidtop/tracker#368).
 * Behaviour is exactly [Context.startActivity].
 */
object SettingsLaunch {
    const val TAG = "droidtop.settings"

    fun start(context: Context, intent: Intent) {
        Log.d(TAG, "start ${target(intent)} from ${caller()}")
        context.startActivity(intent)
    }

    /** The screen an intent asks for: its action (or component) and data. */
    internal fun target(intent: Intent): String =
        listOfNotNull(intent.action, intent.component?.flattenToShortString(), intent.dataString).joinToString(" ")

    /** The first stack frame outside this object. */
    private fun caller(): String =
        Throwable().stackTrace.firstOrNull { !it.className.startsWith(SettingsLaunch::class.java.name) }
            ?.let { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" } ?: "unknown"
}
