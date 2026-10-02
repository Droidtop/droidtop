package dev.droidtop.library.consoles

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.app.AppOpsManager
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

/**
 * Plain-words reasons a launch did not happen, for the launch screen's
 * error line and the emulator test action. A raw exception on screen tells
 * a person nothing they can act on; every sentence here names the
 * emulator and says what to do next.
 */
fun explainLaunchFailure(failure: Throwable, emulatorName: String): String {
    val detail = failure.message?.trim()?.takeIf { it.isNotEmpty() }
    return when (failure) {
        is ActivityNotFoundException ->
            "$emulatorName is installed, but it has no screen matching what droidtop asks it to open. " +
                "Its version may be older or newer than this launch setting expects: update it, or choose another emulator."
        is SecurityException ->
            "Android blocked the launch: $emulatorName does not let other apps open that screen. " +
                "Open $emulatorName once yourself, then try again, or choose another emulator."
        is AmStartCommandToIntentConverter.UnsupportedArgumentException ->
            "The launch setting for $emulatorName uses an option droidtop does not support (${detail ?: "unknown"}). " +
                "Edit the custom player or choose another emulator."
        is IllegalArgumentException ->
            "The launch setting for $emulatorName is not valid: ${detail ?: "no detail"}. " +
                "Edit the custom player or choose another emulator."
        else ->
            "$emulatorName could not be started: ${detail ?: failure.javaClass.simpleName}."
    }
}

/**
 * The template a launch is built from: the preset's plain-path variant when it
 * has one and the emulator can read plain paths, else the one it always had.
 * Pure so the choice is testable without a device.
 */
fun launchTemplateFor(player: Player.AmStart, emulatorReadsPaths: Boolean): String =
    if (emulatorReadsPaths) player.storagePathTemplate ?: player.argumentsTemplate else player.argumentsTemplate

/**
 * Whether [packageName] holds all-files access (API 30+) or the legacy storage
 * permission, i.e. can open a plain `/storage/...` path itself. Reads AppOps and
 * PackageManager, so not for the main thread; any failure answers false, which
 * keeps the content URI launch.
 */
fun emulatorReadsStoragePaths(context: Context, packageName: String): Boolean = runCatching {
    val pm = context.packageManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val uid = pm.getApplicationInfo(packageName, 0).uid
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        ops.unsafeCheckOpNoThrow("android:manage_external_storage", uid, packageName) == AppOpsManager.MODE_ALLOWED
    } else {
        pm.checkPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE, packageName) == PackageManager.PERMISSION_GRANTED
    }
}.getOrDefault(false)

/** The outcome of getting a launch ready: an Intent to send, or why none can be built. */
sealed interface PreparedLaunch {
    class Ready(val intent: Intent) : PreparedLaunch
    class Blocked(val reason: String) : PreparedLaunch
}

/**
 * Builds the launch Intent and checks that the emulator can take it,
 * short of starting anything. The launcher and the emulator test action
 * both come through here, so a test that passes is the launch that runs.
 *
 * [checkGameFile] adds the game file's own checks (the test action's
 * job: it names the file the person chose); a real launch skips them and
 * lets the emulator report a file it cannot open itself.
 *
 * The component and export checks read PackageManager, so this is for a
 * background thread.
 */
fun prepareLaunch(
    context: Context,
    system: ConsoleSystemDef,
    player: Player.AmStart,
    romFile: File,
    checkGameFile: Boolean = false,
): PreparedLaunch {
    if (checkGameFile) {
        if (!romFile.exists()) {
            return PreparedLaunch.Blocked(
                "The game file is not there any more. It may have been moved, or the storage card removed: rescan your library.",
            )
        }
        if (!romFile.canRead()) {
            return PreparedLaunch.Blocked("droidtop cannot read the game file. Check that it still has access to your games folder.")
        }
    }
    if (!isPackageInstalled(context, player.packageName)) {
        return PreparedLaunch.Blocked("${player.name} is not installed. Install it, or choose another emulator.")
    }
    val intent = try {
        buildLaunchIntent(context, system, player, romFile)
    } catch (e: IllegalArgumentException) {
        return PreparedLaunch.Blocked(explainLaunchFailure(e, player.name))
    }
    val pm = context.packageManager
    val component = intent.component
    if (component != null) {
        val info = try {
            pm.getActivityInfo(component, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            return PreparedLaunch.Blocked(
                "${player.name} is installed, but it has no screen named ${component.shortClassName}. " +
                    "Its version may not match this launch setting: update it, or choose another emulator.",
            )
        }
        if (!info.exported) {
            return PreparedLaunch.Blocked(
                "${player.name} keeps that screen private, so other apps cannot open it. Choose another emulator for this system.",
            )
        }
    } else if (pm.resolveActivity(intent, 0) == null) {
        return PreparedLaunch.Blocked("No installed app can open what ${player.name}'s launch setting asks for. Choose another emulator.")
    }
    return PreparedLaunch.Ready(intent)
}
