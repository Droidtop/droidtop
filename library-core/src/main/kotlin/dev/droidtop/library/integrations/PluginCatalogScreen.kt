package dev.droidtop.library.integrations

import dev.droidtop.library.settings.Notice
import dev.droidtop.library.settings.NoticeItem
import dev.droidtop.library.settings.NoticePlacement
import dev.droidtop.library.settings.NoticeTone
import android.content.Context
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogChip
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.ChoiceItem
import dev.droidtop.library.settings.ChoiceOption
import dev.droidtop.library.settings.DocumentPickItem
import dev.droidtop.library.settings.NestedScreenItem
import dev.droidtop.library.settings.TextBlockItem
import dev.droidtop.library.settings.TextInputItem
import dev.droidtop.pluginhost.PluginRecord
import dev.droidtop.pluginhost.PluginStore
import dev.droidtop.pluginhost.UserOriginKey
import dev.droidtop.pluginhost.UserOriginKeys
import dev.droidtop.shared.trust.Sha256
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Plugins > Add (docs/SPEC.md 12a "The catalog", "Added catalogs", "Known
 * catalogs"): droidtop's own catalog opens straight from Add ([officialScreen]),
 * and [screen] is the other catalogs -- the ones the person added, the known
 * ones to switch on with a press, and the address form. A catalog opens its
 * own screen of plugins -- label, description, an "Unofficial" badge for
 * every catalog but droidtop's, the row's own action (Install / Update to
 * <version> / no action, saying why when there is none) and, above the rows,
 * Install all and Update all. Adding a catalog is one flow however it starts
 * (a known catalog's press, a typed address, a QR code; an action link's `catalog.add` step reviews the same way):
 * fetch, review (its name, its disclaimer, whether it is signed, each origin's
 * key fingerprint) and an explicit Accept; nothing is listed or trusted before
 * that. These are [CatalogScreen]s carried inline by the Plugins screen, which
 * both settings renderers already render, and registered for the link.
 *
 * A catalog's own screen may touch the network (the index fetch,
 * [PluginCatalog.currentIndex], off the main thread via [Dispatchers.IO]),
 * and it is deliberate: that screen's whole job is the catalog, so it is the
 * one place a refresh is automatic; the Plugins screen reads cached copies only.
 */
object PluginCatalogScreen {
    const val ID = "plugins_catalogs"

    /** The caution card over a catalog's notice; reserved, so no plugin notice can pose as one (plugin-view-limits.json). */
    const val NOTICE_TITLE = "Notice from the list"
    const val CHANGED_NOTICE_TITLE = "The list's notice changed"

    // Buffers between the address field and its action, and the fetched catalog awaiting the person's
    // decision: the same pending-buffer shape "Keys you trust" uses.
    @Volatile private var pendingAddress = ""
    @Volatile private var pendingProposal: PluginCatalog.Proposal? = null

    // Counts fetches, so a catalog fetched again must be read again: the read-gate of its notice is new each time.
    @Volatile private var proposalSerial = 0

    // What was last searched for in each catalog's screen (by source id): kept while the app runs, like the address buffer above.
    private val searches = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** The kind each catalog's list is narrowed to (docs/plugin-api.md 1.2, "Categories"), or [ALL_KINDS]. */
    private val kinds = java.util.concurrent.ConcurrentHashMap<String, String>()
    private const val ALL_KINDS = "all"

    /**
     * What the last Install all or Update all of a catalog did (Droidtop/tracker#517), by catalog id: its row goes once
     * nothing is left to do, so the result is a notice in its place, where the cursor already is, until dismissed.
     */
    private val lastBulk = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Whether [plugin], listed under [origin], answers a search: every word of [query] is somewhere in its name, id,
     * description or origin, in any case. Pure, over the index already in memory: a search never asks the network.
     */
    internal fun matchesQuery(plugin: PluginCatalogPlugin, origin: String, query: String): Boolean {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val haystack = listOfNotNull(plugin.label, plugin.id, plugin.description, origin).joinToString(" ").lowercase()
        return words.all { it in haystack }
    }

