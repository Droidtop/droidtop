package dev.droidtop.library.consoles

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import dev.droidtop.runtime.tasks.RiskyActions
import dev.droidtop.runtime.tasks.RiskyClass
import dev.droidtop.runtime.tasks.RiskyPrompts
import dev.droidtop.runtime.tasks.TaskManager

/**
 * Giving an emulator the access it needs from the Emulator setup helper (docs/SPEC.md "Risky actions", Droidtop/tracker#248):
 * All files access through the privileged helper's appops, and the runtime permissions it asks for through `pm grant`.
 * Both are risky actions ([RiskyClass.GRANT_ACCESS]): [TaskManager.shell] refuses them unless the person has turned the
 * class on, and every use is confirmed first on the row that runs it. Blocking: background threads only.
 */
object EmulatorAccess {
    /** A runtime permission an app declares but does not hold: its exact name, and its label in the person's words. */
    data class Missing(val permission: String, val label: String)

    /** The most permission rows one emulator gets: an app can ask for dozens, and the setup screen is not Android's list. */
    const val MAX_ROWS = 6

    /** Permissions that All files access (or nothing on the helper's side) already covers, so no separate row is offered. */
    private val COVERED = setOf(
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.WRITE_EXTERNAL_STORAGE",
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO",
        "android.permission.READ_MEDIA_AUDIO",
    )

    /** Whether the helper could be asked to give [packageName] All files access now: the class is on and a provider serves it. */
    fun canGrantAllFiles(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && RiskyActions.allows(RiskyClass.GRANT_ACCESS) && TaskManager.privileges().appOps

    /** Whether the helper could be asked to grant a runtime permission now. */
    fun canGrantPermission(): Boolean = RiskyActions.allows(RiskyClass.GRANT_ACCESS) && TaskManager.privileges().grantPermission

    /**
     * Sets All files access for [packageName] and reads it back, so the answer is what Android now says and not what
     * the helper claimed. Null when it worked, otherwise a sentence saying what did not.
     */
    fun grantAllFiles(context: Context, packageName: String, app: String): String? {
        if (!canGrantAllFiles()) return RiskyPrompts.turnOnHint(RiskyClass.GRANT_ACCESS)
        val asked = TaskManager.shell.setAppOp(packageName, RiskyPrompts.ALL_FILES_OP, "allow")
        return when {
            emulatorReadsStoragePaths(context, packageName) -> null
            asked -> "The helper said yes, but Android still shows no All files access for $app. Open its All files access screen to check."
            else -> "droidtop could not give $app All files access. Open Android's All files access screen for it instead."
        }
    }

    /** Grants [permission] to [packageName] and reads it back. Null when it worked, otherwise a sentence saying what did not. */
    fun grantPermission(context: Context, packageName: String, permission: String, app: String): String? {
        if (!canGrantPermission()) return RiskyPrompts.turnOnHint(RiskyClass.GRANT_ACCESS)
        val asked = TaskManager.shell.grantPermission(packageName, permission)
        return when {
            context.packageManager.checkPermission(permission, packageName) == PackageManager.PERMISSION_GRANTED -> null
            asked -> "The helper said yes, but Android still shows the permission as not given to $app."
            else -> "droidtop could not give $app that permission. Open the app's permissions in Android's settings instead."
        }
    }

    /**
     * The runtime ("dangerous") permissions [packageName] declares and does not hold, which `pm grant` can give; the
     * storage ones All files access covers are left out. Reads the PackageManager: not for the main thread.
     */
    fun missingRuntimePermissions(context: Context, packageName: String): List<Missing> = runCatching {
        val pm = context.packageManager
        val info: PackageInfo = pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        val requested = info.requestedPermissions ?: return@runCatching emptyList()
        val flags = info.requestedPermissionsFlags ?: IntArray(requested.size)
        requested.indices.mapNotNull { i ->
            val name = requested[i]
            if (name in COVERED || (flags.getOrElse(i) { 0 } and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0) return@mapNotNull null
            val permission = runCatching { pm.getPermissionInfo(name, 0) }.getOrNull() ?: return@mapNotNull null
            if (!isRuntime(permission.protectionLevel)) return@mapNotNull null
            Missing(name, plainLabel(permission, pm))
        }.take(MAX_ROWS)
    }.getOrDefault(emptyList())

    /** A permission needs `pm grant` rather than the system's own say when its base protection level is dangerous. Pure. */
    fun isRuntime(protectionLevel: Int): Boolean =
        (protectionLevel and BASE_PROTECTION_MASK) == PermissionInfo.PROTECTION_DANGEROUS

    // PermissionInfo.PROTECTION_MASK_BASE, which is API 28; droidtop's minimum is 26.
    private const val BASE_PROTECTION_MASK = 0xf

    /** Android's own label for the permission ("Camera", "Record audio"), else its name without the prefix. */
    private fun plainLabel(permission: PermissionInfo, pm: PackageManager): String =
        permission.loadLabel(pm).toString().takeIf { it.isNotBlank() && !it.startsWith("android.permission.") }?.lowercase()
            ?: permission.name.substringAfterLast('.').lowercase().replace('_', ' ')
}
