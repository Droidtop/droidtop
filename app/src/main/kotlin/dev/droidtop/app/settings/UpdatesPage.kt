package dev.droidtop.app.settings

import android.content.Context
import android.content.Intent
import dev.droidtop.app.MainActivity
import dev.droidtop.app.LibraryCore
import dev.droidtop.app.update.AppSelfUpdate
import dev.droidtop.app.update.UpdateChecks
import dev.droidtop.app.update.UpdateKinds
import dev.droidtop.app.update.UpdateDownload
import dev.droidtop.app.update.UpdateNow
import dev.droidtop.library.GameUpdates
import dev.droidtop.library.LibraryGrouping
import dev.droidtop.library.LibraryKinds
import dev.droidtop.library.format.ValueFormat
import dev.droidtop.library.integrations.AppCatalogs
import dev.droidtop.library.integrations.GetGamesEntry
import dev.droidtop.library.integrations.PluginCatalog
import dev.droidtop.library.integrations.PluginJobsScreen
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.Mode
import dev.droidtop.library.settings.Modes
import dev.droidtop.library.settings.ChoiceItem
import dev.droidtop.library.settings.ChoiceOption
import dev.droidtop.library.settings.NestedScreenItem
import dev.droidtop.library.settings.TextBlockItem
import dev.droidtop.library.settings.ToggleItem
import dev.droidtop.library.stores.StoreAutoUpdates
import dev.droidtop.pluginhost.PluginStore
import dev.droidtop.pluginhost.UserOriginKeys
import dev.droidtop.shell.standard.BackButtonMenu
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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
    /** Install debug builds: for finding faults in droidtop itself, so it is in the Developer menu (docs/SPEC.md 7, "Developer menu"). */
    fun debugBuildsItem(context: android.content.Context): CatalogItem {
        val update = AppSelfUpdate
        return ToggleItem(
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
        )
    }

    /**
     * The Quick Menu's Downloads tab (owner, 2026-10-29, Droidtop/tracker#749: "This is supposed to be a tab like the
     * steam downloads tab, with actually initializing updates and stuff handled in the QAM"): Check now, Update all,
     * every game and plugin with an update, then the running and recent jobs, then Update settings. A started update is
     * a job, so it shows here and in Downloads and Updates alike.
     */
    fun quickMenuScreen() = screen(PluginJobsScreen.QUICK_MENU_ID, "Downloads")

    /**
     * The same, for a link to Updates where no Quick Menu is drawn (a notification opened outside Gaming, Settings in
     * Standard or Desktop): one builder, so the two never differ.
     */
    fun screen() = screen(AppSettingsCatalogs.SCREEN_UPDATES, "Updates")

    private fun screen(id: String, title: String) = CatalogScreen(
        id = id,
        title = title,
        subtitle = "What has a newer version, and what is downloading",
        needsNetwork = true,
        // Off the main thread as a whole: the rows read preferences and what the slow kinds last read.
        groups = { context ->
            withContext(Dispatchers.IO) {
                listOf(CatalogGroup(id = "updates", title = null, items = rows(context))) +
                    PluginJobsScreen.jobGroups() +
                    CatalogGroup(id = "updates_more", title = null, items = listOf(settingsRow()))
            }
        },
        // Live (Droidtop/tracker#748, the owner: "It not auto-refreshing is a ux bug"): a kind that finished reading,
        // an update that started or finished, a job that moved, each rebuilds the rows at once, so no row waits for a
        // whole Update all.
        live = kotlinx.coroutines.flow.merge(changes, dev.droidtop.pluginhost.PluginJobsCenter.entries()),
    )

    private fun settingsRow() = NestedScreenItem(
        id = "updates_settings",
        title = "Update settings",
        subtitle = "How often droidtop checks, which builds it follows, and what updates without asking",
        inline = settingsScreen(),
    )

    /**
     * Get new games, in Gaming only: the page is Gaming's, which opens it in place by the row's id; in another mode the
     * row is not listed, since it would open Gaming over that mode (Droidtop/tracker#639). Under Downloads and Updates'
     * Get games since updates moved to the Quick Menu (Droidtop/tracker#749).
     */
    fun getNewGamesRow(context: Context): CatalogItem? {
        if (Modes.lastMode(context) != Mode.GAMING.id) return null
        return ActionItem(
            id = GetGamesEntry.UPDATES_ROW_ID,
            title = GetGamesEntry.NEW_GAMES,
            subtitle = "Search the sources your plugins add for games to download",
            run = { ctx ->
                ctx.startActivity(
                    Intent(ctx, MainActivity::class.java)
                        .putExtra(BackButtonMenu.EXTRA_MODE, Mode.GAMING.id)
                        .putExtra(BackButtonMenu.EXTRA_GAMING_START_SECTION, GAMING_NEW_GAMES)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
        )
    }

    /** One update: its row, and what Update all does for it (null where it opens a page instead of installing). */
    private class Update(val row: CatalogItem, val apply: (suspend (Context, (String) -> Unit) -> String)?)

    /**
     * Where the rows come from. The quick ones (droidtop's own build, the library's games) are read each time the rows
     * are; the slow ones (the plugin catalogs, the package manager's apps) are read in the background and kept, so the
     * page shows at once and a slow section fills in its own rows when it is ready (Droidtop/tracker#748: the page took
     * about 10 s to show anything).
     */
    private enum class Section(val checking: String) { PLUGINS("Checking your plugins"), APPS("Checking your apps") }

    /** Each slow section's last rows and when they were read. */
    private val kept = ConcurrentHashMap<Section, Pair<Long, List<Update>>>()
    private val reading: MutableSet<Section> = ConcurrentHashMap.newKeySet()

    /** The rows being updated now, by row id: drawn as Updating with the moving bar until their update returns. */
    private val busy: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val changes = MutableStateFlow(0)
    private val scope = dev.droidtop.runtime.util.AppScopes.io

    /** Reads [section] in the background unless it is being read or was read within [FRESH_MS] ([force] reads it anyway). */
    private fun refresh(context: Context, section: Section, force: Boolean = false) {
        val at = kept[section]?.first
        if (!force && at != null && System.currentTimeMillis() - at < FRESH_MS) return
        if (!reading.add(section)) return
        val app = context.applicationContext
        scope.launch {
            try {
                val rows = when (section) {
                    Section.PLUGINS -> plugins(app)
                    Section.APPS -> apps(app)
                }
                kept[section] = System.currentTimeMillis() to rows
            } catch (e: Exception) {
                android.util.Log.w("droidtop.Updates", "reading ${section.name.lowercase()} failed: ${e.message}")
            } finally {
                reading.remove(section)
                changes.value++
            }
        }
    }

    /**
     * [install] for the row [id] of [section]: the row says Updating with the moving bar while it runs, and once it
     * returns its section is read again, so the row goes (or says what is left) at once.
     */
    private fun tracked(
        id: String,
        section: Section?,
        install: suspend (Context, (String) -> Unit) -> String,
    ): suspend (Context, (String) -> Unit) -> String = { ctx, onStatus ->
        busy.add(id)
        changes.value++
        try {
            install(ctx, onStatus)
        } finally {
            busy.remove(id)
            if (section != null) refresh(ctx, section, force = true) else changes.value++
        }
    }

    /** [update]'s row as drawn now: Updating, with the bar, while its update runs (show, then say: Droidtop/tracker#738). */
    private fun shown(update: Update): CatalogItem {
        val row = update.row
        if (row.id !in busy) return row
        return ActionItem(id = row.id, title = row.title, subtitle = row.subtitle, value = "Updating", progress = -1f)
    }

    private suspend fun rows(context: Context): List<CatalogItem> {
        // Opening the list checks again when the last check is old, so it shows what is out now (Droidtop/tracker#792).
        UpdateChecks.refreshIfStale(context)
        // A section read before the last check ended is read again: the check fetched newer catalogs.
        val checkedAt = UpdateChecks.lastCheck(context) ?: 0L
        Section.entries.forEach { refresh(context, it, force = (kept[it]?.first ?: 0L) < checkedAt) }
        val slow = Section.entries.associateWith { kept[it]?.second }
        val updates = droidtop(context) + slow[Section.PLUGINS].orEmpty() + slow[Section.APPS].orEmpty() + games(context)
        // A section not read yet is one row of its own, loading; the rest of the page does not wait for it.
        val checking = Section.entries.filter { slow[it] == null }.map { section ->
            ActionItem(id = "updates_checking_${section.name.lowercase()}", title = section.checking, progress = -1f)
        }
        val failure = AppSelfUpdate.lastFailure(context)
        val checked = UpdateChecks.lastCheck(context)
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
                    run = { ctx, onStatus ->
                        UpdateChecks.checkAll(ctx, onStatus).also { Section.entries.forEach { refresh(ctx, it, force = true) } }
                    },
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
                        // Each update is the row's own (tracked), so each row changes as its own update starts and ends.
                        run = { ctx, onStatus -> installable.map { it(ctx, onStatus) }.joinToString(". ") },
                    ),
                )
            }
            if (updates.isEmpty() && checked != null && failure == null) {
                add(TextBlockItem(id = "updates_none", text = "Everything is up to date."))
            }
            updates.forEach { add(shown(it)) }
            addAll(checking)
        }
    }

    // Every row names both versions in words, the available one in the value column (Droidtop/tracker#639).
    private fun available(version: String): String = GameUpdates.availableLabel(version)

    // Gaming's section name for Get new games (GamingSection is internal to the shell; its names are the deep link's).
    private const val GAMING_NEW_GAMES = "NEW_GAMES"

    /** droidtop itself, while a check has seen a newer build: Update installs it (Android asks). */
    private fun droidtop(context: Context): List<Update> {
        val newer = AppSelfUpdate.newerSeenVersionName(context) ?: return emptyList()
        val updating = UpdateDownload.liveLine()
        val install = tracked("updates_droidtop", null) { ctx, onStatus -> UpdateNow.startAndAwait(ctx, onStatus) }
        return listOf(
            Update(
                AsyncActionItem(
                    id = "updates_droidtop",
                    title = "droidtop",
                    subtitle = "${GameUpdates.installedLabel(AppSelfUpdate.installedVersionName(context))}. Android asks you to confirm the install",
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
            val install = tracked("updates_plugin_${record.manifest.id}", Section.PLUGINS) { ctx, onStatus ->
                val outcome = PluginCatalog.installOutcome(ctx, offer.plugin, offer.release) { status -> onStatus("${record.manifest.label}: $status") }
                "${record.manifest.label}: ${outcome.line}"
            }
            Update(
                AsyncActionItem(
                    id = "updates_plugin_${record.manifest.id}",
                    title = record.manifest.label,
                    subtitle = "${GameUpdates.installedLabel(record.manifest.version)}. Plugin" + if (offer.source.official) "" else ", unofficial, from ${offer.source.name}",
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
                    val install = tracked("updates_app_${state.offer.id}", Section.APPS) { ctx, onStatus -> updateApp(ctx, state, onStatus) }
                    Update(
                        AsyncActionItem(
                            id = "updates_app_${state.offer.id}",
                            title = state.offer.name,
                            subtitle = "${GameUpdates.installedLabel(state.installed.versionName ?: state.installed.versionCode.toString())}. App, from ${state.offer.source ?: state.pluginLabel}",
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
            .mapNotNull { group -> group.displayEntry.let { entry -> entry.availableUpdate?.let { Triple(entry, it, group) } } }
            .sortedBy { it.first.title.lowercase() }
            .take(MAX_GAME_ROWS)
            .map { (entry, version, group) ->
                val pc = entry.pcInfo
                // What is here: the store's installed build, else the newest version folder of the game.
                val installed = GameUpdates.installedLabel(pc?.installedVersion ?: group.game.defaultVersion?.version)
                val path = pc?.installPath
                if (pc != null && pc.installed && path != null && GameUpdates.forStore(pc) != null) {
                    val key = pc.storeId ?: entry.id
                    val install = tracked("updates_game_${entry.id}", null) { ctx, _ ->
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
                            subtitle = "$installed. Game, from its store",
                            value = available(version),
                            run = install,
                        ),
                        install,
                    )
                } else {
                    Update(sourceUpdateRow({ ctx -> LibraryCore.library(ctx) }, entry.id, entry.title, installed, available(version)), null)
                }
            }
    }

    /**
     * A folder game whose linked source names a newer version: the update's own screen, pushed in place by whatever
     * draws Updates (owner, 2026-10-24, Droidtop/tracker#639: "Clicking the update should present the facet containing
     * the downloads"; "Facets go where they fit. No clunky redirects"). It is a nested screen, so A has nothing to run:
     * no activity starts and no game page opens from this row, in any mode.
     */
    internal fun sourceUpdateRow(
        library: (Context) -> dev.droidtop.library.Library,
        entryId: String,
        title: String,
        installed: String,
        available: String,
    ): CatalogItem =
        NestedScreenItem(
            id = "updates_game_$entryId",
            title = title,
            subtitle = "$installed. Game: its downloads, changelog and Get",
            valueLabel = { available },
            inline = dev.droidtop.shell.gamepad.pc.SourceRecordScreen.forUpdate(library, entryId, title),
        )

    /** droidtop's own update settings and the apps' install-without-asking, one screen under the list. */
    private fun settingsScreen() = CatalogScreen(
        id = "updates_settings",
        title = "Update settings",
        groups = { context ->
            withContext(Dispatchers.IO) {
                val update = AppSelfUpdate
                listOf(
                    // What each kind does once a check finds an update (Droidtop/tracker#570).
                    CatalogGroup(
                        id = "updates_settings_kinds",
                        title = null,
                        items = UpdateKinds.Kind.entries.map { kind ->
                            ChoiceItem(
                                id = "updates_policy_${kind.name.lowercase()}",
                                title = kind.label,
                                subtitle = when (kind) {
                                    UpdateKinds.Kind.DROIDTOP -> "Installing droidtop always ends with Android asking you to confirm"
                                    UpdateKinds.Kind.PLUGINS -> "An update that asks for more access still waits for your approval"
                                    UpdateKinds.Kind.APPS -> "Android may still ask, unless updating apps without asking is on below"
                                    UpdateKinds.Kind.GAMES -> "Follows Download rules: the network, the update window, and not while playing"
                                },
                                options = UpdateKinds.Policy.entries.map { ChoiceOption(it.name, it.label) },
                                current = UpdateKinds.policy(context, kind).name,
                                onSelect = { ctx, value -> UpdateKinds.setPolicy(ctx, kind, UpdateKinds.Policy.valueOf(value)) },
                            )
                        },
                    ),
                    CatalogGroup(
                        id = "updates_settings_main",
                        title = null,
                        items = listOf(
                            ChoiceItem(
                                id = "updates_frequency",
                                title = "Check for updates",
                                subtitle = "How often droidtop, the catalogs and the stores are checked. Nothing about you is sent",
                                options = AppSelfUpdate.Frequency.entries.map { ChoiceOption(it.name, it.label) },
                                current = update.frequency(context).name,
                                onSelect = { ctx, value ->
                                    update.setFrequency(ctx, AppSelfUpdate.Frequency.valueOf(value))
                                    UpdateChecks.schedule(ctx)
                                },
                            ),
                            ToggleItem(
                                id = "updates_unmetered_only",
                                title = "Only on Wi-Fi or unmetered networks",
                                subtitle = "Skips the scheduled check while on mobile data or a metered connection",
                                current = update.unmeteredOnly(context),
                                onToggle = { ctx, value ->
                                    update.setUnmeteredOnly(ctx, value)
                                    UpdateChecks.schedule(ctx)
                                },
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
                                id = "updates_apps_silent",
                                title = "Update apps without asking",
                                subtitle = "On Android 12 and later, for apps droidtop installed. Elsewhere Android asks each time",
                                current = dev.droidtop.app.apps.ApkInstaller.silentUpdates(context),
                                onToggle = { ctx, value -> dev.droidtop.app.apps.ApkInstaller.setSilentUpdates(ctx, value) },
                            ),
                        ),
                    ),
                )
            }
        },
    )

    /** How long a slow section's rows stand before opening the page reads them again. */
    private const val FRESH_MS = 30_000L

    private const val MAX_APP_ROWS = 50
    private const val MAX_GAME_ROWS = 30
}
