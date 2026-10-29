package dev.droidtop.app.settings

import dev.droidtop.runtime.systemstatus.SettingsLaunch
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.droidtop.app.GamesRootPrefs
import dev.droidtop.app.LibraryCore
import dev.droidtop.app.PluginStatusWidgetProvider
import dev.droidtop.app.ScraperKeySetupActivity
import dev.droidtop.library.scraper.importGamelistXml
import dev.droidtop.library.scraper.LibraryScrapeJob
import dev.droidtop.library.consoles.ConsoleSystemDef
import dev.droidtop.library.consoles.canResolveFromFolder
import dev.droidtop.library.consoles.ConsoleSystemEntity
import dev.droidtop.library.consoles.ConsoleSystemsDatabase
import dev.droidtop.library.consoles.ConsoleSystemsRepository
import dev.droidtop.library.consoles.PlatformDatabaseSnapshot
import dev.droidtop.library.consoles.PlatformDatabaseSource
import dev.droidtop.library.consoles.PlatformDatabases
import dev.droidtop.library.consoles.SystemFolders
import dev.droidtop.library.consoles.SystemOverridePrefs
import dev.droidtop.library.consoles.BiosDatabase
import dev.droidtop.library.consoles.KnownPlayers
import dev.droidtop.library.consoles.SystemBiosSpec
import dev.droidtop.library.consoles.availablePlayers
import dev.droidtop.library.integrations.IntegrationCapability
import dev.droidtop.library.integrations.IntegrationPlaceholders
import dev.droidtop.library.integrations.IntegrationStore
import dev.droidtop.library.integrations.AcquireContentSources
import dev.droidtop.library.integrations.PluginAppStatus
import dev.droidtop.library.integrations.PluginCatalog
import dev.droidtop.library.integrations.PluginCatalogScreen
import dev.droidtop.library.integrations.PluginBackground
import dev.droidtop.library.integrations.PluginFacets
import dev.droidtop.library.integrations.PluginPanels
import dev.droidtop.library.integrations.PluginSettingsRows
import dev.droidtop.library.integrations.PluginJobsScreen
import dev.droidtop.library.GameUpdates
import dev.droidtop.library.LibraryGrouping
import dev.droidtop.library.LibraryKinds
import dev.droidtop.library.PcFolderScan
import dev.droidtop.library.GameEngineDetector
import dev.droidtop.library.EnginesDatabase
import dev.droidtop.library.ScanPrune
import dev.droidtop.pluginhost.PluginStore
import dev.droidtop.pluginhost.PluginMainUi
import dev.droidtop.pluginhost.ApiResolution
import dev.droidtop.pluginhost.GrantState
import dev.droidtop.pluginhost.PluginApiResolver
import dev.droidtop.pluginhost.PluginAudit
import dev.droidtop.pluginhost.PluginGrants
import dev.droidtop.pluginhost.PluginPermissions
import dev.droidtop.pluginhost.PluginModes
import dev.droidtop.pluginhost.BackgroundProtocol
import dev.droidtop.pluginhost.PluginProviderChoices
import dev.droidtop.pluginhost.PluginKind
import dev.droidtop.pluginhost.PluginTrustState
import dev.droidtop.pluginhost.PluginCapability
import dev.droidtop.pluginhost.PluginConsent
import dev.droidtop.pluginhost.PermissionTier
import dev.droidtop.pluginhost.ExtensionPoints
import dev.droidtop.pluginhost.PluginCrashPolicy
import dev.droidtop.pluginhost.PluginTier
import dev.droidtop.pluginhost.PluginTiers
import dev.droidtop.pluginhost.PluginDangers
import dev.droidtop.pluginhost.PluginOriginKeys
import dev.droidtop.pluginhost.PluginSourceKeys
import dev.droidtop.pluginhost.PluginRuntimeNeeds
import dev.droidtop.pluginhost.PythonRuntimeManager
import dev.droidtop.pluginhost.RuntimeNeed
import dev.droidtop.pluginhost.FlutterRuntimeManager
import dev.droidtop.pluginhost.UserOriginKey
import dev.droidtop.pluginhost.UserOriginKeys
import dev.droidtop.pluginhost.AddKeyOutcome
import dev.droidtop.library.consoles.resolvePlayer
import dev.droidtop.library.scraper.ScraperKeyCheck
import dev.droidtop.library.gameinfo.GameInfoPrefs
import dev.droidtop.library.scraper.ScraperKeyService
import dev.droidtop.library.scraper.ScraperSource
import dev.droidtop.library.scraper.ScraperSourcePrefs
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.ButtonRole
import dev.droidtop.library.settings.CatalogChip
import dev.droidtop.library.settings.CatalogPage
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.DeveloperMenu
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.ChoiceItem
import dev.droidtop.library.settings.ChoiceOption
import dev.droidtop.library.settings.DocumentPickItem
import dev.droidtop.library.settings.FolderPickItem
import dev.droidtop.library.settings.CatalogIcon
import dev.droidtop.library.settings.GamingSettingsCatalog
import dev.droidtop.library.settings.NestedScreenItem
import dev.droidtop.library.settings.Notice
import dev.droidtop.library.settings.NoticeAction
import dev.droidtop.library.settings.NoticeItem
import dev.droidtop.library.settings.NoticePlacement
import dev.droidtop.library.settings.NoticeTone
import dev.droidtop.library.settings.Notices
import dev.droidtop.library.settings.PageButton
import dev.droidtop.library.settings.PageHeader
import dev.droidtop.library.settings.SettingsScreenRegistry
import dev.droidtop.library.settings.TextBlockItem
import dev.droidtop.library.settings.TextInputItem
import dev.droidtop.library.settings.ToggleItem
import dev.droidtop.library.theme.SystemThemeColors
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The plugin approval/management screen (docs/SPEC.md 12a). Every
 * mutation here (approve, deny, enable/disable, uninstall) goes
 * straight through [PluginStore], the one place allowed to change
 * plugin trust state, and the screen is rebuilt from disk after each
 * one -- same "no separate save step" shape [integrationsScreen]
 * uses.
 */
internal fun AppSettingsCatalogs.pluginsScreen() = CatalogScreen(
    id = SCREEN_PLUGINS,
    title = "Plugins",
    subtitle = "Run only after you approve them",
    groups = { context -> pluginsGroups(context) },
    // A plugin's id opens its page, where it is approved: every "approve it" hint, notice and job leads straight there
    // (Droidtop/tracker#759). "permissions:<id>" opens its Permissions screen (a denied plugin view's way to change it).
    forDeepLink = { link ->
        if (link.startsWith(AcquireContentSources.PERMISSIONS_LINK)) {
            pluginPermissionsScreen(link.removePrefix(AcquireContentSources.PERMISSIONS_LINK))
        } else {
            pluginDetailScreen(link, pluginLabels[link] ?: "Plugin")
        }
    },
    // Each plugin's page, its Approve among it, is found by settings search.
    indexesItsPages = true,
)

/** Plugins' names as the list last read them, so a page opened by id alone is titled with its name. */
private val pluginLabels = java.util.concurrent.ConcurrentHashMap<String, String>()

internal suspend fun AppSettingsCatalogs.pluginsGroups(context: Context): List<CatalogGroup> = withContext(Dispatchers.IO) {
    val installed = PluginStore.installed(context)
    installed.forEach { pluginLabels[it.manifest.id] = it.manifest.label }
    // Read once here, off the main thread; valueLabel below is a
    // plain (non-suspend) closure the renderer may call on the main
    // thread, so it must not touch the key store itself.
    val userKeys = UserOriginKeys.load(UserOriginKeys.storeFile(context))
    val resolution = PluginApiResolver.current(context)
    val grantStore = PluginGrants.forContext(context)
    val providerChoices = PluginProviderChoices.forContext(context).all()
    // The catalogs as last fetched, never a network call: a card says when its plugin has an update (Decky's badge);
    // the update itself is on the plugin's page and in Updates.
    val listings = PluginCatalog.listings(context)
    val catalogNames = catalogNames(context)
    val catalogsSummary = PluginCatalogScreen.summary(context)

    val heldBack = PluginDangers.failures.value.values.toList()
    listOfNotNull(
        heldBack.takeIf { it.isNotEmpty() }?.let { list ->
            CatalogGroup(
                id = "plugins_protection",
                title = null,
                items = listOf(
                    NoticeItem(
                        id = "plugins_protection_notice",
                        notice = Notice(
                            tone = NoticeTone.Caution,
                            title = "Protection didn't start",
                            body = if (list.size == 1) "${list.single().label} is waiting for you to decide." else "${list.size} plugins are waiting for you to decide.",
                            details = list.joinToString("\n") { "${it.label}: ${it.report}" },
                            action = NoticeAction("Review") { PluginDangers.askRunAllUnprotected() },
                        ),
                        placement = NoticePlacement.Page,
                    ),
                ),
            )
        },
        getPluginsGroup(catalogsSummary),
        CatalogGroup(
            id = "plugins_installed",
            title = if (installed.isEmpty()) null else "Installed",
            items = buildList {
                if (installed.isEmpty()) {
                    add(
                        ActionItem(
                            id = "plugins_none",
                            title = "No plugins installed",
                            subtitle = "Get one above; each waits for your approval before it runs",
                        ),
                    )
                    return@buildList
                }
                val cards = installed.map { record ->
                    val grants = grantStore.read(record.manifest.id)
                    val update = PluginCatalog.offerFor(listings, record, userKeys) != null
                    val runtimeNeed = PluginRuntimeNeeds.missing(context, record.manifest)
                    val waiting = resolution.waiting[record.manifest.id]
                    Triple(record, pluginState(record, grants, runtimeNeed, update, waiting), pluginCard(context, record, userKeys, catalogNames, waiting, grants, runtimeNeed, updateAvailable = update))
                }
                val updates = cards.count { (_, state, _) -> state == "Update available" || state == "Needs an update" }
                // Droidtop/tracker#513: what needs the person first, and the list narrowed to it.
                // The updates are in the one Updates list, with Update all there (docs/SPEC.md 10b, Droidtop/tracker#570);
                // this page points at it rather than keeping a second Update all.
                if (updates > 0) {
                    add(
                        NestedScreenItem(
                            id = "plugins_updates",
                            title = "$updates ${if (updates == 1) "update" else "updates"}",
                            subtitle = "In Updates, with everything else that has a newer version",
                            registryId = SCREEN_UPDATES,
                        ),
                    )
                }
                add(
                    ChoiceItem(
                        id = "plugins_filter",
                        title = "Show",
                        options = PluginListFilter.entries.map { ChoiceOption(it.name, it.label) },
                        current = pluginFilter.name,
                        onSelect = { _, value -> pluginFilter = PluginListFilter.entries.firstOrNull { it.name == value } ?: PluginListFilter.ALL },
                    ),
                )
                // Kinds (docs/plugin-api.md 1.2, "Categories", Droidtop/tracker#179): only the kinds installed plugins have.
                fun kindOf(record: dev.droidtop.pluginhost.PluginRecord) = dev.droidtop.pluginhost.PluginPresentation.category(record.manifest.presentation.category)
                val kinds = dev.droidtop.pluginhost.PluginPresentation.CATEGORIES.filter { kind -> installed.any { kindOf(it) == kind } }
                if (kinds.size > 1) {
                    add(
                        ChoiceItem(
                            id = "plugins_kind",
                            title = "Kind",
                            options = listOf(ChoiceOption(ALL_KINDS, "All")) +
                                kinds.map { ChoiceOption(it, dev.droidtop.pluginhost.PluginPresentation.CATEGORY_LABELS.getValue(it)) },
                            current = pluginKind.takeIf { it in kinds } ?: ALL_KINDS,
                            onSelect = { _, value -> pluginKind = value },
                        ),
                    )
                }
                val kind = pluginKind.takeIf { it in kinds } ?: ALL_KINDS
                val shown = cards.filter { (record, state, _) -> pluginFilter.keeps(state) && (kind == ALL_KINDS || kindOf(record) == kind) }
                if (shown.isEmpty()) {
                    add(ActionItem(id = "plugins_filter_none", title = "No plugin ${pluginFilter.none}"))
                }
                shown.forEach { (_, _, card) -> add(card) }
            },
        ),
    ) + listOfNotNull(appInterfacesGroup(context, installed)) + providedByGroups(resolution, providerChoices) + listOf(
        CatalogGroup(
            id = "plugins_more",
            title = null,
            items = listOf(
                // Which plugin or app opens which kind of link (docs/SPEC.md 12a "Links").
                NestedScreenItem(
                    id = "plugins_link_handlers",
                    title = "Link handlers",
                    subtitle = "Which plugin or app opens which kind of link, and sending a kind of link to an app",
                    registryId = dev.droidtop.library.integrations.LinkRouter.HANDLERS_ID,
                ),
                // Other apps that asked to start a game (Droidtop/tracker#124): their answers live with the plugins' grants.
                NestedScreenItem(
                    id = "plugins_external_launch",
                    title = "Apps that start games",
                    subtitle = "Other apps that asked droidtop to start a game from your library",
                    inline = externalLaunchScreen(),
                ),
                // One place for where plugins come from (#513): catalogs, repositories and keys, side by side.
                NestedScreenItem(
                    id = "plugins_sources",
                    title = "Where plugins come from",
                    subtitle = "Catalogs, plugin repositories and the keys droidtop trusts",
                    inline = pluginSourcesScreen(),
                    valueLabel = { if (userKeys.isEmpty()) "Official only" else "Official + ${userKeys.size} more" },
                ),
            ),
        ),
    )
}

/**
 * "In your apps" (docs/plugin-api.md 1.3, docs/SPEC.md 12a; Droidtop/tracker#547): the installed apps that carry a
 * plugin, found with PackageManager and checked by droidtop's own reading of their signatures. Adding one installs
 * it like any plugin: it waits for approval in Installed, with the same grants, Permissions screen and Activity. An
 * app already added is in Installed, and shows here again only when the app changed and its new version waits.
 * Null when no app carries one. Reads every such app's APKs (kept until they change): off the main thread.
 */
