package dev.droidtop.pluginhost

import org.json.JSONObject

/**
 * droidtop's targets for facets and the guards on what a plugin may put there (docs/plugin-api.md 1.10, "Targets",
 * "Store information", "Permissions"). [admit] is the one gate: what it returns is what a target draws from one
 * plugin's reply, in the order it is drawn. Pure.
 */
object FacetGuards {
    /** One of droidtop's targets: the kinds it takes, the subjects it takes, and the risk of its permission row (4.1). */
    data class Target(val name: String, val kinds: Set<String>, val subjects: Set<String>, val risk: PointRisk)

    private val ALL_KINDS = Facet.KINDS.keys
    private val INPUTS = setOf("toggle", "choice", "slider", "input")

    /** The fixed list of targets this build draws (`<plugin>:<slot>` slots aside). Advertised in `host.info().facets`. */
    val TARGETS: Map<String, Target> = listOf(
        Target("game.page", ALL_KINDS - "collection", setOf("game", "item"), PointRisk.MEDIUM),
        Target("game.row", setOf("badge", "status", "progress"), setOf("game"), PointRisk.MEDIUM),
        Target("game.tile", setOf("badge", "status", "progress"), setOf("game"), PointRisk.MEDIUM),
        Target("item.page", Facet.BUNDLES + setOf("action", "notice", "link", "toggle"), setOf("item"), PointRisk.MEDIUM),
        Target("library.filter", setOf("info") + Facet.BUNDLES, setOf("item", "game"), PointRisk.MEDIUM),
        Target("library.sort", setOf("info") + Facet.BUNDLES, setOf("item", "game"), PointRisk.MEDIUM),
        Target("search.result", setOf("list", "collection"), setOf("plugin"), PointRisk.MEDIUM),
        Target("home.shelf", setOf("collection"), setOf("plugin"), PointRisk.MEDIUM),
        Target("home.card", setOf("info", "media", "action", "progress"), setOf("plugin"), PointRisk.MEDIUM),
        Target("quickmenu", setOf("status", "toggle", "action", "progress", "meter", "chart", "choice", "group"), setOf("plugin", "device", "game"), PointRisk.LOW),
        Target("companion.tab", setOf("group"), setOf("plugin", "device", "game"), PointRisk.LOW),
        Target("companion.card", ALL_KINDS - "collection", setOf("plugin", "device", "game"), PointRisk.LOW),
        Target("settings.page", INPUTS + setOf("info", "action", "notice", "account", "group"), setOf("plugin"), PointRisk.LOW),
        Target("notification", setOf("notice", "action", "progress"), setOf("plugin", "device", "game", "item"), PointRisk.MEDIUM),
        Target("status.area", setOf("status", "badge", "meter"), setOf("plugin", "device"), PointRisk.LOW),
        Target("widget", setOf("info", "status", "progress", "meter", "media", "list", "action"), setOf("plugin", "device"), PointRisk.LOW),
        Target("plugin.page", ALL_KINDS, setOf("plugin"), PointRisk.LOW),
        Target("desktop.tray", setOf("status", "badge", "action", "toggle", "meter"), setOf("plugin", "device"), PointRisk.LOW),
        Target("files.action", setOf("action"), setOf("plugin"), PointRisk.HIGH),
        Target("launcher.drawer", setOf("action", "badge"), setOf("plugin"), PointRisk.MEDIUM),
        Target("onboarding.step", setOf("info", "text", "media", "action", "notice") + INPUTS, setOf("device", "plugin"), PointRisk.HIGH),
    ).associateBy { it.name }

    /** Per-target caps of one kind inside a target's own cap (a widget takes two actions, a list five rows). */
    private val KIND_CAPS: Map<Pair<String, String>, Int> = mapOf(
        ("widget" to "action") to 2,
        ("notification" to "action") to 3,
        ("notification" to "notice") to 1,
        ("game.row" to "progress") to 1,
        ("game.tile" to "progress") to 1,
    )

    /** What droidtop knows about the plugin a reply came from, for the guards. */
    data class Plugin(
        val id: String,
        /** The targets its `ui.facets` entries declare (and the slots of other plugins it declares). */
        val declaredTargets: Set<String>,
        /** Its `net.domains`: where its media and its store links may point. */
        val domains: List<String>,
        /** It provides a store point, or `library.sources` or `library.updates`: it may give `store` facets. */
        val storeOrSource: Boolean = false,
        /** A store plugin whose account is signed in: its `owned` is taken. */
        val signedInStore: Boolean = false,
        /** Its declared permissions, for `needs` and for the targets that need one (`notification`, `account`). */
        val permissions: Set<String> = emptySet(),
    )

