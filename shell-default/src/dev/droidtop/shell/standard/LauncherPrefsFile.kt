package dev.droidtop.shell.standard

import android.content.Context
import android.util.Log
import com.android.launcher3.LauncherFiles
import dev.droidtop.runtime.CachedPref

/**
 * Standard's own preferences file (the launcher's, backed up with it) as the place a [CachedPref] keeps its string.
 * A value that does not parse is logged and kept beside it as `<key>_unreadable`, as it was, so it can be recovered.
 */
class LauncherPrefsFile(context: Context) : CachedPref.Backing {
    private val prefs = context.applicationContext.getSharedPreferences(LauncherFiles.SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)

    override fun read(key: String): String? = prefs.getString(key, null)

    override fun write(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun unreadable(key: String, raw: String, error: Throwable) {
        Log.w(TAG, "$key does not parse; kept as ${key}_unreadable", error)
        prefs.edit().putString("${key}_unreadable", raw).apply()
    }

    private companion object {
        const val TAG = "droidtop.prefs"
    }
}