internal fun AppSettingsCatalogs.appInterfacesGroup(context: Context, installed: List<dev.droidtop.pluginhost.PluginRecord>): CatalogGroup? {
    val found = runCatching { dev.droidtop.pluginhost.AppInterfaces.discover(context) }.getOrDefault(emptyList())
    val items = found.mapNotNull { app ->
        if (dev.droidtop.pluginhost.AppInterfaces.isAdded(context, app)) return@mapNotNull null
        val record = installed.firstOrNull { it.manifest.kind == PluginKind.APP_INTERFACE && it.manifest.app?.packageName == app.packageName }
        appInterfaceRow(app, update = record != null)
    }
    if (items.isEmpty()) return null
    return CatalogGroup(id = "plugins_apps", title = "In your apps", items = items)
}

/** One app that carries a plugin, as "In your apps" shows it: what droidtop decided about it, and how to add it. */
internal fun AppSettingsCatalogs.appInterfaceRow(app: dev.droidtop.pluginhost.AppInterfaces.Found, update: Boolean): CatalogItem {
    val verdict = app.verdict
    val fingerprint = dev.droidtop.pluginhost.AppInterfaceTrust.certOf(verdict)?.let { PluginDangers.groupsOfFour(it) }
    val refusal = (verdict as? dev.droidtop.pluginhost.AppInterfaceTrust.Verdict.Refused)?.reason ?: app.problem
    val id = "plugins_app_${app.packageName}"
    if (refusal != null) {
        return ActionItem(id = id, title = app.appLabel, subtitle = "Can't be added: $refusal")
    }
    val runsIn = "Runs in its own app, outside droidtop; it reaches droidtop only through droidtop's checks"
    fun add(ctx: Context): String {
        val result = dev.droidtop.pluginhost.AppInterfaces.install(ctx, dev.droidtop.pluginhost.AppInterfaces.inspect(ctx, app.packageName, app.serviceName))
        val record = result.getOrElse { return "${app.appLabel} can't be added: ${it.message}" }
        pluginsChanged(ctx)
        if (record.trust == PluginTrustState.APPROVED) return "${app.appLabel} is updated"
        // Its page is one press away, not a list to look through (Droidtop/tracker#759).
        Notices.flash(
            "${app.appLabel} is waiting for your approval",
            source = app.appLabel,
            context = ctx,
            opens = { SettingsScreenRegistry.get(SCREEN_PLUGINS, record.manifest.id) },
        )
        return "${app.appLabel} is added: open it to approve it before it runs"
    }
    return when (verdict) {
        is dev.droidtop.pluginhost.AppInterfaceTrust.Verdict.NotTrusted -> AsyncActionItem(
            id = id,
            title = app.appLabel,
            subtitle = "Unofficial: signed by a key you haven't trusted, $fingerprint. $runsIn",
            value = "Trust and add",
            confirmTitle = "Trust the key ${app.appLabel} is signed with ($fingerprint)? droidtop hasn't checked who holds it. " +
                "Its updates signed by the same key are trusted too",
            run = { ctx, _ ->
                UserOriginKeys.trustApp(UserOriginKeys.storeFile(ctx), app.packageName, verdict.certSha256)
                dev.droidtop.pluginhost.AppInterfaces.recheck(app.packageName)
                add(ctx)
            },
        )
        else -> AsyncActionItem(
            id = id,
            title = app.appLabel,
            subtitle = (if (verdict is dev.droidtop.pluginhost.AppInterfaceTrust.Verdict.Official) "Official. " else "Unofficial, signed by a key you trust, $fingerprint. ") +
                (if (update) "The app changed: its new version waits for your approval. " else "") + runsIn,
            value = if (update) "Review the new version" else "Add",
            run = { ctx, _ -> add(ctx) },
        )
    }
}

/** The Plugins place's first group (Droidtop/tracker#513): getting plugins, above the installed list. */
internal fun AppSettingsCatalogs.getPluginsGroup(catalogsSummary: String?) = CatalogGroup(
    id = "plugins_add",
    title = "Get plugins",
    items = listOf(
        NestedScreenItem(
            id = "plugins_add_official",
            title = "droidtop plugins",
            subtitle = "Made by droidtop. Install one with a press, or all of them",
            inline = PluginCatalogScreen.officialScreen(),
        ),
        NestedScreenItem(
            id = "plugins_add_catalog",
            title = "More catalogs",
            subtitle = "From other people: switch one on, or add one by address, QR code or link",
            inline = PluginCatalogScreen.screen(),
            valueLabel = { catalogsSummary },
        ),
        DocumentPickItem(
            id = "plugins_add_file",
            title = "Install plugin file",
            subtitle = "A signed .droidplugin.tar.xz bundle",
            mimeType = "*/*",
            // A downloaded bundle is in Downloads; the picker otherwise opened on an empty Documents (rig, build 1535).
            startIn = "Download",
            onPicked = { ctx, uri -> PluginStore.importFromPicker(ctx, uri) },
        ),
    ),
)

/**
 * Where plugins come from, on one page (Droidtop/tracker#513): the catalogs, the plugin repositories and the keys
 * droidtop trusts were three entries whose counts contradicted each other ("None added" beside a trusted key).
 */
internal fun AppSettingsCatalogs.pluginSourcesScreen() = CatalogScreen(
    id = "plugins_sources_screen",
    title = "Where plugins come from",
    groups = { context ->
        val userKeys = withContext(Dispatchers.IO) { UserOriginKeys.load(UserOriginKeys.storeFile(context)) }
        val summary = withContext(Dispatchers.IO) { PluginCatalogScreen.summary(context) }
        listOf(
            CatalogGroup(
                id = "plugins_sources_list",
                title = null,
                items = listOf(
                    NestedScreenItem(
                        id = "plugins_sources_catalogs",
                        title = "Catalogs",
                        subtitle = "droidtop's, and any you added",
                        inline = PluginCatalogScreen.screen(),
                        valueLabel = { summary },
                    ),
                    GitHubAccountCatalog.reposRow(userKeys.values.count { it.repo != null }),
                    NestedScreenItem(
                        id = "plugins_keys_you_trust",
                        title = "Keys you trust",
                        subtitle = "Every origin whose plugins droidtop accepts: from a catalog, a repository or added by hand",
                        registryId = SCREEN_PLUGIN_KEYS,
                        valueLabel = { if (userKeys.isEmpty()) "Official only" else "Official + ${userKeys.size} more" },
                    ),
                ),
            ),
        )
    },
)

/** What the Plugins list shows (Droidtop/tracker#513), kept while droidtop runs. */
internal enum class PluginListFilter(val label: String, val none: String) {
    ALL("All", "is installed"),
    NEEDS_APPROVAL("Needs approval", "waits for approval"),
    UPDATES("Updates", "has an update"),
    OFF("Off", "is off");

    fun keeps(state: String): Boolean = when (this) {
        ALL -> true
        NEEDS_APPROVAL -> state == "Needs approval" || state == "Wants new access"
        UPDATES -> state == "Update available" || state == "Needs an update"
        OFF -> state == "Off" || state == "Stopped" || state == "Denied"
    }
}

@Volatile private var pluginFilter = PluginListFilter.ALL

/** The kind the installed list is narrowed to (docs/plugin-api.md 1.2, "Categories"), or [ALL_KINDS]. */
@Volatile private var pluginKind = ALL_KINDS
private const val ALL_KINDS = "all"

/**
 * A plugin's trust badge, the same words in the list, on its page and on the approval screen
 * (docs/SPEC.md 12a "The badge", "Added catalogs"): an origin trusted through an added catalog is
 * "Unofficial" whatever else vouches for it, since a catalog that is not droidtop's listed it.
 */
internal fun AppSettingsCatalogs.pluginTrustBadge(origin: String, userKeys: Map<String, UserOriginKey>, catalogNames: Map<String, String>): String {
    if (PluginOriginKeys.isOfficial(origin)) return "Official"
    val entry = userKeys[origin] ?: return "NOT TRUSTED"

    entry.catalog?.let { return "Unofficial: from ${catalogNames[it] ?: it}" }
    return entry.repo?.let { "Verified by: $it" } ?: "Added by you"
}

/** True for a plugin whose publisher is trusted through a catalog that is not droidtop's: it carries the "Unofficial" chip wherever it appears. */
internal fun AppSettingsCatalogs.isUnofficial(origin: String, userKeys: Map<String, UserOriginKey>): Boolean =
    !PluginOriginKeys.isOfficial(origin) && userKeys[origin]?.catalog != null

/** Added catalogs' display names by id, for the badge. Disk. */
internal fun AppSettingsCatalogs.catalogNames(context: Context): Map<String, String> =
    dev.droidtop.library.integrations.PluginCatalogSources.added(dev.droidtop.library.integrations.PluginCatalogSources.storeFile(context))
        .associate { it.id to it.name }

/** A plugin's state in one word or two, the same on its list card, its page's header and the Quick Menu (PluginStatus). */
internal fun AppSettingsCatalogs.pluginState(
    record: dev.droidtop.pluginhost.PluginRecord,
    grants: PluginGrants.Snapshot,
    runtimeNeed: RuntimeNeed?,
    updateAvailable: Boolean,
    waiting: List<dev.droidtop.pluginhost.RequiredApi>?,
): String = dev.droidtop.library.integrations.PluginStatus.of(record, grants, runtimeNeed, updateAvailable, waiting).word

/** The installed-plugins list row: what it's called, what it adds in plain words, its trust badge and its state -- the whole card, one tap into [pluginDetailScreen]. */
internal fun AppSettingsCatalogs.pluginCard(
    context: Context,
    record: dev.droidtop.pluginhost.PluginRecord,
    userKeys: Map<String, UserOriginKey>,
    catalogNames: Map<String, String>,
    waiting: List<dev.droidtop.pluginhost.RequiredApi>?,
    grants: PluginGrants.Snapshot,
    runtimeNeed: RuntimeNeed?,
    updateAvailable: Boolean = false,
): NestedScreenItem {
    val m = record.manifest
    val enabledPoints = when (record.trust) {
        PluginTrustState.PENDING -> PluginGrants.approvalTicked(record, grants)
            .filterTo(HashSet()) { it.startsWith(PluginPermissions.PROVIDE_PREFIX) }
        PluginTrustState.APPROVED -> m.v2.provides.mapNotNullTo(HashSet()) { entry ->
            (PluginPermissions.PROVIDE_PREFIX + entry.point).takeIf { PluginGrants.provideState(record, grants, entry.point) == GrantState.GRANTED }
        }
        else -> emptySet()
    }
    val state = pluginState(record, grants, runtimeNeed, updateAvailable, waiting)
    return NestedScreenItem(
        id = "plugin_${m.id}",
        title = m.label,
        // What it is, in its own line; its trust and access are the chip and its page's header (#514), never stitched on.
        subtitle = m.presentation.summary ?: pluginSummary(m, enabledPoints),
        inline = pluginDetailScreen(m.id, m.label, m.presentation.summary),
        valueLabel = { "$state · v${m.version}" },
        // An app's plugin is trusted by its app's signature, not an origin key (Droidtop/tracker#547).
        chip = if (m.kind == PluginKind.APP_INTERFACE) {
            if (dev.droidtop.pluginhost.AppInterfaces.isOfficial(record)) null else dev.droidtop.library.settings.RowTag.UNOFFICIAL
        } else if (isUnofficial(m.origin, userKeys)) dev.droidtop.library.settings.RowTag.UNOFFICIAL else null,
        // Its own icon and its summary, always shown (#513, #514). Built on IO, like the whole list.
        iconFile = PluginStore.iconFile(context, record),
        summaryShown = true,
    )
}

/**
 * One plugin's own page (owner: "actual design", not a flat list):
 * what it does, its trust and running state, approve/deny/enable,
 * what a python/flutter-kind plugin still needs before it can run,
 * its version and any catalog update, and Uninstall -- with the
 * id, origin and digest technical fields behind the page's own
 * Advanced fold at the bottom, off the page's face. Permission
 * grant/revoke per plugin is agent pluginapi's model landing separately
 * (docs/plugin-api.md); this page has the seam (the "What it
 * provides" group below) but no controls yet -- nothing here should invent
 * a permissions UI ahead of that data actually existing.
 *
 * `groups` re-reads [PluginStore] and the cached catalog index fresh
 * on every (re)entry (same contract as every other [CatalogScreen]),
 * so approving, enabling or uninstalling on this page and coming
 * back always shows the current record, never one captured when the
 * parent list was built.
 */
internal fun AppSettingsCatalogs.pluginDetailScreen(pluginId: String, label: String, summary: String? = null) = CatalogScreen(
    id = "plugin_detail_$pluginId",
    title = label,
    subtitle = summary,
    groups = { context ->
        withContext(Dispatchers.IO) {
            val record = PluginStore.installed(context).firstOrNull { it.manifest.id == pluginId }
            if (record == null) {
                listOf(
                    CatalogGroup(
                        id = "plugin_detail_gone",
                        title = null,
                        items = listOf(
                            ActionItem(
                                id = "plugin_detail_gone_row",
                                title = "This plugin is no longer installed",
                            ),
                        ),
                    ),
                )
            } else {
                val userKeys = UserOriginKeys.load(UserOriginKeys.storeFile(context))
                pluginDetailPage(context, record, userKeys, catalogNames(context)).asGroups()
            }
        }
    },
    live = PluginGrants.changes,
)

/**
 * One plugin's page (Droidtop/tracker#512): a header saying which plugin it is (its icon, name, maker and version, its
 * trust and its state), the bar with the one thing to do now (Approve, Update, Try again, Turn on), and More holding
 * the rest, Deny and Uninstall in danger with a confirm. The sections follow, what it does first.
 */