    /**
     * The facets of one reply that [target] draws from [plugin], in drawing order: only facets that asked for this
     * target, of a kind it takes, about a subject it takes, under the plugin's per-target cap, and past the store and
     * media guards; by priority, then the order the plugin gave. What was turned away is said in [messages].
     */
    fun admit(facets: List<Facet>, target: String, plugin: Plugin, messages: MutableList<String> = mutableListOf()): List<Facet> {
        val slot = ':' in target
        val spec = TARGETS[target]
        if (spec == null && !slot) {
            messages += "target \"$target\" is not one droidtop draws"
            return emptyList()
        }
        if (target !in plugin.declaredTargets) {
            messages += "target \"$target\" is not declared on the plugin's ui.facets entry, so nothing was drawn there"
            return emptyList()
        }
        needed(target)?.let { permission ->
            if (permission !in plugin.permissions) {
                messages += "target \"$target\" needs the $permission permission, which the plugin does not declare"
                return emptyList()
            }
        }
        val taken = facets.filter { facet ->
            when {
                target !in facet.targets -> false
                spec != null && facet.kind !in spec.kinds -> drop(messages, facet, "$target does not take ${facet.kind}")
                spec != null && facet.subject.type !in spec.subjects -> drop(messages, facet, "$target is not about a ${facet.subject.type}")
                else -> guarded(facet, plugin, messages)
            }
        }.mapNotNull { facet -> shown(facet, spec, plugin, messages) }.sortedBy { -it.priority.ordinal }

        val cap = PluginViewLimits.facetsPerTarget(target)
        val perKind = HashMap<String, Int>()
        return buildList {
            for (facet in taken) {
                if (size >= cap) {
                    drop(messages, facet, "at most $cap facets of a plugin on $target")
                    continue
                }
                val kindCap = KIND_CAPS[target to facet.kind]
                if (kindCap != null) {
                    val n = perKind.getOrDefault(facet.kind, 0)
                    if (n >= kindCap) {
                        drop(messages, facet, "at most $kindCap ${facet.kind} facets of a plugin on $target")
                        continue
                    }
                    perKind[facet.kind] = n + 1
                }
                add(facet)
            }
        }
    }

    /**
     * The facet as the target draws it: a group keeps only the facets inside it that the target takes and that pass
     * the guards (a group cannot carry a kind its target does not draw), and store information is cut to what it may
     * claim ([cleanStore]). Null when nothing of a group is left.
     */
    private fun shown(facet: Facet, spec: Target?, plugin: Plugin, messages: MutableList<String>): Facet? = when (val body = facet.body) {
        is FacetBody.Group -> {
            val inner = body.facets.filter { child ->
                when {
                    child.kind == "group" -> drop(messages, child, "a group inside a group is one level too deep")
                    spec != null && child.kind !in spec.kinds -> drop(messages, child, "${spec.name} does not take ${child.kind}")
                    else -> guarded(child, plugin, messages)
                }
            }.map { child -> (child.body as? FacetBody.Store)?.let { child.copy(body = cleanStore(it, plugin)) } ?: child }
            if (inner.isEmpty()) null else facet.copy(body = body.copy(facets = inner))
        }
        is FacetBody.Store -> facet.copy(body = cleanStore(body, plugin))
        else -> facet
    }

    /** The permission a target needs besides its own row (docs/plugin-api.md 1.10, "Permissions"). */
    fun needed(target: String): String? = when (target) {
        "notification" -> "notify.post"
        else -> null
    }

    /** The guards that hold on every target: store information, where media and links point, and `account`. */
    private fun guarded(facet: Facet, plugin: Plugin, messages: MutableList<String>): Boolean = when (val body = facet.body) {
        is FacetBody.Store -> storeGuard(facet, body, plugin, messages)
        is FacetBody.Media -> onDomains(body.url, plugin) && (body.poster == null || onDomains(body.poster, plugin)) ||
            drop(messages, facet, "its picture is not on the plugin's own sites (net.domains)")
        is FacetBody.Image -> onDomains(body.media.url, plugin) || drop(messages, facet, "its picture is not on the plugin's own sites (net.domains)")
        // A link opens only on the person's press; it never carries the plugin's session, so any https address is fine.
        is FacetBody.Link -> NetScope.isHttps(body.url) || drop(messages, facet, "a link must be https")
        is FacetBody.Account -> "web.session" in plugin.permissions || drop(messages, facet, "an account facet needs web.session")
        else -> true
    }

    /** "Store information: who may give it, and its guards". The facet is kept, less what it may not claim. */
    private fun storeGuard(facet: Facet, body: FacetBody.Store, plugin: Plugin, messages: MutableList<String>): Boolean {
        if (!plugin.storeOrSource) return drop(messages, facet, "only a store or source plugin may give store information")
        if (facet.subject !is FacetSubject.Item && facet.subject !is FacetSubject.Game) return drop(messages, facet, "store information is about an item or a game")
        if (!onDomains(body.url, plugin)) return drop(messages, facet, "its store address is not on the plugin's own sites (net.domains)")
        return true
    }

