package dev.droidtop.library.consoles

import android.content.Context

/**
 * Explicit folder-to-system assignment, overriding [resolveSystem]'s
 * name-based matching -- the more robust design Daijishō itself actually
 * uses (its real PlatformEntity has an `itemsSyncTreeUriList`, letting a
 * user assign folders to a platform directly rather than relying on the
 * folder being named exactly right; confirmed via its decompiled sources).
 * Keyed by the folder's absolute path in the same shared prefs file
 * `:shell-default`'s settings and `:app`'s onboarding already use, so a
 * folder keeps its assignment even if its resolved system would otherwise
 * be ambiguous or wrong (e.g. a folder named something ES-DE's data
 * doesn't recognize at all, not just a known alias mismatch).
 */
object SystemOverridePrefs {
    private const val PREFS_NAME = "com.android.launcher3.prefs"
    private const val KEY_PREFIX = "droidtop_system_override_"

    fun get(context: Context, folderPath: String): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_PREFIX + folderPath, null)

    fun set(context: Context, folderPath: String, systemId: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (systemId == null) {
            prefs.edit().remove(KEY_PREFIX + folderPath).apply()
        } else {
            prefs.edit().putString(KEY_PREFIX + folderPath, systemId).apply()
        }
    }

    /**
     * [resolveSystem] by folder name, but an explicit override for
     * [folderPath] wins first. [systemsById] is a live snapshot from
     * [ConsoleSystemsRepository.allSystems] -- see [resolveSystem]'s own
     * doc comment for why this takes it as a parameter instead of reading
     * a compile-time constant.
     */
    fun resolveForFolder(context: Context, folderPath: String, folderName: String, systemsById: Map<String, ConsoleSystemDef>): ConsoleSystemDef? {
        val overrideId = get(context, folderPath)
        if (overrideId != null) return systemsById[overrideId]
        return resolveSystem(folderName, systemsById)
    }
}