    fun screen(): CatalogScreen = CatalogScreen(
        id = ID,
        title = "More catalogs",
        needsNetwork = true,
        subtitle = "Other plugin lists, not droidtop's and not vetted. You read each notice before adding it",
        groups = { context -> catalogsGroups(context) },
    )

    /** droidtop's own catalog: what Plugins > Add opens first. */
    fun officialScreen(): CatalogScreen = catalogScreen(PluginCatalogSources.OFFICIAL_ID, "droidtop plugins")

    /** The Plugins screen row's value. Disk; call off the main thread. */
    fun summary(context: Context): String {
        val added = PluginCatalogSources.added(PluginCatalogSources.storeFile(context)).size
        return if (added == 0) "None added" else "$added added"
    }

    private fun slug(id: String): String = Sha256.hex(id.toByteArray()).take(12)

    private suspend fun catalogsGroups(context: Context): List<CatalogGroup> = withContext(Dispatchers.IO) {
        val added = PluginCatalogSources.added(PluginCatalogSources.storeFile(context))
        val known = PluginCatalogSources.knownNotAdded(added)
        val proposal = pendingProposal
        // The review is the whole screen while there is one: the notice and its Accept are never below a form.
        if (proposal != null) return@withContext listOf(proposalGroup(proposal))
        listOfNotNull(
            if (added.isEmpty()) {
                null
            } else {
                CatalogGroup(
                    id = "plugins_catalogs_list",
                    title = "Your catalogs",
                    items = added.map { source ->
                        NestedScreenItem(
                            id = "plugins_catalog_${slug(source.id)}",
                            title = source.name,
                            subtitle = "Not part of droidtop. ${source.indexUrl}",
                            inline = catalogScreen(source.id, source.name),
                            chip = chipFor(source),
                        )
                    },
                )
            },
            if (known.isEmpty()) {
                null
            } else {
                CatalogGroup(
                    id = "plugins_catalogs_known",
                    title = "Catalogs you can switch on",
                    items = known.map { catalog ->
                        AsyncActionItem(
                            id = "plugins_catalogs_known_${slug(catalog.id)}",
                            title = catalog.name,
                            subtitle = "${catalog.about}. Not part of droidtop: you read its notice before it is added",
                            chip = UNOFFICIAL,
                            value = "Switch on",
                            run = { ctx, onStatus ->
                                pendingAddress = catalog.address
                                fetch(ctx, catalog.address, onStatus)
                            },
                        )
                    },
                )
            },
            CatalogGroup(
                id = "plugins_catalogs_add",
                title = "Add by address",
                items = listOf(
                    TextInputItem(
                        id = "plugins_catalogs_add_address",
                        title = "Catalog address",
                        subtitle = "Its GitHub repository (https://github.com/<owner>/<repo>) or its index.json address",
                        value = pendingAddress,
                        onChange = { _, v -> pendingAddress = v.trim() },
                    ),
                    AsyncActionItem(
                        id = "plugins_catalogs_add_fetch",
                        title = "Fetch this catalog",
                        subtitle = "Shows its disclaimer and keys below; nothing is added until you accept",
                        run = { ctx, onStatus -> fetch(ctx, pendingAddress, onStatus) },
                    ),
                    DocumentPickItem(
                        id = "plugins_catalogs_add_qr",
                        title = "Read a QR code",
                        subtitle = "A photo or screenshot of the catalog's QR code",
                        mimeType = "image/*",
                        onPicked = { ctx, uri ->
                            val text = PluginCatalogSources.qrText(ctx, uri)
                            if (text == null) {
                                "No QR code found in that image"
                            } else {
                                // A catalog's QR code carries its action link (the README's, docs/SPEC.md 12a "Action links"), an older
                                // add-catalog link, or just its address; here only the catalog it adds is read.
                                pendingAddress = ActionLinks.catalogAddress(text) ?: text.trim()
                                fetch(ctx, pendingAddress) {}
                            }
                        },
                    ),
                ),
            ),
        )
    }

