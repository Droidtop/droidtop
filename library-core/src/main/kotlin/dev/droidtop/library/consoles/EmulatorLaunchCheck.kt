package dev.droidtop.library.consoles

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.app.AppOpsManager
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME
import dev.droidtop.runtime.prefs.PrefsFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

/**
 * Whether a launch should stop and ask for the emulator's file access first
 * (Droidtop/tracker#270): the preset has a plain-path launch ([hasPathTemplate]),
 * the emulator cannot read plain paths ([emulatorReadsPaths] false) so the
 * content URI would run, and the person has not chosen "Launch anyway" for it.
 * Pure so the decision is testable without a device.
 */
fun launchNeedsFileAccess(hasPathTemplate: Boolean, emulatorReadsPaths: Boolean, launchAnyway: Boolean): Boolean =
    hasPathTemplate && !emulatorReadsPaths && !launchAnyway

/** The emulators the person chose "Launch anyway" for when they lack file access. */
object LaunchAnywayPrefs {
    private val prefs = { context: Context -> PrefsFile(context, LAUNCHER_PREFS_FILE_NAME).keyedStrings("droidtop_launch_anyway_") }

    fun get(context: Context, packageName: String): Boolean = prefs(context).get(packageName) != null

    fun set(context: Context, packageName: String) = prefs(context).set(packageName, "1")
}

/**
 * True when launching [player] should first ask for file access. Reads AppOps
 * and PackageManager, so not for the main thread.
 */
fun needsFileAccessPrompt(context: Context, player: Player.AmStart): Boolean =
    player.storagePathTemplate != null &&
        launchNeedsFileAccess(
            hasPathTemplate = true,
            emulatorReadsPaths = emulatorReadsStoragePaths(context, player.packageName),
            launchAnyway = LaunchAnywayPrefs.get(context, player.packageName),
        )

/** The one-line reason shared by the launch dialog and the Emulators launch test. */
fun fileAccessMessage(emulatorName: String): String = "$emulatorName can't read your games."

/**
 * Android's own All files access screen for [packageName] (API 30+), else the app's details page.
 * The caller falls back to [Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION] when this cannot be
 * resolved. droidtop never grants the permission itself.
 */
fun allFilesAccessIntent(packageName: String): Intent {
    val uri = Uri.parse("package:$packageName")
    val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
    } else {
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS
    }
    return Intent(action, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/**
 * A launch that stopped to ask for the emulator's file access. [launchAnyway] remembers the choice
 * for the emulator and runs the launch again with the content URI.
 */
class EmulatorNeedsFileAccess(
    val emulatorName: String,
    val packageName: String,
    val launchAnyway: suspend () -> Unit,
) : IllegalStateException(fileAccessMessage(emulatorName))

/**
 * The prompt the shell's failure dialog shows beside the plain message: set when a launch stops for
 * file access, read by `LaunchFailureDialog` to offer "Give access" and "Launch anyway".
 */
object LaunchFileAccessPrompt {
    private val pendingFlow = MutableStateFlow<EmulatorNeedsFileAccess?>(null)
    val pending: StateFlow<EmulatorNeedsFileAccess?> = pendingFlow

    fun show(prompt: EmulatorNeedsFileAccess) { pendingFlow.value = prompt }

    fun clear() { pendingFlow.value = null }
}

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
    if (checkGameFile && needsFileAccessPrompt(context, player)) {
        return PreparedLaunch.Blocked(
            "${fileAccessMessage(player.name)} Give it All files access in Android's settings, then test again.",
        )
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
