package dev.droidtop.app.settings

import android.content.Context
import dev.droidtop.app.LibraryCore
import dev.droidtop.app.update.AppSelfUpdate
import dev.droidtop.app.update.UpdateDownload
import dev.droidtop.app.update.UpdateNow
import dev.droidtop.library.GameUpdates
import dev.droidtop.library.LibraryGrouping
import dev.droidtop.library.LibraryKinds
import dev.droidtop.library.format.ValueFormat
import dev.droidtop.library.integrations.AppCatalogs
import dev.droidtop.library.integrations.PluginCatalog
import dev.droidtop.library.integrations.PluginCatalogSources
import dev.droidtop.library.integrations.PluginRepoUpdates
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.ChoiceItem
import dev.droidtop.library.settings.ChoiceOption
import dev.droidtop.library.settings.NestedScreenItem
import dev.droidtop.library.settings.TextBlockItem
import dev.droidtop.library.settings.ToggleItem
import dev.droidtop.library.stores.StoreAutoUpdates
import dev.droidtop.pluginhost.PluginStore
import dev.droidtop.pluginhost.UserOriginKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Updates: one list of everything that has a newer version, each with Update, and Update all above them (docs/SPEC.md
 * 10b "The Updates page", Droidtop/tracker#570). The owner, 2026-10-16: "the updates menu is fucky, it has a tab that
 * just always shows up to date. ... Updates should be a single tab".
 *
 * It is one group, so no surface draws it as tabs. Its rows: Check now (when the last check ran, or why it failed),
 * Update all, then one row per update (droidtop, plugins, Android apps, store games, games whose source names a newer
 * version), then the settings and Get apps. With nothing to update it says so once, after a check that worked. It
 * reads only what checks already recorded; Check now is what asks.
 */
object UpdatesPage {
    private const val PREFS = "updates_page"
    private const val KEY_LAST_CHECK = "last_check_ms"

    fun screen() = CatalogScreen(
        id = AppSettingsCatalogs.SCREEN_UPDATES,
        title = "Updates",
        subtitle = "What has a newer version",
        // Off the main thread as a whole: the rows read preferences, caches and the package manager.
        groups = { context -> withContext(Dispatchers.IO) { listOf(CatalogGroup(id = "updates", title = null, items = rows(context))) } },
    )

    /** One update: its row, and what Update all does for it (null where it opens a page instead of installing). */
    private class Update(val row: CatalogItem, val apply: (suspend (Context, (String) -> Unit) -> String)?)

    private fun lastCheck(context: Context): Long? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_CHECK, 0L).takeIf { it > 0 }

    private suspend fun rows(context: Context): List<CatalogItem> {
        val updates = droidtop(context) + plugins(context) + apps(context) + games(context)
        val failure = AppSelfUpdate.lastFailure(context)
        val checked = lastCheck(context)
        val lastLine = checked?.let { "Last checked " + ValueFormat.ago(System.currentTimeMillis(), it).replaceFirstChar { c -> c.lowercase() } }
        return buildList {
            add(
                AsyncActionItem(
                    id = "updates_check",
                    title = "Check now",
                    subtitle = listOfNotNull(lastLine ?: "Not checked yet", failure?.let { "The last check of droidtop failed: $it" })
                        .joinToString(". "),
                    value = when {
                        updates.isNotEmpty() -> "${updates.size} to update"
                        checked == null -> "Not checked"
                        failure != null -> "Check failed"
                        else -> "Up to date"
                    },
                    run = { ctx, onStatus -> checkAll(ctx, onStatus) },
                ),
            )
            val installable = updates.mapNotNull { it.apply }
            if (installable.size > 1) {
                add(
                    AsyncActionItem(
                        id = "updates_all",
                        title = "Update all",
                        subtitle = "Each update downloads and installs the way its own Update does, one after another",
                        value = installable.size.toString(),
                        run = { ctx, onStatus -> installable.map { it(ctx, onStatus) }.joinToString(". ") },
                    ),
                )
            }
            if (updates.isEmpty() && checked != null && failure == null) {
                add(TextBlockItem(id = "updates_none", text = "Everything is up to date."))
            }
            updates.forEach { add(it.row) }
            add(
                NestedScreenItem(
                    id = "updates_settings",
                    title = "Update settings",
                    subtitle = "How often droidtop checks, which builds it follows, and what updates without asking",
                    inline = settingsScreen(),
                ),
            )
            add(
                NestedScreenItem(
                    id = "updates_get_apps",
                    title = "Get apps",
                    subtitle = "Browse and install apps from the catalogs your plugins add, such as F-Droid repositories",
                    registryId = dev.droidtop.library.integrations.AppCatalogScreen.ID,
                ),
            )
        }
    }

    /**
     * Check now: every kind asks its source, then the list is read again. droidtop's own build is checked and recorded,
     * never installed here (its row's Update installs). Blocking network calls, off the main thread.
     */
    suspend fun checkAll(context: Context, onStatus: (String) -> Unit): String = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val lines = mutableListOf<String>()
        onStatus("Checking droidtop...")
        lines += AppSelfUpdate.check(app)
        onStatus("Checking the plugin catalogs...")
        PluginCatalogSources.all(app).forEach { source -> runCatching { PluginCatalog.refresh(app, source) } }
        // Plugins from trusted repositories install by themselves when that is on (Update settings).
        runCatching { PluginRepoUpdates.runDue(app, metered = false) }
        val providers = AppCatalogs.providers(app)
        if (providers.isNotEmpty()) onStatus("Checking the app catalogs...")
        providers.forEach { record -> runCatching { AppCatalogs.refresh(app, record, null) {} } }
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        lines.joinToString(". ")
    }

    private fun available(version: String): String = "$version available"

    /** droidtop itself, while a check has seen a newer build: Update installs it (Android asks). */
    private fun droidtop(context: Context): List<Update> {
        val newer = AppSelfUpdate.newerSeenVersionName(context) ?: return emptyList()
        val updating = UpdateDownload.liveLine()
        val install: suspend (Context, (String) -> Unit) -> String = { ctx, onStatus -> UpdateNow.startAndAwait(ctx, onStatus) }
        return listOf(
            Update(
                AsyncActionItem(
                    id = "updates_droidtop",
                    title = "droidtop",
                    subtitle = "Installed ${AppSelfUpdate.installedVersionName(context)}. Android asks you to confirm the install",
                    value = updating ?: available(newer),
                    run = install,
                ),
                install,
            ),
        )
    }

    /** Installed plugins with a newer release in a catalog, as last fetched. */
    private fun plugins(context: Context): List<Update> {
        val installed = PluginStore.installed(context)
        val offers = PluginCatalog.offersFor(installed, PluginCatalog.listings(context), UserOriginKeys.load(UserOriginKeys.storeFile(context)))
        return offers.map { (record, offer) ->
            val install: suspend (Context, (String) -> Unit) -> String = { ctx, onStatus ->
                val outcome = PluginCatalog.installOutcome(ctx, offer.plugin, offer.release) { status -> onStatus("${record.manifest.label}: $status") }
                "${record.manifest.label}: ${outcome.line}"
            }
            Update(
                AsyncActionItem(
                    id = "updates_plugin_${record.manifest.id}",
                    title = record.manifest.label,
                    subtitle = "Plugin, installed ${record.manifest.version}" + if (offer.source.official) "" else ". Unofficial, from ${offer.source.name}",
                    value = available(offer.release.version),
                    run = install,
                ),
                install,
            )
        }
    }

    /** Android apps (emulators among them) with a newer version in an app catalog, as last fetched. */
    private fun apps(context: Context): List<Update> {
        val cached = AppCatalogs.cachedOffers(context)
        if (cached.isEmpty()) return emptyList()
        val states = AppCatalogs.updates(cached, dev.droidtop.library.integrations.AppPackages.allInstalledFacts(context), context.packageName)
        return states.take(MAX_APP_ROWS).map { state ->
            when (state) {
                is AppCatalogs.UpdateState.Available -> {
                    val install: suspend (Context, (String) -> Unit) -> String = { ctx, onStatus -> updateApp(ctx, state, onStatus) }
                    Update(
                        AsyncActionItem(
                            id = "updates_app_${state.offer.id}",
                            title = state.offer.name,
                            subtitle = "App, installed ${state.installed.versionName ?: state.installed.versionCode}. From ${state.offer.source ?: state.pluginLabel}",
                            value = available(state.offer.version),
                            run = install,
                        ),
                        install,
                    )
                }
                // Android would refuse it, so it is said, not offered: Update explains instead of failing at install.
                is AppCatalogs.UpdateState.KeyDiffers -> Update(
                    AsyncActionItem(
                        id = "updates_app_${state.offer.id}",
                        title = state.offer.name,
                        subtitle = "${state.offer.version} from ${state.offer.source ?: state.pluginLabel} is signed by a different key than the installed app",
                        value = "Different key",
                        run = { _, _ -> "Android refuses an update signed by a different key. Uninstall the app first to install this version" },
                    ),
                    null,
                )
            }
        }
    }

    private suspend fun updateApp(context: Context, state: AppCatalogs.UpdateState.Available, onStatus: (String) -> Unit): String {
        val record = AppCatalogs.providers(context).firstOrNull { it.manifest.id == state.pluginId }
            ?: return "${state.offer.name}: ${state.pluginLabel} is no longer installed or allowed"
        val result = AppCatalogs.install(context, record, state.offer.id, state.offer.versionCode, state.offer.name, onStatus)
        return if (result.ok) result.values["summary"] ?: "${state.offer.name} updated" else "${state.offer.name}: ${result.error ?: "it failed"}"
    }

    /**
     * Games with a newer version: a store game its store named one for (Update starts the store's download in place, as
     * the automatic updates do), and a folder game whose linked source names one (Update opens its page, where the
     * source's Get is; droidtop does not replace a folder's files by itself).
     */
    private fun games(context: Context): List<Update> {
        val published = LibraryCore.library(context).backgroundScanState(LibraryKinds.GAMES).value ?: return emptyList()
        return LibraryGrouping.group(published)
            .mapNotNull { group -> group.displayEntry.let { entry -> entry.availableUpdate?.let { entry to it } } }
            .sortedBy { it.first.title.lowercase() }
            .take(MAX_GAME_ROWS)
            .map { (entry, version) ->
                val pc = entry.pcInfo
                val path = pc?.installPath
                if (pc != null && pc.installed && path != null && GameUpdates.forStore(pc) != null) {
                    val key = pc.storeId ?: entry.id
                    val install: suspend (Context, (String) -> Unit) -> String = { ctx, _ ->
                        if (StoreAutoUpdates.startUpdate(ctx, key, entry.title, path, pc.sizeBytes)) {
                            "${entry.title}: updating. It is listed under Downloads"
                        } else {
                            "${entry.title}: no store in this build updates it"
                        }
                    }
                    Update(
                        AsyncActionItem(
                            id = "updates_game_${entry.id}",
                            title = entry.title,
                            subtitle = "Game, from its store",
                            value = GameUpdates.line(version),
                            run = install,
                        ),
                        install,
                    )
                } else {
                    Update(
                        AsyncActionItem(
                            id = "updates_game_${entry.id}",
                            title = entry.title,
                            subtitle = "Game. Its source has a newer version; its page has the source's Get",
                            value = GameUpdates.line(version),
                            run = { ctx, _ ->
                                ctx.startActivity(dev.droidtop.app.LauncherGamesActivity.pageIntent(ctx, entry.id))
                                "Opened ${entry.title}'s page"
                            },
                        ),
                        null,
                    )
                }
            }
    }

    /** droidtop's own update settings and the apps' install-without-asking, one screen under the list. */
    private fun settingsScreen() = CatalogScreen(
        id = "updates_settings",
        title = "Update settings",
        groups = { context ->
            withContext(Dispatchers.IO) {
                val update = AppSelfUpdate
                listOf(
                    CatalogGroup(
                        id = "updates_settings_main",
                        title = null,
                        items = listOf(
                            ChoiceItem(
                                id = "updates_frequency",
                                title = "Check for updates",
                                subtitle = "Fetches one small file describing the latest build; nothing about this device or your " +
                                    "library is sent. Picking Never stops the automatic checks; Check now still works",
                                options = AppSelfUpdate.Frequency.entries.map { ChoiceOption(it.name, it.label) },
                                current = update.frequency(context).name,
                                onSelect = { ctx, value -> update.setFrequency(ctx, AppSelfUpdate.Frequency.valueOf(value)) },
                            ),
                            ToggleItem(
                                id = "updates_unmetered_only",
                                title = "Only on Wi-Fi and other unmetered networks",
                                subtitle = "Skips the scheduled check while on mobile data or a metered connection",
                                current = update.unmeteredOnly(context),
                                onToggle = { ctx, value -> update.setUnmeteredOnly(ctx, value) },
                            ),
                            ChoiceItem(
                                id = "updates_channel",
                                title = "Build channel",
                                subtitle = "Which builds this device follows. Unstable gets every new build; the others get a " +
                                    "build once it has been promoted to them",
                                options = AppSelfUpdate.Channel.entries.map { ChoiceOption(it.name, it.label) },
                                current = update.channel(context).name,
                                onSelect = { ctx, value -> update.setChannel(ctx, AppSelfUpdate.Channel.valueOf(value)) },
                            ),
                            ToggleItem(
                                id = "updates_debug_builds",
                                title = "Install debug builds",
                                // A device can be ON a debug build while this stays Off: both variants are signed with the same
                                // key, so Check now finds and installs the faster release build either way.
                                subtitle = when {
                                    update.debugBuilds(context) ->
                                        "Debug builds run several times slower. Turn this off and check again to go back"
                                    AppSettingsCatalogs.ctxIsDebuggable(context) ->
                                        "Currently running a debug build. Update installs the faster release build with this off"
                                    else -> "Much slower builds for debugging droidtop itself. Leave this off to play"
                                },
                                current = update.debugBuilds(context),
                                onToggle = { ctx, value -> update.setDebugBuilds(ctx, value) },
                            ),
                            ToggleItem(
                                id = "updates_apps_silent",
                                title = "Update apps without asking",
                                subtitle = "Where Android allows it (Android 12 and later, for apps droidtop installed). Elsewhere Android asks each time",
                                current = dev.droidtop.app.apps.ApkInstaller.silentUpdates(context),
                                onToggle = { ctx, value -> dev.droidtop.app.apps.ApkInstaller.setSilentUpdates(ctx, value) },
                            ),
                        ),
                    ),
                )
            }
        },
    )

    private const val MAX_APP_ROWS = 50
    private const val MAX_GAME_ROWS = 30
}
