package dev.droidtop.app.settings

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState

/**
 * Registers :app's settings-catalog screens into the process-wide
 * [dev.droidtop.library.settings.SettingsScreenRegistry] at process
 * start. A manifest-declared ContentProvider's onCreate runs before ANY
 * Activity in the process -- including :shell-default's SettingsActivity,
 * which renders catalogs but cannot depend on :app -- so every surface
 * can resolve these screens by id without an initialization race and
 * without a dependency edge that can't exist. Provides no actual
 * content; the provider mechanism is only the earliest real, ordered
 * process-start hook Android offers an app module.
 */
class SettingsCatalogInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        AppSettingsCatalogs.ensureRegistered()
        // What each mode shows on a secondary display. Registered here
        // rather than from an Activity because :display's
        // SecondaryDisplayActivity is placed by the PLATFORM, which can
        // happen before any droidtop Activity has run -- a display
        // attached at boot, or reattached while the shell is not
        // foreground. See docs/SPEC.md section 4c.
        registerSecondaryDisplayContent()
        // Warm the platforms-database cache off the main thread: the few
        // synchronous label lookups (GamepadShell group labels, the
        // second-screen companion) read PlatformsDatabase.builtInsOrEmpty,
        // which serves only what this load put in the cache.
        val appContext = context?.applicationContext
        if (appContext != null) {
            Thread {
                runCatching { dev.droidtop.library.consoles.PlatformsDatabase.builtIns(appContext) }
            }.start()
        }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

/**
 * Handheld draws either the companion surface or the input surface, per
 * the user's role choice for that mode; Standard hands off to Launcher3's
 * own secondary-display UI; Desktop draws the input surface, which is what
 * docs/SPEC.md section 4 says its lower screen is for.
 *
 * The role is read at composition rather than captured once: the same
 * registration is used by the idle SECONDARY_HOME activity, which is
 * re-rendered on resume, so a role changed in settings takes effect the
 * next time that screen comes up rather than needing a restart.
 */
private fun registerSecondaryDisplayContent() {
    dev.droidtop.display.SecondaryDisplayContent.register(
        dev.droidtop.display.SecondaryDisplayContent.Mode.HANDHELD,
    ) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val role = dev.droidtop.app.SecondScreenInputPrefs.role(
            context,
            dev.droidtop.display.SecondaryDisplayContent.Mode.HANDHELD,
        )
        if (role == dev.droidtop.app.SecondScreenInputPrefs.Role.INPUT) {
            dev.droidtop.app.SecondScreenInputSurface(
                dev.droidtop.display.SecondaryDisplayContent.Mode.HANDHELD,
            )
        } else {
            val entry by dev.droidtop.app.CompanionState.focusedEntry.collectAsState()
            dev.droidtop.app.ui.DroidtopTheme(darkTheme = true) {
                dev.droidtop.app.CompanionSurfaceHost(entry)
            }
        }
    }
    dev.droidtop.display.SecondaryDisplayContent.register(
        dev.droidtop.display.SecondaryDisplayContent.Mode.DESKTOP,
    ) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val role = dev.droidtop.app.SecondScreenInputPrefs.role(
            context,
            dev.droidtop.display.SecondaryDisplayContent.Mode.DESKTOP,
        )
        if (role == dev.droidtop.app.SecondScreenInputPrefs.Role.INPUT) {
            dev.droidtop.app.SecondScreenInputSurface(
                dev.droidtop.display.SecondaryDisplayContent.Mode.DESKTOP,
            )
        } else {
            val entry by dev.droidtop.app.CompanionState.focusedEntry.collectAsState()
            dev.droidtop.app.ui.DroidtopTheme(darkTheme = true) {
                dev.droidtop.app.CompanionSurfaceHost(entry)
            }
        }
    }
    dev.droidtop.display.SecondaryDisplayContent.registerHandoff(
        dev.droidtop.display.SecondaryDisplayContent.Mode.STANDARD,
    ) { context ->
        runCatching {
            context.startActivity(
                android.content.Intent().setClassName(
                    context.packageName,
                    "com.android.launcher3.secondarydisplay.SecondaryDisplayLauncher",
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
    }
}
