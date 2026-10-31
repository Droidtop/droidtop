package dev.droidtop.app.settings

import android.content.Context
import android.content.Intent
import dev.droidtop.app.MainActivity
import dev.droidtop.app.LibraryCore
import dev.droidtop.app.update.AppSelfUpdate
import dev.droidtop.app.update.UpdateChecks
import dev.droidtop.app.update.UpdateKinds
import dev.droidtop.app.update.UpdateNow
import dev.droidtop.library.GameUpdates
import dev.droidtop.library.LibraryGrouping
import dev.droidtop.library.LibraryKinds
import dev.droidtop.library.format.ValueFormat
import dev.droidtop.library.integrations.AppCatalogs
import dev.droidtop.library.integrations.GetGamesEntry
import dev.droidtop.library.integrations.PluginCatalog
import dev.droidtop.library.integrations.PluginJobsScreen
import dev.droidtop.library.integrations.PluginRepoUpdates
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.ButtonRole
import dev.droidtop.library.settings.CatalogLayout
import dev.droidtop.library.settings.CatalogPage
import dev.droidtop.library.settings.PageButton
import dev.droidtop.library.settings.PageHeader
import dev.droidtop.library.settings.UpdatesWaiting
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
 * Updates: one first-class page for everything that has a newer version (docs/SPEC.md 10b "The Updates page",
 * Droidtop/tracker#792; the owner, 2026-10-30: "We need a cleaner and more uniform updates page that covers everything
 * (plugins, games, etc)"). Its header says when droidtop last checked; its bar is Check now and Update all; its views,
 * on the shell's strip (L1 and R1), are All and each kind that has updates, each with its count. Every update is one
 * row of one shape: its picture, its name, "installed → available · size · from where", and its state in the value
 * column (Update, Updating, Waiting, Failed · Retry), A starting it. It reads only what checks already recorded; Check
 * now is what asks. The page is its own: the Quick Menu's Downloads and Updates tab, the left menu (with its badge) and
 * the companion reach it, each saying how many are waiting ([UpdatesWaiting]); none draws its rows.
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
     * The Quick Menu's Downloads tab (Droidtop/tracker#749): the running and recent jobs, under one row that opens
     * Updates and says how many are waiting (the owner, 2026-10-30: "An updates link should be in the Downloads and
     * Updates tab. Not there directly, but a shortcut"). The updates themselves are only on their page.
     */
    fun quickMenuScreen() = CatalogScreen(
        id = PluginJobsScreen.QUICK_MENU_ID,
        title = "Downloads",
        subtitle = "What is downloading, and the way to Updates",
        groups = { context ->
            UpdatesWaiting.refresh?.invoke(context)
            listOf(PluginJobsScreen.updatesGroup()) + PluginJobsScreen.jobGroups()
        },
        live = kotlinx.coroutines.flow.merge(UpdatesWaiting.count, dev.droidtop.pluginhost.PluginJobsCenter.entries()),
    )

    /** The page, wherever it is opened: the left menu's Updates, a notification, Settings, the Quick Menu's row. */
    fun screen() = CatalogScreen(
        id = AppSettingsCatalogs.SCREEN_UPDATES,
        title = "Updates",
        subtitle = "What has a newer version",
        needsNetwork = true,
        layout = CatalogLayout.STRIP,
        // Off the main thread as a whole: the rows read preferences and what the slow kinds last read.
        groups = { context -> withContext(Dispatchers.IO) { page(context).asGroups() } },
        // Live (Droidtop/tracker#748): a kind that finished reading, an update that started or ended, each rebuilds
        // the rows at once.
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
     * Get games (Droidtop/tracker#749).
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

    /** The kinds the page's strip names after All, in this order. */
    internal enum class Kind(val label: String) { DROIDTOP("droidtop"), PLUGINS("Plugins"), APPS("Apps"), GAMES("Games") }

    /** Where one update stands, the value its row shows: one model for every kind. */
    internal enum class State(val label: String) {
        AVAILABLE("Update"),
        DOWNLOADING("Updating"),
        WAITING("Waiting"),
        FAILED("Failed · Retry"),
    }

    /** One row's state from what the page knows: running, queued by Update all, failed last time, else available. Pure. */
    internal fun stateOf(id: String, busy: Set<String>, waiting: Set<String>, failed: Set<String>): State = when (id) {
        in busy -> State.DOWNLOADING
        in waiting -> State.WAITING
        in failed -> State.FAILED
        else -> State.AVAILABLE
    }

    /** A row's one line: the version step, the size, where it comes from. Pure. */
    internal fun line(step: String, sizeBytes: Long?, from: String?): String = listOfNotNull(
        step,
        sizeBytes?.takeIf { it > 0 }?.let(ValueFormat::size),
        from?.trim()?.takeIf { it.isNotEmpty() }?.let { "from $it" },
    ).joinToString(" · ")

    /** The strip's views: All with every update, then each kind that has any, each titled with its count. Pure. */
    internal fun viewTitles(counts: Map<Kind, Int>): List<Pair<Kind?, String>> =
        listOf<Pair<Kind?, String>>(null to "All · ${counts.values.sum()}") +
            Kind.entries.filter { (counts[it] ?: 0) > 0 }.map { it to "${it.label} · ${counts[it]}" }

    /** How an update ended: its line, and whether it worked (a failed one is drawn as Failed · Retry). */
    private class Done(val line: String, val ok: Boolean)

    /**
     * One update. [install] runs it (Update, Retry and Update all); null for a row that opens a page instead (a folder
     * game's source) or explains why it cannot be installed ([own] draws those).
     */
    private class Update(
        val kind: Kind,
        val id: String,
        val title: String,
        val step: String,
        val sizeBytes: Long? = null,
        val from: String? = null,
        val iconFile: String? = null,
        val install: (suspend (Context, (String) -> Unit) -> String)? = null,
        val own: CatalogItem? = null,
    )

    /**
     * Where the rows come from. The quick ones (droidtop's own build, the plugins, the library's games) are read each
     * time the rows are; the slow one (the package manager's apps) is read in the background and kept, so the page shows
     * at once and it fills in its own rows when it is ready (Droidtop/tracker#748). Plugins are read the way Settings >
     * Plugins reads them, from the catalogs on file each time: a kept copy missed updates the Plugins list showed
     * (Droidtop/tracker#837). The trusted plugin repositories are the plugins' second source, read the same way from
     * what their last check found ([PluginRepoUpdates.pendingUpdates]).
     */
    private enum class Section(val kind: Kind, val checking: String) {
        APPS(Kind.APPS, "Checking your apps"),
    }

    /** Each slow section's last updates and when they were read. */
    private val kept = ConcurrentHashMap<Section, Pair<Long, List<Update>>>()
    private val reading: MutableSet<Section> = ConcurrentHashMap.newKeySet()

    /** Rows updating now, queued by Update all, and whose last update failed, by row id. */
    private val busy: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val waiting: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val failed: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val changes = MutableStateFlow(0)

    /** Check now's line, drawn in place at the top of the page: its live status while it runs, then what it found (#837). */
    @Volatile private var checkLine: String? = null
    @Volatile private var checkRunning = false
    private val scope = dev.droidtop.runtime.util.AppScopes.io

    /** Reads [section] in the background unless it is being read or was read within [FRESH_MS] ([force] reads it anyway). */
    private fun refresh(context: Context, section: Section, force: Boolean = false) {
        val at = kept[section]?.first
        if (!force && at != null && System.currentTimeMillis() - at < FRESH_MS) return
        if (!reading.add(section)) return
        val app = context.applicationContext
        scope.launch {
            try {
                // Stamped when the read starts: a check that ends while it runs leaves the kept rows older than the
                // check, so the next look reads them again.
                val startedAt = System.currentTimeMillis()
                val found = when (section) {
                    Section.APPS -> apps(app)
                }
                kept[section] = startedAt to found
            } catch (e: Exception) {
                android.util.Log.w("droidtop.Updates", "reading ${section.name.lowercase()} failed: ${e.message}")
            } finally {
                reading.remove(section)
                changes.value++
                publishCount(app)
            }
        }
    }

    /**
     * [install] for the row [id]: Updating while it runs, Failed · Retry if it did not work, and once it returns its
     * section is read again, so the row goes (or says what is left) at once.
     */
    private fun tracked(
        id: String,
        section: Section?,
        install: suspend (Context, (String) -> Unit) -> Done,
    ): suspend (Context, (String) -> Unit) -> String = { ctx, onStatus ->
        waiting.remove(id)
        failed.remove(id)
        busy.add(id)
        changes.value++
        try {
            val done = runCatching { install(ctx, onStatus) }.getOrElse { Done(it.message ?: "it failed", ok = false) }
            if (!done.ok) failed.add(id)
            done.line
        } finally {
            busy.remove(id)
            if (section != null) refresh(ctx, section, force = true) else changes.value++
        }
    }

    /** Every update known now, and the slow sections still being read. */
    private fun collect(context: Context): Pair<List<Update>, List<Section>> {
        val checkedAt = UpdateChecks.lastCheck(context) ?: 0L
        // A section read before the last check ended is read again: the check fetched newer catalogs.
        Section.entries.forEach { refresh(context, it, force = (kept[it]?.first ?: 0L) < checkedAt) }
        val slow = Section.entries.associateWith { kept[it]?.second }
        val updates = droidtop(context) + plugins(context) + slow[Section.APPS].orEmpty() + games(context)
        return updates to Section.entries.filter { slow[it] == null }
    }

    /** Lists the updates and publishes how many there are ([UpdatesWaiting]), for an entry drawn before the page. */
    fun publishCount(context: Context) {
        UpdatesWaiting.publish(collect(context).first.size)
    }

    /** One update's row: the one shape for every kind. */
    private fun rowOf(update: Update): CatalogItem {
        update.own?.let { return it }
        val state = stateOf(update.id, busy, waiting, failed)
        return AsyncActionItem(
            id = update.id,
            title = update.title,
            subtitle = line(update.step, update.sizeBytes, update.from),
            value = state.label,
            iconFile = update.iconFile,
            run = update.install ?: { _, _ -> "" },
        ).let { row -> if (state == State.DOWNLOADING) ActionItem(id = row.id, title = row.title, subtitle = row.subtitle, value = row.value, progress = -1f) else row }
    }

    private suspend fun page(context: Context): CatalogPage {
        // Opening the page checks again when the last check is old, so it shows what is out now (Droidtop/tracker#792).
        UpdateChecks.refreshIfStale(context)
        val (updates, unread) = collect(context)
        UpdatesWaiting.publish(updates.size)
        val failure = AppSelfUpdate.lastFailure(context)
        val checked = UpdateChecks.lastCheck(context)
        val lastLine = checked?.let { "Last checked " + ValueFormat.ago(System.currentTimeMillis(), it).replaceFirstChar { c -> c.lowercase() } }
        val installable = updates.mapNotNull { update -> update.install?.let { update.id to it } }
        val checkingRows = unread.associate { section ->
            section.kind to ActionItem(id = "updates_checking_${section.name.lowercase()}", title = section.checking, progress = -1f)
        }
        val counts = updates.groupingBy { it.kind }.eachCount()
        val views = viewTitles(counts).map { (kind, title) ->
            val shown = updates.filter { kind == null || it.kind == kind }
            CatalogGroup(
                id = "updates_view_${kind?.name?.lowercase() ?: "all"}",
                title = title,
                items = buildList {
                    if (kind == null) {
                        checkLine?.let { line ->
                            add(
                                if (checkRunning) {
                                    ActionItem(id = "updates_check_now", title = line, progress = -1f)
                                } else {
                                    TextBlockItem(id = "updates_check_now", text = line)
                                },
                            )
                        }
                    }
                    if (kind == null && updates.isEmpty() && checked != null && failure == null && unread.isEmpty()) {
                        add(TextBlockItem(id = "updates_none", text = "Everything is up to date."))
                    }
                    shown.forEach { add(rowOf(it)) }
                    checkingRows.filterKeys { kind == null || it == kind }.values.forEach(::add)
                    if (kind == null) add(settingsRow())
                },
            )
        }
        return CatalogPage(
            header = PageHeader(
                title = "Updates",
                byline = listOfNotNull(lastLine ?: "Not checked yet", failure?.let { "The last check of droidtop failed: $it" }).joinToString(". "),
            ),
            buttons = listOf(
                // Its progress and its answer are a line at the top of the page, in place: the press was easy to miss when
                // the answer was a flash, and it said nothing at all while the check it joined was stuck (#837).
                PageButton(
                    id = "updates_check",
                    label = "Check now",
                    role = ButtonRole.PRIMARY,
                    enabled = !checkRunning,
                    disabledReason = "Checking",
                ) { ctx ->
                    if (checkRunning) return@PageButton
                    checkRunning = true
                    checkLine = "Checking..."
                    changes.value++
                    scope.launch {
                        val said = try {
                            UpdateChecks.checkAll(ctx) { line ->
                                checkLine = line
                                changes.value++
                            }
                        } finally {
                            checkRunning = false
                        }
                        Section.entries.forEach { refresh(ctx, it, force = true) }
                        checkLine = said.ifBlank { "Checked" }
                        changes.value++
                    }
                },
                PageButton(
                    id = "updates_all",
                    label = "Update all",
                    enabled = installable.isNotEmpty(),
                    // A row that opens its source's page has nothing for Update all to install: the button says so,
                    // never "Nothing to update" beside rows (Droidtop/tracker#837).
                    disabledReason = if (updates.isEmpty()) "Nothing to update" else "Each one opens its own page",
                ) { ctx ->
                    // Each update is the row's own (tracked): the rest wait, each row changes as its own starts and ends.
                    waiting.addAll(installable.map { it.first })
                    changes.value++
                    scope.launch {
                        val said = try {
                            installable.map { (_, install) -> install(ctx) { } }.filter { it.isNotBlank() }.joinToString(". ")
                        } finally {
                            waiting.removeAll(installable.map { it.first }.toSet())
                            changes.value++
                        }
                        dev.droidtop.library.settings.Notices.flash(said.ifBlank { "Updated" }, context = ctx)
                    }
                },
            ),
            groups = views,
        )
    }

    // Gaming's section name for Get new games (GamingSection is internal to the shell; its names are the deep link's).
    private const val GAMING_NEW_GAMES = "NEW_GAMES"

    /** droidtop itself, while a check has seen a newer build: Update installs it (Android asks). */
    private fun droidtop(context: Context): List<Update> {
        val newer = AppSelfUpdate.newerSeenVersionName(context) ?: return emptyList()
        val id = "updates_droidtop"
        return listOf(
            Update(
                kind = Kind.DROIDTOP,
                id = id,
                title = "droidtop",
                step = GameUpdates.step(AppSelfUpdate.installedVersionName(context), newer),
                from = "droidtop's releases",
                install = tracked(id, null) { ctx, onStatus ->
                    val said = UpdateNow.startAndAwait(ctx, onStatus)
                    Done(said, ok = AppSelfUpdate.lastFailure(ctx) == null)
                },
            ),
        )
    }

    /**
     * Installed plugins with a newer version, from both of their sources as last read: a catalog (what Settings > Plugins
     * reads) and a trusted plugin repository (what its screen under Plugin repositories reads), Droidtop/tracker#837. A
     * plugin both offer is one row, the higher version's, the catalog's on a tie ([PluginRepoUpdates.pendingUpdates]).
     */
    private fun plugins(context: Context): List<Update> {
        val installed = PluginStore.installed(context)
        val userKeys = UserOriginKeys.load(UserOriginKeys.storeFile(context))
        val offers = PluginCatalog.offersFor(installed, PluginCatalog.listings(context), userKeys)
        val fromRepos = PluginRepoUpdates.pendingUpdates(
            context,
            installed,
            userKeys,
            catalogVersions = offers.associate { (record, offer) -> record.manifest.id to offer.release.version },
        ).associateBy { it.installed.manifest.id }
        val fromCatalogs = offers.filter { (record, _) -> record.manifest.id !in fromRepos }.associateBy { (record, _) -> record.manifest.id }
        return installed.mapNotNull { record ->
            val id = "updates_plugin_${record.manifest.id}"
            val label = record.manifest.label
            fromCatalogs[record.manifest.id]?.let { (_, offer) ->
                Update(
                    kind = Kind.PLUGINS,
                    id = id,
                    title = label,
                    step = GameUpdates.step(record.manifest.version, offer.release.version),
                    sizeBytes = offer.release.bundle.size,
                    from = if (offer.source.official) "droidtop's catalog" else "${offer.source.name}, unofficial",
                    iconFile = PluginStore.iconFile(context, record),
                    install = tracked(id, null) { ctx, onStatus ->
                        val outcome = PluginCatalog.installOutcome(ctx, offer.plugin, offer.release) { status -> onStatus("$label: $status") }
                        Done("$label: ${outcome.line}", outcome.installed)
                    },
                )
            } ?: fromRepos[record.manifest.id]?.let { update ->
                Update(
                    kind = Kind.PLUGINS,
                    id = id,
                    title = label,
                    step = GameUpdates.step(record.manifest.version, update.version),
                    sizeBytes = update.bundle.sizeBytes,
                    from = "${update.repo.repo} on GitHub",
                    iconFile = PluginStore.iconFile(context, record),
                    // The repository's own install, the one its screen's rows run: the latest release's bundle, verified again.
                    install = tracked(id, null) { ctx, onStatus ->
                        onStatus("$label: downloading...")
                        val outcome = withContext(Dispatchers.IO) { PluginRepoUpdates.installFromRepo(ctx, update.repo, update.bundle.assetName) }
                        Done(if (outcome.installed) outcome.line else "$label: ${outcome.line}", outcome.installed)
                    },
                )
            }
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
                    val id = "updates_app_${state.offer.id}"
                    Update(
                        kind = Kind.APPS,
                        id = id,
                        title = state.offer.name,
                        step = GameUpdates.step(state.installed.versionName ?: state.installed.versionCode.toString(), state.offer.version),
                        from = state.offer.source ?: state.pluginLabel,
                        install = tracked(id, Section.APPS) { ctx, onStatus -> updateApp(ctx, state, onStatus) },
                    )
                }
                // Android would refuse it, so it is said, not offered: Update explains instead of failing at install.
                is AppCatalogs.UpdateState.KeyDiffers -> Update(
                    kind = Kind.APPS,
                    id = "updates_app_${state.offer.id}",
                    title = state.offer.name,
                    step = state.offer.version,
                    own = AsyncActionItem(
                        id = "updates_app_${state.offer.id}",
                        title = state.offer.name,
                        subtitle = "${state.offer.version} from ${state.offer.source ?: state.pluginLabel} is signed by a different key than the installed app",
                        value = "Different key",
                        run = { _, _ -> "Android refuses an update signed by a different key. Uninstall the app first to install this version" },
                    ),
                )
            }
        }
    }

    private suspend fun updateApp(context: Context, state: AppCatalogs.UpdateState.Available, onStatus: (String) -> Unit): Done {
        val record = AppCatalogs.providers(context).firstOrNull { it.manifest.id == state.pluginId }
            ?: return Done("${state.offer.name}: ${state.pluginLabel} is no longer installed or allowed", ok = false)
        val result = AppCatalogs.install(context, record, state.offer.id, state.offer.versionCode, state.offer.name, onStatus)
        return if (result.ok) Done(result.values["summary"] ?: "${state.offer.name} updated", ok = true) else Done("${state.offer.name}: ${result.error ?: "it failed"}", ok = false)
    }

    /**
     * Games with a newer version: a store game its store named one for (Update starts the store's download in place, as
     * the automatic updates do), and a folder game whose linked source names one (its row opens the source's own
     * screen, with its downloads and changelog; droidtop does not replace a folder's files by itself).
     */
    private fun games(context: Context): List<Update> {
        val published = LibraryCore.library(context).backgroundScanState(LibraryKinds.GAMES).value ?: return emptyList()
        return LibraryGrouping.group(published)
            .mapNotNull { group -> group.displayEntry.let { entry -> entry.availableUpdate?.let { Triple(entry, it, group) } } }
            // One row per game: a game with a linked copy is one group, but its entry can appear in two (#745, #837).
            .distinctBy { it.first.id }
            .sortedBy { it.first.title.lowercase() }
            .take(MAX_GAME_ROWS)
            .map { (entry, version, group) ->
                val pc = entry.pcInfo
                // What is here: the store's installed build, else the newest version folder of the game.
                val installedVersion = pc?.installedVersion ?: group.game.defaultVersion?.version
                val id = "updates_game_${entry.id}"
                val path = pc?.installPath
                if (pc != null && pc.installed && path != null && GameUpdates.forStore(pc) != null) {
                    val key = pc.storeId ?: entry.id
                    Update(
                        kind = Kind.GAMES,
                        id = id,
                        title = entry.title,
                        step = GameUpdates.step(installedVersion, version),
                        sizeBytes = pc.sizeBytes,
                        from = "its store",
                        iconFile = entry.artworkUri?.toString(),
                        install = tracked(id, null) { ctx, _ ->
                            if (StoreAutoUpdates.startUpdate(ctx, key, entry.title, path, pc.sizeBytes)) {
                                Done("${entry.title}: updating. It is listed under Downloads", ok = true)
                            } else {
                                Done("${entry.title}: no store in this build updates it", ok = false)
                            }
                        },
                    )
                } else {
                    Update(
                        kind = Kind.GAMES,
                        id = id,
                        title = entry.title,
                        step = GameUpdates.step(installedVersion, version),
                        own = sourceUpdateRow(
                            { ctx -> LibraryCore.library(ctx) },
                            entry.id,
                            entry.title,
                            GameUpdates.step(installedVersion, version),
                        ),
                    )
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
        step: String,
    ): CatalogItem =
        NestedScreenItem(
            id = "updates_game_$entryId",
            title = title,
            // The one row line ("installed → available · from where"), and the state a row that opens a page has.
            subtitle = line(step, null, "its source: its downloads and changelog"),
            valueLabel = { "Open" },
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