    /**
     * What droidtop shows of a store facet: no price beside `free`, a price only as a decimal of at most 1,000,000 with
     * an ISO 4217 currency, `onSale` only with a price, and `owned` only from a signed-in store plugin.
     */
    fun cleanStore(body: FacetBody.Store, plugin: Plugin): FacetBody.Store {
        val price = body.price?.takeIf { !body.free && body.currency != null && Regex("""[A-Z]{3}""").matches(body.currency) }
            ?.takeIf { p -> p.toBigDecimalOrNull()?.let { it.signum() >= 0 && it <= java.math.BigDecimal(1_000_000) } == true }
        return body.copy(
            price = price,
            currency = body.currency.takeIf { price != null },
            onSale = body.onSale && price != null,
            owned = body.owned.takeIf { plugin.signedInStore },
        )
    }

    /** True when [url] is https on one of the plugin's `net.domains`. */
    fun onDomains(url: String, plugin: Plugin): Boolean {
        if (!NetScope.isHttps(url)) return false
        val host = NetScope.hostOf(url) ?: return false
        return plugin.domains.any { NetScope.matches(host, it) }
    }

    /** What this build draws, for `host.info().facets`: `{kinds: {status: [1], ...}, targets: {quickmenu: [1], ...}}`. */
    fun hostInfo(): JSONObject = JSONObject()
        .put("kinds", JSONObject().also { o -> Facet.KINDS.forEach { (k, v) -> o.put(k, org.json.JSONArray(v.sorted())) } })
        .put("targets", JSONObject().also { o -> TARGETS.keys.sorted().forEach { o.put(it, org.json.JSONArray(listOf(1))) } })

    /** The targets a plugin's `ui.facets` entries declare (`"targets": [...]` on each entry). */
    fun declaredTargets(provides: List<ProvidedPoint>): Set<String> = provides
        .filter { it.point == POINT }
        .flatMap { entry ->
            val array = runCatching { JSONObject(entry.extra).optJSONArray("targets") }.getOrNull() ?: return@flatMap emptyList<String>()
            List(array.length()) { array.optString(it).trim() }
        }
        .filter { it.isNotEmpty() && (it in TARGETS || Regex("""[A-Za-z0-9._-]+:[A-Za-z0-9._-]+""").matches(it)) }
        .toSet()

    const val POINT = "ui.facets"

    /**
     * The grant-file key of the person's switch for one target under the plugin's "Where it appears" (docs/plugin-api.md
     * 1.10, "The person arranges them"). On unless switched off; kept across updates like the mode switches.
     */
    const val TARGET_KEY_PREFIX = "facets:"

    /** The plugin's own pages: they are where its switches are, so they have none of their own. */
    val OWN_PAGES: Set<String> = setOf("plugin.page", "settings.page")

    /** What the person reads for each target on "Where it appears". */
    val TARGET_LABELS: Map<String, String> = mapOf(
        "game.page" to "Game pages",
        "game.row" to "Game lists",
        "game.tile" to "Game tiles",
        "item.page" to "Update pages",
        "library.filter" to "Library filters",
        "library.sort" to "Library sorting",
        "search.result" to "Search results",
        "home.shelf" to "Home shelves",
        "home.card" to "Home cards",
        "quickmenu" to "Quick Menu",
        "companion.tab" to "Companion tabs",
        "companion.card" to "Companion cards",
        "notification" to "Notifications",
        "status.area" to "Status bar",
        "widget" to "Home-screen widgets",
        "desktop.tray" to "Desktop tray",
        "files.action" to "Files",
        "launcher.drawer" to "App drawer",
        "onboarding.step" to "Setup",
    )

    fun targetKey(target: String): String = TARGET_KEY_PREFIX + target

    /** The grant-file key of the person's switch for one facet on one target ("the hidden facets to show again"). */
    const val FACET_KEY_PREFIX = "facet:"

    fun facetKey(target: String, facetId: String): String = "$FACET_KEY_PREFIX$target/$facetId"

    /** The person's switch for one facet: shown unless they turned it off under "Where it appears". */
    fun facetOn(snapshot: PluginGrants.Snapshot, target: String, facetId: String): Boolean =
        snapshot.states[facetKey(target, facetId)] != GrantState.DENIED

    /** [facets] less the ones the person hid on [target]. A hidden facet is not drawn, and its group goes with it. */
    fun unhidden(facets: List<Facet>, target: String, snapshot: PluginGrants.Snapshot): List<Facet> =
        facets.filter { facetOn(snapshot, target, it.id) }

    /** The person's switch for [target]: on unless they turned it off, and always on for the plugin's own pages. */
    fun targetOn(snapshot: PluginGrants.Snapshot, target: String): Boolean =
        target in OWN_PAGES || snapshot.states[targetKey(target)] != GrantState.DENIED

    /** The targets "Where it appears" offers a switch for: the declared ones, in droidtop's order, without its own pages. */
    fun switchable(provides: List<ProvidedPoint>): List<String> {
        val declared = declaredTargets(provides)
        return TARGETS.keys.filter { it in declared && it !in OWN_PAGES } + declared.filter { ':' in it }.sorted()
    }

    private fun drop(messages: MutableList<String>, facet: Facet, why: String): Boolean {
        messages += "facet \"${facet.id}\" was not drawn: $why"
        return false
    }
}