    private suspend fun fetch(context: Context, address: String, onStatus: (String) -> Unit): String {
        if (address.isBlank()) return "Type the catalog's address first"
        onStatus("Fetching $address...")
        return when (val result = PluginCatalog.propose(context, address)) {
            is PluginCatalog.ProposeResult.Failed -> {
                pendingProposal = null
                result.reason
            }
            is PluginCatalog.ProposeResult.Ready -> {
                pendingProposal = result.proposal
                proposalSerial++
                "Fetched \"${result.proposal.info.name}\". Read its notice on this screen; nothing is added until you accept it."
            }
        }
    }

    /**
     * The review before adding, as one screen (docs/SPEC.md 12a "Added catalogs"): the catalog's name and an "Unofficial" chip,
     * its notice in full above Accept (Accept opens once the text was scrolled to its end or three seconds after it was
     * shown), then its address, whether it is signed and every key fingerprint as rows, and Discard. Plain words: a
     * "publisher" is an origin, and a fingerprint is what a key is called when two are compared.
     */
    private fun proposalGroup(proposal: PluginCatalog.Proposal): CatalogGroup {
        val newOrigins = proposal.origins.count { it.existing == null && it.keyBase64 != null }
        val gate = "plugins_catalogs_proposal_read_$proposalSerial"
        return CatalogGroup(
            id = "plugins_catalogs_proposal",
            title = "Review before adding",
            chips = listOf(CatalogChip("Unofficial")),
            items = buildList {
                // U2 (docs/SPEC.md 7k, "Notices"): the caution banner; the address and signature are the rows below.
                add(
                    NoticeItem(
                        id = "plugins_catalogs_proposal_who",
                        notice = Notice(
                            tone = NoticeTone.Caution,
                            title = "Unofficial list",
                            body = "Add from it only if someone you trust pointed you to it.",
                            details = "${proposal.info.name} is not part of droidtop and droidtop has not checked it. " +
                                "Nothing is added until you accept the notice below.\nAddress: ${proposal.indexUrl}",
                        ),
                        placement = NoticePlacement.Page,
                    ),
                )
                addAll(noticeItems("plugins_catalogs_proposal_notice", proposal.info.name, false, proposal.disclaimer.text, gate))
                add(ActionItem(id = "plugins_catalogs_proposal_address", title = proposal.indexUrl.removePrefix("https://"), subtitle = "Where droidtop reads it from"))
                add(
                    ActionItem(
                        id = "plugins_catalogs_proposal_signed",
                        title = "Signed by the catalog",
                        subtitle = if (proposal.signed) null else "droidtop can only check that it came from this address",
                        value = if (proposal.signed) "Yes" else "No",
                    ),
                )
                proposal.info.keyBase64?.let { master ->
                    add(
                        ActionItem(
                            id = "plugins_catalogs_proposal_catalog_key",
                            title = "Catalog key",
                            subtitle = "Vouches for this catalog's plugins; droidtop refuses a later copy under another key",
                            value = UserOriginKeys.fingerprint(master) ?: "unreadable",
                        ),
                    )
                }
                proposal.origins.forEach { origin -> add(originRow(origin)) }
                add(
                    AsyncActionItem(
                        id = "plugins_catalogs_proposal_accept",
                        title = "Accept and add",
                        subtitle = "Its publishers' plugins can then install, marked Unofficial; each runs once you approve it",
                        gate = gate,
                        confirmTitle = "Accept the notice of \"${proposal.info.name}\"" +
                            (if (newOrigins > 0) " and trust its $newOrigins publisher${if (newOrigins == 1) "" else "s"}?" else "?"),
                        run = { ctx, _ ->
                            val message = PluginCatalog.accept(ctx, proposal)
                            pendingProposal = null
                            pendingAddress = ""
                            message
                        },
                    ),
                )
                add(
                    ActionItem(
                        id = "plugins_catalogs_proposal_discard",
                        title = "Discard",
                        subtitle = "Add nothing, trust nothing",
                        run = { _ -> pendingProposal = null },
                    ),
                )
            },
        )
    }

