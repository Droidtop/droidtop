package dev.droidtop.library.integrations

import android.content.Context
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogChip
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.DocumentPickItem
import dev.droidtop.library.settings.NestedScreenItem
import dev.droidtop.library.settings.TextBlockItem
import dev.droidtop.library.settings.TextInputItem
import dev.droidtop.library.settings.ToggleItem
import dev.droidtop.pluginhost.PluginRecord
import dev.droidtop.pluginhost.PluginStore
import dev.droidtop.pluginhost.ProvidedPoint
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * "Get apps" (docs/SPEC.md 10b "Installing apps", Droidtop/tracker#261): every app catalog plugin
 * ([AppCatalogs]) drawn by droidtop, the sibling of Get games. Per catalog: a search, its sources (repositories or
 * tracked pages: each with its address, key fingerprint, apps, last refresh, on/off, Refresh and Remove), the known
 * sources it lists to add with a press, and Add by address or QR code. An app's page shows its summary and
 * description, its anti-features with the catalog's reasons, who signs it, and Install / Update to / Installed per
 * version. Adding a source is the same review however it starts (a link through [LinkRouter], a QR code, the
 * address field, a known source): the plugin's review, then the person's Accept.
 *
 * These screens call the plugin (its index lives there) and read one installed package for an app's page; they
 * never walk anything, and a list's rows come from one reply.
 */
object AppCatalogScreen {
    const val ID = "apps_get"

    /** The review a link opened ([LinkRouter]); registered so the link host can open it. */
    const val REVIEW_ID = "apps_source_review"

    private val queries = ConcurrentHashMap<String, String>()
    private val addresses = ConcurrentHashMap<String, String>()

    /** The review waiting for the person's answer: which catalog, and what it said. */
    @Volatile private var pending: Pair<PluginRecord, AppCatalogs.Review>? = null

    /** Called for a link a catalog plugin declared ([LinkRouter.registerPoint]): fetches its review and opens it. */
    val linkHandler: LinkPointHandler = { context, record, _: ProvidedPoint, link ->
        AppCatalogs.review(context, record, link).fold(
            onSuccess = { review ->
                pending = record to review
                LinkRouter.Result.Open(REVIEW_ID)
            },
            onFailure = { LinkRouter.Result.Message("${record.manifest.label}: ${it.message}") },
        )
    }

    fun screen(): CatalogScreen = CatalogScreen(
        id = ID,
        title = "Get apps",
        subtitle = "Android apps from the catalogs your plugins add. droidtop checks each app's package and signing key before Android installs it",
        groups = { context -> withContext(Dispatchers.IO) { rootGroups(context) } },
        indexGroups = { emptyList() },
    )

    fun reviewScreen(): CatalogScreen = CatalogScreen(
        id = REVIEW_ID,
        title = "Add a source",
        groups = { _ ->
            val waiting = pending
            if (waiting == null) {
                listOf(CatalogGroup("apps_review_none", null, listOf(ActionItem("apps_review_none_row", "Nothing is waiting to be added", run = {}))))
            } else {
                listOf(reviewGroup(waiting.first, waiting.second))
            }
        },
        indexGroups = { emptyList() },
    )

    private suspend fun rootGroups(context: Context): List<CatalogGroup> {
        val providers = AppCatalogs.providers(context)
        if (providers.isEmpty()) {
            val waiting = PluginStore.installed(context).any { record -> record.manifest.v2.provides.any { it.point == AppCatalogs.POINT } }
            return listOf(
                CatalogGroup(
                    "apps_none",
                    null,
                    listOf(
                        ActionItem(
                            "apps_none_row",
                            if (waiting) "An app catalog plugin is installed but not allowed yet" else "No app catalog is added",
                            subtitle = if (waiting) {
                                "Open it on the Plugins screen and allow \"Android apps from a catalog\""
                            } else {
                                "Apps come from catalog plugins, such as one that reads F-Droid repositories. Plugins > Add lists them"
                            },
                            run = {},
                        ),
                    ),
                ),
            )
        }
        return providers.map { record -> catalogGroup(context, record) }
    }

    private suspend fun catalogGroup(context: Context, record: PluginRecord): CatalogGroup {
        val id = record.manifest.id
        val query = queries[id].orEmpty()
        val items = buildList<CatalogItem> {
            add(
                NestedScreenItem(
                    id = "apps_sources_$id",
                    title = "Sources",
                    subtitle = "Where ${record.manifest.label} reads apps from. Add, switch off, refresh or remove them",
                    inline = sourcesScreen(record),
                ),
            )
            add(
                TextInputItem(
                    id = "apps_search_$id",
                    title = "Search ${record.manifest.label}",
                    subtitle = "A name, a word from its description, or a package name",
                    value = query,
                    onChange = { _, value -> queries[id] = value.trim() },
                ),
            )
            if (query.isNotBlank()) {
                AppCatalogs.search(context, record, query).fold(
                    onSuccess = { rows ->
                        if (rows.isEmpty()) add(ActionItem("apps_search_none_$id", "Nothing matches \"$query\"", run = {}))
                        rows.forEach { add(appRow(record, it)) }
                    },
                    onFailure = { add(ActionItem("apps_search_failed_$id", "${record.manifest.label}: ${it.message}", run = {})) },
                )
            }
        }
        return CatalogGroup("apps_catalog_$id", record.manifest.label, items)
    }

    private fun appRow(record: PluginRecord, row: AppCatalogs.AppRow): CatalogItem = NestedScreenItem(
        id = "apps_app_${record.manifest.id}_${row.id}",
        title = row.name,
        subtitle = listOfNotNull(row.summary, row.source?.let { "from $it" }).joinToString(" - ").ifEmpty { null },
        inline = appScreen(record, row.id, row.name),
        chip = if (row.antiFeatures.isNotEmpty()) "Anti-features" else null,
    )

    // ------------------------------------------------------------------
    // Sources.
    // ------------------------------------------------------------------

    private fun sourcesScreen(record: PluginRecord): CatalogScreen = CatalogScreen(
        id = "apps_sources_screen_${record.manifest.id}",
        title = "Sources",
        subtitle = "From ${record.manifest.label}. Nothing is added until you accept its review",
        groups = { context -> withContext(Dispatchers.IO) { sourcesGroups(context, record) } },
        indexGroups = { emptyList() },
    )

    private suspend fun sourcesGroups(context: Context, record: PluginRecord): List<CatalogGroup> {
        val waiting = pending
        // The review is the whole screen while there is one, as with plugin catalogs: Accept is never below a form.
        if (waiting != null && waiting.first.manifest.id == record.manifest.id) return listOf(reviewGroup(record, waiting.second))
        val id = record.manifest.id
        val listed = AppCatalogs.sources(context, record)
        val sources = listed.getOrNull()
        return listOfNotNull(
            CatalogGroup(
                "apps_sources_list_$id",
                "Your sources",
                buildList<CatalogItem> {
                    if (sources == null) add(ActionItem("apps_sources_failed", "${record.manifest.label}: ${listed.exceptionOrNull()?.message}", run = {}))
                    if (sources != null && sources.sources.isEmpty()) {
                        add(ActionItem("apps_sources_empty", "None added", subtitle = "Add one below: from the list, by its address or its QR code", run = {}))
                    }
                    sources?.sources?.forEach { source ->
                        add(
                            NestedScreenItem(
                                id = "apps_source_${id}_${source.id.hashCode()}",
                                title = source.name,
                                subtitle = listOfNotNull(
                                    source.apps?.let { "$it apps" },
                                    source.updated?.let { "refreshed " + relative(it) } ?: "not refreshed yet",
                                    if (source.enabled) null else "switched off",
                                ).joinToString(" - "),
                                inline = sourceScreen(record, source),
                            ),
                        )
                    }
                    if (!sources?.sources.isNullOrEmpty()) {
                        add(
                            AsyncActionItem(
                                id = "apps_sources_refresh_$id",
                                title = "Refresh all",
                                subtitle = "Fetches every source's index again and checks its signature",
                                run = { ctx, onStatus -> AppCatalogs.refresh(ctx, record, null, onStatus) },
                            ),
                        )
                    }
                },
            ),
            sources?.known?.takeIf { it.isNotEmpty() }?.let { known ->
                CatalogGroup(
                    "apps_sources_known_$id",
                    "Sources you can add",
                    known.map { k ->
                        AsyncActionItem(
                            id = "apps_sources_known_${id}_${k.address.hashCode()}",
                            title = k.name,
                            subtitle = listOfNotNull(k.about, k.address).joinToString(" - "),
                            value = "Review",
                            run = { ctx, onStatus -> fetchReview(ctx, record, k.address, onStatus) },
                        )
                    },
                )
            },
            CatalogGroup(
                "apps_sources_add_$id",
                "Add by address",
                listOf(
                    TextInputItem(
                        id = "apps_sources_address_$id",
                        title = "Address",
                        subtitle = "The source's address or link, with its fingerprint when you have it",
                        value = addresses[id].orEmpty(),
                        onChange = { _, v -> addresses[id] = v.trim() },
                    ),
                    AsyncActionItem(
                        id = "apps_sources_fetch_$id",
                        title = "Fetch this source",
                        subtitle = "Shows its name, key and apps; nothing is added until you accept",
                        run = { ctx, onStatus -> fetchReview(ctx, record, addresses[id].orEmpty(), onStatus) },
                    ),
                    DocumentPickItem(
                        id = "apps_sources_qr_$id",
                        title = "Read a QR code",
                        subtitle = "A photo or screenshot of the source's QR code",
                        mimeType = "image/*",
                        onPicked = { ctx, uri ->
                            val text = PluginCatalogSources.qrText(ctx, uri)
                            if (text == null) {
                                "No QR code found in that image"
                            } else {
                                addresses[id] = text.trim()
                                fetchReview(ctx, record, text.trim()) {}
                            }
                        },
                    ),
                ),
            ),
        )
    }

    private suspend fun fetchReview(context: Context, record: PluginRecord, address: String, onStatus: (String) -> Unit): String {
        if (address.isBlank()) return "Type the source's address first"
        onStatus("Fetching $address...")
        return AppCatalogs.review(context, record, address).fold(
            onSuccess = { review ->
                pending = record to review
                "Fetched \"${review.name}\". Read its review on this screen; nothing is added until you accept it."
            },
            onFailure = { it.message ?: "It could not be read" },
        )
    }

    /** The review before adding: what the source is, its key's fingerprint and whether the link vouched for it, then Accept or Discard. */
    private fun reviewGroup(record: PluginRecord, review: AppCatalogs.Review): CatalogGroup = CatalogGroup(
        id = "apps_review",
        title = "Review before adding",
        chips = listOf(CatalogChip("From ${record.manifest.label}")),
        items = buildList<CatalogItem> {
            add(
                TextBlockItem(
                    id = "apps_review_who",
                    title = review.name,
                    text = "This source is not part of droidtop and droidtop has not checked its apps. Add it only if you trust " +
                        "the people who run it. Each app is still checked against the key this source names before Android installs it.",
                ),
            )
            review.description?.let { add(TextBlockItem(id = "apps_review_description", title = "", text = it)) }
            add(ActionItem("apps_review_address", review.address, subtitle = "Where it is read from", run = {}))
            add(
                ActionItem(
                    "apps_review_key",
                    "Signing key",
                    subtitle = when {
                        review.fingerprint == null -> "The source is not signed"
                        review.fingerprintFromLink -> "Matches the fingerprint in the link"
                        else -> "The link named no fingerprint. Compare this one with the source's own page before you accept"
                    },
                    value = review.fingerprint?.let(::grouped) ?: "None",
                    run = {},
                ),
            )
            review.apps?.let { add(ActionItem("apps_review_apps", "Apps", value = it.toString(), run = {})) }
            review.note?.let { add(ActionItem("apps_review_note", "Note", subtitle = it, run = {})) }
            if (review.existing) {
                add(ActionItem("apps_review_existing", "Already added", subtitle = "Nothing to do", run = { pending = null }))
            } else {
                add(
                    AsyncActionItem(
                        id = "apps_review_accept",
                        title = "Accept and add",
                        subtitle = "${record.manifest.label} fetches its index now. Its apps are then listed and can be installed",
                        confirmTitle = "Add \"${review.name}\"?",
                        run = { ctx, onStatus ->
                            pending = null
                            AppCatalogs.accept(ctx, record, review, onStatus)
                        },
                    ),
                )
            }
            add(ActionItem("apps_review_discard", "Discard", subtitle = "Add nothing", run = { _ -> pending = null }))
        },
    )

    private fun sourceScreen(record: PluginRecord, source: AppCatalogs.Source): CatalogScreen = CatalogScreen(
        id = "apps_source_screen_${record.manifest.id}_${source.id.hashCode()}",
        title = source.name,
        subtitle = "From ${record.manifest.label}",
        groups = { context ->
            // Read again, so a setting changed on this page shows its new value.
            val fresh = withContext(Dispatchers.IO) {
                AppCatalogs.sources(context, record).getOrNull()?.sources?.firstOrNull { it.id == source.id }
            } ?: source
            listOf(
                CatalogGroup(
                    "apps_source_${fresh.id.hashCode()}",
                    null,
                    buildList<CatalogItem> {
                        add(ActionItem("apps_source_address", fresh.address, subtitle = "Address", run = {}))
                        fresh.fingerprint?.let { add(ActionItem("apps_source_key", "Signing key", value = grouped(it), run = {})) }
                        fresh.note?.let { add(ActionItem("apps_source_note", "Note", subtitle = it, run = {})) }
                        // The source's own settings, as the plugin describes them (AppCatalogs.SourceOption).
                        fresh.options.forEach { option -> add(optionItem(record, fresh, option)) }
                        add(
                            ToggleItem(
                                id = "apps_source_enabled",
                                title = "Use this source",
                                subtitle = "Off: its apps are not listed or offered as updates, and it is kept",
                                current = fresh.enabled,
                                onToggle = { ctx, value ->
                                    AppCatalogs.quick(ctx, record, "set_source_enabled", JSONObject().put("source", fresh.id).put("enabled", value), "Done")
                                    AppCatalogs.refreshOffers(ctx, record)
                                },
                            ),
                        )
                        add(
                            AsyncActionItem(
                                id = "apps_source_refresh",
                                title = "Refresh",
                                subtitle = "Fetches its index again and checks its signature",
                                run = { ctx, onStatus -> AppCatalogs.refresh(ctx, record, fresh.id, onStatus) },
                            ),
                        )
                        add(
                            AsyncActionItem(
                                id = "apps_source_remove",
                                title = "Remove this source",
                                subtitle = "Its apps are no longer listed or updated from it. Apps you installed stay",
                                confirmTitle = "Remove \"${fresh.name}\"?",
                                run = { ctx, _ ->
                                    val message = AppCatalogs.quick(ctx, record, "remove_source", JSONObject().put("source", fresh.id), "Removed ${fresh.name}")
                                    AppCatalogs.refreshOffers(ctx, record)
                                    message
                                },
                            ),
                        )
                    },
                ),
            )
        },
        indexGroups = { emptyList() },
    )

    private fun optionItem(record: PluginRecord, source: AppCatalogs.Source, option: AppCatalogs.SourceOption): CatalogItem {
        val id = "apps_source_option_${option.name}"
        return when (option.kind) {
            "bool" -> ToggleItem(
                id = id,
                title = option.label,
                subtitle = option.description,
                current = option.value.equals("true", ignoreCase = true),
                onToggle = { ctx, value -> AppCatalogs.setSourceOption(ctx, record, source.id, option, value) },
            )
            "choice" -> dev.droidtop.library.settings.ChoiceItem(
                id = id,
                title = option.label,
                subtitle = option.description,
                options = option.choices.map { (value, label) -> dev.droidtop.library.settings.ChoiceOption(value, label) },
                current = option.value,
                // ChoiceItem's callback is not suspend: the change is sent off the main thread.
                onSelect = { ctx, value ->
                    kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { AppCatalogs.setSourceOption(ctx, record, source.id, option, value) }
                },
            )
            else -> TextInputItem(
                id = id,
                title = option.label,
                subtitle = option.description,
                value = option.value,
                onChange = { ctx, value -> AppCatalogs.setSourceOption(ctx, record, source.id, option, value) },
            )
        }
    }

    // ------------------------------------------------------------------
    // An app's page.
    // ------------------------------------------------------------------

    fun appScreen(record: PluginRecord, appId: String, name: String): CatalogScreen = CatalogScreen(
        id = "apps_app_screen_${record.manifest.id}_$appId",
        title = name,
        subtitle = "From ${record.manifest.label}",
        groups = { context -> withContext(Dispatchers.IO) { appGroups(context, record, appId) } },
        indexGroups = { emptyList() },
    )

    private suspend fun appGroups(context: Context, record: PluginRecord, appId: String): List<CatalogGroup> {
        val page = AppCatalogs.app(context, record, appId).getOrElse { error ->
            return listOf(CatalogGroup("apps_app_failed", null, listOf(ActionItem("apps_app_failed_row", "${record.manifest.label}: ${error.message}", run = {}))))
        }
        val installed = AppPackages.installedFacts(context, appId)
        val newest = page.versions.maxByOrNull { it.versionCode }
        val groups = mutableListOf<CatalogGroup>()
        groups += CatalogGroup(
            "apps_app_about",
            null,
            buildList<CatalogItem> {
                add(TextBlockItem("apps_app_summary", title = page.name, text = page.summary ?: page.id))
                add(ActionItem("apps_app_installed", "Installed", value = installed?.let { it.versionName ?: it.versionCode.toString() } ?: "No", run = {}))
                page.description?.let { add(TextBlockItem("apps_app_description", title = "", text = it)) }
            },
            chips = listOfNotNull(page.license?.let { CatalogChip(it) }, page.source?.let { CatalogChip(it) }),
        )
        newest?.antiFeatures?.takeIf { it.isNotEmpty() }?.let { features ->
            groups += CatalogGroup(
                "apps_app_antifeatures",
                "Anti-features",
                features.map { f -> ActionItem("apps_app_af_${f.key}", f.label, subtitle = f.reason, run = {}) },
            )
        }
        groups += CatalogGroup(
            "apps_app_versions",
            "Versions",
            page.versions.sortedByDescending { it.versionCode }.map { version -> versionRow(record, page, version, installed) },
        )
        groups += CatalogGroup(
            "apps_app_facts",
            "Details",
            buildList<CatalogItem> {
                add(ActionItem("apps_app_package", "Package", value = page.id, run = {}))
                val signers = newest?.signers.orEmpty()
                add(
                    ActionItem(
                        "apps_app_signer",
                        "Signed by",
                        subtitle = when {
                            signers.isEmpty() -> "The catalog names no key; droidtop checks the download against the installed app's key"
                            installed != null && installed.signers.isNotEmpty() && signers.none { it in installed.signers } ->
                                "A different key from the installed app's: Android would refuse this version over it"
                            else -> "droidtop checks the download is signed by this key"
                        },
                        value = signers.firstOrNull()?.let(::grouped) ?: "Not named",
                        run = {},
                    ),
                )
                page.author?.let { add(ActionItem("apps_app_author", "Author", value = it, run = {})) }
                page.website?.let { add(ActionItem("apps_app_web", "Website", subtitle = it, run = {})) }
                page.sourceCode?.let { add(ActionItem("apps_app_code", "Source code", subtitle = it, run = {})) }
            },
        )
        return groups
    }

    private fun versionRow(record: PluginRecord, page: AppCatalogs.AppPage, version: AppCatalogs.AppVersion, installed: AppCatalogs.PackageFacts?): CatalogItem {
        val id = "apps_app_v${version.versionCode}"
        val subtitle = listOfNotNull(
            version.size?.let { "${it / (1024 * 1024) + 1} MB" },
            version.minSdk?.let { "Android API $it or later" },
            version.antiFeatures.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "Anti-features: ") { it.label },
            version.whatsNew,
        ).joinToString(" - ").ifEmpty { null }
        val installedCode = installed?.versionCode
        return when {
            installedCode != null && installedCode == version.versionCode -> ActionItem(id, version.version, subtitle = subtitle, value = "Installed", run = {})
            installedCode != null && installedCode > version.versionCode -> ActionItem(id, version.version, subtitle = subtitle, value = "Older than installed", run = {})
            else -> {
                val action = if (installedCode == null) "Install" else "Update to"
                AsyncActionItem(
                    id = id,
                    title = version.version,
                    subtitle = subtitle,
                    value = action,
                    confirmTitle = "$action ${page.name} ${version.version}" +
                        (if (version.antiFeatures.isEmpty()) "?" else " (" + version.antiFeatures.joinToString(", ") { it.label } + ")?"),
                    run = { ctx, onStatus ->
                        val result = AppCatalogs.install(ctx, record, page.id, version.versionCode, page.name, onStatus)
                        if (result.ok) result.values["summary"] ?: "Done" else result.error ?: "It failed"
                    },
                )
            }
        }
    }

    /** A key fingerprint in groups of four, as it is read aloud and compared. */
    internal fun grouped(hex: String): String = hex.lowercase().chunked(4).joinToString(" ").take(80)

    private fun relative(time: Long): String =
        android.text.format.DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString()
}
