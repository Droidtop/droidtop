package dev.droidtop.app.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.droidtop.app.OnboardingActivity
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogPrefs
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.ChoiceItem
import dev.droidtop.library.settings.ChoiceOption
import dev.droidtop.library.settings.DocumentPickItem
import dev.droidtop.library.settings.Mode
import dev.droidtop.library.settings.Modes
import dev.droidtop.library.settings.ToggleItem
import dev.droidtop.shell.standard.HomeRolePrefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * Global settings and Desktop mode's settings, as catalogs (docs/SPEC.md
 * settings architecture), so the Gaming shell draws them in its own rows
 * with focus and a hint row, and the Standard settings surface draws the
 * same data as preferences. They were launcher3 preference XML, which the
 * shell could only open as a stock Android list that the pad could not
 * drive (UI pass 2026-09-24, H4).
 */
object DroidtopWideSettings {

    const val SCREEN_GLOBAL = "global_settings"
    const val SCREEN_DESKTOP = "desktop_settings"

    private const val KEY_TASKBAR_TOP = "pref_desktop_taskbar_top"

    fun globalScreen() = CatalogScreen(
        id = SCREEN_GLOBAL,
        title = "Global settings",
        subtitle = "Home role, modes, and droidtop's settings as a whole",
        groups = { context ->
            listOf(
                CatalogGroup(
                    id = "global_home",
                    title = null,
                    items = listOf(
                        // STANDARD <-> NONE only: a person on ALTERNATIVE
                        // (another launcher droidtop forwards to) chose that
                        // in onboarding, and leaves it there too.
                        ToggleItem(
                            id = "pref_global_home_role",
                            title = "Use droidtop as home screen",
                            subtitle = "Make the Standard shell your device's Android home screen",
                            current = HomeRolePrefs.activeHomeImplementation(context) ==
                                HomeRolePrefs.HomeImplementation.STANDARD,
                            onToggle = { ctx, on ->
                                HomeRolePrefs.setActiveHomeImplementation(
                                    ctx,
                                    if (on) HomeRolePrefs.HomeImplementation.STANDARD else HomeRolePrefs.HomeImplementation.NONE,
                                )
                            },
                        ),
                    ),
                ),
                CatalogGroup(
                    id = "global_modes",
                    title = "Modes",
                    items = listOf(
                        defaultModeItem(context),
                        modeToggle(context, Mode.DESKTOP),
                        modeToggle(context, Mode.GAMING),
                    ),
                ),
                CatalogGroup(
                    id = "global_data",
                    title = "Data",
                    items = listOf(
                        ActionItem(
                            id = "pref_global_rerun_onboarding",
                            title = "Rerun onboarding",
                            subtitle = "Go through first-run setup again from the start; nothing is reset until you change it there",
                            run = { ctx ->
                                ctx.startActivity(
                                    Intent(ctx, OnboardingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            },
                        ),
                        DocumentPickItem(
                            id = "pref_global_backup",
                            title = "Back up settings",
                            subtitle = "Save droidtop's settings to a file (not games, ROMs or downloaded themes)",
                            mimeType = "application/json",
                            createName = "droidtop-backup.json",
                            onPicked = ::writeBackup,
                        ),
                        DocumentPickItem(
                            id = "pref_global_restore",
                            title = "Restore settings",
                            subtitle = "Load droidtop's settings from a backup file",
                            mimeType = "application/json",
                            onPicked = ::readBackup,
                        ),
                    ),
                ),
            )
        },
    )

    fun desktopScreen() = CatalogScreen(
        id = SCREEN_DESKTOP,
        title = "Desktop mode",
        subtitle = "Settings for the Desktop shell",
        groups = { context ->
            listOf(
                CatalogGroup(
                    id = "desktop",
                    title = null,
                    items = listOf(
                        ToggleItem(
                            id = KEY_TASKBAR_TOP,
                            title = "Taskbar at top",
                            subtitle = "Move the desktop taskbar to the top of the screen instead of the bottom",
                            current = CatalogPrefs.prefs(context).getBoolean(KEY_TASKBAR_TOP, false),
                            onToggle = { ctx, on -> CatalogPrefs.prefs(ctx).edit().putBoolean(KEY_TASKBAR_TOP, on).apply() },
                        ),
                        ActionItem(
                            id = "pref_desktop_root_compositor_setup",
                            title = "Desktop setup",
                            subtitle = "Check what this device can run, and choose the distro and compositor",
                            run = { ctx ->
                                ctx.startActivity(
                                    Intent(ctx, OnboardingActivity::class.java)
                                        .putExtra(OnboardingActivity.EXTRA_START_STEP, "DESKTOP_SETUP")
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            },
                        ),
                        ActionItem(
                            id = "pref_desktop_containers",
                            title = "Containers",
                            subtitle = "Manage Linux containers and distros: create, start, stop, delete",
                            run = { ctx ->
                                ctx.startActivity(
                                    Intent("dev.droidtop.app.action.CONTAINERS")
                                        .setPackage(ctx.packageName)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            },
                        ),
                    ),
                ),
            )
        },
    )

    /** Only enabled modes are offered; a stored default naming a disabled one reads as "last used". */
    private fun defaultModeItem(context: Context): ChoiceItem {
        val options = buildList {
            add(ChoiceOption("", "Whichever was used last"))
            listOf(Mode.DESKTOP, Mode.GAMING)
                .filter { Modes.isEnabledInStorage(context, it) }
                .forEach { add(ChoiceOption(it.id, it.label)) }
        }
        return ChoiceItem(
            id = "pref_global_default_mode",
            title = "Default mode",
            subtitle = "Which mode droidtop starts in when launched fresh",
            options = options,
            current = Modes.defaultMode(context)?.takeIf { id -> options.any { it.value == id } } ?: "",
            onSelect = { ctx, value -> Modes.setDefaultMode(ctx, Mode.byId(value.ifEmpty { null })) },
        )
    }

    private fun modeToggle(context: Context, mode: Mode) = ToggleItem(
        id = if (mode == Mode.DESKTOP) "pref_global_enable_desktop" else "pref_global_enable_gaming",
        title = "Enable ${mode.label} mode",
        subtitle = "Show ${mode.label} as a choice in the shell switcher",
        current = Modes.isEnabledInStorage(context, mode),
        onToggle = { ctx, on -> Modes.setEnabled(ctx, mode, on) },
    )

    /**
     * The one SharedPreferences file every droidtop setting lives in, as
     * JSON. Not a device backup: the scan cache, downloaded themes and
     * folder grants are separate state, and grants need consent again.
     */
    private fun writeBackup(context: Context, uri: Uri): String = runCatching {
        val json = JSONObject()
        for ((key, value) in CatalogPrefs.prefs(context).all) {
            when (value) {
                is Boolean, is Int, is Long, is Float, is String -> json.put(key, value)
                is Set<*> -> json.put(key, JSONArray(value.toList()))
                else -> {}
            }
        }
        context.contentResolver.openOutputStream(uri)?.use { it.write(json.toString(2).toByteArray()) }
            ?: error("the file could not be opened")
        "Backup saved"
    }.getOrElse { "Backup failed: ${it.message}" }

    private fun readBackup(context: Context, uri: Uri): String = runCatching {
        val text = context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
            ?: error("the file could not be opened")
        val json = JSONObject(text)
        val editor = CatalogPrefs.prefs(context).edit()
        for (key in json.keys()) {
            when (val value = json.get(key)) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Double -> editor.putFloat(key, value.toFloat())
                is String -> editor.putString(key, value)
                is JSONArray -> editor.putStringSet(key, (0 until value.length()).map { value.getString(it) }.toSet())
            }
        }
        editor.apply()
        "Restored. Restart droidtop for every setting to apply."
    }.getOrElse { "Restore failed: ${it.message}" }
}
