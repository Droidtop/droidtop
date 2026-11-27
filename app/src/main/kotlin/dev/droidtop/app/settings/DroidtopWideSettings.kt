package dev.droidtop.app.settings

import dev.droidtop.runtime.systemstatus.SettingsLaunch
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import dev.droidtop.app.AccessibilityPrefs
import dev.droidtop.app.OnboardingActivity
import dev.droidtop.app.ScreenOrientationPrefs
import dev.droidtop.library.settings.DesktopPrefs
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
import dev.droidtop.library.settings.NestedScreenItem
import dev.droidtop.library.settings.DeveloperMenu
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
        "droidtop_retroachievements_apikey",
    )

    private fun isCredentialKey(key: String): Boolean {
        val normalized = key.lowercase()
        return key in CREDENTIAL_KEYS || listOf("password", "passwd", "secret", "apikey", "api_key", "token", "credential")
            .any(normalized::contains)
    }

    const val SCREEN_GLOBAL = "global_settings"
    const val SCREEN_DESKTOP = "desktop_settings"
    const val SCREEN_STANDARD = "standard_settings"


    fun globalScreen() = CatalogScreen(
        id = SCREEN_GLOBAL,
        title = "Settings",
        // The Gaming settings page draws these groups as sections of its own categories (each group
        // names one) instead of a level of its own; every other surface still opens this screen.
        merged = true,
        groups = { context ->
            listOf(
                CatalogGroup(
                    id = "global_home",
                    title = "Home screen",
                    category = "Modes",
                    items = listOf(
                        // DROIDTOP <-> NONE only: a person on ALTERNATIVE
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
                            title = "droidtop is your home screen",
                            subtitle = homeRoleSubtitle(context),
                            current = HomeRolePrefs.activeHomeImplementation(context) ==
                                HomeRolePrefs.HomeImplementation.DROIDTOP && HomeRolePrefs.isDroidtopHome(context),
                            onToggle = { ctx, on ->
                                HomeRolePrefs.setActiveHomeImplementation(
                                    ctx,
                                    if (on) HomeRolePrefs.HomeImplementation.DROIDTOP else HomeRolePrefs.HomeImplementation.NONE,
                                )
                                if (on && !HomeRolePrefs.isDroidtopHome(ctx)) {
                                    SettingsLaunch.start(ctx, HomeRolePrefs.homeSettingsIntent())
                                }
                            },
                        ),
                        // Which of droidtop's UIs Home opens, beside the row that makes droidtop Home
                        // (mytop-project/tracker#571 row 11).
                        defaultModeItem(context),
                    ) + listOfNotNull(usersItem(context)),
                ),
                CatalogGroup(
                    id = "global_modes",
                    title = "Modes",
                    category = "Modes",
                    items = listOf(
                        modeToggle(context, Mode.DESKTOP),
                        modeToggle(context, Mode.GAMING),
                        dev.droidtop.library.settings.NestedScreenItem(
                            id = "pref_standard_settings", title = "Mobile mode settings",
                            subtitle = "Launcher and screen settings", registryId = SCREEN_STANDARD,
                            icon = dev.droidtop.library.settings.CatalogIcon.STANDARD,
                        ),
                    ),
                ),
                // Whether droidtop may change other apps for you (docs/SPEC.md "Risky actions"); off by default.
                CatalogGroup(
                    id = "global_risky_actions",
                    title = null,
                    category = "System",
                    items = listOf(RiskyActionsCatalog.linkRow("pref_global_risky_actions")),
                ),
                CatalogGroup(
                    id = "global_appearance",
                    title = "Light, dark and colours",
                    category = "Appearance",
                    items = listOf(
                        dev.droidtop.library.settings.AppearancePrefs.item(context),
                        // The colour source and the theme tint (docs/SPEC.md 7k, mytop-project/tracker#827 and #844).
                        dev.droidtop.library.settings.AppearancePrefs.colourSourceItem(context),
                        dev.droidtop.library.settings.AppearancePrefs.customAccentItem(context),
                        dev.droidtop.library.settings.AppearancePrefs.tintThemesItem(context),
                    ),
                ),
                CatalogGroup(
                    id = "global_accessibility",
                    title = "Accessibility",
                    category = "Appearance",
                    items = listOf(
                        // droidtop's own language (mytop-project/tracker#627): Follow Android, or a shipped language.
                        dev.droidtop.app.AppLanguage.item(context),
                        ChoiceItem(
                            id = AccessibilityPrefs.KEY_COLOR_VISION,
                            title = "Colour vision",
                            subtitle = "droidtop's own screens only",
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
                            subtitle = "On top of Android's font size",
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
                            // Each size shows itself on a sample line (mytop-project/tracker#673).
                            sampleScale = { it.toFloatOrNull() },
                        ),
                        dev.droidtop.library.settings.ReadingPrefs.infoTextItem(context),
                        dev.droidtop.library.settings.ReadingPrefs.highContrastItem(context),
                        dev.droidtop.library.settings.ReadingPrefs.sentenceCaseItem(context),
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
                            run = { ctx ->
                                ctx.startActivity(
                                    Intent(ctx, OnboardingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            },
                        ),
                        // A feedback tool for anyone, not a developer's: the owner, 2026-10-17, "Diagnostics are a user
                        // feedback tool, not a dev tool" (docs/SPEC.md 7, "Developer menu").
                        AsyncActionItem(
                            id = "pref_global_share_diagnostics",
                            title = "Share diagnostics",
                            subtitle = "Logs and settings, no passwords",
                            run = { ctx, onStatus ->
                                onStatus("Packing")
                                shareDiagnostics(ctx)
                            },
                        ),
                        ToggleItem(
                            id = KEY_BACKUP_CREDENTIALS,
                            title = "Back up credentials",
                            subtitle = "Passwords and keys go in backups, encoded",
                            current = CatalogPrefs.prefs(context).getBoolean(KEY_BACKUP_CREDENTIALS, false),
                            onToggle = { ctx, on -> CatalogPrefs.prefs(ctx).edit().putBoolean(KEY_BACKUP_CREDENTIALS, on).apply() },
                        ),
                        // One archive of what a person did in droidtop (docs/SPEC.md "Data", mytop-project/tracker#625).
                        DocumentPickItem(
                            id = "pref_global_backup",
                            title = "Back up droidtop",
                            mimeType = "application/zip",
                            createName = "droidtop-backup.zip",
                            onPicked = ::writeBackup,
                        ),
                        DocumentPickItem(
                            id = "pref_global_restore",
                            title = "Restore droidtop",
                            subtitle = "The rest applies on next start",
                            mimeType = "*/*",
                            confirmTitle = "Replace this device's library data with the backup's?",
                            onPicked = ::readBackup,
                        ),
                    ) + shareBackupRows(context),
                ),
                // Which droidtop this is; seven taps on it turn on the Developer menu, where it then opens from.
                CatalogGroup(
                    id = "global_about",
                    title = "About droidtop",
                    category = "System",
                    items = listOfNotNull(
                        // The one Updates screen, in About where people look for it (mytop-project/tracker#792: "There's no plugin,
                        // or DROIDTOP even, update path in the UI"). Gaming also has it as a left-menu place, and the Quick
                        // Menu opens that place; all are links to this screen. Its value says when droidtop has a newer build.
                        dev.droidtop.library.settings.NestedScreenItem(
                            id = "pref_global_updates",
                            title = "Updates",
                            registryId = AppSettingsCatalogs.SCREEN_UPDATES,
                            icon = dev.droidtop.library.settings.CatalogIcon.SYSTEM_UPDATES,
                            // Read here, as the screen is built off the main thread; the label is drawn in composition.
                            valueLabel = (
                                dev.droidtop.app.update.AppSelfUpdate.newerSeenVersionName(context)?.let { "droidtop $it is out" }
                                    ?: "Check for updates"
                                ).let { label -> { _: android.content.Context -> label } },
                        ),
                        ActionItem(
                            id = "pref_global_version",
                            title = "Version",
                            value = dev.droidtop.app.update.AppSelfUpdate.installedVersionName(context),
                            run = { ctx ->
                                DeveloperMenu.versionTapped(ctx)?.let { dev.droidtop.library.settings.Notices.flash(it, context = ctx) }
                            },
                        ),
                        // Every build's changelog, the same text as its release notes (docs/SPEC.md 7b, "Update onboarding").
                        ActionItem(
                            id = "pref_global_whats_new",
                            title = "What's new",
                            run = { ctx ->
                                ctx.startActivity(
                                    dev.droidtop.app.WhatsNewActivity.intent(ctx, all = true)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            },
                        ),
                        // The whole of the last update (owner, 2026-11-04: "display the TOTAL changelog, computed from previous
                        // version to current"): every build between the version droidtop was updated from and this one, merged
                        // with what to do after it. Only once droidtop has been updated with What's new in it.
                        dev.droidtop.app.WhatsNew.updatedFrom(context)?.let { from ->
                            ActionItem(
                                id = "pref_global_changes_since",
                                title = "Changes since your last update",
                                run = { ctx ->
                                    ctx.startActivity(dev.droidtop.app.WhatsNewActivity.since(ctx, from).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                },
                            )
                        },
                        if (DeveloperMenu.on(context)) {
                            NestedScreenItem(
                                id = "pref_global_developer",
                                title = "Developer",
                                registryId = DeveloperMenu.SCREEN,
                            )
                        } else {
                            null
                        },
                    ),
                ),
            )
        },
    )

    fun desktopScreen() = CatalogScreen(
        id = SCREEN_DESKTOP,
        title = "Desktop mode",
        groups = { context ->
            listOf(
                CatalogGroup(
                    id = "desktop",
                    title = null,
                    items = listOfNotNull(
                        orientationChoice(context, Mode.DESKTOP),
                        ToggleItem(
                            id = DesktopPrefs.KEY_TASKBAR_TOP,
                            title = "Taskbar at top",
                            current = DesktopPrefs.read(context).taskbarAtTop,
                            onToggle = { ctx, on -> DesktopPrefs.setTaskbarAtTop(ctx, on) },
                        ),
                        desktopScaleChoice(context),
                        desktopResolutionChoice(context),
                        // The seat's one input method, left to a container's fcitx5 or ibus (mytop-project/tracker#809).
                        ToggleItem(
                            id = DesktopPrefs.KEY_CONTAINER_INPUT_METHOD,
                            title = "A container's own input method",
                            subtitle = "fcitx5 or ibus does the typing",
                            current = DesktopPrefs.read(context).containerInputMethod,
                            onToggle = { ctx, on -> DesktopPrefs.setContainerInputMethod(ctx, on) },
                        ),
                        ToggleItem(
                            id = "pref_desktop_keep_running",
                            title = "Keep the desktop running",
                            subtitle = "Uses memory and battery while you're elsewhere",
                            current = dev.droidtop.app.DesktopSetupPrefs.keepRunning(context),
                            onToggle = { ctx, on -> dev.droidtop.app.DesktopSetupPrefs.setKeepRunning(ctx, on) },
                        ),
                        keyboardLayoutChoice(context),
                        desktopSystemKeysItem(context),
                        ChoiceItem(
                            id = "pref_desktop_panel",
                            title = "Desktop panel",
                            subtitle = dev.droidtop.app.DesktopSetupPrefs.panel(context).about + ". Applies at next start",
                            options = dev.droidtop.runtime.DesktopPanel.entries.map { ChoiceOption(it.id, it.label) },
                            current = dev.droidtop.app.DesktopSetupPrefs.panel(context).id,
                            onSelect = { ctx, value ->
                                dev.droidtop.app.DesktopSetupPrefs.setPanel(ctx, dev.droidtop.runtime.DesktopPanel.fromId(value))
                            },
                        ),
                        ToggleItem(
                            id = "pref_desktop_tile_windows",
                            title = "Tile windows",
                            subtitle = "Side by side instead of floating",
                            current = dev.droidtop.app.DesktopSetupPrefs.tileWindows(context),
                            onToggle = { ctx, on -> dev.droidtop.app.DesktopSetupPrefs.setTileWindows(ctx, on) },
                        ),
                        ChoiceItem(
                            id = "pref_desktop_background",
                            title = "Desktop background",
                            subtitle = "A colour or a picture",
                            options = dev.droidtop.runtime.DesktopAppearance.Background.entries.map { ChoiceOption(it.id, it.label) },
                            current = dev.droidtop.app.DesktopSetupPrefs.background(context).id,
                            onSelect = { ctx, value ->
                                val choice = dev.droidtop.runtime.DesktopAppearance.Background.fromId(value)
                                // A picture is chosen in the system's picker; the setting moves once it is saved.
                                if (choice == dev.droidtop.runtime.DesktopAppearance.Background.PICTURE) {
                                    dev.droidtop.app.DesktopBackgroundPicture.choose(ctx)
                                } else {
                                    dev.droidtop.app.DesktopSetupPrefs.setBackground(ctx, choice)
                                }
                            },
                        ),
                        // The rows above that apply "when the desktop next starts" can apply now (mytop-project/tracker#687).
                        // What Android allows droidtop's programs before it ends them (mytop-project/tracker#816).
                        PhantomProcessRow.item(context, "pref_desktop_child_processes"),
                        PhantomBreakdownRow.item(context, "pref_desktop_child_processes_what"),
                        ActionItem(
                            id = "pref_desktop_restart",
                            title = "Restart the desktop",
                            subtitle = "Closes every window",
                            run = { ctx -> dev.droidtop.app.DesktopSessionService.restart(ctx) },
                        ),
                        ActionItem(
                            id = "pref_desktop_root_compositor_setup",
                            title = "Desktop setup",
                            run = { ctx -> ctx.startActivity(dev.droidtop.app.DesktopSetupPrefs.setupIntent(ctx)) },
                        ),
                        // The container manager is a catalog screen of its own
                        // (docs/SPEC.md 3d), opened in place by whichever
                        // surface is showing these settings.
                        dev.droidtop.library.settings.NestedScreenItem(
                            id = "pref_desktop_containers",
                            title = "Containers",
                            registryId = ContainersCatalog.SCREEN_ID,
                            icon = dev.droidtop.library.settings.CatalogIcon.CONTAINERS,
                        ),
                        // Desktop's Start > Settings opens this page in droidtop's screen host (mytop-project/tracker#692):
                        // the rest of Settings is one row away.
                        dev.droidtop.library.settings.NestedScreenItem(
                            id = "pref_desktop_all_settings",
                            title = "Settings",
                            registryId = SCREEN_GLOBAL,
                        ),
                    ),
                ),
            )
        },
    )

    fun standardScreen() = CatalogScreen(
        id = SCREEN_STANDARD,
        title = "Mobile mode",
        groups = { context -> listOf(CatalogGroup(
            id = "standard", title = null,
            items = listOf(orientationChoice(context, Mode.LAUNCHER)),
        )) },
        // One row: drawn as a section of Global settings, not a level of its own (docs/SPEC.md
        // "Settings layout").
        merged = true,
    )

    /**
     * How large the Linux desktop draws (dev.droidtop.runtime.DesktopScale,
     * mytop-project/tracker#386). Automatic names the scale it works out for the
     * desktop's own screen; the desktop takes a change at once.
     */
    private fun desktopScaleChoice(context: Context): ChoiceItem {
        // The desktop's own screen, as the desktop recorded it (mytop-project/tracker#386): Settings may be on another
        // screen, whose density would name a scale the desktop does not use.
        val dpi = dev.droidtop.library.settings.DesktopPrefs.screenDpi(context) ?: context.resources.displayMetrics.densityDpi
        val automatic = dev.droidtop.runtime.DesktopScale.automatic(dpi)
        return ChoiceItem(
            id = dev.droidtop.runtime.DesktopScale.KEY,
            title = "Desktop scale",
            subtitle = "Size of text and windows",
            options = listOf(
                ChoiceOption(dev.droidtop.runtime.DesktopScale.AUTOMATIC, "Automatic (${dev.droidtop.runtime.DesktopScale.label(automatic)})"),
            ) + dev.droidtop.runtime.DesktopScale.CHOICES.map { value ->
                ChoiceOption(value, dev.droidtop.runtime.DesktopScale.label(value.toDouble()))
            },
            current = CatalogPrefs.prefs(context).getString(dev.droidtop.runtime.DesktopScale.KEY, dev.droidtop.runtime.DesktopScale.AUTOMATIC),
            onSelect = { ctx, value -> CatalogPrefs.prefs(ctx).edit().putString(dev.droidtop.runtime.DesktopScale.KEY, value).apply() },
        )
    }

    /**
     * How many of the screen's pixels the Linux desktop draws (mytop-project/tracker#426): fewer is faster and
     * blurrier. Beside [desktopScaleChoice], which sets how large things are, not how sharp.
     */
    private fun desktopResolutionChoice(context: Context): ChoiceItem {
        val key = dev.droidtop.runtime.DesktopScale.RESOLUTION_KEY
        return ChoiceItem(
            id = key,
            title = "Desktop resolution",
            subtitle = "Lower is faster and blurrier",
            options = listOf(ChoiceOption(dev.droidtop.runtime.DesktopScale.FULL_RESOLUTION, "Full")) +
                dev.droidtop.runtime.DesktopScale.RESOLUTION_CHOICES.map { value ->
                    ChoiceOption(value, "${(value.toDouble() * 100).toInt()}%")
                },
            current = CatalogPrefs.prefs(context).getString(key, dev.droidtop.runtime.DesktopScale.FULL_RESOLUTION),
            onSelect = { ctx, value -> CatalogPrefs.prefs(ctx).edit().putString(key, value).apply() },
        )
    }

    /**
     * The desktop keyboard's layout (mytop-project/tracker#387): Automatic, named
     * with what it resolves to and where that came from, or one of the
     * container's own layouts (its list is copied the first time a desktop
     * runs; before that, the layouts droidtop can name from a language).
     */
    /**
     * Whether the keys Android keeps (Super, Alt+Tab, Ctrl+Space) reach the desktop (mytop-project/tracker#697): a grant
     * the person gives once, through droidtop's accessibility service, and takes back in Android's list.
     */
    private suspend fun desktopSystemKeysItem(context: Context): AsyncActionItem {
        val (on, line) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            dev.droidtop.runtime.keyboard.AccessibilityKeyboard.desktopKeys(context)
        }
        return AsyncActionItem(
            id = "pref_desktop_system_keys",
            title = "Super and Alt+Tab",
            subtitle = line,
            value = if (on) "On" else "Off",
            statusMark = if (on) null else dev.droidtop.library.settings.StatusMark.ATTENTION,
            run = { ctx, _ ->
                if (on) {
                    dev.droidtop.runtime.keyboard.AccessibilityKeyboard.openSettings(ctx)
                    "Turn it off in Android's list"
                } else {
                    dev.droidtop.runtime.keyboard.AccessibilityKeyboard.turnOn(ctx)
                }
            },
        )
    }

    private suspend fun keyboardLayoutChoice(context: Context): ChoiceItem {
        val names = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            dev.droidtop.app.DesktopKeyboardLayout.layoutNames(context)
        }
        val stored = dev.droidtop.app.DesktopKeyboardLayout.setting(context)
        val auto = dev.droidtop.app.DesktopKeyboardLayout.automatic(context)
        val current = dev.droidtop.app.DesktopKeyboardLayout.resolve(context)
        val ids = names.keys.ifEmpty { dev.droidtop.runtime.KeyboardLayouts.KNOWN }
        val options = listOf(
            ChoiceOption(
                dev.droidtop.runtime.KeyboardLayouts.AUTOMATIC,
                // The layout's name only; where it came from is on the row's line. "Automatic (English (US) (us), from
                // the attached keyboard)" was cut in the value column (rig, AVD dev.3354).
                "Automatic · " + (names[auto.layout.layout] ?: auto.layout.id),
            ),
        ) + (ids + listOfNotNull(stored.takeIf { it != dev.droidtop.runtime.KeyboardLayouts.AUTOMATIC }))
            .distinct()
            .map { id -> ChoiceOption(id, names[id]?.let { "$it ($id)" } ?: id) }
        return ChoiceItem(
            id = dev.droidtop.runtime.KeyboardLayouts.KEY,
            title = "Keyboard layout",
            subtitle = (if (stored == dev.droidtop.runtime.KeyboardLayouts.AUTOMATIC) "Automatic: " else "") +
                dev.droidtop.app.DesktopKeyboardLayout.describe(current, names) +
                ". For a physical keyboard; the on-screen keyboards type as they show",
            options = options,
            current = stored,
            onSelect = { ctx, value -> dev.droidtop.app.DesktopKeyboardLayout.setSetting(ctx, value) },
        )
    }

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
            title = "Home opens",
            subtitle = "Also where the Home button goes",
            options = options,
            current = Modes.defaultMode(context)?.takeIf { id -> options.any { it.value == id } } ?: "",
            onSelect = { ctx, value -> Modes.setDefaultMode(ctx, Mode.byId(value.ifEmpty { null })) },
        )
    }

    /**
     * Android's own Users screen, for a shared device (mytop-project/tracker#79). An
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
     * unverified (rig, verify-2026-09-29, mytop-project/tracker#49). The throw
     * is posted to the main looper so it reaches CrashRecovery's
     * uncaught-exception handler exactly like a real crash, whichever
     * surface runs the row.
     */
    /**
     * The Developer menu (docs/SPEC.md 7, "Developer menu"; mytop-project/tracker#579): what is for finding faults in droidtop rather than
     * for playing. Its own rows are droidtop-wide; a plugin's, an emulator's or a page's technical rows stay on their
     * page and show there only while this menu is on ([DeveloperMenu.on]).
     */
    fun developerScreen() = CatalogScreen(
        id = DeveloperMenu.SCREEN,
        title = "Developer",
        groups = { context ->
            listOf(
                CatalogGroup(
                    id = "developer_diagnostics",
                    title = "Recovery and debugging",
                    items = listOfNotNull(
                        AppSettingsCatalogs.rebuildIndexItem(),
                        UpdatesPage.debugBuildsItem(context),
                        debugCrashItem(context),
                        debugDesktopSecondScreenItem(context),
                    ),
                ),
                // The calibration set for name matching (mytop-project/tracker#318 §3.3): a scrape run with this on writes what
                // each source answered, and a person fills in which answer was right.
                CatalogGroup(
                    id = "developer_matching",
                    title = "Matching",
                    items = listOf(
                        ToggleItem(
                            id = "developer_record_match_answers",
                            title = "Record match answers",
                            subtitle = "While on, each scrape adds what the source answered for every PC game, and every " +
                                "ROM it matched by hash, to Download/droidtop/${dev.droidtop.library.identity.MatchRecorder.FILE_NAME}",
                            current = dev.droidtop.library.identity.MatchRecorder.on(context),
                            onToggle = { ctx, on -> dev.droidtop.library.identity.MatchRecorder.set(ctx, on) },
                        ),
                    ),
                ),
                // Per-app volume and EQ's open questions, answered on a device (mytop-project/tracker#836, slice 0).
                CatalogGroup(
                    id = "developer_audio",
                    title = "Sound",
                    items = listOf(
                        dev.droidtop.library.settings.AsyncActionItem(
                            id = "developer_audio_probe",
                            title = "Probe per-app audio",
                            subtitle = "Play a game first. Saves Android's audio dumps and dips each app for 4 s",
                            run = { ctx, _ -> AudioProbe.run(ctx).lineSequence().last() },
                        ),
                    ),
                ),
                // Wine's tree under the sandbox broker, learning only (docs/SPEC.md 5b, mytop-project/tracker#584 piece 0).
                CatalogGroup(
                    id = "developer_wine_sandbox",
                    title = "Windows games",
                    items = listOf(
                        ToggleItem(
                            id = "developer_wine_sandbox_learning",
                            title = "Learn what Wine reaches",
                            subtitle = "No protection while on: each game launched runs under the sandbox broker in " +
                                "learning mode, which allows everything and records what a lockdown would refuse, " +
                                "to droidtop's files, wine-sandbox (the newest five launches)",
                            current = dev.droidtop.runtime.windows.WineSandbox.learning(context),
                            onToggle = { ctx, on -> dev.droidtop.runtime.windows.WineSandbox.setLearning(ctx, on) },
                        ),
                    ),
                ),
                CatalogGroup(
                    id = "developer_menu",
                    title = "Developer menu",
                    items = listOf(
                        ActionItem(
                            id = "developer_turn_off",
                            title = "Turn off the Developer menu",
                            subtitle = "Tap the version seven times to bring it back",
                            run = { ctx -> DeveloperMenu.set(ctx, false) },
                        ),
                    ),
                ),
            )
        },
    )

    private fun debugCrashItem(context: Context): ActionItem? {
        if (!AppSettingsCatalogs.ctxIsDebuggable(context)) return null
        return ActionItem(
            id = "pref_global_debug_crash",
            title = "Debug: force a crash",
            confirmTitle = "Crash droidtop now?",
            run = { _ ->
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    throw RuntimeException("Debug: forced crash from Global settings")
                }
            },
        )
    }

    /**
     * A debug build's shortcut for a rig check of the desktop on a second screen (mytop-project/tracker#908): flips the
     * first other screen's Desktop answer between Extend and Keep it as it is, the answer the question and
     * Settings > Displays set (step D5), so it goes through the same path ([dev.droidtop.app.DesktopScreens]) and the
     * companion is kept off the screen the same way.
     */
    private fun debugDesktopSecondScreenItem(context: Context): AsyncActionItem? {
        if (!AppSettingsCatalogs.ctxIsDebuggable(context)) return null
        return AsyncActionItem(
            id = "pref_global_debug_desktop_second_screen",
            title = "Debug: desktop on the other screen",
            run = { ctx, _ ->
                val session = dev.droidtop.app.DesktopSessionService.state.value as? dev.droidtop.app.DesktopSessionState.Connected
                if (session == null) {
                    "Start the desktop first"
                } else {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val other = dev.droidtop.runtime.DisplayOutputRepository(ctx).currentOutputsSnapshot()
                            .firstOrNull { it.androidDisplayId != session.primaryOutput.androidDisplayId }
                        if (other == null) {
                            "There is no other screen"
                        } else {
                            val extended = dev.droidtop.runtime.ScreenRoles.desktopSaved(ctx)[other.uniqueId] ==
                                dev.droidtop.runtime.DesktopScreenChoice.EXTEND
                            val next = if (extended) dev.droidtop.runtime.DesktopScreenChoice.COMPANION else dev.droidtop.runtime.DesktopScreenChoice.EXTEND
                            dev.droidtop.runtime.ScreenRoles.setDesktop(ctx, other.uniqueId, next)
                            if (extended) "Taken off the other screen" else "Shown on the other screen"
                        }
                    }
                }
            },
        )
    }

    private fun homeRoleSubtitle(context: Context): String {
        if (HomeRolePrefs.isDroidtopHome(context)) return "The Home button opens droidtop"
        // Who holds Home now, by name: the Mobile settings row it replaces said "???" when nothing resolved.
        val other = currentHomeLabel(context)
        return if (other != null) {
            "$other is your home screen. Turn this on to choose droidtop in Android's Default home app screen"
        } else {
            "Turn this on to choose droidtop in Android's Default home app screen"
        }
    }

    /** The app Android resolves Home to now, by its label; null when it asks each time or nothing resolves. */
    private fun currentHomeLabel(context: Context): String? {
        val pm = context.packageManager
        val info = pm.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
        ) ?: return null
        if (info.activityInfo.packageName == "android" || info.activityInfo.packageName == context.packageName) return null
        return info.loadLabel(pm).toString().takeIf { it.isNotBlank() }
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
        DroidtopBackup.write(context, uri, settingsJson(context))
    }.getOrElse {
        android.util.Log.w("droidtop.settings", "Backup failed", it)
        "Backup failed. Check there is space left on the storage you chose and try again."
    }

    /**
     * The settings file as JSON, with the credentials only when the person chose to back them up (encoded) and
     * [mayCarryCredentials]: a backup sent to a share the person trusts in plain never carries them (SPEC 7p).
     */
    private fun settingsJson(context: Context, mayCarryCredentials: Boolean = true): JSONObject {
        val json = JSONObject()
        val includeCredentials = mayCarryCredentials && CatalogPrefs.prefs(context).getBoolean(KEY_BACKUP_CREDENTIALS, false)
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
        return json
    }

    /**
     * Builds the diagnostics archive (10c) and only then opens the system
     * share sheet with it; the caller runs this off the main thread.
     */
    private fun shareDiagnostics(context: Context): String = dev.droidtop.library.diagnostics.DiagnosticsArchive.share(context)

    /**
     * A droidtop backup archive (a zip), or a settings file from before the archive (JSON): the settings apply now,
     * and an archive's files wait for the next start ([DroidtopBackup.finishPendingRestore]).
     */
    private fun readBackup(context: Context, uri: Uri): String = readBackupFrom(context) { context.contentResolver.openInputStream(uri) }

    /** [readBackup] from whatever [open] opens anew each time it is called: a picked file, or a file fetched from a share. */
    private fun readBackupFrom(context: Context, open: () -> java.io.InputStream?): String = runCatching {
        val head = open()?.use { stream -> ByteArray(2).also { stream.read(it) } }
            ?: error("the file could not be opened")
        if (head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) {
            val staged = open()?.use { DroidtopBackup.stage(context, it) } ?: error("the file could not be opened")
            staged.settings?.let { applySettings(context, it) }
            val plugins = if (staged.plugins.isEmpty()) "" else
                " Reinstall these plugins from Plugins: ${staged.plugins.joinToString(", ")}."
            return@runCatching "Restored the settings. The rest (${staged.files} files) is put in place the next time droidtop " +
                "starts: restart droidtop now.$plugins"
        }
        val text = open()?.use { it.bufferedReader().readText() }
            ?: error("the file could not be opened")
        applySettings(context, JSONObject(text))
        "Restored. Restart droidtop for every setting to apply."
    }.getOrElse {
        android.util.Log.w("droidtop.settings", "Restore failed", it)
        "Restore failed. Check that the file is a droidtop backup and try again."
    }

    /**
     * Back up droidtop to one of the person's shares, and restore from one (docs/SPEC.md 7p, mytop-project/tracker#573): one
     * row per share under Restore droidtop. Nothing is shown until a share is added. Preferences: off the main thread.
     */
    private fun shareBackupRows(context: Context): List<dev.droidtop.library.settings.CatalogItem> =
        dev.droidtop.library.storage.StorageShares.all(context).map { share ->
            val id = "pref_global_share_backup_${share.id}"
            dev.droidtop.library.settings.NestedScreenItem(
                id = id,
                title = "Back up to ${share.name}",
                subtitle = if (share.trusted) "In plain on your share; sign-ins are never included" else "Locked: only this device can open it",
                inline = dev.droidtop.library.settings.CatalogScreen(
                    id = "${id}_screen",
                    title = share.name,
                    groups = { ctx ->
                        val backups = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ShareBackups.list(ctx, share) }
                        listOf(
                            CatalogGroup(
                                id = "${id}_now",
                                title = null,
                                items = listOf(
                                    AsyncActionItem(
                                        id = "${id}_run",
                                        title = "Back up now",
                                        run = { c, onStatus ->
                                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                onStatus("Backing up...")
                                                ShareBackups.backUp(c, share) { out -> DroidtopBackup.write(c, out, settingsJson(c, mayCarryCredentials = !share.trusted)) }
                                            }
                                        },
                                    ),
                                ),
                            ),
                            CatalogGroup(
                                id = "${id}_list",
                                title = "Restore",
                                items = when (backups) {
                                    is ShareBackups.Listed.Failed -> listOf(
                                        dev.droidtop.library.settings.TextBlockItem(id = "${id}_failed", text = backups.line),
                                    )
                                    is ShareBackups.Listed.Found -> if (backups.backups.isEmpty()) {
                                        listOf(dev.droidtop.library.settings.TextBlockItem(id = "${id}_none", text = "No droidtop backups on ${share.name} yet"))
                                    } else {
                                        backups.backups.map { backup ->
                                            AsyncActionItem(
                                                id = "${id}_restore_${backup.path.hashCode()}",
                                                title = backup.label,
                                                subtitle = backup.device + if (backup.locked) ", locked" else "",
                                                confirmTitle = "Replace this device's library data with the backup's?",
                                                run = { c, onStatus ->
                                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                        onStatus("Fetching the backup...")
                                                        ShareBackups.fetch(c, share, backup) { file -> readBackupFrom(c) { file.inputStream() } }
                                                    }
                                                },
                                            )
                                        }
                                    }
                                },
                            ),
                        )
                    },
                ),
            )
        }

    /** Writes a backup's settings into the settings file; credentials only from the backup's encoded section. */
    private fun applySettings(context: Context, json: JSONObject) {
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
    }
}
