package dev.droidtop.app.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import dev.droidtop.app.AccessibilityPrefs
import dev.droidtop.app.OnboardingActivity
import dev.droidtop.app.ScreenOrientationPrefs
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogPrefs
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.ChoiceItem
import dev.droidtop.library.settings.ChoiceOption
import dev.droidtop.library.settings.DocumentPickItem
import dev.droidtop.library.settings.Mode
import dev.droidtop.library.settings.Modes
import dev.droidtop.library.credentials.CredentialStore
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

    private const val KEY_BACKUP_CREDENTIALS = "droidtop_backup_credentials_opt_in"
    private const val BACKUP_CREDENTIALS_FIELD = "_credentials_b64"
    private val CREDENTIAL_KEYS = setOf(
        "droidtop_screenscraper_devid",
        "droidtop_screenscraper_devpassword",
        "droidtop_screenscraper_ssid",
        "droidtop_screenscraper_sspassword",
        "droidtop_steamgriddb_apikey",
        "droidtop_thegamesdb_apikey",
        "droidtop_igdb_client_id",
        "droidtop_igdb_client_secret",
    )

    private fun isCredentialKey(key: String): Boolean {
        val normalized = key.lowercase()
        return key in CREDENTIAL_KEYS || listOf("password", "passwd", "secret", "apikey", "api_key", "token", "credential")
            .any(normalized::contains)
    }

    const val SCREEN_GLOBAL = "global_settings"
    const val SCREEN_DESKTOP = "desktop_settings"
    const val SCREEN_STANDARD = "standard_settings"

    private const val KEY_TASKBAR_TOP = "pref_desktop_taskbar_top"

    fun globalScreen() = CatalogScreen(
        id = SCREEN_GLOBAL,
        title = "Global settings",
        subtitle = "Home screen and modes",
        // The Gaming settings page draws these groups as sections of its own categories (each group
        // names one) instead of a level of its own; every other surface still opens this screen.
        merged = true,
        groups = { context ->
            listOf(
                CatalogGroup(
                    id = "global_home",
                    title = "Home screen",
                    category = "Home & modes",
                    items = listOf(
                        // STANDARD <-> NONE only: a person on ALTERNATIVE
                        // (another launcher droidtop forwards to) chose that
                        // in onboarding, and leaves it there too.
                        // On means droidtop's launcher IS what Home opens, as
                        // Android says, not only that it is enabled: on the
                        // Android 14 rig this row read "On" while Pixel
                        // Launcher was Home (dq-onboard-01). Turning it on
                        // hands over to Android's own Default home app
                        // screen, the only place that choice is made.
                        ToggleItem(
                            id = "pref_global_home_role",
                            title = "Use droidtop as home screen",
                            subtitle = homeRoleSubtitle(context),
                            current = HomeRolePrefs.activeHomeImplementation(context) ==
                                HomeRolePrefs.HomeImplementation.STANDARD && HomeRolePrefs.isDroidtopHome(context),
                            onToggle = { ctx, on ->
                                HomeRolePrefs.setActiveHomeImplementation(
                                    ctx,
                                    if (on) HomeRolePrefs.HomeImplementation.STANDARD else HomeRolePrefs.HomeImplementation.NONE,
                                )
                                if (on && !HomeRolePrefs.isDroidtopHome(ctx)) {
                                    ctx.startActivity(HomeRolePrefs.homeSettingsIntent())
                                }
                            },
                        ),
                    ) + listOfNotNull(usersItem(context)),
                ),
                CatalogGroup(
                    id = "global_modes",
                    title = "Modes",
                    category = "Home & modes",
                    items = listOf(
                        defaultModeItem(context),
                        modeToggle(context, Mode.DESKTOP),
                        modeToggle(context, Mode.GAMING),
                        dev.droidtop.library.settings.NestedScreenItem(
                            id = "pref_standard_settings", title = "Standard mode settings",
                            subtitle = "Launcher and screen settings", registryId = SCREEN_STANDARD,
                            icon = dev.droidtop.library.settings.CatalogIcon.STANDARD,
                        ),
                    ),
                ),
                // The one Updates screen, reachable from every mode's settings. Gaming also has it as a
                // left-menu place (and the Quick Menu opens that place); all are links to this screen.
                CatalogGroup(
                    id = "global_updates",
                    title = null,
                    category = "System",
                    items = listOf(
                        dev.droidtop.library.settings.NestedScreenItem(
                            id = "pref_global_updates", title = "Updates",
                            subtitle = "droidtop, plugins and games with a newer version",
                            registryId = AppSettingsCatalogs.SCREEN_UPDATES,
                            icon = dev.droidtop.library.settings.CatalogIcon.SYSTEM_UPDATES,
                        ),
                    ),
                ),
                CatalogGroup(
                    id = "global_accessibility",
                    title = "Accessibility",
                    category = "Appearance",
                    items = listOf(
                        ChoiceItem(
                            id = AccessibilityPrefs.KEY_COLOR_VISION,
                            title = "Colour vision",
                            subtitle = "Recolours droidtop's own screens; games and other apps are not changed",
                            options = listOf(
                                ChoiceOption(AccessibilityPrefs.VISION_NONE, "Off"),
                                ChoiceOption(AccessibilityPrefs.VISION_PROTAN, "Protanopia (red-weak)"),
                                ChoiceOption(AccessibilityPrefs.VISION_DEUTAN, "Deuteranopia (green-weak)"),
                                ChoiceOption(AccessibilityPrefs.VISION_TRITAN, "Tritanopia (blue-weak)"),
                                ChoiceOption(AccessibilityPrefs.VISION_GREY, "Greyscale"),
                            ),
                            current = AccessibilityPrefs.colorVision(context),
                            onSelect = { ctx, value ->
                                CatalogPrefs.prefs(ctx).edit().putString(AccessibilityPrefs.KEY_COLOR_VISION, value).apply()
                            },
                        ),
                        ChoiceItem(
                            id = AccessibilityPrefs.KEY_TEXT_SCALE,
                            title = "Text size",
                            subtitle = "Scales droidtop's own text on top of Android's font size",
                            options = listOf(
                                ChoiceOption("1.0", "Normal"),
                                ChoiceOption("1.15", "Large"),
                                ChoiceOption("1.3", "Larger"),
                                ChoiceOption("1.5", "Largest"),
                            ),
                            current = AccessibilityPrefs.textScale(context).let { cur ->
                                listOf("1.0", "1.15", "1.3", "1.5").minByOrNull { kotlin.math.abs(it.toFloat() - cur) }
                            },
                            onSelect = { ctx, value ->
                                CatalogPrefs.prefs(ctx).edit().putString(AccessibilityPrefs.KEY_TEXT_SCALE, value).apply()
                            },
                        ),
                    ),
                ),
                CatalogGroup(
                    id = "global_data",
                    title = "Data",
                    category = "System",
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
                        AsyncActionItem(
                            id = "pref_global_share_diagnostics",
                            title = "Share diagnostics",
                            subtitle = "Zip the logs, settings without credentials, build and theme names; " +
                                "nothing is sent until you choose where to send it",
                            run = { ctx, onStatus ->
                                onStatus("Packing diagnostics...")
                                shareDiagnostics(ctx)
                            },
                        ),
                        ToggleItem(
                            id = KEY_BACKUP_CREDENTIALS,
                            title = "Back up credentials",
                            subtitle = "Include passwords and API credentials in an encoded section of settings backups",
                            current = CatalogPrefs.prefs(context).getBoolean(KEY_BACKUP_CREDENTIALS, false),
                            onToggle = { ctx, on -> CatalogPrefs.prefs(ctx).edit().putBoolean(KEY_BACKUP_CREDENTIALS, on).apply() },
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
                    ) + listOfNotNull(debugCrashItem(context)),
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
                // The same first row Gaming's settings lead with: from
                // Desktop, Global settings (and the Modes in it) was only
                // an unlabelled action-bar icon, and with Gaming turned off
                // the rig found no way back to it (dq-coordinator-23, F5).
                CatalogGroup(
                    id = dev.droidtop.library.settings.GamingSettingsCatalog.GROUP_GLOBAL,
                    title = null,
                    items = listOf(
                        // The Global settings page itself, titled as such: shown
                        // in place under this page it kept the title "Desktop
                        // mode" (rig, dq-onboard-01).
                        ActionItem(
                            id = "pref_desktop_global_settings",
                            title = "Global settings",
                            subtitle = "Modes, your home screen and setup",
                            run = { ctx -> dev.droidtop.shell.standard.BackButtonMenu.openGlobalSettings(ctx) },
                        ),
                    ),
                ),
                CatalogGroup(
                    id = "desktop",
                    title = null,
                    items = listOf(
                        orientationChoice(context, Mode.DESKTOP),
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
                            run = { ctx -> ctx.startActivity(dev.droidtop.app.DesktopSetupPrefs.setupIntent(ctx)) },
                        ),
                        // The container manager is a catalog screen of its own
                        // (docs/SPEC.md 3d), opened in place by whichever
                        // surface is showing these settings.
                        dev.droidtop.library.settings.NestedScreenItem(
                            id = "pref_desktop_containers",
                            title = "Containers",
                            subtitle = "Manage Linux containers and distros: create, start, stop, delete",
                            registryId = ContainersCatalog.SCREEN_ID,
                            icon = dev.droidtop.library.settings.CatalogIcon.CONTAINERS,
                        ),
                    ),
                ),
            )
        },
    )

    fun standardScreen() = CatalogScreen(
        id = SCREEN_STANDARD,
        title = "Standard mode",
        subtitle = "Settings for the launcher",
        groups = { context -> listOf(CatalogGroup(
            id = "standard", title = null,
            items = listOf(orientationChoice(context, Mode.LAUNCHER)),
        )) },
        // One row: drawn as a section of Global settings, not a level of its own (docs/SPEC.md
        // "Settings layout").
        merged = true,
    )

    private fun orientationChoice(context: Context, mode: Mode): ChoiceItem {
        val values = ScreenOrientationPrefs.options(mode)
        val key = ScreenOrientationPrefs.KEY_PREFIX + mode.id
        return ChoiceItem(
            id = key,
            title = "Screen orientation",
            options = values.map { dev.droidtop.library.settings.ChoiceOption(it.first, it.second) },
            current = ScreenOrientationPrefs.choice(context, mode),
            onSelect = { ctx, value -> CatalogPrefs.prefs(ctx).edit().putString(key, value).apply() },
        )
    }

    /**
     * Only modes that are on are offered; a stored default naming one that
     * is off reads as "last used". Android is one of them whenever droidtop
     * holds the home screen: onboarding's "Opens into Android" is stored
     * here, and without the option this row read "Whichever was used
     * last" right after that answer (rig, dq-coordinator-23, F6).
     */
    private fun defaultModeItem(context: Context): ChoiceItem {
        val options = buildList {
            add(ChoiceOption("", "Whichever was used last"))
            if (HomeRolePrefs.activeHomeImplementation(context) != HomeRolePrefs.HomeImplementation.NONE) {
                add(ChoiceOption(Mode.LAUNCHER.id, Mode.LAUNCHER.label))
            }
            listOf(Mode.DESKTOP, Mode.GAMING)
                .filter { Modes.isEnabledInStorage(context, it) }
                .forEach { add(ChoiceOption(it.id, it.label)) }
        }
        return ChoiceItem(
            id = "pref_global_default_mode",
            title = "Default mode",
            subtitle = "Where droidtop opens, and where the Home button takes you",
            options = options,
            current = Modes.defaultMode(context)?.takeIf { id -> options.any { it.value == id } } ?: "",
            onSelect = { ctx, value -> Modes.setDefaultMode(ctx, Mode.byId(value.ifEmpty { null })) },
        )
    }

    /**
     * Android's own Users screen, for a shared device (Droidtop/tracker#79). An
     * app cannot switch users itself (that needs a system-only permission), so
     * this hands over to the screen that can, and is offered only where the
     * device supports more than one user and a Settings activity answers.
     */
    private fun usersItem(context: Context): ActionItem? {
        if (!android.os.UserManager.supportsMultipleUsers()) return null
        val intent = Intent("android.settings.USER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (context.packageManager.resolveActivity(intent, 0) == null) return null
        return ActionItem(
            id = "pref_global_users",
            title = "Users and guest",
            subtitle = "Opens Android's Users screen to switch user or start a guest session",
            run = { ctx -> ctx.startActivity(Intent(intent)) },
        )
    }

    /**
     * The one way to crash droidtop on purpose (debug builds only), next to
     * Share diagnostics in the Data group -- the screen the third-crash
     * route itself opens. Crash notes and the crash-loop counter (SPEC 10c)
     * had no other way to be exercised on a device once setup was done:
     * `am crash` does not exist on the rigs, and the fresh-install
     * onDestroy crash stops reproducing after onboarding, which left the
     * third-crash Global settings route and the one-minute counter reset
     * unverified (rig, verify-2026-09-29, Droidtop/tracker#49). The throw
     * is posted to the main looper so it reaches CrashRecovery's
     * uncaught-exception handler exactly like a real crash, whichever
     * surface runs the row.
     */
    private fun debugCrashItem(context: Context): ActionItem? {
        if (!AppSettingsCatalogs.ctxIsDebuggable(context)) return null
        return ActionItem(
            id = "pref_global_debug_crash",
            title = "Debug: force a crash",
            subtitle = "Crashes droidtop on purpose: writes a crash note and counts toward safe mode, like a real crash",
            confirmTitle = "Crash droidtop now?",
            run = { _ ->
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    throw RuntimeException("Debug: forced crash from Global settings")
                }
            },
        )
    }

    private fun homeRoleSubtitle(context: Context): String = when {
        HomeRolePrefs.isDroidtopHome(context) -> "droidtop's own launcher is what the Home button opens"
        HomeRolePrefs.activeHomeImplementation(context) == HomeRolePrefs.HomeImplementation.STANDARD ->
            "Another app is the Home app. Turn this on to choose droidtop in Android's Default home app screen"
        else -> "Turn this on to choose droidtop's own launcher in Android's Default home app screen"
    }

    private fun modeToggle(context: Context, mode: Mode) = ToggleItem(
        id = if (mode == Mode.DESKTOP) "pref_global_enable_desktop" else "pref_global_enable_gaming",
        title = "Enable ${mode.label} mode",
        subtitle = if (Modes.isEnabledInStorage(context, mode)) {
            "Turn off to stop ${mode.label} and leave it out of the mode switcher"
        } else {
            "${mode.label} is off: it runs nothing and is not in the mode switcher"
        },
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
        val includeCredentials = CatalogPrefs.prefs(context).getBoolean(KEY_BACKUP_CREDENTIALS, false)
        val credentials = JSONObject()
        for ((key, value) in CatalogPrefs.prefs(context).all) {
            if (key == KEY_BACKUP_CREDENTIALS) continue
            if (isCredentialKey(key)) {
                if (includeCredentials && key in CREDENTIAL_KEYS && key !in CredentialStore.KEYS && value is String) credentials.put(key, value)
                continue
            }
            when (value) {
                is Boolean, is Int, is Long, is Float, is String -> json.put(key, value)
                is Set<*> -> json.put(key, JSONArray(value.toList()))
                else -> {}
            }
        }
        if (includeCredentials) {
            // The user's own keys live in the encrypted store, not the preferences file.
            CredentialStore.snapshot(context).forEach { (key, value) -> credentials.put(key, value) }
            json.put(BACKUP_CREDENTIALS_FIELD, Base64.encodeToString(credentials.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
        }
        context.contentResolver.openOutputStream(uri)?.use { it.write(json.toString(2).toByteArray()) }
            ?: error("the file could not be opened")
        "Backup saved"
    }.getOrElse {
        android.util.Log.w("droidtop.settings", "Backup failed", it)
        "Backup failed. Check there is space left on the storage you chose and try again."
    }

    /**
     * Builds the diagnostics archive (10c) and only then opens the system
     * share sheet with it; the caller runs this off the main thread.
     */
    private fun shareDiagnostics(context: Context): String = runCatching {
        val file = dev.droidtop.library.diagnostics.DiagnosticsArchive.build(context)
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "droidtop diagnostics")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Share diagnostics").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        "Diagnostics packed (${file.length() / 1024} KB); choose where to send it"
    }.getOrElse {
        android.util.Log.w("droidtop.settings", "Diagnostics archive failed", it)
        "Couldn't pack diagnostics. Check there is space left on this device and try again."
    }

    private fun readBackup(context: Context, uri: Uri): String = runCatching {
        val text = context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
            ?: error("the file could not be opened")
        val json = JSONObject(text)
        val editor = CatalogPrefs.prefs(context).edit()
        for (key in json.keys()) {
            if (key == BACKUP_CREDENTIALS_FIELD || key == KEY_BACKUP_CREDENTIALS || isCredentialKey(key)) continue
            when (val value = json.get(key)) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Double -> editor.putFloat(key, value.toFloat())
                is String -> editor.putString(key, value)
                is JSONArray -> editor.putStringSet(key, (0 until value.length()).map { value.getString(it) }.toSet())
            }
        }
        json.optString(BACKUP_CREDENTIALS_FIELD).takeIf { it.isNotEmpty() }?.let { encoded ->
            val credentials = JSONObject(String(Base64.decode(encoded, Base64.NO_WRAP), Charsets.UTF_8))
            for (key in credentials.keys()) {
                if (key in CredentialStore.KEYS) {
                    CredentialStore.put(context, key, credentials.getString(key))
                } else if (key in CREDENTIAL_KEYS) {
                    editor.putString(key, credentials.getString(key))
                }
            }
        }
        editor.apply()
        "Restored. Restart droidtop for every setting to apply."
    }.getOrElse {
        android.util.Log.w("droidtop.settings", "Restore failed", it)
        "Restore failed. Check that the file is a droidtop backup and try again."
    }
}
