package dev.droidtop.library.consoles

import android.content.Context
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME
import dev.droidtop.runtime.prefs.PrefsFile

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
    private val prefs = { context: Context -> PrefsFile(context, LAUNCHER_PREFS_FILE_NAME).keyedStrings("droidtop_system_override_") }

    /**
     * The value for a folder the person picked in order to choose its
     * system, before they have chosen one. It resolves to no system (not
     * to a guess from the folder's name) and keeps the folder listed on
     * the Console systems page until a system is chosen.
     */
    const val NOT_SET = "-"

    fun get(context: Context, folderPath: String): String? = prefs(context).get(folderPath)

    fun set(context: Context, folderPath: String, systemId: String?) = prefs(context).set(folderPath, systemId)

    /** Every folder with an explicit value, by absolute path. */
    fun assigned(context: Context): Map<String, String> = prefs(context).entries()

    /**
     * [resolveSystem] by folder name, but an explicit override for
     * [folderPath] wins first. [systemsById] is a live snapshot from
     * [ConsoleSystemsRepository.allSystems] -- see [resolveSystem]'s own
     * doc comment for why this takes it as a parameter instead of reading
     * a compile-time constant.
     */
    fun resolveForFolder(context: Context, folderPath: String, folderName: String, systemsById: Map<String, ConsoleSystemDef>): ConsoleSystemDef? {
        val overrideId = get(context, folderPath)
        if (overrideId != null) return systemsById[overrideId]?.takeIf { it.canResolveFromFolder() }
        return resolveSystem(folderName, systemsById)
    }

    /**
     * The system [folder] is: an explicit override first, else
     * [resolveSystemFolder] -- the folder's name AND the system's files in it,
     * so a collection folder that only shares a system's name is not one.
     */
    fun resolveForFolder(context: Context, folder: java.io.File, systemsById: Map<String, ConsoleSystemDef>): ConsoleSystemDef? {
        val overrideId = get(context, folder.absolutePath)
        if (overrideId != null) return systemsById[overrideId]?.takeIf { it.canResolveFromFolder() }
        return resolveSystemFolder(folder, systemsById)
    }
}
