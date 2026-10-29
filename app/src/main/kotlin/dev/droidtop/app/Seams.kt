package dev.droidtop.app

import android.app.Application
import dev.droidtop.runtime.PrimaryContainerSession

/**
 * Every seam droidtop's modules call back through, installed once per process from [DroidtopApplication.onCreate]
 * (Droidtop/tracker#763): the plugin broker's providers, the download and debrid hooks, and the library's runtimes. A
 * seam is set here before anything can ask it, never as a side effect of something else starting. The catalog
 * provider's seams stay in SettingsCatalogInitProvider: the catalogs and the restored jobs that read them start
 * there, before onCreate.
 */
internal object Seams {
    fun install(app: Application) {
        // A plugin's web sign-in follows the keyboard placement droidtop's own pages do (WebViewKeys, docs/SPEC.md 4c).
        dev.droidtop.pluginhost.WebSessionActivity.webViewSetup = { view ->
            WebViewKeys.attach(view)
            // No app-named X-Requested-With on a site's requests (Droidtop/tracker#600): XenForo, which F95zone runs,
            // reads that header to tell its own in-page requests, and its log-in overlay failed in this view.
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                androidx.webkit.WebSettingsCompat.setRequestedWithHeaderOriginAllowList(view.settings, emptySet())
            }
        }
        // droidtop's hidden web views run behind the screen in front, where a site's check finishes (Droidtop/tracker#783).
        ForegroundScreen.track(app)
        dev.droidtop.pluginhost.HiddenWebViews.window = { ForegroundScreen.content() }
        // What `host.info` tells a plugin about the mode droidtop is in (docs/plugin-api.md 3 J4).
        // One spelling for a mode wherever a plugin meets it (docs/plugin-api.md 1.9): `standard`, `gaming`, `desktop`.
        dev.droidtop.pluginhost.PluginBrokers.modeProvider = { dev.droidtop.library.settings.Modes.lastMode(app) }
        // Standard is always there (the Home button's screen); Gaming and Desktop when the person has them on.
        dev.droidtop.pluginhost.PluginBrokers.modesProvider = {
            val on = dev.droidtop.library.settings.Modes.enabled.map { it.id }.toSet() + dev.droidtop.pluginhost.PluginModes.STANDARD
            dev.droidtop.pluginhost.PluginModes.ALL.filter { it in on }
        }
        // A plugin's first-use permission sheet (docs/plugin-api.md 4.3): one window, drawn over whatever is on screen
        // in every mode, opened by the broker during a call the person started.
        dev.droidtop.pluginhost.PluginGrantPrompts.setSurface { PluginGrantSheetActivity.open(app) }
        // A plugin's secret facet, shown on droidtop's own sheet when the person presses Show (docs/plugin-api.md 1.10).
        dev.droidtop.pluginhost.PluginSecrets.setSurface { PluginSecretSheetActivity.open(app) }
        // The keyboard's "Follow light or dark" follows droidtop's Light or dark (docs/SPEC.md 7k, Droidtop/tracker#562),
        // and its panels and glide trail take droidtop's palette for that light or dark (Droidtop/tracker#772).
        org.pocketworkstation.pckeyboard.KeyboardAppearance.host = object : org.pocketworkstation.pckeyboard.KeyboardAppearance.Host {
            override fun light(context: android.content.Context) = dev.droidtop.library.settings.AppearancePrefs.light(context)

            override fun colors(context: android.content.Context, light: Boolean): org.pocketworkstation.pckeyboard.KeyboardColors {
                // The palette droidtop draws now (its theme's, when one is held) when it is the same light or dark,
                // else droidtop's own palette for the keyboard's light or dark.
                val live = dev.droidtop.shell.gamepad.GamingTheme.palette.colors
                val c = if (live.isLight == light) live else dev.droidtop.shell.gamepad.GamingPalette.defaultFor(light).colors
                return org.pocketworkstation.pckeyboard.KeyboardColors(
                    ground = c.ground,
                    panel = c.overlaySurface,
                    key = c.card,
                    keyFocused = c.cardFocused,
                    text = c.onSurface,
                    muted = c.onSurfaceMuted,
                    accent = c.accent,
                )
            }
        }
        // `library.read` `systems` (docs/plugin-api.md 3 A1): the broker runs on a binder thread, which may block here;
        // the answer comes from the library's in-memory index, never a folder walk.
        dev.droidtop.pluginhost.PluginBrokers.librarySystemsProvider = {
            kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                dev.droidtop.library.integrations.PluginLibraryRead.snapshot(app, LibraryCore.library(app))
            }
        }
        // `saves` and `library.read` `list_engine_games` (docs/plugin-api.md 3 A7a): an app's plugin asks where a game's
        // saves go and says where it saved, answered by the same save model droidtop's own Enginehost launch uses.
        dev.droidtop.pluginhost.PluginBrokers.saves = dev.droidtop.library.integrations.AppInterfaceSaves(
            app,
            library = { LibraryCore.library(app) },
            records = { LibraryCore.records(app) },
        )
        // `engines.cores` (docs/plugin-api.md 3 A7b, Droidtop/tracker#752): what Enginehost can run or get decides a game's
        // runner, so its list is asked at start, when its app connects through the door and when it says the list changed.
        dev.droidtop.pluginhost.PluginBrokers.enginesChanged = { pluginId ->
            dev.droidtop.library.integrations.EngineCoreList.changed(app, pluginId)
        }
        // A source's engine hint (docs/plugin-api.md 1.6, acquire reply `engine`) pins the placed download to that
        // engine before the library indexes it, and an update's saves are copied into its new version folder
        // (docs/SPEC.md 7g, "Getting an update"), with the version it follows offered for removal.
        // The guided key setup, opened in place from a game page's sign-in row (Droidtop/tracker#143).
        dev.droidtop.library.scraper.ScraperKeySetupLink.open = { context, service ->
            context.startActivity(ScraperKeySetupActivity.intent(context, service).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        // Debrid (Droidtop/tracker#638): a torrent job asks the person's accounts first, from the keys they entered.
        dev.droidtop.pluginhost.debrid.DebridStep.accounts = { context -> dev.droidtop.library.debrid.DebridAccounts.connected(context) }
        dev.droidtop.pluginhost.debrid.DebridStep.waitsForService = { context -> dev.droidtop.library.debrid.DebridAccounts.waits(context) }
        // And a source's link on a file hoster the account serves (Pixeldrain, Mega, ...), before the page opens.
        dev.droidtop.pluginhost.debrid.DebridLinks.accounts = { dev.droidtop.library.debrid.DebridAccounts.connected(app) }
        dev.droidtop.pluginhost.debrid.DebridLinks.waits = { dev.droidtop.library.debrid.DebridAccounts.waits(app) }
        // The person's own account on a file host (PixelDrain): its premium downloads are asked before any debrid account.
        dev.droidtop.pluginhost.hosts.HostLinks.accounts = { hostId ->
            dev.droidtop.library.scraper.ScraperKeyService.entries.firstOrNull { it.hostId == hostId }
                ?.read(app)?.values?.firstOrNull()?.takeIf { it.isNotBlank() }
        }
        dev.droidtop.pluginhost.debrid.DebridChoice.spreadMode = { dev.droidtop.library.debrid.DebridAccounts.spread(app) }
        dev.droidtop.pluginhost.debrid.DebridChoice.onKeyRefused = { id, message -> dev.droidtop.library.debrid.DebridAccounts.refused(app, id, message) }
        dev.droidtop.pluginhost.DownloadJobs.onPlaced = { context, placed, args ->
            dev.droidtop.library.integrations.AcquireEngineHint.apply(context, placed, args[dev.droidtop.library.integrations.AcquireEngineHint.KEY])
            listOfNotNull(
                dev.droidtop.library.integrations.UpdatePlacement.copySaves(placed, args),
                // The version it follows, offered for removal (Droidtop/tracker#624).
                dev.droidtop.library.integrations.UpdatePlacement.removalOffer(args),
            ).joinToString(". ").ifEmpty { null }
        }
        // `social.changed` (docs/plugin-api.md 3 C19): a social provider plugin says something changed, and the
        // social hub asks it again off the binder thread (SPEC "Social").
        dev.droidtop.pluginhost.PluginBrokers.socialChanged = { pluginId, change ->
            dev.droidtop.library.integrations.PluginSocialProviders.changed(app, pluginId, change)
        }
        // Fills library-core's PcGameRuntime seam, which is what makes the
        // WINE_PREFIX / LINUX_CONTAINER launch strategies real rather than
        // error() stubs. The session supplier is only for the native-Linux
        // half; Windows games go through the WineEngine seam and need
        // neither a desktop session nor root. It stays a supplier because
        // DesktopSessionService may still be connecting when this runs --
        // and, with Desktop disabled, never connects at all, which the
        // supplier already expresses as "no primary session".
        dev.droidtop.library.PcGameRuntimeRegistry.runtime =
            dev.droidtop.runtime.windows.DroidtopPcGameRuntime(
                context = app,
                primarySession = {
                    (DesktopSessionService.state.value as? DesktopSessionState.Connected)
                        ?.let { PrimaryContainerSession(it.runtime, it.container) }
                },
            )
        // Where a store game's Wine prefix is, for its cloud saves (docs/SPEC.md 7g, "Stores").
        dev.droidtop.library.stores.StoreSaves.locator = dev.droidtop.runtime.windows.DroidtopWinePrefixLocator
        // A screenshot taken during a game is filed under it too (Droidtop/tracker#596).
        dev.droidtop.runtime.Capture.fileUnderGame = { context, name, bytes -> dev.droidtop.library.GameScreenshots.save(context, name, bytes) }
        // Install on a GOG installer's row (docs/SPEC.md 7g, "Installers in a games folder"): the one install door.
        dev.droidtop.library.FolderInstalls.runner = { context, installer, title, onStatus ->
            dev.droidtop.runtime.windows.WindowsInstalls.install(context, installer, null, title, onStatus)
        }
    }
}