    /**
     * A catalog's notice (its `disclaimer`, Droidtop/tracker#521 item 10): droidtop's own caution card titled
     * [NOTICE_TITLE] and marked as from [catalogName], then the text shown whole, a paragraph per row (the pad steps
     * through them), the last one marking the end of the text for [gate]. The card says the condition in droidtop's
     * words; the text is the catalog's own, which a plugin never repeats ([NOTICE_TITLE] is reserved).
     */
    private fun noticeItems(idPrefix: String, catalogName: String, changed: Boolean, text: String, gate: String?): List<CatalogItem> {
        val paragraphs = text.trim().split(Regex("\\n+")).map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf(text.trim()) }
        val card = NoticeItem(
            id = "${idPrefix}_card",
            notice = Notice(
                tone = NoticeTone.Caution,
                title = if (changed) CHANGED_NOTICE_TITLE else NOTICE_TITLE,
                body = if (gate != null) "Read it to the end: Accept opens there" else null,
                source = catalogName,
            ),
        )
        return listOf(card) + paragraphs.mapIndexed { index, paragraph ->
            TextBlockItem(
                id = "${idPrefix}_$index",
                title = "",
                text = paragraph,
                gate = gate,
                last = index == paragraphs.lastIndex,
            )
        }
    }

    /** One publisher's key as a row: its name and what accepting does with it in the title, the key's fingerprint in the value column. */
    private fun originRow(origin: PluginCatalog.ProposedOrigin): ActionItem {
        val key = origin.keyBase64
        val fingerprint = key?.let { UserOriginKeys.fingerprint(it) } ?: "unreadable"
        val existing = origin.existing
        val title = when {
            key == null -> "Publisher \"${origin.origin}\": no usable key, so its plugins are not offered"
            existing == null -> "Publisher \"${origin.origin}\": new, trusted when you accept"
            UserOriginKeys.keySha256(existing.keyBase64) == UserOriginKeys.keySha256(key) -> "Publisher \"${origin.origin}\": a key you already trust"
            else -> "Publisher \"${origin.origin}\": not added, you trust a different key (${UserOriginKeys.fingerprint(existing.keyBase64)})"
        }
        return ActionItem(
            id = "plugins_catalogs_proposal_origin_${origin.origin}",
            title = title,
            subtitle = "Third-party, not official, and droidtop has not checked it",
            value = fingerprint,
        )
    }

    // ------------------------------------------------------------------
    // One catalog's own screen.
    // ------------------------------------------------------------------

    private fun catalogScreen(sourceId: String, name: String): CatalogScreen = CatalogScreen(
        id = "plugins_catalog_screen_${slug(sourceId)}",
        title = name,
        needsNetwork = true,
        subtitle = if (sourceId == PluginCatalogSources.OFFICIAL_ID) {
            "What droidtop's catalog lists. Nothing here runs until you approve it on the Plugins screen"
        } else {
            "Unofficial catalog, not part of droidtop. Nothing here runs until you approve it on the Plugins screen"
        },
        groups = { context -> catalogGroups(context, sourceId) },
    )

    private suspend fun catalogGroups(context: Context, sourceId: String): List<CatalogGroup> = withContext(Dispatchers.IO) {
        val source = PluginCatalogSources.byId(context, sourceId)
            ?: return@withContext listOf(
                CatalogGroup(
                    id = "plugins_catalog_gone",
                    title = null,
                    items = listOf(ActionItem(id = "plugins_catalog_gone_row", title = "This catalog was removed")),
                ),
            )
        val load = PluginCatalog.currentIndex(context, source)
        // Read again: a fetch may have recorded the catalog's signing key.
        val current = PluginCatalogSources.byId(context, sourceId) ?: source
        val installed = PluginStore.installed(context).associateBy { it.manifest.id }
        val userKeys = UserOriginKeys.load(UserOriginKeys.storeFile(context))
        val index = load.index
        val query = searches[sourceId].orEmpty()
        val top = buildList<CatalogItem> {
            if (index != null && PluginCatalog.listable(current, index)) {
                val installedList = installed.values.toList()
                val toInstall = PluginCatalog.installable(current, index, installedList, userKeys).size
                val toUpdate = PluginCatalog.offersFor(installedList, listOf(PluginCatalog.Listing(current, index)), userKeys).size
                if (toInstall > 0) {
                    add(
                        AsyncActionItem(
                            id = "plugins_catalog_install_all_$sourceId",
                            title = "Install all",
                            subtitle = "$toInstall plugin${if (toInstall == 1) "" else "s"} from ${current.name}. Each one waits for your approval before it runs",
                            value = toInstall.toString(),
                            run = { ctx, onStatus ->
                                PluginCatalog.installAll(ctx, PluginCatalogSources.byId(ctx, sourceId) ?: current, onStatus).also { lastBulk[sourceId] = it }
                            },
                        ),
                    )
                }
                if (toUpdate > 0) {
                    add(
                        AsyncActionItem(
                            id = "plugins_catalog_update_all_$sourceId",
                            title = "Update all",
                            subtitle = "$toUpdate plugin${if (toUpdate == 1) "" else "s"} installed from ${current.name} ${if (toUpdate == 1) "has" else "have"} a newer version",
                            value = toUpdate.toString(),
                            run = { ctx, onStatus ->
                                PluginCatalog.updateAll(ctx, onStatus, only = PluginCatalogSources.byId(ctx, sourceId) ?: current).also { lastBulk[sourceId] = it }
                            },
                        ),
                    )
                }
                lastBulk[sourceId]?.let { said ->
                    add(
                        NoticeItem(
                            id = "plugins_catalog_bulk_result_$sourceId",
                            notice = Notice(
                                tone = if (said.contains("Not installed") || said.contains("Couldn't")) NoticeTone.Caution else NoticeTone.Info,
                                title = if (said.startsWith("Installed") || said.startsWith("Updated")) "Done" else "Finished",
                                body = said,
                                action = dev.droidtop.library.settings.NoticeAction("OK") { lastBulk.remove(sourceId) },
                            ),
                        ),
                    )
                }
            }
            if (index != null && PluginCatalog.listable(current, index)) {
                add(
                    TextInputItem(
                        id = "plugins_catalog_search",
                        title = "Search",
                        value = query,
                        search = true,
                        onChange = { _, value -> searches[sourceId] = value.trim() },
                    ),
                )
            }
            if (index != null && PluginCatalog.listable(current, index)) {
                val present = index.origins.flatMap { it.plugins }.map { it.category }.toSet()
                val categories = dev.droidtop.pluginhost.PluginPresentation.CATEGORIES.filter { it in present }
                if (categories.size > 1) {
                    add(
                        ChoiceItem(
                            id = "plugins_catalog_category",
                            title = "Kind",
                            options = listOf(ChoiceOption(ALL_KINDS, "All")) + categories.map {
                                ChoiceOption(it, dev.droidtop.pluginhost.PluginPresentation.CATEGORY_LABELS.getValue(it))
                            },
                            current = kinds[sourceId] ?: ALL_KINDS,
                            onSelect = { _, value -> kinds[sourceId] = value },
                        ),
                    )
                }
                add(
                    ChoiceItem(
                        id = "plugins_catalog_channel_$sourceId",
                        title = "Channel",
                        subtitle = "Which versions to take. Testing and Unstable are sooner and less sure; a plugin can differ",
                        options = PluginChannel.entries.map { ChoiceOption(it.id, it.label) },
                        current = PluginChannels.forContext(context).catalog(sourceId).id,
                        onSelect = { ctx, value -> PluginChannel.of(value)?.let { PluginChannels.forContext(ctx).setCatalog(sourceId, it) } },
                    ),
                )
            }
            add(
                AsyncActionItem(
                    id = "plugins_catalog_refresh",
                    title = "Refresh catalog",
                    subtitle = if (current.official) "Fetch the latest list from droidtop" else "Fetch the latest list from ${current.indexUrl}",
                    run = { ctx, _ -> PluginCatalog.refresh(ctx, PluginCatalogSources.byId(ctx, sourceId) ?: current) },
                ),
            )
            load.note?.let { note ->
                add(ActionItem(id = "plugins_catalog_note", title = "Catalog status", subtitle = note))
            }
            if (index == null) {
                add(
                    ActionItem(
                        id = "plugins_catalog_empty",
                        title = if (load.published) "Nothing to show yet" else "No catalog is published yet",
                        subtitle = if (load.published) {
                            "The refresh above just failed. A plugin file can still be installed from the Plugins screen."
                        } else if (current.official) {
                            "There is nothing to browse yet. Install a plugin file from the Plugins screen instead."
                        } else {
                            "Nothing is published at this catalog's address now."
                        },
                    ),
                )
            }
        }
        val groups = mutableListOf(CatalogGroup(id = "plugins_catalog_top", title = null, items = top))
        if (index != null && !PluginCatalog.listable(current, index)) {
            groups += disclaimerChangedGroup(current, index)
        } else if (index != null) {
            if (!current.official) {
                originDecisions(current, index, userKeys)?.let { groups += it }
            }
            val rows = index.origins.flatMap { origin ->
                val state = PluginCatalog.originState(current, origin, userKeys)
                val kind = kinds[sourceId] ?: ALL_KINDS
                origin.plugins.filter { matchesQuery(it, origin.origin, query) && (kind == ALL_KINDS || it.category == kind) }.map { plugin -> rowFor(current, origin, state, plugin, installed[plugin.id], installed[plugin.id]?.let { PluginStore.iconFile(context, it) }) }
            }
            groups += CatalogGroup(
                id = "plugins_catalog_list",
                title = if (query.isBlank()) "Plugins" else "Plugins matching \"$query\"",
                items = if (rows.isEmpty() && query.isNotBlank()) {
                    listOf(ActionItem(id = "plugins_catalog_search_none", title = "Nothing matches \"$query\""))
                } else {
                    rows
                },
            )
        }
        if (!current.official) groups += aboutGroup(current, index)
        groups
    }

    /** A newer disclaimer than the one accepted: its text above Accept, which opens once the text was read, and nothing listed until then. */
    private fun disclaimerChangedGroup(source: PluginCatalogSource, index: PluginCatalogIndex): CatalogGroup {
        val disclaimer = index.disclaimer!!
        val gate = "plugins_catalog_disclaimer_read_${slug(source.id)}_${disclaimer.version}"
        return CatalogGroup(
            id = "plugins_catalog_disclaimer_changed",
            title = "The notice changed",
            chips = listOf(CatalogChip("Unofficial")),
            items = noticeItems("plugins_catalog_disclaimer_new", source.name, true, disclaimer.text, gate) + listOf(
                AsyncActionItem(
                    id = "plugins_catalog_disclaimer_accept",
                    title = "Accept",
                    subtitle = "Lists this catalog's plugins again",
                    gate = gate,
                    confirmTitle = "Accept the new notice of \"${source.name}\"?",
                    run = { ctx, _ ->
                        PluginCatalog.acceptDisclaimer(ctx, PluginCatalogSources.byId(ctx, source.id) ?: source, disclaimer.version)
                        "Accepted. \"${source.name}\" is listed again"
                    },
                ),
            ),
        )
    }

    /** Origins that need the person: new in the catalog since it was accepted, a changed key, or revoked. */
    private fun originDecisions(source: PluginCatalogSource, index: PluginCatalogIndex, userKeys: Map<String, UserOriginKey>): CatalogGroup? {
        val items = index.origins.mapNotNull { origin ->
            val fingerprint = origin.keyBase64?.let(UserOriginKeys::fingerprint) ?: "unreadable"
            when (val state = PluginCatalog.originState(source, origin, userKeys)) {
                PluginCatalog.OriginState.NotTrusted -> AsyncActionItem(
                    id = "plugins_catalog_trust_${origin.origin}",
                    title = "Trust origin \"${origin.origin}\"",
                    subtitle = "New in this catalog. Key fingerprint $fingerprint: third-party, not official, and droidtop has not vetted it",
                    // The fingerprint first: an armed row on the handheld shows this question in two lines at most.
                    confirmTitle = "Trust key $fingerprint for origin \"${origin.origin}\" from \"${source.name}\"?",
                    run = { ctx, _ -> PluginCatalog.trustOrigin(ctx, source, origin.origin, origin.keyBase64) },
                )
                is PluginCatalog.OriginState.KeyChanged -> {
                    val trusted = UserOriginKeys.fingerprint(state.stored.keyBase64) ?: "unreadable"
                    AsyncActionItem(
                        id = "plugins_catalog_changed_${origin.origin}",
                        title = "\"${origin.origin}\" has a new key",
                        subtitle = "You trust $trusted; this catalog now names $fingerprint. Its plugins here are not offered " +
                            "until you decide. Trust the new key only if its publisher said it changed it",
                        confirmTitle = "Trust new key $fingerprint for \"${origin.origin}\" in place of $trusted? Its plugins keep " +
                            "their approval and permissions." + if (source.indexSigned) "" else " This catalog's index is not signed.",
                        run = { ctx, _ -> PluginCatalog.replaceOriginKey(ctx, source, origin.origin, origin.keyBase64) },
                    )
                }

                else -> null
            }
        }
        return if (items.isEmpty()) null else CatalogGroup(id = "plugins_catalog_origins", title = "Origins", items = items)
    }

    /** An added catalog's own facts, its disclaimer, and Remove. */
    private fun aboutGroup(source: PluginCatalogSource, index: PluginCatalogIndex?): CatalogGroup = CatalogGroup(
        id = "plugins_catalog_about",
        title = "About this catalog",
        items = buildList {
            add(
                ActionItem(
                    id = "plugins_catalog_about_where",
                    title = "Unofficial",
                    subtitle = buildString {
                        append("Not part of droidtop and not vetted by it. Index ").append(source.indexUrl)
                        source.homepage?.let { append(". Home ").append(it) }
                        append(". ")
                        append(
                            (source.masterKeyBase64?.let { "Master key ${UserOriginKeys.fingerprint(it)}. " } ?: "No master key. ") +
                                (if (source.indexSigned) "Index signed" else "Index not signed"),
                        )
                    },
                ),
            )
            index?.disclaimer?.let { addAll(noticeItems("plugins_catalog_about_disclaimer", source.name, false, it.text, null)) }
            add(
                AsyncActionItem(
                    id = "plugins_catalog_remove",
                    title = "Remove this catalog",
                    subtitle = "Nothing is listed or updated from it. Plugins from it stay, under the keys you trusted",
                    confirmTitle = "Remove \"${source.name}\"?",
                    run = { ctx, _ ->
                        if (PluginCatalog.removeCatalog(ctx, source)) "Removed \"${source.name}\"" else "It was already removed"
                    },
                ),
            )
        },
    )

    private val UNOFFICIAL = dev.droidtop.library.settings.RowTag.UNOFFICIAL

    private fun chipFor(source: PluginCatalogSource): dev.droidtop.library.settings.RowTag? = if (source.official) null else UNOFFICIAL

    /**
     * One plugin of a catalog (Droidtop/tracker#514): its name, its one-line summary and what pressing on would do, and
     * a page of its own with the whole description, who makes it and which catalog lists it, then the action. Nothing is
     * stitched onto the summary: the maker and the catalog are fields of the page, and the description is never a tooltip.
     */
    private fun rowFor(
        source: PluginCatalogSource,
        origin: PluginCatalogOrigin,
        state: PluginCatalog.OriginState,
        plugin: PluginCatalogPlugin,
        installed: PluginRecord?,
        // The installed plugin's own icon; the catalog index carries none, so a plugin not installed has none yet.
        iconFile: String?,
    ): CatalogItem {
        val action = actionFor(source, state, plugin, installed)
        val id = "plugins_catalog_${plugin.id}"
        val conflict = PluginCatalog.hasOrderConflict(plugin)
        val page = CatalogScreen(
            id = "${id}_page",
            title = plugin.label,
            subtitle = plugin.summary,
            groups = {
                listOf(
                    CatalogGroup(
                        id = "${id}_about",
                        title = null,
                        items = listOfNotNull(
                            (plugin.description ?: plugin.summary)?.let { TextBlockItem(id = "${id}_description", text = it) },
                            ActionItem(id = "${id}_maker", title = "Made by", value = plugin.publisher ?: origin.origin),
                            ActionItem(id = "${id}_from", title = "Listed in", value = if (source.official) "droidtop's catalog" else source.name, chip = chipFor(source)),
                            if (conflict) {
                                NoticeItem(
                                    id = "${id}_conflict",
                                    notice = Notice(
                                        tone = NoticeTone.Caution,
                                        title = "No release offered",
                                        body = "The catalog lists releases whose versions and dates disagree, so none is offered until it is fixed.",
                                    ),
                                )
                            } else {
                                null
                            },
                        ),
                    ),
                    CatalogGroup(id = "${id}_action", title = null, items = listOf(action)),
                )
            },
        )
        return NestedScreenItem(
            id = id,
            title = plugin.label,
            subtitle = plugin.summary ?: plugin.description?.substringBefore(". ")?.take(120) ?: "No description",
            inline = page,
            valueLabel = { action.value },
            chip = chipFor(source),
            iconFile = iconFile,
            // The summary is always under the name (#514), not only in Info.
            summaryShown = true,
        )
    }

    /** The plugin page's one action: install, update, or why neither can happen. */
    private fun actionFor(
        source: PluginCatalogSource,
        state: PluginCatalog.OriginState,
        plugin: PluginCatalogPlugin,
        installed: PluginRecord?,
    ): CatalogItem {
        val offered = state == PluginCatalog.OriginState.Offered
        val channel = PluginCatalog.channelFor(source, plugin.id)
        val latest = PluginCatalog.latest(plugin, channel)
        val isUpdate = offered && installed != null && latest != null && PluginCatalog.updateFor(plugin, installed, channel) != null
        val id = "plugins_catalog_${plugin.id}_action"
        return when {
            isUpdate -> AsyncActionItem(
                id = id,
                title = "Update",
                value = "Update to ${latest!!.version}",
                run = { ctx, onStatus -> PluginCatalog.install(ctx, plugin, latest!!, onStatus) },
            )
            installed != null -> ActionItem(id = id, title = "Installed", value = "Installed ${installed.manifest.version}")
            offered && latest != null -> AsyncActionItem(
                id = id,
                title = "Install",
                subtitle = "It waits for your approval before it runs",
                value = "Install ${latest.version}",
                run = { ctx, onStatus -> PluginCatalog.install(ctx, plugin, latest, onStatus) },
            )
            !offered -> ActionItem(
                id = id,
                title = "Not offered",
                value = when (state) {
                    PluginCatalog.OriginState.NotTrusted -> "Trust its origin first"
                    is PluginCatalog.OriginState.KeyChanged -> "Its key changed"
                    is PluginCatalog.OriginState.Unusable -> "Not available (${state.reason})"
                    PluginCatalog.OriginState.Offered -> ""
                },
            )
            else -> ActionItem(id = id, title = "Not offered", value = "No stable release yet")
        }
    }
}