internal fun AppSettingsCatalogs.pluginDetailPage(
    context: Context,
    record: dev.droidtop.pluginhost.PluginRecord,
    userKeys: Map<String, UserOriginKey>,
    catalogNames: Map<String, String>,
): CatalogPage {
    val m = record.manifest
    val resolution = PluginApiResolver.current(context)
    val grantSnapshot = PluginGrants.forContext(context).read(m.id)
    val runtimeNeed = PluginRuntimeNeeds.missing(context, m)
    val catalogListings = PluginCatalog.listings(context)
    val catalogOffer = PluginCatalog.offerFor(catalogListings, record, userKeys)
    val statusGroup = buildList<CatalogItem> {
        val statusLine = when {
            record.trust == PluginTrustState.PENDING -> "Awaiting approval"
            record.trust == PluginTrustState.DENIED -> "Denied"
            record.disabledReason != null && catalogOffer != null -> "Needs an update: ${record.disabledReason}"
            record.disabledReason != null -> "Stopped: ${record.disabledReason}"
            record.trust == PluginTrustState.APPROVED && record.enabled && runtimeNeed != null ->
                "Needs the ${runtimeNeed.runtime} runtime (${runtimeNeed.sizeLabel}) to run"
            record.trust == PluginTrustState.APPROVED && record.enabled -> "Running"
            else -> "Disabled"
        }
        val trustLine = if (m.kind == PluginKind.APP_INTERFACE) {
            // An app's plugin: trusted by the app's own signature, checked again before every run (Droidtop/tracker#547).
            val key = PluginDangers.groupsOfFour(record.approvedKeySha256)
            if (dev.droidtop.pluginhost.AppInterfaces.isOfficial(record)) "Official: signed by its maker's release key, $key" else "Unofficial: signed by a key you trust, $key"
        } else if (!PluginOriginKeys.isOfficial(m.origin) && !userKeys.containsKey(m.origin)) {
            "NOT TRUSTED: no trusted key for this origin anymore (see Keys you trust)"
        } else {
            pluginTrustBadge(m.origin, userKeys, catalogNames)
        }
        val unofficialFrom = userKeys[m.origin]?.catalog?.takeIf { !PluginOriginKeys.isOfficial(m.origin) }
        // The approval screen says where the plugin came from before anything is approved
        // (docs/SPEC.md 12a "Added catalogs"): from a catalog that is not droidtop's, it is Unofficial.
        // U3 (docs/SPEC.md 7, "Developer menu", "Notices"): an update from a different maker waits for the person, in caution, with the
        // fingerprints in Info; approving it asks in red (D3).
        if (m.kind == PluginKind.APP_INTERFACE) {
            add(
                ActionItem(
                    id = "plugin_${m.id}_app",
                    title = "Runs in its own app, outside droidtop",
                    subtitle = "${m.app?.packageName}: it reaches droidtop only through droidtop's checks. $trustLine",
                ),
            )
        }
        if (record.trust == PluginTrustState.PENDING && record.replacedKeySha256 != null) {
            add(
                NoticeItem(
                    id = "plugin_${m.id}_other_maker",
                    notice = Notice(
                        tone = NoticeTone.Caution,
                        title = "Update made by someone else",
                        body = "The update isn't from the maker of your version.",
                        details = "Your version: ${PluginDangers.groupsOfFour(record.replacedKeySha256.orEmpty())} (${record.replacedOrigin ?: m.origin})\n" +
                            "This update: ${PluginDangers.groupsOfFour(record.approvedKeySha256)} (${m.origin})",
                    ),
                    placement = NoticePlacement.Page,
                ),
            )
        }
        if (record.trust == PluginTrustState.PENDING && unofficialFrom != null) {
            // U1 (docs/SPEC.md 7, "Developer menu", "Notices"): the caution banner, its exact source and maker in Info.
            add(
                NoticeItem(
                    id = "plugin_${m.id}_unofficial",
                    notice = Notice(
                        tone = NoticeTone.Caution,
                        title = "Unofficial plugin",
                        body = "Approve it only if someone you trust pointed you to it.",
                        details = "Approving lets it run and use the permissions listed below.\n" +
                            "Source: the catalog \"${catalogNames[unofficialFrom] ?: unofficialFrom}\", which is not part of droidtop.\n" +
                            "Maker: ${m.origin}, key ${PluginDangers.groupsOfFour(record.approvedKeySha256)}\n" +
                            "Digest: ${record.archiveDigest}",
                    ),
                    placement = NoticePlacement.Page,
                ),
            )
        }
        // A pending status used to look like a button but had no action. The approval controls below
        // are the action; keep the trust/status row for plugins that already have a decision.
        if (record.trust != PluginTrustState.PENDING) {
            // The header says its state and trust; a row is left only for what the header cannot: a stop's
            // reason, and an origin droidtop no longer trusts.
            if (record.disabledReason != null || trustLine.startsWith("NOT TRUSTED")) {
                add(
                    ActionItem(
                        id = "plugin_${m.id}_status",
                        title = statusLine,
                        subtitle = trustLine,
                    ),
                )
            }
            // The only access rows the person ever sees are the full-access warning, a plugin that needs it and has
            // not been allowed it (docs/plugin-api.md 4.6), and the plain warning of a granted permission that lets
            // the plugin reach past droidtop's checks (gpu.render, 5.3). A plugin on the safe default carries no
            // access row: what it may do is the one list on its Permissions screen, not a tier to understand.
            val refusal = PluginTiers.refusal(record, grantSnapshot)
            val accessBadge = PluginTiers.badge(record, grantSnapshot)
            val caution = PluginTiers.caution(record, grantSnapshot)
            when {
                refusal != null && PluginTiers.declaresFullTrust(m) -> add(NoticeItem("plugin_${m.id}_access", needsFullAccessNotice(record, refusal)))
                refusal != null -> add(ActionItem(id = "plugin_${m.id}_access", title = "Needs an update", subtitle = refusal))
                accessBadge != null -> add(NoticeItem("plugin_${m.id}_access", hasFullAccessNotice(record, grantSnapshot)))
                // A granted permission that lets its own code reach past droidtop's checks (gpu.render, docs/plugin-api.md 5.3).
                caution != null -> add(ActionItem(id = "plugin_${m.id}_access", title = CAUTION_TITLE, subtitle = caution))
            }
        }
        // A stopped plugin always has something to press: the newer version when the catalog has one (the row under
        // "Version" below says which), otherwise another go. Switching it back on clears the reason; it stops again if it fails again.
        // A stopped plugin's way forward (Update, or Try again) is the page's primary button.
        // A main job the person left blocked (Droidtop/tracker#519): high-risk points start unticked, and a plugin whose
        // point is off looks broken ("search finds nothing") unless its page says why, with the way to turn it on.
        if (record.trust == PluginTrustState.APPROVED) {
            m.v2.provides.map { it.point }.distinct().forEach { point ->
                val registry = dev.droidtop.pluginhost.ExtensionPoints.find(point) ?: return@forEach
                if (!registry.risk.needsConsent || PluginGrants.provideState(record, grantSnapshot, point) != GrantState.DENIED) return@forEach
                add(
                    NoticeItem(
                        id = "plugin_${m.id}_blocked_$point",
                        notice = Notice(
                            tone = NoticeTone.Caution,
                            title = "${registry.label} is off",
                            body = "${m.label} can't do its job here until you allow it. ${registry.lets}",
                            action = NoticeAction("Allow") { ctx ->
                                withContext(Dispatchers.IO) {
                                    PluginGrants.forContext(ctx).set(m.id, PluginPermissions.PROVIDE_PREFIX + point, GrantState.GRANTED)
                                }
                                withContext(Dispatchers.Main) { pluginsChanged(ctx) }
                            },
                        ),
                    ),
                )
            }
        }
        // The headline above is a plain sentence; what the plugin actually reported is one press away,
        // for its developer or a bug report (docs/SPEC.md 12a, Droidtop/tracker#167), in the Developer menu's
        // rows (docs/SPEC.md 7, "Developer menu").
        val detail = record.disabledDetail
        if (record.disabledReason != null && detail != null && DeveloperMenu.on(context)) {
            add(
                NestedScreenItem(
                    id = "plugin_${m.id}_detail",
                    title = "Technical details",
                    subtitle = "Plugin details",
                    inline = CatalogScreen(
                        id = "plugin_${m.id}_detail_screen",
                        title = "Technical details",
                        groups = { _ ->
                            listOf(
                                CatalogGroup(
                                    id = "plugin_${m.id}_detail_group",
                                    title = null,
                                    items = listOf(ActionItem(id = "plugin_${m.id}_detail_text", title = "${m.label} reported", subtitle = detail)),
                                ),
                            )
                        },
                    ),
                ),
            )
        }
        resolution.waiting[m.id]?.let { missing ->
            add(
                ActionItem(
                    id = "plugin_${m.id}_waiting",
                    title = "Needs " + missing.joinToString { it.api },
                    subtitle = "Waiting for a running plugin",
                ),
            )
        }
        if (grantSnapshot.wantsNewAccess) {
            add(
                ActionItem(
                    id = "plugin_${m.id}_new_access",
                    title = "Wants new access",
                    subtitle = "An update asks for more; nothing new is on until you choose",
                ),
            )
        }
        // On and off are the page's buttons (Turn on is primary while it is off; Turn off is in More).
        if (m.requestsRoot) {
            add(
                ActionItem(
                    id = "plugin_${m.id}_root",
                    title = if (record.rootApproved) "Uses root" else "Can use root",
                    subtitle = if (record.rootApproved) "Approved" else "Not granted: approve with root above to allow it",
                ),
            )
        }
    }

    // Approval is a list (docs/plugin-api.md 4.3): every item is a tick box, the actions come after it, and the plugin runs with exactly what is ticked.
    val approvalTicks = if (record.trust == PluginTrustState.PENDING) tickBuffer(m.id, PluginGrants.approvalTicked(record, grantSnapshot)) else null
    var approveAction: (suspend (Context, Boolean) -> String)? = null
    if (approvalTicks != null) {
        fun approve(ctx: Context, root: Boolean) {
            PluginStore.setApproval(ctx, m.id, approved = true, grantRoot = root, ticked = approvalTicks.toSet())
            pendingTicks.remove(m.id)
            pluginsChanged(ctx)
        }

        // Approving with full access ticked (or an older plugin, which always has it) asks in red first
        // (Droidtop/tracker#481, D1); Cancel approves nothing.
        suspend fun approveAsking(ctx: Context, root: Boolean): String {
            // An update from a different maker asks first (D3), then full access does (D1).
            if (!PluginDangers.askDifferentMaker(record)) return "Not approved"
            val full = m.contractVersion < 2 || PluginTiers.FULL_TRUST in approvalTicks
            if (full && !PluginDangers.askFullAccess(record, update = false)) return "Not approved"
            withContext(Dispatchers.Main) { approve(ctx, root) }
            return "Approved"
        }
        approveAction = { ctx, root -> approveAsking(ctx, root) }
    }
    // An update that added items asks about those only, on the same list (docs/plugin-api.md 4.3, "Updates").
    val freshIds = if (record.trust == PluginTrustState.APPROVED) grantSnapshot.fresh.filterTo(HashSet()) { grantSnapshot.states[it] == GrantState.ASK } else emptySet<String>()
    val newKey = "${m.id}#new"
    val newTicks = if (freshIds.isEmpty()) null else tickBuffer(newKey, PluginGrants.defaultTicked(record).filterTo(HashSet()) { it in freshIds })
    val newAccessGroups: List<CatalogGroup> = if (newTicks == null) {
        emptyList()
    } else {
        pluginConsentGroups(context, record, userKeys, catalogNames, ticks = newTicks, only = freshIds) + CatalogGroup(
            id = "plugin_${m.id}_new_access_group",
            title = null,
            items = listOf(
                AsyncActionItem(
                    id = "plugin_${m.id}_new_access_save",
                    title = "Allow what is ticked",
                    subtitle = "Changes only the new items",
                    run = { ctx, _ ->
                        if (PluginTiers.FULL_TRUST in newTicks && !PluginDangers.askFullAccess(record, update = true)) {
                            "Nothing changed"
                        } else {
                            withContext(Dispatchers.IO) { PluginGrants.forContext(ctx).answerNew(record, freshIds, newTicks.toSet()) }
                            withContext(Dispatchers.Main) {
                                pendingTicks.remove(newKey)
                                pluginsChanged(ctx)
                            }
                            "Saved"
                        }
                    },
                ),
            ),
        )
    }

    // "Asks for" is what approval is about; once approved, the Permissions screen below holds the real
    // state of each one, and a second list saying "Asks first" beside "7 allowed, 0 ask" contradicts it.
    val consentGroups = pluginConsentGroups(context, record, userKeys, catalogNames, ticks = approvalTicks)
        .filterNot { record.trust == PluginTrustState.APPROVED && it.id.endsWith("_consent_asks") }
    // The lists are their own page, one row under the header (Droidtop/tracker#512): the page leads with who the
    // plugin is and what to do, not with eight rows of permissions. Approval's ticks are the plugin's one tick buffer,
    // so what is ticked there is what Approve on the bar allows.
    val consentGroup: CatalogGroup? = consentGroups.takeIf { it.isNotEmpty() }?.let { groups ->
        val pending = approvalTicks != null
        CatalogGroup(
            id = "plugin_${m.id}_consent_group",
            title = null,
            items = listOf(
                NestedScreenItem(
                    id = "plugin_${m.id}_consent",
                    title = if (pending) "What you're allowing" else "What it adds and can do",
                    subtitle = groups.mapNotNull { it.title }.joinToString(", "),
                    inline = CatalogScreen(
                        id = "plugin_consent_${m.id}",
                        title = if (pending) "What you're allowing" else "What it adds and can do",
                        subtitle = if (pending) "Untick what you don't want it to have, then Approve" else m.label,
                        groups = { ctx ->
                            withContext(Dispatchers.IO) {
                                pluginConsentGroups(ctx, record, userKeys, catalogNames, ticks = approvalTicks)
                                    .filterNot { record.trust == PluginTrustState.APPROVED && it.id.endsWith("_consent_asks") }
                            }
                        },
                    ),
                    valueLabel = { groups.sumOf { it.items.size }.let { n -> if (n == 1) "1 item" else "$n items" } },
                ),
            ),
        )
    }

    // Grants exist once the plugin is approved (docs/plugin-api.md 4.4): one screen per plugin, no second place.
    val permissionsGroup: CatalogGroup? = if (record.trust != PluginTrustState.APPROVED) {
        null
    } else {
        val rows = permissionRows(record, grantSnapshot)
        val summary = "${rows.count { it.state == GrantState.GRANTED }} allowed, ${rows.count { it.state == GrantState.ASK }} ask first, ${rows.count { it.state == GrantState.DENIED }} blocked"
        CatalogGroup(
            id = "plugin_${m.id}_permissions_group",
            title = "Permissions",
            items = listOf(
                NestedScreenItem(
                    id = "plugin_${m.id}_permissions",
                    title = "Permissions",
                    subtitle = "What it may do, and when it last did",
                    inline = pluginPermissionsScreen(m.id),
                    valueLabel = { summary },
                ),
                // What droidtop had to change to draw its pages (docs/SPEC.md 7, "Developer menu", "Notices"), for the plugin's maker.
                // A Developer menu row (docs/SPEC.md 7, "Developer menu").
                *dev.droidtop.pluginhost.PluginMessages.read(context, m.id).size.takeIf { it > 0 && DeveloperMenu.on(context) }?.let { count ->
                    arrayOf(
                        NestedScreenItem(
                            id = "plugin_${m.id}_messages",
                            title = "Plugin messages",
                            subtitle = "What droidtop changed to draw its pages",
                            inline = pluginMessagesScreen(m.id, m.label),
                            valueLabel = { count.toString() },
                        ),
                    )
                }.orEmpty(),
                NestedScreenItem(
                    id = "plugin_${m.id}_activity",
                    title = "Activity",
                    subtitle = "What it asked droidtop to do, newest first",
                    inline = pluginActivityScreen(m.id),
                ),
            ),
        )
    }

    val enabledPoints = when {
        approvalTicks != null -> approvalTicks
            .filterTo(HashSet()) { it.startsWith(PluginPermissions.PROVIDE_PREFIX) }
        record.trust == PluginTrustState.APPROVED -> m.v2.provides.mapNotNullTo(HashSet()) { entry ->
            val id = PluginPermissions.PROVIDE_PREFIX + entry.point
            id.takeIf { PluginGrants.provideState(record, grantSnapshot, entry.point) == GrantState.GRANTED }
        }
        else -> emptySet()
    }
    val providesGroup = listOf<CatalogItem>(
        ActionItem(
            id = "plugin_${m.id}_provides",
            title = pluginSummary(m, enabledPoints),
        ),
    )

    // What the plugin is for, first (Decky's model, Droidtop/tracker#316): its Quick Menu panel, its settings, its
    // own app and the app it manages. The panel is the same screen the Quick Menu's Plugins section opens, so the
    // Standard and Desktop modes, which have no Quick Menu, reach it here.
    val useGroup = buildList<CatalogItem> {
        // Its own page's facets (docs/plugin-api.md 1.10, target plugin.page), first: what it is for, drawn by droidtop.
        if (record.runnable() && PluginFacets.declares(record, PluginFacets.PLUGIN_PAGE)) {
            add(
                NestedScreenItem(
                    id = "plugin_${m.id}_facets_page",
                    title = "Status and actions",
                    subtitle = "What ${m.label} shows on its page",
                    inline = PluginFacets.pageScreen(record),
                ),
            )
        }
        if (record.runnable()) {
            PluginPanels.panelsFor(context).firstOrNull { it.pluginId == m.id }?.let { panel ->
                add(
                    NestedScreenItem(
                        id = "plugin_${m.id}_panel",
                        title = "Panel",
                        subtitle = "What the plugin shows in the Quick Menu",
                        inline = PluginPanels.panelScreen(context, panel, PluginPanels.SURFACE_SETTINGS, game = null, withMore = false),
                    ),
                )
            }
        }
        if (record.runnable() && dev.droidtop.library.integrations.PluginSettingsRows.has(record)) {
            add(
                NestedScreenItem(
                    id = "plugin_${m.id}_settings_rows",
                    title = "Settings",
                    subtitle = "Settings this plugin contributes",
                    inline = PluginSettingsRows.screenFor(record),
                ),
            )
        }
        // The plugin's own full-screen app (ui.main, docs/plugin-api.md 1.7), the same row in every mode.
        if (PluginMainUi.offered(record)) {
            add(
                AsyncActionItem(
                    id = "plugin_${m.id}_open_main",
                    title = "Open ${m.label}",
                    subtitle = "Its own screen, full-screen. Back returns here",
                    run = { ctx, _ -> PluginMainUi.open(ctx, record) ?: "Opened" },
                ),
            )
        }
        // The site account a web.session plugin signs in to (docs/SPEC.md 12a "A plugin's site account"): the one
        // way in. Signed out, pressing the row signs in at once; signed in, it names the account and opens Sign out
        // and Switch account. droidtop draws it from the session it keeps, so no plugin draws its own.
        if (record.runnable()) {
            dev.droidtop.pluginhost.PluginWebAccount.of(context, record)?.let { account -> add(pluginAccountRow(record, account)) }
        }
        // A store the plugin provides (docs/plugin-api.md 3 A13): signed in with the account row above, read here.
        if (record.runnable()) {
            record.manifest.v2.provides.filter { it.point == dev.droidtop.library.integrations.PluginStores.POINT }.mapNotNull { it.id }
                .mapNotNull { dev.droidtop.library.stores.StoreLibraries.byId(it) }
                .filterIsInstance<dev.droidtop.library.integrations.PluginStoreLibrary>()
                .forEach { store ->
                    add(
                        AsyncActionItem(
                            id = "plugin_${m.id}_store_${store.id}_sync",
                            title = "${store.label}: read your games",
                            subtitle = "Adds the games your ${store.label} account holds to your library. Sign in with the account row first",
                            run = { ctx, _ ->
                                if (!withContext(Dispatchers.IO) { store.signedIn(ctx) }) {
                                    "Sign in to ${store.label} first"
                                } else {
                                    store.sync(ctx).fold({ "$it games from ${store.label}" }, { it.message ?: "${store.label} did not answer" })
                                }
                            },
                        ),
                    )
                }
        }
        if (record.runnable() && PluginCapability.APP_STATUS in m.capabilities) {
            add(
                NestedScreenItem(
                    id = "plugin_${m.id}_app_status",
                    title = "App status",
                    subtitle = "Status and actions for the app this plugin manages",
                    inline = PluginAppStatus.screenFor(record),
                ),
            )
        }
    }

    // Shown on THIS plugin's own page, not as a top-level runtimes
    // list (owner direction): a python/flutter_embed-kind plugin is
    // the only reason the runtime matters, so the download/remove
    // action lives where that reason is visible. Fetching it
    // automatically as part of approval is agent pluginapi's
    // permission-model work landing separately; today it is still an
    // explicit action, same as before.
    val runtimeGroup: List<CatalogItem> = listOfNotNull(runtimeItem(context, m.kind, m.label, runtimeNeed))

    val updateGroup = buildList<CatalogItem> {
        val listings = catalogListings
        val offer = catalogOffer
        add(
            if (offer != null) {
                AsyncActionItem(
                    id = "plugin_${m.id}_update",
                    title = "Version ${m.version}",
                    subtitle = if (offer.source.official) "An update is available in the catalog" else "An update is available in \"${offer.source.name}\" (unofficial)",
                    value = "Update to ${offer.release.version}",
                    run = { ctx, onStatus -> PluginCatalog.install(ctx, offer.plugin, offer.release, onStatus) },
                )
            } else {
                ActionItem(
                    id = "plugin_${m.id}_version",
                    title = "Version ${m.version}",
                    subtitle = if (listings.isEmpty()) "Catalog not fetched yet: open Add > Catalogs to check" else "No newer version on its channel in your catalogs",
                )
            },
        )
        // Its own release line, or its catalog's (SPEC 12a "Channels", Droidtop/tracker#179).
        if (listings.any { listing -> listing.index.pluginById(m.id) != null }) {
            val channels = dev.droidtop.library.integrations.PluginChannels.forContext(context)
            val listedIn = listings.firstOrNull { it.index.pluginById(m.id) != null }?.source
            val catalogs = listedIn?.let { channels.catalog(it.id) } ?: dev.droidtop.library.integrations.PluginChannel.STABLE
            add(
                ChoiceItem(
                    id = "plugin_${m.id}_channel",
                    title = "Channel",
                    subtitle = "Testing and Unstable get new versions sooner and may not work as well",
                    options = listOf(dev.droidtop.library.settings.ChoiceOption(CATALOG_CHANNEL, "As its catalog (${catalogs.label})")) +
                        dev.droidtop.library.integrations.PluginChannel.entries.map { dev.droidtop.library.settings.ChoiceOption(it.id, it.label) },
                    current = channels.plugin(m.id)?.id ?: CATALOG_CHANNEL,
                    onSelect = { ctx, value ->
                        dev.droidtop.library.integrations.PluginChannels.forContext(ctx).setPlugin(m.id, dev.droidtop.library.integrations.PluginChannel.of(value))
                    },
                ),
            )
        }
    }

    // The plugin's own facts in droidtop's layout (docs/plugin-api.md 1.2 "Presentation", Droidtop/tracker#521 item 2),
    // so a plugin never writes its maker or its site into its description.
    val about = m.presentation
    val aboutGroup = listOfNotNull(
        about.publisher?.let { ActionItem(id = "plugin_${m.id}_publisher", title = "Made by", value = it) },
        about.category?.let { ActionItem(id = "plugin_${m.id}_category", title = "Kind", value = categoryLabel(it)) },
        about.homepage?.let { url ->
            ActionItem(
                id = "plugin_${m.id}_homepage",
                title = "Its page",
                subtitle = url.removePrefix("https://").removeSuffix("/"),
                value = "Open",
                run = { ctx ->
                    dev.droidtop.library.Links.open(ctx, url)
                },
            )
        },
    )

    val advancedGroup = listOf(
        ActionItem(id = "plugin_${m.id}_id", title = "Plugin id", subtitle = m.id),
        ActionItem(id = "plugin_${m.id}_origin", title = "Origin", subtitle = m.origin),
        ActionItem(id = "plugin_${m.id}_digest", title = "Archive digest", subtitle = record.archiveDigest),
        // docs/plugin-api.md 5.3: loads the plugin as a call would and shows what its process reached when it tried.
        AsyncActionItem(
            id = "plugin_${m.id}_containment",
            title = "Containment check",
            subtitle = "Where it runs, and whether it can reach the network, droidtop's files or shared storage by itself",
            run = { ctx, _ ->
                val policy = PluginCrashPolicy(ctx.applicationContext)
                try {
                    policy.containmentReport(record)
                } finally {
                    policy.shutdown()
                }
            },
        ),
    ) + listOfNotNull(
        // A debug build's crash-containment check (docs/SPEC.md 12a). Its status tile itself is in the plugin's panel.
        if (record.runnable() && PluginCapability.STATUS_TILE in m.capabilities && ctxIsDebuggable(context)) {
            AsyncActionItem(
                id = "plugin_${m.id}_force_crash",
                title = "Debug: force a crash",
                subtitle = "droidtop should survive and disable this plugin",
                confirmTitle = "Force ${m.label} to crash now?",
                run = { ctx, _ ->
                    val policy = PluginCrashPolicy(ctx.applicationContext)
                    try {
                        val result = policy.invoke(record, PluginCapability.STATUS_TILE, mapOf("query" to "force-crash"))
                        if (result.ok) "Unexpected success: ${result.values}" else "Crashed as expected: ${result.error}"
                    } finally {
                        policy.shutdown()
                    }
                },
            )
        } else {
            null
        },
    )

    // A runtime the plugin cannot run without goes straight under the status line that says so, where
    // the person is looking; an installed one (only its removal) stays further down.
    val runtimeGroupItem = if (runtimeGroup.isEmpty()) null else CatalogGroup(id = "plugin_${m.id}_runtime_group", title = "Runtime", items = runtimeGroup)
    val groups = listOfNotNull(
        CatalogGroup(
            id = "plugin_${m.id}_status_group",
            title = null,
            items = statusGroup,
            chips = if (isUnofficial(m.origin, userKeys)) listOf(CatalogChip("Unofficial")) else emptyList(),
        ),
        runtimeGroupItem.takeIf { runtimeNeed != null },
        useGroup.takeIf { it.isNotEmpty() }?.let { CatalogGroup(id = "plugin_${m.id}_use_group", title = null, items = it) },
    ).filterNot { it.items.isEmpty() && it.id == "plugin_${m.id}_status_group" } + newAccessGroups + listOfNotNull(
        consentGroup,
        permissionsGroup,
        CatalogGroup(id = "plugin_${m.id}_provides_group", title = "What it provides", items = providesGroup),
        engineCoresGroup(context, m.id),
        runtimeGroupItem.takeIf { runtimeNeed == null },
        aboutGroup.takeIf { it.isNotEmpty() }?.let { CatalogGroup(id = "plugin_${m.id}_about_group", title = "About", items = it) },
        CatalogGroup(id = "plugin_${m.id}_update_group", title = "Version", items = updateGroup),
        // Its id, origin, digest and containment check are for finding faults: Developer menu rows (docs/SPEC.md 7, "Developer menu";
        // the owner, 2026-10-17: "moving a lot of the advanced stuff (like the containment checker) to a dev menu").
        CatalogGroup(id = "plugin_${m.id}_advanced_group", title = "Advanced", items = advancedGroup).takeIf { DeveloperMenu.on(context) },
    )
    return pluginPageTop(context, record, userKeys, catalogNames, grantSnapshot, runtimeNeed, catalogOffer, approveAction, groups)
}

