package dev.droidtop.app

import android.content.Context

/**
 * Which shell is active is a user setting, not a build-time choice — all
 * three shells (:shell-default, :shell-desktop, :shell-gamepad) read the
 * exact same dev.droidtop.library.Library, so switching is just "render a
 * different Composable," never a rearchitecture or a different app build.
 */
enum class ShellKind {
    STANDARD,
    DESKTOP,
    HANDHELD,
}

object ShellPreference {
    private const val PREFS_NAME = "shell_preference"
    private const val KEY_SHELL = "active_shell"

    fun get(context: Context): ShellKind {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SHELL, null)
            ?: return ShellKind.STANDARD
        return runCatching { ShellKind.valueOf(stored) }.getOrDefault(ShellKind.STANDARD)
    }

    fun set(context: Context, shell: ShellKind) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SHELL, shell.name)
            .apply()
    }
}
