package dev.droidtop.library

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * JoiPlay (`cyou.joiplay.joiplay`) — an interpreter for Ren'Py/RPG Maker
 * games on Android, same category of integration as RetroArch or
 * DuckStation: droidtop detects games and hands them off, it doesn't
 * interpret them itself. Launches by firing `ACTION_VIEW` at the game's
 * executable, the same as a file manager's "Open With" — JoiPlay doesn't
 * expose an add-game/launch API, so that's the real integration point.
 * Not yet run against a real installed JoiPlay to confirm resolution.
 */
object JoiPlay {
    const val PACKAGE_NAME = "cyou.joiplay.joiplay"

    /** Must match the `<provider android:authorities>` in this module's AndroidManifest.xml. */
    const val FILE_PROVIDER_AUTHORITY = "dev.droidtop.app.joiplay.fileprovider"

    /** Needs `<queries><package android:name="cyou.joiplay.joiplay" /></queries>` in the caller's manifest on API 30+ (package visibility). */
    fun isInstalled(context: Context): Boolean =
        try {
            context.packageManager.getPackageInfo(PACKAGE_NAME, 0)
            true
        } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
            false
        }

    fun launchViaJoiPlay(context: Context, executableFile: File, authority: String) {
        check(isInstalled(context)) { "JoiPlay ($PACKAGE_NAME) isn't installed" }
        val uri = FileProvider.getUriForFile(context, authority, executableFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, null) // extension-based resolution, same as a real "Open With"
            setPackage(PACKAGE_NAME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