/**
 * An app's engine cores as it last listed them (`engines.cores`, docs/plugin-api.md 3 A7b, Droidtop/tracker#752):
 * installed ones first, then what its catalog can install. Null for a plugin that never listed any.
 */
internal fun AppSettingsCatalogs.engineCoresGroup(context: Context, pluginId: String): CatalogGroup? {
    val answer = dev.droidtop.library.integrations.EngineCoreList.answers(context)[pluginId] ?: return null
    if (answer.cores.isEmpty()) return null
    val items = answer.cores.sortedWith(compareBy({ !it.installed }, { it.label.lowercase() })).map { core ->
        ActionItem(
            id = "plugin_${pluginId}_core_${core.id}",
            title = core.label,
            subtitle = when {
                !core.installed -> listOfNotNull("Can be installed", core.availableVersion).joinToString(" ")
                else -> listOfNotNull("Installed", core.installedVersion).joinToString(" ") +
                    (core.availableVersion?.takeIf { core.updateAvailable }?.let { ", update $it waiting" } ?: "")
            },
        )
    }
    return CatalogGroup(id = "plugin_${pluginId}_cores_group", title = "Engine cores", items = items)
}

/** [pluginDetailPage]'s header and bar, around its [groups]. */
/** Signs in to [record]'s site from its account row, and tells the plugin it may read the account again. */
internal suspend fun AppSettingsCatalogs.signInFromRow(ctx: Context, record: dev.droidtop.pluginhost.PluginRecord, switch: Boolean): String {
    val m = record.manifest
    val web = dev.droidtop.pluginhost.PluginWebAccount
    val account = withContext(Dispatchers.IO) { web.of(ctx, record) } ?: return "${m.label} has no site account"
    if (!account.allowed) return "Turn sign-in on in ${m.label}'s Permissions first"
    // Sign-in waits for the web view to close: never on the main thread.
    val kept = withContext(Dispatchers.IO) { if (switch) web.switchAccount(ctx, record) else web.open(ctx, record, dev.droidtop.pluginhost.PluginWebAccount.Need.SIGN_IN) }
    if (!kept) return web.notKept(m.label, dev.droidtop.pluginhost.PluginWebAccount.Need.SIGN_IN)
    dev.droidtop.library.integrations.notifyWebSignedIn(ctx, record)
    return "Signed in to ${account.site}"
}

