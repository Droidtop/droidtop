package dev.droidtop.runtime.tasks

import android.content.Context

/** Packages the user marked so Clear all never closes them: a string set in droidtop's own preferences. */
object ProtectedApps {
    private const val PREFS = "task_manager"
    private const val KEY = "protected_packages"

    fun get(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, emptySet()).orEmpty().toSet()

    fun set(context: Context, packageName: String, protect: Boolean) {
        val next = get(context).let { if (protect) it + packageName else it - packageName }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY, next).apply()
    }
}
