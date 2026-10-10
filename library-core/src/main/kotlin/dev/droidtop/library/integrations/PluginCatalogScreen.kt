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
import dev.droidtop.pluginhost.PluginRecord
import dev.droidtop.pluginhost.PluginStore
import dev.droidtop.pluginhost.UserOriginKey
import dev.droidtop.pluginhost.UserOriginKeys
import dev.droidtop.runtime.util.Sha256
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

    // Buffers between the address field and its action, and the fetched catalog awaiting the person's
    // decision: the same pending-buffer shape "Keys you trust" uses.
    @Volatile private var pendingAddress = ""
    @Volatile private var pendingProposal: PluginCatalog.Proposal? = null

    // Counts fetches, so a catalog fetched again must be read again: the read-gate of its notice is new each time.
    @Volatile private var proposalSerial = 0

    fun screen(): CatalogScreen = CatalogScreen(
        id = ID,
        title = "More catalogs",
        subtitle = "Other places plugins are listed. They are not part of droidtop and droidtop has not vetted them: " +
            "you read each one's notice before it is added",
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
                        subtitle = "The catalog's GitHub repository (https://github.com/<owner>/<repo>) or the https address of its index.json",
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
                add(
                    TextBlockItem(
                        id = "plugins_catalogs_proposal_who",
                        title = proposal.info.name,
                        text = "This catalog is not part of droidtop and droidtop has not checked it. " +
                            "Add it only if you trust the people who run it. Nothing is added until you accept the notice below.",
                    ),
                )
                addAll(noticeItems("plugins_catalogs_proposal_notice", "Notice from the catalog", proposal.disclaimer.text, gate))
                add(ActionItem(id = "plugins_catalogs_proposal_address", title = proposal.indexUrl.removePrefix("https://"), subtitle = "Where droidtop reads it from", run = {}))
                add(
                    ActionItem(
                        id = "plugins_catalogs_proposal_signed",
                        title = "Signed by the catalog",
                        subtitle = if (proposal.signed) null else "droidtop can only check that it came from this address",
                        value = if (proposal.signed) "Yes" else "No",
                        run = {},
                    ),
                )
                proposal.info.keyBase64?.let { master ->
                    add(
                        ActionItem(
                            id = "plugins_catalogs_proposal_catalog_key",
                            title = "Catalog key",
                            subtitle = "Vouches for this catalog's plugins; droidtop refuses a later copy under another key",
                            value = UserOriginKeys.fingerprint(master) ?: "unreadable",
                            run = {},
                        ),
                    )
                }
                proposal.origins.forEach { origin -> add(originRow(origin)) }
                add(
                    AsyncActionItem(
                        id = "plugins_catalogs_proposal_accept",
                        title = "Accept and add",
                        subtitle = "You accept the notice above. Plugins from the publishers listed can then be installed and updated " +
                            "from this catalog, marked Unofficial; each still runs only after you approve it",
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

    /** A text shown whole, a paragraph per row (the pad steps through them), the last one marking the end of the text for [gate]. */
    private fun noticeItems(idPrefix: String, heading: String, text: String, gate: String?): List<CatalogItem> {
        val paragraphs = text.trim().split(Regex("\\n+")).map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf(text.trim()) }
        return paragraphs.mapIndexed { index, paragraph ->
            TextBlockItem(
                id = "${idPrefix}_$index",
                title = if (index == 0) heading else "",
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
            run = {},
        )
    }

    // ------------------------------------------------------------------
    // One catalog's own screen.
    // ------------------------------------------------------------------

    private fun catalogScreen(sourceId: String, name: String): CatalogScreen = CatalogScreen(
        id = "plugins_catalog_screen_${slug(sourceId)}",
        title = name,
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
                    items = listOf(ActionItem(id = "plugins_catalog_gone_row", title = "This catalog was removed", run = {})),
                ),
            )
        val load = PluginCatalog.currentIndex(context, source)
        // Read again: a fetch may have recorded the catalog's signing key.
        val current = PluginCatalogSources.byId(context, sourceId) ?: source
        val installed = PluginStore.installed(context).associateBy { it.manifest.id }
        val userKeys = UserOriginKeys.load(UserOriginKeys.storeFile(context))
        val index = load.index
        val top = buildList<CatalogItem> {
            if (index != null && PluginCatalog.listable(current, index)) {
                val installedList = installed.values.toList()
                val toInstall = PluginCatalog.installable(current, index, installedList, userKeys).size
                val toUpdate = PluginCatalog.offersFor(installedList, listOf(PluginCatalog.Listing(current, index)), userKeys).size
                if (toInstall > 0) {
                    add(
                        AsyncActionItem(
                            id = "plugins_catalog_install_all",
                            title = "Install all",
                            subtitle = "$toInstall plugin${if (toInstall == 1) "" else "s"} from ${current.name}. Each one waits for your approval before it runs",
                            value = toInstall.toString(),
                            run = { ctx, onStatus -> PluginCatalog.installAll(ctx, PluginCatalogSources.byId(ctx, sourceId) ?: current, onStatus) },
                        ),
                    )
                }
                if (toUpdate > 0) {
                    add(
                        AsyncActionItem(
                            id = "plugins_catalog_update_all",
                            title = "Update all",
                            subtitle = "$toUpdate plugin${if (toUpdate == 1) "" else "s"} installed from ${current.name} ${if (toUpdate == 1) "has" else "have"} a newer version",
                            value = toUpdate.toString(),
                            run = { ctx, onStatus -> PluginCatalog.updateAll(ctx, onStatus, only = PluginCatalogSources.byId(ctx, sourceId) ?: current) },
                        ),
                    )
                }
            }
            add(
                AsyncActionItem(
                    id = "plugins_catalog_refresh",
                    title = "Refresh catalog",
                    subtitle = if (current.official) "Re-fetch the index from droidtop-platforms" else "Re-fetch the index from ${current.indexUrl}",
                    run = { ctx, _ -> PluginCatalog.refresh(ctx, PluginCatalogSources.byId(ctx, sourceId) ?: current) },
                ),
            )
            load.note?.let { note ->
                add(ActionItem(id = "plugins_catalog_note", title = "Catalog status", subtitle = note, run = {}))
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
                        run = {},
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
            groups += CatalogGroup(
                id = "plugins_catalog_list",
                title = "Plugins",
                items = index.origins.flatMap { origin ->
                    val state = PluginCatalog.originState(current, origin, userKeys)
                    origin.plugins.map { plugin -> rowFor(current, origin, state, plugin, installed[plugin.id]) }
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
            items = noticeItems("plugins_catalog_disclaimer_new", "New notice from the catalog", disclaimer.text, gate) + listOf(
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
                    confirmTitle = "Trust origin \"${origin.origin}\" ($fingerprint) from \"${source.name}\"?",
                    run = { ctx, _ -> PluginCatalog.trustOrigin(ctx, source, origin.origin, origin.keyBase64) },
                )
                is PluginCatalog.OriginState.KeyChanged -> ActionItem(
                    id = "plugins_catalog_changed_${origin.origin}",
                    title = "\"${origin.origin}\": a different key",
                    subtitle = "You trust ${UserOriginKeys.fingerprint(state.stored.keyBase64)}; this catalog now names $fingerprint. " +
                        "Its plugins here are not offered and nothing was changed. Compare the two under Keys you trust",
                    run = {},
                )

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
                    run = {},
                ),
            )
            index?.disclaimer?.let { addAll(noticeItems("plugins_catalog_about_disclaimer", "Notice from the catalog", it.text, null)) }
            add(
                AsyncActionItem(
                    id = "plugins_catalog_remove",
                    title = "Remove this catalog",
                    subtitle = "Nothing is listed or updated from it any more. Plugins you installed from it stay, under the keys you trusted",
                    confirmTitle = "Remove \"${source.name}\"?",
                    run = { ctx, _ ->
                        if (PluginCatalog.removeCatalog(ctx, source)) "Removed \"${source.name}\"" else "It was already removed"
                    },
                ),
            )
        },
    )

    private const val UNOFFICIAL = "Unofficial"

    private fun chipFor(source: PluginCatalogSource): String? = if (source.official) null else UNOFFICIAL

    private fun rowFor(
        source: PluginCatalogSource,
        origin: PluginCatalogOrigin,
        state: PluginCatalog.OriginState,
        plugin: PluginCatalogPlugin,
        installed: PluginRecord?,
    ): CatalogItem {
        val subtitle = buildString {
            append(plugin.description ?: "No description")
            if (source.official) append(" - ").append(origin.origin) else append(" - from ").append(source.name)
            if (PluginCatalog.hasOrderConflict(plugin)) {
                append(" - the catalog lists releases whose versions and dates disagree, so none is offered until it is fixed")
            }
        }
        val offered = state == PluginCatalog.OriginState.Offered
        val latest = PluginCatalog.latestStable(plugin)
        val isUpdate = offered && installed != null && latest != null &&
            !latest.manifestSha256.equals(installed.archiveDigest, ignoreCase = true)
        val id = "plugins_catalog_${plugin.id}"
        return when {
            isUpdate -> AsyncActionItem(
                id = id,
                title = plugin.label,
                subtitle = subtitle,
                chip = chipFor(source),
                value = "Update to ${latest!!.version}",
                run = { ctx, onStatus -> PluginCatalog.install(ctx, plugin, latest!!, onStatus) },
            )
            installed != null -> ActionItem(id = id, title = plugin.label, subtitle = subtitle, value = "Installed ${installed.manifest.version}", chip = chipFor(source), run = {})
            offered && latest != null -> AsyncActionItem(
                id = id,
                title = plugin.label,
                subtitle = subtitle,
                chip = chipFor(source),
                value = "Install ${latest.version}",
                run = { ctx, onStatus -> PluginCatalog.install(ctx, plugin, latest, onStatus) },
            )
            !offered -> ActionItem(
                id = id,
                title = plugin.label,
                subtitle = subtitle,
                chip = chipFor(source),
                value = when (state) {
                    PluginCatalog.OriginState.NotTrusted -> "Trust its origin above first"
                    is PluginCatalog.OriginState.KeyChanged -> "Not offered: its key changed"

                    is PluginCatalog.OriginState.Unusable -> "Not available (${state.reason})"
                    PluginCatalog.OriginState.Offered -> ""
                },
                run = {},
            )
            else -> ActionItem(id = id, title = plugin.label, subtitle = subtitle, value = "No stable release yet", chip = chipFor(source), run = {})
        }
    }
}