/**
 * A plugin's site account row (docs/SPEC.md 12a "A plugin's site account"). Signed out, pressing it opens the site's
 * sign-in straight away; signed in, its value is the account and pressing it offers Sign out, and Switch account
 * where the plugin declared a sign-in page.
 */
internal fun AppSettingsCatalogs.pluginAccountRow(record: dev.droidtop.pluginhost.PluginRecord, account: dev.droidtop.pluginhost.PluginWebAccount.Account): CatalogItem {
    val m = record.manifest
    val title = "${account.site} account"
    if (!account.signedIn) {
        return AsyncActionItem(
            id = "plugin_${m.id}_web_account",
            title = title,
            subtitle = if (account.allowed) "Your sign-in stays in droidtop: ${m.label} never sees your password" else "Sign-in is turned off for ${m.label} in its Permissions",
            value = account.state,
            run = { ctx, _ -> signInFromRow(ctx, record, switch = false) },
        )
    }
    return NestedScreenItem(
        id = "plugin_${m.id}_web_account",
        title = title,
        subtitle = "Your sign-in stays in droidtop: ${m.label} never sees your password",
        valueLabel = { account.state },
        inline = CatalogScreen(
            id = "plugin_${m.id}_web_account_screen",
            title = title,
            groups = { context ->
                val now = withContext(Dispatchers.IO) { dev.droidtop.pluginhost.PluginWebAccount.of(context, record) }
                listOf(
                    CatalogGroup(
                        id = "plugin_${m.id}_web_account_actions",
                        title = null,
                        items = buildList {
                            if (now == null || !now.signedIn) {
                                add(AsyncActionItem(id = "plugin_${m.id}_web_sign_in", title = "Sign in", value = "Not signed in", run = { ctx, _ -> signInFromRow(ctx, record, switch = false) }))
                            } else {
                                add(
                                    AsyncActionItem(
                                        id = "plugin_${m.id}_web_sign_out",
                                        title = "Sign out",
                                        value = now.state,
                                        confirmTitle = "Sign out of ${now.site}?",
                                        run = { ctx, _ ->
                                            if (withContext(Dispatchers.IO) { dev.droidtop.pluginhost.PluginWebAccount.signOut(ctx, record) }) "Signed out of ${now.site}" else "Already signed out"
                                        },
                                    ),
                                )
                                if (now.canSwitch) {
                                    add(
                                        AsyncActionItem(
                                            id = "plugin_${m.id}_web_switch",
                                            title = "Switch account",
                                            subtitle = "Signs out, then opens ${now.site}'s sign-in for another account",
                                            run = { ctx, _ -> signInFromRow(ctx, record, switch = true) },
                                        ),
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

internal fun AppSettingsCatalogs.pluginPageTop(
    context: Context,
    record: dev.droidtop.pluginhost.PluginRecord,
    userKeys: Map<String, UserOriginKey>,
    catalogNames: Map<String, String>,
    grants: PluginGrants.Snapshot,
    runtimeNeed: RuntimeNeed?,
    offer: dev.droidtop.library.integrations.PluginCatalog.Offer?,
    approve: (suspend (Context, Boolean) -> String)?,
    groups: List<CatalogGroup>,
): CatalogPage {
    val m = record.manifest
    val app = context.applicationContext
    /** Runs a page action that waits on dialogs or the network off the button, then says what happened. */
    fun later(action: suspend (Context) -> String?) {
        noticeScope.launch {
            val said = dev.droidtop.runtime.util.runCatchingCancellable { action(app) }.getOrElse { it.message ?: "That did not work" }
            said?.let { Notices.flash(it, source = m.label, context = app) }
            pluginsChanged(app)
        }
    }
    val state = pluginState(record, grants, runtimeNeed, offer != null, PluginApiResolver.current(context).waiting[m.id])
    val chips = listOfNotNull(
        CatalogChip(state, ok = state == "Running"),
        CatalogChip(if (isUnofficial(m.origin, userKeys)) "Unofficial" else pluginTrustBadge(m.origin, userKeys, catalogNames)),
        PluginTiers.badge(record, grants)?.let { CatalogChip("Full access") },
    )
    val icon = PluginStore.iconFile(context, record)
    val header = PageHeader(
        title = m.label,
        byline = listOfNotNull(m.presentation.publisher, "v${m.version}").joinToString(" · "),
        chips = chips,
        imagePath = icon,
    )
    val uninstall = PageButton(
        id = "plugin_${m.id}_uninstall",
        label = "Uninstall",
        role = ButtonRole.DANGER,
        confirmTitle = "Remove ${m.label} and its data?",
        run = { ctx -> PluginStore.uninstall(ctx, m.id); pluginsChanged(ctx) },
    )
    val buttons = mutableListOf<PageButton>()
    val more = mutableListOf<PageButton>()
    when {
        record.trust == PluginTrustState.PENDING && approve != null -> {
            buttons += PageButton(
                id = "plugin_${m.id}_approve",
                label = "Approve",
                role = ButtonRole.PRIMARY,
                run = { later { ctx -> approve(ctx, false).also { if (it == "Approved") Notices.flash("${m.label} is on", source = m.label, context = ctx) }.takeIf { it != "Approved" } } },
            )
            if (m.requestsRoot) {
                more += PageButton(
                    id = "plugin_${m.id}_approve_root",
                    label = "Approve and allow root",
                    confirmTitle = "Let \"${m.label}\" use root on this device?",
                    run = { later { ctx -> approve(ctx, true) } },
                )
            }
            more += PageButton(
                id = "plugin_${m.id}_deny",
                label = "Deny",
                role = ButtonRole.DANGER,
                confirmTitle = "Deny ${m.label}? It stays installed and never runs",
                run = { ctx -> pendingTicks.remove(m.id); PluginStore.setApproval(ctx, m.id, approved = false, grantRoot = false); pluginsChanged(ctx) },
            )
        }
        offer != null && (record.disabledReason != null || record.trust == PluginTrustState.APPROVED) -> buttons += PageButton(
            id = "plugin_${m.id}_update_button",
            label = "Update to ${offer.release.version}",
            role = ButtonRole.PRIMARY,
            run = { later { ctx -> withContext(Dispatchers.IO) { PluginCatalog.install(ctx, offer.plugin, offer.release) {} } } },
        )
        record.disabledReason != null -> buttons += PageButton(
            id = "plugin_${m.id}_retry",
            label = "Try again",
            role = ButtonRole.PRIMARY,
            run = { ctx -> PluginStore.setEnabled(ctx, m.id, true); pluginsChanged(ctx) },
        )
        record.trust == PluginTrustState.APPROVED && !record.enabled -> buttons += PageButton(
            id = "plugin_${m.id}_turn_on",
            label = "Turn on",
            role = ButtonRole.PRIMARY,
            run = { ctx -> PluginStore.setEnabled(ctx, m.id, true); pluginsChanged(ctx); Notices.flash("${m.label} is on", source = m.label, context = ctx) },
        )
    }
    if (record.trust == PluginTrustState.APPROVED && record.enabled && record.disabledReason == null) {
        more += PageButton(
            id = "plugin_${m.id}_turn_off",
            label = "Turn off",
            run = { ctx -> PluginStore.setEnabled(ctx, m.id, false); pluginsChanged(ctx); Notices.flash("${m.label} is off", source = m.label, context = ctx) },
        )
    }
    more += uninstall
    return CatalogPage(header = header, buttons = buttons, more = more, groups = groups)
}

/**
 * The runtime row of a plugin's own page: when the plugin cannot run without a runtime the device
 * lacks, the one labelled action that downloads it; when it is installed, its removal (the surface
 * asks for an explicit yes first). Null for a kind that needs no runtime.
 */
internal fun AppSettingsCatalogs.runtimeItem(context: Context, kind: PluginKind, forPluginLabel: String, need: RuntimeNeed?): CatalogItem? {
    if (need != null) {
        return AsyncActionItem(
            id = "plugins_${need.runtime.lowercase()}_runtime_download",
            title = need.actionLabel,
            subtitle = "$forPluginLabel needs it",
            run = { ctx, onStatus -> PluginRuntimeNeeds.install(ctx, need, onStatus) ?: "${need.runtime} runtime installed" },
        )
    }
    return when (kind) {
        PluginKind.PYTHON -> ActionItem(
            id = "plugins_python_runtime_remove",
            title = "Python runtime: installed",
            subtitle = "CPython ${PythonRuntimeManager.installedVersion(context) ?: PythonRuntimeManager.pinnedVersion(context)} (${PythonRuntimeManager.currentAbi()}); Python plugins stop until it is downloaded again",
            confirmTitle = "Remove the downloaded Python runtime?",
            run = { ctx -> PythonRuntimeManager.remove(ctx) },
        )
        PluginKind.FLUTTER_EMBED -> ActionItem(
            id = "plugins_flutter_runtime_remove",
            title = "Flutter runtime: installed",
            subtitle = "Flutter engine ${FlutterRuntimeManager.pinnedVersion(context)} (${FlutterRuntimeManager.currentAbi()}); Flutter plugins stop until it is downloaded again",
            confirmTitle = "Remove the downloaded Flutter runtime?",
            run = { ctx -> FlutterRuntimeManager.remove(ctx) },
        )
        else -> null
    }
}

/** The title of the access row of a plugin holding a permission with a caution (`gpu.render`, docs/plugin-api.md 5.3). */
private const val CAUTION_TITLE = "Could reach past droidtop's checks"

/** Where a notice's action that asks first (a red dialog) runs: the process's main scope, outliving the row that started it. */
internal val noticeScope = kotlinx.coroutines.MainScope()

/**
 * U4 (docs/SPEC.md 7, "Developer menu", "Notices"; Droidtop/tracker#481): a plugin running with full access says so quietly on its page
 * after it was allowed, with the way back. Limit access takes the grant away when the plugin can run with normal
 * protection, and otherwise turns the plugin off, since it cannot run any other way.
 */
internal fun AppSettingsCatalogs.hasFullAccessNotice(record: dev.droidtop.pluginhost.PluginRecord, grants: PluginGrants.Snapshot): Notice {
    val m = record.manifest
    val blocker = PluginTiers.containmentBlocker(m)
    return Notice(
        tone = NoticeTone.Caution,
        title = "Has full access",
        body = "It can use your files and go online freely.",
        details = accessLine(record, grants) +
            (blocker?.let { "\nThis plugin can only run with full access ($it), so Limit access turns it off." } ?: ""),
        action = NoticeAction("Limit access") { ctx ->
            withContext(Dispatchers.IO) {
                if (blocker == null && PluginTiers.declaresFullTrust(m)) {
                    PluginGrants.forContext(ctx).set(m.id, PluginTiers.FULL_TRUST, GrantState.DENIED)
                } else {
                    PluginStore.setEnabled(ctx, m.id, false)
                }
            }
            pluginsChanged(ctx)
        },
    )
}

/** A plugin that can only run with full access and was not allowed it: Allow asks in red (D1). */
internal fun AppSettingsCatalogs.needsFullAccessNotice(record: dev.droidtop.pluginhost.PluginRecord, refusal: String): Notice = Notice(
    tone = NoticeTone.Caution,
    title = "Needs full access to work",
    body = "It won't run until you allow it.",
    details = refusal,
    action = NoticeAction("Allow") { ctx ->
        if (PluginDangers.askFullAccess(record, update = false)) {
            withContext(Dispatchers.IO) { PluginGrants.forContext(ctx).set(record.manifest.id, PluginTiers.FULL_TRUST, GrantState.GRANTED) }
            pluginsChanged(ctx)
        }
    },
)

/** The subtitle of the full-access row (docs/plugin-api.md 4.6): the one warning, in plain words. */
internal fun AppSettingsCatalogs.accessLine(record: dev.droidtop.pluginhost.PluginRecord, grants: PluginGrants.Snapshot): String =
    "This plugin can do anything droidtop can. Only what it asks droidtop to do is listed under Activity. Allow it only if you trust the plugin."

/** One line of the Permissions screen: a permission, a high-risk point it may provide, or an export, with its current state. */
internal data class PermissionRow(val id: String, val label: String, val reason: String?, val tier: PermissionTier, val state: GrantState)

/** Every item [record]'s grants cover, in the manifest's order (docs/plugin-api.md 4.3, 4.4). */
internal fun AppSettingsCatalogs.permissionRows(
    record: dev.droidtop.pluginhost.PluginRecord,
    snap: PluginGrants.Snapshot,
    providerLabels: Map<String, String> = emptyMap(),
): List<PermissionRow> {
    val m = record.manifest
    val rows = mutableListOf<PermissionRow>()
    for (declared in m.v2.permissions) {
        if (declared.id.startsWith(PluginPermissions.PROVIDE_PREFIX)) continue
        val state = PluginGrants.stateOf(record, snap, declared.id) ?: continue
        val reason = listOfNotNull(PluginPermissions.find(declared.id)?.caution, declared.reason).joinToString(" - ").ifEmpty { null }
        rows += PermissionRow(declared.id, PluginPermissions.labelFor(declared.id) ?: providerLabels[declared.id] ?: declared.id, reason, PluginGrants.tierOf(declared), state)
    }
    for (entry in m.v2.provides) {
        val point = ExtensionPoints.find(entry.point) ?: continue
        val id = PluginPermissions.PROVIDE_PREFIX + entry.point
        val tier = when (point.risk) {
            dev.droidtop.pluginhost.PointRisk.CRITICAL -> PermissionTier.CRITICAL
            dev.droidtop.pluginhost.PointRisk.HIGH -> PermissionTier.DANGEROUS
            else -> PermissionTier.NORMAL
        }
        rows += PermissionRow(id, entry.titled(point.label), point.lets.ifEmpty { null }, tier, PluginGrants.provideState(record, snap, entry.point))
    }
    for (export in m.v2.exports) {
        rows += PermissionRow(PluginGrants.EXPORT_PREFIX + export.grantKey, export.offerLabel(), null, PermissionTier.NORMAL, PluginGrants.exportState(snap, export.grantKey))
    }
    return rows.distinctBy { it.id }
}

internal fun AppSettingsCatalogs.pluginPermissionsScreen(pluginId: String) = CatalogScreen(
    id = "plugin_permissions_$pluginId",
    title = "Permissions",
    subtitle = "A change takes effect on the plugin's next call",
    groups = { context -> withContext(Dispatchers.IO) { pluginPermissionGroups(context, pluginId) } },
    live = PluginGrants.changes,
)

/**
 * Accounts and sources > Plugins > one plugin > Activity (docs/plugin-api.md 4.6): the activity log, newest first. For a
 * contained plugin that is everything it reached beyond its own process that is logged; a full-trust plugin can also
 * act directly, which nothing can list, and the screen says so.
 */
internal fun AppSettingsCatalogs.pluginActivityScreen(pluginId: String) = CatalogScreen(
    id = "plugin_activity_$pluginId",
    title = "Activity",
    groups = { context -> withContext(Dispatchers.IO) { pluginActivityGroups(context, pluginId) } },
)

internal fun AppSettingsCatalogs.pluginActivityGroups(context: Context, pluginId: String): List<CatalogGroup> {
    val record = PluginStore.installed(context).firstOrNull { it.manifest.id == pluginId }
    val grants = PluginGrants.forContext(context).read(pluginId)
    val note = when {
        record == null -> "This plugin was removed; its activity is kept for 7 days"
        PluginTiers.of(record, grants) == PluginTier.FULL_TRUST ->
            "This plugin has full access; only what it asks droidtop to do is listed."
        PluginTiers.caution(record, grants) != null ->
            "Everything this plugin asks droidtop to do is listed here. One permission it holds could let it reach past droidtop's checks, and anything it did that way would not be listed."
        else ->
            "droidtop carries out everything this plugin does. Its network and shared-file use and every sensitive action are listed here; everyday things (its own data, short messages) are not."
    }
    val format = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
    val entries = PluginAudit.forContext(context).entries(pluginId).asReversed().take(ACTIVITY_SHOWN)
    val rows = entries.mapIndexed { i, e ->
        ActionItem(
            id = "plugin_${pluginId}_activity_$i",
            title = (PluginPermissions.labelFor(e.permission) ?: e.permission) + ": " + e.api + " " + e.op,
            subtitle = listOfNotNull(
                format.format(java.util.Date(e.atMs)),
                e.target.takeIf { it.isNotBlank() },
                if (e.result == "ok") null else "refused or failed: ${e.result}",
                e.via.takeIf { it.isNotEmpty() }?.let { "via ${it.joinToString(" > ")}" },
            ).joinToString(" - "),
        )
    }
    // Everyday calls are counted per day, not listed (docs/plugin-api.md 4.6, Droidtop/tracker#61).
    val days = PluginAudit.countsForContext(context).days(pluginId).take(COUNT_DAYS_SHOWN)
    val dayRows = days.map { (day, counts) ->
        ActionItem(
            id = "plugin_${pluginId}_counts_$day",
            title = day + " - " + counts.sumOf { it.second } + " calls",
            subtitle = counts.joinToString(", ") { (key, n) -> (PluginPermissions.labelFor(key) ?: key) + " " + n },
        )
    }
    return listOfNotNull(
        CatalogGroup(id = "plugin_activity_note", title = null, items = listOf(ActionItem(id = "plugin_activity_note_row", title = note))),
        CatalogGroup(
            id = "plugin_activity_entries",
            title = "Newest first",
            items = rows.ifEmpty { listOf(ActionItem(id = "plugin_activity_empty", title = "Nothing logged yet")) },
        ),
        dayRows.takeIf { it.isNotEmpty() }?.let { CatalogGroup(id = "plugin_activity_counts", title = "Everyday calls, counted by day", items = it) },
    )
}

/**
 * One plugin's messages (docs/SPEC.md 7, "Developer menu", "Notices"; docs/plugin-api.md 1.6): what droidtop cut, dropped or moved
 * to draw its pages, newest first, with Copy all for a bug report.
 */
internal fun AppSettingsCatalogs.pluginMessagesScreen(pluginId: String, label: String) = CatalogScreen(
    id = "plugin_messages_$pluginId",
    title = "Plugin messages",
    subtitle = label,
    groups = { context ->
        withContext(Dispatchers.IO) {
            val entries = dev.droidtop.pluginhost.PluginMessages.read(context, pluginId).asReversed()
            val format = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
            listOf(
                CatalogGroup(
                    id = "plugin_messages_actions",
                    title = null,
                    items = listOf(
                        ActionItem(
                            id = "plugin_messages_copy",
                            title = "Copy all",
                            run = { ctx ->
                                val clipboard = ctx.getSystemService(android.content.ClipboardManager::class.java)
                                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("$label messages", dev.droidtop.pluginhost.PluginMessages.asText(ctx, pluginId)))
                                dev.droidtop.library.settings.Notices.flash("Copied", context = ctx)
                            },
                        ),
                    ),
                ),
                CatalogGroup(
                    id = "plugin_messages_list",
                    title = "Newest first",
                    items = entries.mapIndexed { i, e ->
                        TextBlockItem(id = "plugin_messages_$i", text = e.text, title = "${format.format(java.util.Date(e.atMs))} · ${e.view}")
                    },
                ),
            )
        }
    },
)

/** After anything that changes which plugins run or what they may do: the status widget and background work follow. */
internal fun AppSettingsCatalogs.pluginsChanged(context: Context) {
    PluginStatusWidgetProvider.requestUpdate(context)
    PluginBackground.changed(context)
    // A store a plugin provides comes and goes with it (docs/plugin-api.md 3 A13).
    dev.droidtop.library.integrations.PluginStores.refreshAsync(context)
}

internal fun AppSettingsCatalogs.pluginPermissionGroups(context: Context, pluginId: String): List<CatalogGroup> {
    val installed = PluginStore.installed(context)
    val record = installed.firstOrNull { it.manifest.id == pluginId }
        ?: return listOf(
            CatalogGroup(
                id = "plugin_permissions_gone",
                title = null,
                items = listOf(ActionItem(id = "plugin_permissions_gone_row", title = "This plugin is no longer installed")),
            ),
        )
    val snap = PluginGrants.forContext(context).read(pluginId)
    val audit = PluginAudit.forContext(context)
    val providerLabels = installed.flatMap { it.manifest.v2.exports }.flatMap { it.permissions }.associate { it.id to it.label }
    val rows = permissionRows(record, snap, providerLabels)
    val format = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
    val dayFormat = java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT)
    val counts = PluginAudit.countsForContext(context)
    fun item(row: PermissionRow): CatalogItem {
        // A logged call has its time; an everyday one is only counted per day (Droidtop/tracker#60, #61), so its day.
        val lastUsed = audit.lastUsed(pluginId, row.id)?.let { "Last used ${format.format(java.util.Date(it))}" }
            ?: counts.lastDay(pluginId, row.id)?.let { day ->
                runCatching {
                    val start = java.time.LocalDate.parse(day).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                    "Last used ${dayFormat.format(java.util.Date(start))}"
                }.getOrNull()
            }
        val wanted = if (row.id in snap.wanted) "It tried to use this but could not ask you first" else null
        return dev.droidtop.library.settings.ChoiceItem(
            id = "plugin_${pluginId}_perm_${row.id}",
            title = row.label,
            subtitle = listOfNotNull(row.reason, wanted, lastUsed).joinToString(" - ").ifEmpty { null },
            options = listOf(
                // State chips with tones (Droidtop/tracker#519): what a state costs shows at a glance.
                dev.droidtop.library.settings.ChoiceOption(GrantState.GRANTED.name, "Allowed"),
                dev.droidtop.library.settings.ChoiceOption(GrantState.ASK.name, "Ask first", dev.droidtop.library.settings.NoticeTone.Caution),
                dev.droidtop.library.settings.ChoiceOption(GrantState.DENIED.name, "Blocked", dev.droidtop.library.settings.NoticeTone.Danger),
            ),
            current = row.state.name,
            onSelect = { ctx, value ->
                val state = GrantState.fromId(value)
                if (row.id == PluginTiers.FULL_TRUST && state == GrantState.GRANTED) {
                    // Droidtop/tracker#481, D1: allowed only through the red dialog; the screen reads again on the grant.
                    noticeScope.launch {
                        if (PluginDangers.askFullAccess(record, update = false)) {
                            withContext(Dispatchers.IO) { PluginGrants.forContext(ctx).set(pluginId, row.id, GrantState.GRANTED) }
                            pluginsChanged(ctx)
                        }
                    }
                } else {
                    state?.let { PluginGrants.forContext(ctx).set(pluginId, row.id, it) }
                    pluginsChanged(ctx)
                }
            },
        )
    }
    // "Where it appears" (docs/plugin-api.md 1.9): one switch per mode the plugin has a place in; off takes every
    // contribution of this plugin out of that mode, and nowhere else.
    val modeLabels = mapOf(PluginModes.GAMING to "Gaming", PluginModes.STANDARD to "Android home screen", PluginModes.DESKTOP to "Desktop")
    val modeItems = PluginModes.modesOf(record.manifest).map { mode ->
        ToggleItem(
            id = "plugin_${pluginId}_mode_$mode",
            title = modeLabels[mode] ?: mode,
            current = PluginModes.allowed(snap, mode),
            onToggle = { ctx, on ->
                PluginGrants.forContext(ctx).set(pluginId, PluginModes.key(mode), if (on) GrantState.GRANTED else GrantState.DENIED)
                pluginsChanged(ctx)
            },
        )
    }
    // And one switch per facet target it declares (docs/plugin-api.md 1.10, "The person arranges them"): off, that
    // place no longer asks the plugin. Its own page and its settings have none; they are where these switches are.
    val targetItems = dev.droidtop.pluginhost.FacetGuards.switchable(record.manifest.v2.provides).map { target ->
        ToggleItem(
            id = "plugin_${pluginId}_target_$target",
            title = dev.droidtop.pluginhost.FacetGuards.TARGET_LABELS[target] ?: target,
            current = dev.droidtop.pluginhost.FacetGuards.targetOn(snap, target),
            onToggle = { ctx, on ->
                PluginGrants.forContext(ctx).set(pluginId, dev.droidtop.pluginhost.FacetGuards.targetKey(target), if (on) GrantState.GRANTED else GrantState.DENIED)
                pluginsChanged(ctx)
            },
        )
    }
    // And the facets it has shown, a switch each, so one hidden is shown again here (the same section).
    val facetItems = PluginFacets.Seen.read(context, pluginId).flatMap { (target, seen) ->
        seen.map { (facetId, label) ->
            ToggleItem(
                id = "plugin_${pluginId}_facet_${target}_$facetId",
                title = label,
                subtitle = dev.droidtop.pluginhost.FacetGuards.TARGET_LABELS[target] ?: if (target == PluginFacets.PLUGIN_PAGE) "Its page" else if (target == PluginFacets.SETTINGS_PAGE) "Its settings" else target,
                current = dev.droidtop.pluginhost.FacetGuards.facetOn(snap, target, facetId),
                onToggle = { ctx, on ->
                    PluginGrants.forContext(ctx).set(pluginId, dev.droidtop.pluginhost.FacetGuards.facetKey(target, facetId), if (on) GrantState.GRANTED else GrantState.DENIED)
                    pluginsChanged(ctx)
                },
            )
        }
    }
    // Each background service and schedule has its own switch (docs/plugin-api.md 3 E8, E9), under the point's.
    val backgroundItems = record.manifest.v2.provides
        .filter { it.point == BackgroundProtocol.SERVICE_POINT || it.point == BackgroundProtocol.SCHEDULE_POINT }
        .map { entry ->
            val service = entry.point == BackgroundProtocol.SERVICE_POINT
            val every = BackgroundProtocol.everyMs(entry)
            ToggleItem(
                id = "plugin_${pluginId}_bg_${entry.point}_${BackgroundProtocol.entryId(entry)}",
                title = entry.label ?: record.manifest.label,
                subtitle = when {
                    service -> "Keeps running in the background"
                    every == null -> "Cannot run: its schedule is not between 15 minutes and 30 days"
                    else -> "Runs every ${every / 60_000} minutes"
                },
                current = BackgroundProtocol.entryOn(snap, entry),
                onToggle = { ctx, on ->
                    PluginGrants.forContext(ctx).set(pluginId, BackgroundProtocol.entryKey(entry), if (on) GrantState.GRANTED else GrantState.DENIED)
                    pluginsChanged(ctx)
                },
            )
        }
    val rest = rows
    val accessRow = run {
        val refusal = PluginTiers.refusal(record, snap)
        val badge = PluginTiers.badge(record, snap)
        when {
            refusal != null -> ActionItem(id = "plugin_permissions_access_row", title = if (PluginTiers.declaresFullTrust(record.manifest)) "Needs full access to work" else "Needs an update", subtitle = refusal)
            record.manifest.contractVersion < 2 -> ActionItem(id = "plugin_permissions_access_row", title = badge ?: "Full access (older plugin)", subtitle = "Written before permissions existed: it can do anything droidtop can, and only what it asks droidtop to do is listed under Activity")
            badge != null -> ActionItem(id = "plugin_permissions_access_row", title = badge, subtitle = accessLine(record, snap))
            // A permission's caution (gpu.render) is on its own line below, where the person can take it back.
            else -> null
        }
    }
    // A plugin that signs in to a download site droidtop also keeps a sign-in for borrows droidtop's, unless the
    // person turns that off here (docs/SPEC.md 12a "Download sites", Droidtop/tracker#648 slice 5).
    val sharedSites = dev.droidtop.pluginhost.DownloadSites.declaresAny(context, dev.droidtop.pluginhost.WebSessions.declaredDomains(record.manifest.v2.permissions))
    val sharingItem = sharedSites.takeIf { it.isNotEmpty() }?.let { sites ->
        ToggleItem(
            id = "plugin_permissions_download_sites",
            title = "Use your download-site sign-ins",
            subtitle = "For ${sites.joinToString { it.name }}: when ${record.manifest.label} has no sign-in of its own there, " +
                "it uses the one under Accounts and sources > Download sites. It never sees your password",
            current = dev.droidtop.pluginhost.DownloadSites.sharing(context, pluginId),
            onToggle = { ctx, on -> dev.droidtop.pluginhost.DownloadSites.setSharing(ctx, pluginId, on) },
        )
    }
    return listOfNotNull(
        accessRow?.let { CatalogGroup(id = "plugin_permissions_access", title = "Access", items = listOf(it)) },
        sharingItem?.let { CatalogGroup(id = "plugin_permissions_download_sites_group", title = "Download sites", items = listOf(it)) },
        (modeItems + targetItems).takeIf { it.isNotEmpty() }?.let { CatalogGroup("plugin_permissions_modes", "Where it appears", it) },
        facetItems.takeIf { it.isNotEmpty() }?.let { CatalogGroup("plugin_permissions_facets", "What it shows", it) },
        backgroundItems.takeIf { it.isNotEmpty() }?.let { CatalogGroup("plugin_permissions_background", "Background tasks", it) },
        rest.filter { it.tier == PermissionTier.CRITICAL }.takeIf { it.isNotEmpty() }?.let { CatalogGroup("plugin_permissions_critical", "Critical", it.map(::item)) },
        rest.filter { it.tier == PermissionTier.DANGEROUS }.takeIf { it.isNotEmpty() }?.let { CatalogGroup("plugin_permissions_dangerous", "Sensitive", it.map(::item)) },
        rest.filter { it.tier == PermissionTier.NORMAL }.takeIf { it.isNotEmpty() }?.let { CatalogGroup("plugin_permissions_normal", "Can", it.map(::item)) },
        if (rows.isEmpty()) {
            CatalogGroup(
                id = "plugin_permissions_none",
                title = null,
                items = listOf(ActionItem(id = "plugin_permissions_none_row", title = "This plugin asks for no permissions")),
            )
        } else {
            null
        },
    )
}

/** "Provided by" (docs/plugin-api.md 2.3): one choice per API that more than one running plugin provides. Nothing is ranked for the user. */
internal fun AppSettingsCatalogs.providedByGroups(resolution: ApiResolution, chosen: Map<String, String>): List<CatalogGroup> {
    val shared = resolution.providers.filter { it.value.size > 1 }
    if (shared.isEmpty()) return emptyList()
    return listOf(
        CatalogGroup(
            id = "plugins_provided_by",
            title = "Provided by",
            items = shared.map { (api, list) ->
                dev.droidtop.library.settings.ChoiceItem(
                    id = "plugins_provided_by_$api",
                    title = api,
                    subtitle = "Which plugin answers this for the plugins that use it",
                    options = list.map { dev.droidtop.library.settings.ChoiceOption(it.plugin.manifest.id, it.plugin.manifest.label) },
                    current = chosen[api]?.takeIf { id -> list.any { it.plugin.manifest.id == id } } ?: list.first().plugin.manifest.id,
                    onSelect = { ctx, value -> PluginProviderChoices.forContext(ctx).choose(api, value) },
                )
            },
        ),
    )
}

/** The plugin page's "Channel" value for following its catalog's channel. */
private const val CATALOG_CHANNEL = "catalog"

/** A manifest `category` in the person's words ([dev.droidtop.pluginhost.PluginPresentation.CATEGORIES]). */
internal fun AppSettingsCatalogs.categoryLabel(category: String): String =
    dev.droidtop.pluginhost.PluginPresentation.CATEGORY_LABELS.getValue(dev.droidtop.pluginhost.PluginPresentation.category(category))

/** What a plugin adds, in plain words, for the installed-list row and the detail page's provides row. */
internal fun AppSettingsCatalogs.pluginSummary(m: dev.droidtop.pluginhost.PluginManifest, enabledPoints: Set<String>): String =
    if (m.v2.provides.isNotEmpty()) {
        m.v2.provides
            .filter { PluginPermissions.PROVIDE_PREFIX + it.point in enabledPoints }
            .mapNotNull { ExtensionPoints.find(it.point)?.label }
            .distinct()
            .joinToString()
            .ifEmpty { "No extensions allowed" }
    } else {
        m.capabilities.joinToString { it.display }.ifEmpty { "No capabilities declared" }
    }

/**
 * The approval list of one plugin (docs/plugin-api.md 4.3): what it adds and where, what it can do, what it
 * asks for, what it offers and what it uses from other plugins, and what this droidtop does not support.
 * Built from the registries by [PluginConsent]. With [ticks] every item that has a grant key is a tick box
 * the user can switch off before approving, high-risk ones marked; the box state lives in [ticks] until the
 * Approve action writes it. Without [ticks] the list is read-only. [only] cuts the list down to what an
 * update added.
 */
internal fun AppSettingsCatalogs.pluginConsentGroups(
    context: Context,
    record: dev.droidtop.pluginhost.PluginRecord,
    userKeys: Map<String, UserOriginKey>,
    catalogNames: Map<String, String>,
    ticks: MutableSet<String>? = null,
    only: Set<String>? = null,
): List<CatalogGroup> {
    val m = record.manifest
    val scope = if (only == null) "" else "new_"
    val full = PluginConsent.of(m, PluginStore.installed(context)) { pluginTrustBadge(it, userKeys, catalogNames) }
    val view = if (only == null) full else full.only(only)
    fun info(key: String, title: String, subtitle: String? = null, value: String? = null) =
        ActionItem(id = "plugin_${m.id}_$scope$key", title = title, subtitle = subtitle, value = value)
    fun line(key: String, l: dev.droidtop.pluginhost.ConsentLine, lead: String? = null, trail: String? = null, value: String? = null): CatalogItem {
        val grantKey = l.id
        if (ticks == null || grantKey == null) {
            return info(key, l.title, PluginConsent.sentences(lead, l.detail, trail), value)
        }
        return ToggleItem(
            id = "plugin_${m.id}_${scope}tick_$grantKey",
            title = l.title,
            // Sentences, never fragments joined with " - " (Droidtop/tracker#516).
            subtitle = PluginConsent.sentences(lead, l.detail, trail, if (l.highRisk) "High risk: allow it only for a plugin you trust" else null),
            current = grantKey in ticks,
            onToggle = { _, on -> if (on) ticks.add(grantKey) else ticks.remove(grantKey) },
        )
    }
    fun group(key: String, title: String, items: List<CatalogItem>) =
        if (items.isEmpty()) null else CatalogGroup(id = "plugin_${m.id}_consent_$scope$key", title = title, items = items)
    return listOfNotNull(
        if (only != null) {
            null
        } else if (view.olderPluginFullAccess) {
            group("older", "Older plugin", listOf(info("older", "Full access (older plugin)", "Written before permissions existed: it can do everything it could before. Allow it only if you trust the plugin")))
        } else if (view.asksFullAccess) {
            group("access", "Access", listOf(info("access", "Asks for full access", "Allowed, it can do anything droidtop can; only what it asks droidtop to do is listed under Activity. Allow it only if you trust the plugin")))
        } else {
            // The safe default carries no access line: the lists below are the one thing the person decides.
            null
        },
        group(
            "adds", "Adds",
            view.adds.flatMap { (mode, lines) -> lines.mapIndexed { i, l -> line("adds_${mode}_$i", l, lead = placementWords(mode).takeIf { ticks != null }, value = placementWords(mode).takeIf { ticks == null }) } },
        ),
        group("can", "Can", view.can.mapIndexed { i, l -> line("can_$i", l) }),
        group(
            "asks", "Asks for",
            view.asks.mapIndexed { i, a ->
                line(
                    "asks_$i", a.line,
                    trail = if (a.needed) "Needed: if you leave it off, droidtop asks when the plugin first uses it" else null,
                    value = if (a.tier == PermissionTier.CRITICAL) "Critical" else "Asks first",
                )
            },
        ),
        group("offers", "Offers to other plugins", view.offers.mapIndexed { i, l -> line("offers_$i", l) }),
        group(
            "uses", "Uses from other plugins",
            view.uses.mapIndexed { i, u ->
                info(
                    "uses_$i", u.line.title,
                    PluginConsent.sentences(u.line.detail, if (u.optional) "It works without it" else "It needs this to work"),
                    if (u.providerLabel != null) "${u.providerLabel} (${u.providerBadge})" else "No plugin provides this yet",
                )
            },
        ),
        group("unsupported", "Not supported by this version of droidtop", view.unsupported.mapIndexed { i, t -> info("unsupported_$i", t) }),
    )
}

/** The most activity lines one plugin's Activity screen shows; the log keeps up to 2,000. */
private const val ACTIVITY_SHOWN = 200
private const val COUNT_DAYS_SHOWN = 7

/** The tick boxes of one approval list, kept between redraws until the user answers. Keyed by plugin, and by plugin plus "#new" for an update's list. */
private val pendingTicks = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()

internal fun AppSettingsCatalogs.tickBuffer(key: String, start: Set<String>): MutableSet<String> =
    pendingTicks.getOrPut(key) { java.util.Collections.synchronizedSet(start.toMutableSet()) }

// ------------------------------------------------------------------
// Keys you trust (docs/SPEC.md 12a): user-trusted plugin origin keys.
// ------------------------------------------------------------------

/**
 * A fetched key awaiting the user's one TOFU confirmation -- shown
 * as explicit rows, never trusted on fetch. [existing] is non-null
 * when the source now publishes a DIFFERENT key than the one already
 * trusted for that origin: then the only way forward is the
 * explicit replace, behind a warning naming both fingerprints.
 */
internal data class ProposedKey(
    val origin: String,
    val keyBase64: String,
    val sourceUrl: String,
    val existing: UserOriginKey?,
)

// Buffers between the text fields and their actions, the same
// pending-buffer shape the game folders screen uses for a typed path.
private var pendingKeySourceUrl = ""
private var pendingKeyManualOrigin = ""
private var pendingKeyManualKey = ""
private var pendingKeyProposal: ProposedKey? = null

/** "Apps you trust": the app certificates trusted for an app's plugin, each for its one app, with Stop trusting. */
internal suspend fun AppSettingsCatalogs.appKeysGroup(context: Context): CatalogGroup {
    val apps = withContext(Dispatchers.IO) { UserOriginKeys.appEntries(UserOriginKeys.storeFile(context)) }
    return CatalogGroup(
        id = "plugin_keys_apps",
        title = "Apps you trust",
        items = if (apps.isEmpty()) {
            listOf(ActionItem(id = "plugin_keys_apps_none", title = "No app keys added by you", subtitle = "An app's plugin signed by its maker's official key needs none"))
        } else {
            apps.entries.sortedBy { it.key }.flatMap { (packageName, cert) ->
                listOf(
                    ActionItem(
                        id = "plugin_key_app_$packageName",
                        title = packageName,
                        subtitle = "An app's plugin, trusted by you: NOT official. Key ${PluginDangers.groupsOfFour(cert)}",
                    ),
                    AsyncActionItem(
                        id = "plugin_key_app_${packageName}_remove",
                        title = "Stop trusting $packageName",
                        subtitle = "Its plugin stops running until you trust its key again",
                        confirmTitle = "Stop trusting the key $packageName is signed with?",
                        run = { ctx, _ ->
                            UserOriginKeys.forgetApp(UserOriginKeys.storeFile(ctx), packageName)
                            dev.droidtop.pluginhost.AppInterfaces.recheck(packageName)
                            pluginsChanged(ctx)
                            "No longer trusting $packageName's key"
                        },
                    ),
                )
            }
        },
    )
}

internal fun AppSettingsCatalogs.pluginKeysScreen() = CatalogScreen(
    id = SCREEN_PLUGIN_KEYS,
    title = "Keys you trust",
    subtitle = "Origins whose plugin signatures droidtop verifies. The official one is certified inside droidtop itself; " +
        "any other is one YOU chose to trust: third-party, not official, and droidtop has not vetted it",
    groups = { context ->
        val userKeys = withContext(Dispatchers.IO) { UserOriginKeys.load(UserOriginKeys.storeFile(context)) }
        // The pinned signing root is read from the APK's resources on first use: off the main thread.
        val rootFingerprint = withContext(Dispatchers.IO) { dev.droidtop.runtime.util.DroidtopSigningRoot.keySha256() }
        val proposal = pendingKeyProposal
        listOf(
            CatalogGroup(
                id = "plugin_keys_trusted",
                title = "Trusted origins",
                items = buildList {
                    add(
                        ActionItem(
                            id = "plugin_keys_official",
                            title = PluginOriginKeys.OFFICIAL_ORIGIN,
                            subtitle = "Official: certified inside droidtop itself. Signing root " +
                                (rootFingerprint?.take(16)?.chunked(4)?.joinToString(" ") ?: "not pinned in this build"),
                        ),
                    )
                    if (userKeys.isEmpty()) {
                        add(
                            ActionItem(
                                id = "plugin_keys_none",
                                title = "No keys added by you",
                                subtitle = "Only the official origin is trusted. Add a plugin source below and droidtop fetches its key, or paste one by hand",
                            ),
                        )
                    }
                    userKeys.values.sortedBy { it.origin }.forEach { entry ->
                        add(
                            ActionItem(
                                id = "plugin_key_${entry.origin}",
                                title = entry.origin,
                                subtitle = buildString {
                                    append(if (entry.catalog != null) "Unofficial, from the catalog ${entry.catalog}" else "Added by you")
                                    append(": third-party, NOT official. Key fingerprint ")
                                    append(UserOriginKeys.fingerprint(entry.keyBase64) ?: "unreadable")
                                    entry.source?.let { append(". Added from $it") }

                                },
                            ),
                        )
                        add(
                            AsyncActionItem(
                                id = "plugin_key_${entry.origin}_remove",
                                title = "Stop trusting \"${entry.origin}\"",
                                subtitle = "Plugins it signed stop running and can no longer be updated, until you trust it again",
                                confirmTitle = "Stop trusting \"${entry.origin}\"?",
                                run = { ctx, _ ->
                                    if (UserOriginKeys.remove(UserOriginKeys.storeFile(ctx), entry.origin)) {
                                        pluginsChanged(ctx)
                                        "No longer trusting \"${entry.origin}\": its plugins are flagged on the Plugins screen"
                                    } else {
                                        "Nothing to remove"
                                    }
                                },
                            ),
                        )
                    }
                },
            ),
            // The app keys the person trusted for an app's plugin (Droidtop/tracker#547): each for one app alone.
            appKeysGroup(context),
            CatalogGroup(
                id = "plugin_keys_source",
                title = "Add a plugin source",
                items = listOf(
                    TextInputItem(
                        id = "plugin_keys_source_url",
                        title = "Source address",
                        subtitle = "A GitHub plugin repo (https://github.com/<owner>/<repo>), a catalog index ending in .json, " +
                            "or a plain https address. droidtop fetches the key it publishes and asks you to trust it below; " +
                            "plain http addresses are refused",
                        value = pendingKeySourceUrl,
                        onChange = { _, v -> pendingKeySourceUrl = v.trim() },
                    ),
                    AsyncActionItem(
                        id = "plugin_keys_source_fetch",
                        title = "Fetch this source's key",
                        subtitle = "Trust-on-first-use: nothing is trusted until you confirm it below",
                        run = { ctx, onStatus -> fetchSourceKey(ctx, pendingKeySourceUrl, onStatus) },
                    ),
                ),
            ),
        ) + listOfNotNull(
            proposal?.let { proposedKeyGroup(it) },
            CatalogGroup(
                id = "plugin_keys_manual",
                title = "Add a key by hand",
                items = listOf(
                    TextInputItem(
                        id = "plugin_keys_manual_origin",
                        title = "Origin id",
                        subtitle = "The origin's own id, e.g. acme; every plugin it signs carries it in its name (<origin>.<name>). " +
                            "It can never be \"${PluginOriginKeys.OFFICIAL_ORIGIN}\": that is the official origin",
                        value = pendingKeyManualOrigin,
                        onChange = { _, v -> pendingKeyManualOrigin = v.trim() },
                    ),
                    TextInputItem(
                        id = "plugin_keys_manual_key",
                        title = "Certificate or public key",
                        subtitle = "The origin's certificate (or its public key), pasted exactly as its author publishes it",
                        // The value column beside a row's title is narrow:
                        // the whole ~124-char base64 squeezed this row's
                        // title and description out of the row once one
                        // was saved (the Origin id row above kept both,
                        // Droidtop/tracker#51), so the row shows a
                        // truncated prefix and the buffer keeps the full
                        // key "Add this key" below reads.
                        value = pendingKeyManualKey.take(16) + if (pendingKeyManualKey.length > 16) "…" else "",
                        multiline = true,
                        onChange = { _, v -> pendingKeyManualKey = v.trim() },
                    ),
                    AsyncActionItem(
                        id = "plugin_keys_manual_add",
                        title = "Add this key",
                        subtitle = "Shown as \"Added by you\" on every plugin it signs -- third-party, never official",
                        run = { ctx, _ -> addManualKey(ctx, pendingKeyManualOrigin, pendingKeyManualKey) },
                    ),
                    DocumentPickItem(
                        id = "plugin_keys_manual_file",
                        title = "Add key from a file",
                        subtitle = "A droidtop-plugin-key.json file (it holds the origin and the key), or a plain key file with the Origin id filled in above",
                        // Not application/json: many file managers label a
                        // .json as octet-stream, which a narrower filter
                        // would hide. The file is parsed before it is kept.
                        mimeType = "*/*",
                        onPicked = { ctx, uri -> importKeyFile(ctx, uri) },
                    ),
                ),
            ),
        )
    },
)

/** The fetched-key review: WHO, WHICH key, what trusting does NOT mean -- and the changed-key warning when it is one. */
internal fun AppSettingsCatalogs.proposedKeyGroup(proposal: ProposedKey): CatalogGroup {
    val fingerprint = UserOriginKeys.fingerprint(proposal.keyBase64) ?: "unreadable"
    val existingFingerprint = proposal.existing?.let { UserOriginKeys.fingerprint(it.keyBase64) ?: "unreadable" }
    return CatalogGroup(
        id = "plugin_keys_proposal",
        title = "Review before trusting",
        items = listOf(
            ActionItem(
                id = "plugin_keys_proposal_info",
                title = if (proposal.existing == null) {
                    "Origin \"${proposal.origin}\", from ${proposal.sourceUrl}"
                } else {
                    "WARNING: ${proposal.sourceUrl} now publishes a DIFFERENT key for \"${proposal.origin}\""
                },
                subtitle = if (proposal.existing == null) {
                    "Key fingerprint $fingerprint. Third-party, not official: droidtop has not vetted it, so trusting it " +
                        "is your call; plugins from it will show as \"Added by you\"."
                } else {
                    "You trusted fingerprint $existingFingerprint; this fetch publishes $fingerprint. A changed key can " +
                        "mean the source rotated it, or that the source or this fetch is compromised, and droidtop cannot " +
                        "tell the two apart. Nothing has been changed yet. Replacing the stored key keeps its plugins " +
                        "approved: each runs again once it updates to a version signed by the new key."
                },
            ),
            AsyncActionItem(
                id = "plugin_keys_proposal_trust",
                title = if (proposal.existing == null) {
                    "Trust origin \"${proposal.origin}\" from this source"
                } else {
                    "Replace the stored key for \"${proposal.origin}\""
                },
                subtitle = if (proposal.existing == null) {
                    "Updates from this source are then verified against this key automatically"
                } else {
                    "Only do this if you have reason to believe the source really rotated its key"
                },
                // The fingerprints first: an armed row on the handheld shows this question in two lines at most.
                confirmTitle = if (proposal.existing == null) {
                    "Trust key $fingerprint for origin \"${proposal.origin}\" and everything it signs?"
                } else {
                    "Trust new key $fingerprint for \"${proposal.origin}\" in place of $existingFingerprint? Its plugins keep " +
                        "their approval when they update."
                },
                run = { ctx, _ -> commitTrustedKey(ctx, proposal) },
            ),
            ActionItem(
                id = "plugin_keys_proposal_discard",
                title = "Discard",
                subtitle = "Trust nothing, change nothing",
                run = { _ -> pendingKeyProposal = null },
            ),
        ),
    )
}

/**
 * The fetch half of trust-on-first-use: fetches the source's
 * published key (validating it) and only ever PROPOSES it -- the
 * group above is where the user decides. Runs on the renderers' IO
 * dispatcher (an [AsyncActionItem] run), like every other network
 * action on this screen.
 */
internal fun AppSettingsCatalogs.fetchSourceKey(context: Context, url: String, onStatus: (String) -> Unit): String {
    if (url.isBlank()) return "Type the source's address first"
    onStatus("Fetching $url...")
    return when (val fetched = PluginSourceKeys.fetchKey(url, dev.droidtop.net.GitHubTokenStore.get(context))) {
        is PluginSourceKeys.FetchResult.Failed -> fetched.reason
        is PluginSourceKeys.FetchResult.Fetched -> {
            val published = fetched.key
            val existing = UserOriginKeys.load(UserOriginKeys.storeFile(context))[published.origin]
            when {
                existing != null && UserOriginKeys.fingerprint(published.keyBase64) == UserOriginKeys.fingerprint(existing.keyBase64) ->
                    "Origin \"${published.origin}\" is already trusted with this same key -- nothing to do"
                existing != null -> {
                    pendingKeyProposal = ProposedKey(published.origin, published.keyBase64, url, existing)
                    "This source now publishes a DIFFERENT key for \"${published.origin}\" than you trusted. Review the warning below -- nothing has been changed."
                }
                else -> {
                    pendingKeyProposal = ProposedKey(published.origin, published.keyBase64, url, null)
                    "Origin \"${published.origin}\" wants to be trusted. Review it below -- nothing is trusted until you confirm."
                }
            }
        }
    }
}

/** The confirm half: writes the proposal (add, or the explicitly-confirmed replace) and clears it. */
internal fun AppSettingsCatalogs.commitTrustedKey(context: Context, proposal: ProposedKey): String {
    val store = UserOriginKeys.storeFile(context)
    val outcome = if (proposal.existing == null) {
        UserOriginKeys.add(store, proposal.origin, proposal.keyBase64, proposal.sourceUrl)
    } else {
        UserOriginKeys.replace(store, proposal.origin, proposal.keyBase64, proposal.sourceUrl)
    }
    val message = when (outcome) {
        is AddKeyOutcome.Added ->
            if (proposal.existing == null) {
                "Origin \"${proposal.origin}\" is now trusted -- plugins it signs will verify against this key"
            } else {
                val restored = PluginStore.keyReplaced(context, proposal.origin)
                "Replaced the key for \"${proposal.origin}\". Its plugins update under the new key and keep their approval" +
                    if (restored > 0) ". $restored waiting for approval because of the change were approved again" else ""
            }
        AddKeyOutcome.AlreadyTrustedSameKey -> "Origin \"${proposal.origin}\" is already trusted with this same key"
        is AddKeyOutcome.KeyChanged ->
            "The stored key for \"${proposal.origin}\" changed since this fetch -- fetch the source again and review the warning"
        is AddKeyOutcome.Refused -> "Refused: ${outcome.reason}"
    }
    pendingKeyProposal = null
    PluginStatusWidgetProvider.requestUpdate(context)
    return message
}

internal fun AppSettingsCatalogs.addManualKey(context: Context, origin: String, key: String): String {
    if (origin.isBlank()) return "Type the origin's id first"
    if (key.isBlank()) return "Paste the origin's public key first"
    return addKeyOutcomeMessage(UserOriginKeys.add(UserOriginKeys.storeFile(context), origin, key, source = null))
}

/**
 * A picked key file: the published JSON shape (origin + key, the
 * same two fields a source publishes) or, failing that, a raw
 * base64 key file whose origin comes from the Origin id field.
 * Runs on the renderers' IO dispatcher, like every other
 * [DocumentPickItem] onPicked.
 */
internal fun AppSettingsCatalogs.importKeyFile(context: Context, uri: Uri): String {
    val text = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull() ?: return "Couldn't read that file"
    val store = UserOriginKeys.storeFile(context)
    PluginSourceKeys.parsePublishedKey(text)?.let { published ->
        return addKeyOutcomeMessage(UserOriginKeys.add(store, published.origin, published.keyBase64, source = null))
    }
    if (pendingKeyManualOrigin.isBlank()) {
        return "That file isn't a droidtop-plugin-key.json (it needs \"origin\" and \"key\"), " +
            "and the Origin id field above is empty; fill it in to add a plain key file"
    }
    return addKeyOutcomeMessage(UserOriginKeys.add(store, pendingKeyManualOrigin, text, source = null))
}

/** The one wording for what [UserOriginKeys.add] decided, shared by every manual add path. */
internal fun AppSettingsCatalogs.addKeyOutcomeMessage(outcome: AddKeyOutcome): String = when (outcome) {
    is AddKeyOutcome.Added ->
        "Origin \"${outcome.entry.origin}\" is now trusted -- plugins it signs will verify against this key"
    AddKeyOutcome.AlreadyTrustedSameKey ->
        "That origin is already trusted with this same key"
    is AddKeyOutcome.KeyChanged ->
        "Refused: \"${outcome.stored.origin}\" is already trusted with a DIFFERENT key. If its source really rotated " +
            "the key, add the source's address above and fetch it so you can compare both fingerprints -- or stop " +
            "trusting the origin first, then add the new key."
    is AddKeyOutcome.Refused -> "Refused: ${outcome.reason}"
}

// ------------------------------------------------------------------
// Scraper source + credentials.
// ------------------------------------------------------------------
