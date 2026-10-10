package dev.droidtop.library.integrations

import android.content.Context
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogChip
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.TextBlockItem
import dev.droidtop.library.settings.ToggleItem
import dev.droidtop.pluginhost.GrantState
import dev.droidtop.pluginhost.PluginGrants
import dev.droidtop.pluginhost.PluginLinks
import dev.droidtop.pluginhost.PluginRecord
import dev.droidtop.pluginhost.PluginStore
import dev.droidtop.pluginhost.ProvidedPoint
import dev.droidtop.pluginhost.UserOriginKeys
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * droidtop's action links (docs/SPEC.md 12a "Action links", Droidtop/tracker#459): one `droidtop://` grammar that
 * carries an ordered list of calls, read by one parser, shown step by step, and run only on the person's press.
 *
 * ```
 * droidtop://do?v=1&a=<action>[&<param>=<value>]...[&a=<action>...]...
 * https://droidtop.github.io/do?v=1&a=...          (the same link for places that render only web links)
 * ```
 *
 * Each `a=` starts a step; the parameters up to the next `a=` are that step's. An action is built in
 * ([builtIns]: `catalog.add`, `key.trust`, `plugin.install`, `apps.source.add`) or a plugin's, `<plugin id>:<action>`,
 * declared in its manifest ([pluginSpecs]). Every parameter is checked against its declaration, and an unknown action,
 * an unknown, repeated, missing or malformed parameter, a wrong version, more than [MAX_STEPS] steps or more than
 * [MAX_LENGTH] characters refuses the WHOLE link with a plain reason ([parse], pure). A step depends on an earlier
 * step that provides what it uses (a `catalog.add` for the catalog a `key.trust` or `plugin.install` names; a
 * `key.trust` for the catalog a `plugin.install` names) and on the steps it names in `needs`.
 *
 * The review ([screen]) lists every step in plain words with its own Approve switch (on by default; a catalog's
 * notice is shown in full and must be read). Denying a step refuses its dependants on the screen before anything
 * runs; at run time a step that fails stops its dependants with the reason, and independent steps still run. Adding
 * a catalog and trusting a key are separate steps, as by hand. The older `droidtop://add-catalog` and
 * `droidtop://install-plugin` links (and their https forms) are read by the same parser as one step each.
 */
object ActionLinks {
    const val SCREEN_ID = "links_actions"
    const val VERSION = 1
    const val MAX_STEPS = 10
    const val MAX_LENGTH = 4096
    const val HOST = "do"
    const val WEB_HOST = "droidtop.github.io"
    const val WEB_PATH = "/do"

    /** The reserved parameter any step may carry: earlier step numbers it depends on. */
    const val NEEDS = "needs"

    /** How a parameter is checked; [check] returns the value to use, or null when it is not valid. */
    enum class Kind {
        TEXT, URL, ID, SHA256, NUMBER, BOOL;

        fun check(value: String): String? = when (this) {
            TEXT -> value.trim().takeIf { it.isNotEmpty() && it.length <= 500 && it.none(Char::isISOControl) }
            URL -> value.trim().takeIf { it.length <= 1000 && runCatching { URI(it) }.getOrNull()?.let { u -> u.scheme.equals("https", true) && !u.host.isNullOrBlank() } == true }
            ID -> value.trim().takeIf { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) }
            SHA256 -> value.trim().lowercase().replace(":", "").replace(" ", "").takeIf { it.matches(Regex("[0-9a-f]{64}")) }
            NUMBER -> value.trim().takeIf { it.matches(Regex("-?[0-9]{1,18}")) }
            BOOL -> value.trim().lowercase().takeIf { it == "true" || it == "false" }
        }

        companion object {
            fun of(word: String): Kind? = entries.firstOrNull { it.name.equals(word, ignoreCase = true) }
        }
    }

    data class Param(val name: String, val kind: Kind, val required: Boolean = true)

    enum class Risk(val label: String) { LOW("Low risk"), MEDIUM("Medium risk"), HIGH("High risk") }

    /**
     * One link-callable action: its id, plain words for what it does, its parameters and its risk; what a step of it
     * provides to later steps and needs from earlier ones ([provides], [uses], keys like `catalog:<index url>`).
     */
    class Spec(
        val id: String,
        val label: String,
        val risk: Risk,
        val params: List<Param>,
        val provides: (Map<String, String>) -> Set<String> = { emptySet() },
        val uses: (Map<String, String>) -> Set<String> = { emptySet() },
        /** The plugin that declared it, null for a built-in action. */
        val plugin: PluginRecord? = null,
        val entry: ProvidedPoint? = null,
    )

    /** One step of a link: 1-based [number], its action, its checked parameters, and the earlier steps it depends on. */
    data class Step(val number: Int, val spec: Spec, val params: Map<String, String>, val dependsOn: Set<Int>)

    sealed class Parsed {
        data class Ok(val steps: List<Step>) : Parsed()
        data class Refused(val reason: String) : Parsed()
    }

    // ------------------------------------------------------------------
    // Parsing (pure).
    // ------------------------------------------------------------------

    /** Whether [link] is one of droidtop's action links (or an older catalog link), in either form. */
    fun isActionLink(link: String): Boolean {
        val uri = PluginLinks.parse(link) ?: return false
        return when (uri.scheme.lowercase()) {
            PluginLinks.OWN_SCHEME -> uri.host?.lowercase() in setOf(HOST, PluginCatalogSources.LINK_HOST, PluginCatalogSources.INSTALL_LINK_HOST)
            "https" -> uri.host.equals(WEB_HOST, true) &&
                uri.path.orEmpty().trimEnd('/') in setOf(WEB_PATH, PluginCatalogSources.WEB_LINK_PATH, PluginCatalogSources.WEB_INSTALL_PATH)
            else -> false
        }
    }

    /**
     * Reads [link] into its steps, or refuses it whole. [lookup] finds an action's spec by id (built-in or a plugin's).
     * The older `add-catalog` and `install-plugin` links become one `catalog.add` or `plugin.install` step.
     */
    fun parse(link: String, lookup: (String) -> Spec?): Parsed {
        if (link.length > MAX_LENGTH) return Parsed.Refused("The link is longer than $MAX_LENGTH characters")
        if (!isActionLink(link)) return Parsed.Refused("That isn't a droidtop action link")
        PluginCatalogSources.installFromLink(link)?.let { install ->
            val params = buildMap { put("id", install.pluginId); install.catalog?.let { put("catalog", it) } }
            return parseSteps(listOf("plugin.install" to params.entries.map { it.key to it.value }), lookup)
        }
        PluginCatalogSources.addressFromLink(link)?.let { address ->
            return parseSteps(listOf("catalog.add" to listOf("address" to address)), lookup)
        }
        val uri = PluginLinks.parse(link)!!
        val path = uri.path.orEmpty().trimEnd('/')
        if (uri.host.equals(PluginCatalogSources.LINK_HOST, true) || uri.host.equals(PluginCatalogSources.INSTALL_LINK_HOST, true) ||
            path == PluginCatalogSources.WEB_LINK_PATH || path == PluginCatalogSources.WEB_INSTALL_PATH
        ) {
            return Parsed.Refused("The link names no catalog or plugin droidtop can read")
        }
        val pairs = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.map { pair ->
            val name = pair.substringBefore('=')
            val value = runCatching { URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8") }.getOrNull()
                ?: return Parsed.Refused("A value in the link is not readable text")
            name to value
        }
        if (pairs.firstOrNull() != ("v" to VERSION.toString())) {
            return Parsed.Refused("The link is for another version of droidtop's links (it must start with v=$VERSION)")
        }
        val calls = mutableListOf<Pair<String, MutableList<Pair<String, String>>>>()
        for ((name, value) in pairs.drop(1)) {
            if (name == "a") {
                calls += value.trim() to mutableListOf()
            } else {
                val current = calls.lastOrNull() ?: return Parsed.Refused("\"$name\" comes before any action")
                current.second += name to value
            }
        }
        return parseSteps(calls, lookup)
    }

    private fun parseSteps(calls: List<Pair<String, List<Pair<String, String>>>>, lookup: (String) -> Spec?): Parsed {
        if (calls.isEmpty()) return Parsed.Refused("The link asks for nothing")
        if (calls.size > MAX_STEPS) return Parsed.Refused("The link has more than $MAX_STEPS steps")
        val steps = mutableListOf<Step>()
        for ((i, call) in calls.withIndex()) {
            val number = i + 1
            val (actionId, raw) = call
            val spec = lookup(actionId) ?: return Parsed.Refused("Step $number: droidtop has no action \"$actionId\"")
            val params = LinkedHashMap<String, String>()
            var needs = emptySet<Int>()
            for ((name, value) in raw) {
                if (!name.matches(Regex("[a-z][a-zA-Z0-9]*"))) return Parsed.Refused("Step $number: \"$name\" is not a parameter name")
                if (name in params || (name == NEEDS && needs.isNotEmpty())) return Parsed.Refused("Step $number: \"$name\" is given twice")
                if (name == NEEDS) {
                    needs = value.split(',').map { it.trim().toIntOrNull() ?: return Parsed.Refused("Step $number: needs lists step numbers") }.toSet()
                    if (needs.any { it < 1 || it >= number }) return Parsed.Refused("Step $number: needs names a step that does not come before it")
                    continue
                }
                val param = spec.params.firstOrNull { it.name == name } ?: return Parsed.Refused("Step $number: ${spec.id} takes no \"$name\"")
                params[name] = param.kind.check(value) ?: return Parsed.Refused("Step $number: \"$name\" is not a valid ${param.kind.name.lowercase()}")
            }
            spec.params.firstOrNull { it.required && it.name !in params }?.let {
                return Parsed.Refused("Step $number: ${spec.id} needs \"${it.name}\"")
            }
            val uses = spec.uses(params)
            val implied = steps.filter { earlier -> earlier.spec.provides(earlier.params).any { it in uses } }.map { it.number }
            steps += Step(number, spec, params, needs + implied)
        }
        return Parsed.Ok(steps)
    }

    /** The steps [denied] (and the steps that cannot run) take with them: every step that depends on one, directly or not. Pure. */
    fun refused(steps: List<Step>, denied: Set<Int>): Set<Int> {
        val out = denied.toMutableSet()
        for (step in steps) if (step.dependsOn.any { it in out }) out += step.number
        return out
    }

    /** The catalog address the first `catalog.add` of [text] names (an action link, an older add-catalog link, or the address itself). */
    fun catalogAddress(text: String): String? {
        val parsed = parse(text.trim()) { id -> builtIns[id] } as? Parsed.Ok ?: return PluginCatalogSources.addressFromLink(text)
        return parsed.steps.firstOrNull { it.spec.id == "catalog.add" }?.params?.get("address")
    }

    // ------------------------------------------------------------------
    // The built-in actions.
    // ------------------------------------------------------------------

    /** The key a catalog goes by in [Spec.provides] and [Spec.uses]: its index address, or droidtop's own. */
    internal fun catalogKey(address: String?): String =
        "catalog:" + (address?.let { PluginCatalogSources.indexUrlFor(it) ?: it } ?: PluginCatalogSources.OFFICIAL_ID)

    private fun keyKey(address: String?): String = "key:" + catalogKey(address).removePrefix("catalog:")

    val builtIns: Map<String, Spec> = listOf(
        Spec(
            id = "catalog.add",
            label = "Add a plugin catalog",
            risk = Risk.MEDIUM,
            params = listOf(Param("address", Kind.URL)),
            provides = { setOf(catalogKey(it["address"])) },
        ),
        Spec(
            id = "key.trust",
            label = "Trust a publisher's signing key",
            risk = Risk.HIGH,
            params = listOf(Param("catalog", Kind.URL), Param("origin", Kind.ID), Param("sha256", Kind.SHA256)),
            provides = { setOf(keyKey(it["catalog"])) },
            uses = { setOf(catalogKey(it["catalog"])) },
        ),
        Spec(
            id = "plugin.install",
            label = "Install a plugin",
            risk = Risk.HIGH,
            params = listOf(Param("id", Kind.ID), Param("catalog", Kind.URL, required = false)),
            uses = { setOf(catalogKey(it["catalog"]), keyKey(it["catalog"])) },
        ),
        Spec(
            id = "apps.source.add",
            label = "Add a source of Android apps",
            risk = Risk.MEDIUM,
            params = listOf(Param("plugin", Kind.ID), Param("address", Kind.URL), Param("fingerprint", Kind.SHA256, required = false)),
        ),
    ).associateBy { it.id }

    /**
     * The actions runnable plugins declare (`provides` entry `actions: [{id, label, params: [{name, kind, required}]}]`),
     * by `<plugin id>:<action>`. A plugin's actions are offered only while it holds `intents.in` and may provide the
     * point the entry is on. Disk; off the main thread.
     */
    fun pluginSpecs(context: Context): Map<String, Spec> {
        val grants = PluginGrants.forContext(context)
        return PluginStore.installed(context).filter { it.runnable() }.flatMap { record ->
            val snapshot = grants.read(record.manifest.id)
            if (PluginGrants.stateOf(record, snapshot, PluginLinks.PERMISSION) != GrantState.GRANTED) return@flatMap emptyList()
            record.manifest.v2.provides.filter { PluginGrants.pointRefusal(record, snapshot, it.point) == null }.flatMap { entry ->
                declaredActions(record, entry)
            }
        }.associateBy { it.id }
    }

    /** The actions one `provides` entry declares; malformed ones are dropped. Pure. */
    fun declaredActions(record: PluginRecord, entry: ProvidedPoint): List<Spec> {
        val array = runCatching { JSONObject(entry.extra).optJSONArray("actions") }.getOrNull() ?: return emptyList()
        return (0 until minOf(array.length(), 32)).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").trim().takeIf { it.matches(Regex("[a-z][a-z0-9._-]{0,63}")) } ?: return@mapNotNull null
            val params = o.optJSONArray("params")?.let { list ->
                (0 until minOf(list.length(), 16)).map { j ->
                    val p = list.optJSONObject(j) ?: return@mapNotNull null
                    val name = p.optString("name").takeIf { it.matches(Regex("[a-z][a-zA-Z0-9]*")) && it != NEEDS } ?: return@mapNotNull null
                    Param(name, Kind.of(p.optString("kind", "text")) ?: return@mapNotNull null, p.optBoolean("required", true))
                }
            }.orEmpty()
            Spec(
                id = record.manifest.id + ":" + id,
                label = o.optString("label").trim().ifEmpty { id }.take(120),
                risk = Risk.MEDIUM,
                params = params,
                plugin = record,
                entry = entry,
            )
        }
    }

    // ------------------------------------------------------------------
    // The review and the run.
    // ------------------------------------------------------------------

    /** What a step's review found before anything runs: words to show, a notice to read, or why it cannot run. */
    private class Review(
        val title: String,
        val lines: List<String>,
        val notice: String? = null,
        val problem: String? = null,
        /** Already true on this device (the catalog is already added): nothing to run, and dependants may go on. */
        val done: Boolean = false,
        val proposal: PluginCatalog.Proposal? = null,
        val appReview: Pair<PluginRecord, AppCatalogs.Review>? = null,
    )

    private class Plan(val link: String, val steps: List<Step>, val reviews: Map<Int, Review>, val serial: Int) {
        val approved = ConcurrentHashMap<Int, Boolean>().apply { steps.forEach { put(it.number, reviews[it.number]?.problem == null) } }
        @Volatile var results: List<String>? = null
    }

    @Volatile private var plan: Plan? = null
    @Volatile private var serial = 0

    /** The link router's built-in handler for these links: reads, reviews and opens the screen, or says why not. */
    val routerHandler: LinkBuiltIn = { context, link ->
        if (!isActionLink(link)) null else prepare(context, link)
    }

    /** Parses [link], reviews each step (fetching a catalog to show its notice) and opens the review. Off the main thread. */
    suspend fun prepare(context: Context, link: String): LinkRouter.Result = withContext(Dispatchers.IO) {
        val plugins by lazy { pluginSpecs(context) }
        when (val parsed = parse(link) { id -> builtIns[id] ?: plugins[id] }) {
            is Parsed.Refused -> LinkRouter.Result.Message("droidtop did not open this link: ${parsed.reason}. Nothing was changed")
            is Parsed.Ok -> {
                val reviews = HashMap<Int, Review>()
                for (step in parsed.steps) reviews[step.number] = review(context, step, parsed.steps)
                plan = Plan(link, parsed.steps, reviews, ++serial)
                LinkRouter.Result.Open(SCREEN_ID)
            }
        }
    }

    private suspend fun review(context: Context, step: Step, all: List<Step>): Review {
        val p = step.params
        fun addedEarlier(key: String) = all.any { it.number < step.number && key in it.spec.provides(it.params) }
        return when (step.spec.id) {
            "catalog.add" -> when (val result = PluginCatalog.propose(context, p.getValue("address"))) {
                is PluginCatalog.ProposeResult.Ready -> {
                    val proposal = result.proposal
                    Review(
                        title = "Add the plugin catalog \"${proposal.info.name}\"",
                        lines = listOfNotNull(
                            "From ${proposal.indexUrl}",
                            "Not part of droidtop and not checked by it",
                            if (proposal.signed) "The catalog is signed" else "The catalog is not signed: droidtop can only check it came from this address",
                            proposal.origins.takeIf { it.isNotEmpty() }?.let { o ->
                                "It lists plugins from " + o.joinToString(", ") { "\"${it.origin}\"" } + ". This step trusts none of their keys"
                            },
                        ),
                        notice = proposal.disclaimer.text,
                        proposal = proposal,
                    )
                }
                is PluginCatalog.ProposeResult.Failed -> if (result.reason.contains("already one of your catalogs") || result.reason.contains("droidtop's own catalog")) {
                    Review("Add the plugin catalog at ${p.getValue("address")}", listOf(result.reason), done = true)
                } else {
                    Review("Add the plugin catalog at ${p.getValue("address")}", emptyList(), problem = result.reason)
                }
            }
            "key.trust" -> {
                val catalog = p.getValue("catalog")
                val known = catalogSource(context, catalog) != null || addedEarlier(catalogKey(catalog))
                Review(
                    title = "Trust the signing key of publisher \"${p.getValue("origin")}\"",
                    lines = listOf(
                        "In the catalog at $catalog",
                        "Key SHA-256 ${AppCatalogScreen.grouped(p.getValue("sha256"))}",
                        "Plugins this publisher signs can then be installed from that catalog, marked Unofficial. " +
                            "droidtop trusts it only if the catalog names exactly this key",
                    ),
                    problem = if (known) null else "That catalog is not added, and this link does not add it",
                )
            }
            "plugin.install" -> {
                val catalog = p["catalog"]
                val source = if (catalog == null) PluginCatalogSources.OFFICIAL else catalogSource(context, catalog)
                val listed = source?.let { s -> PluginCatalog.listings(context).firstOrNull { it.source.id == s.id } }
                    ?.index?.origins?.flatMap { it.plugins }?.firstOrNull { it.id == p.getValue("id") }
                Review(
                    title = "Install the plugin " + (listed?.label?.let { "\"$it\"" } ?: p.getValue("id")),
                    lines = listOfNotNull(
                        listed?.description,
                        "From " + (catalog ?: "droidtop's own catalog"),
                        "droidtop checks its signature and every file. It does not run until you approve it on the Plugins screen",
                    ),
                    problem = if (source != null || addedEarlier(catalogKey(catalog))) null else "That catalog is not added, and this link does not add it",
                )
            }
            "apps.source.add" -> {
                val record = AppCatalogs.providers(context).firstOrNull { it.manifest.id == p.getValue("plugin") }
                    ?: return Review("Add a source of Android apps", listOf(p.getValue("address")), problem = "No app catalog plugin \"${p.getValue("plugin")}\" is installed and allowed")
                val link = p.getValue("address") + (p["fingerprint"]?.let { (if ('?' in p.getValue("address")) "&" else "?") + "fingerprint=$it" } ?: "")
                AppCatalogs.review(context, record, link).fold(
                    onSuccess = { r ->
                        Review(
                            title = "Add \"${r.name}\" to ${record.manifest.label}",
                            lines = listOfNotNull(
                                r.address,
                                r.fingerprint?.let { "Signing key " + AppCatalogScreen.grouped(it) + if (r.fingerprintFromLink) " (matches the link)" else " (the link named none: compare it with the source's own page)" },
                                r.apps?.let { "$it apps" },
                                r.description,
                            ),
                            done = r.existing,
                            appReview = record to r,
                        )
                    },
                    onFailure = { Review("Add a source of Android apps", listOf(p.getValue("address")), problem = "${record.manifest.label}: ${it.message}") },
                )
            }
            else -> Review(
                title = step.spec.label,
                lines = listOf("From the plugin ${step.spec.plugin?.manifest?.label}") + p.map { (k, v) -> "$k: $v" },
            )
        }
    }

    private fun catalogSource(context: Context, address: String): PluginCatalogSource? {
        val url = PluginCatalogSources.indexUrlFor(address) ?: return null
        if (url.equals(PluginCatalog.indexUrl(context), ignoreCase = true)) return PluginCatalogSources.OFFICIAL
        return PluginCatalogSources.all(context).firstOrNull { it.indexUrl.equals(url, ignoreCase = true) }
    }

    /** Runs one step; null on success with the line to show, or the reason it failed. */
    private suspend fun run(context: Context, step: Step, review: Review, onStatus: (String) -> Unit): Pair<Boolean, String> {
        val p = step.params
        if (review.done) return true to "already so"
        return when (step.spec.id) {
            "catalog.add" -> {
                val line = PluginCatalog.accept(context, review.proposal!!, trustOrigins = false)
                (line.startsWith("Added") || line.contains("already one of your catalogs")) to line
            }
            "key.trust" -> {
                val source = catalogSource(context, p.getValue("catalog")) ?: return false to "the catalog is not added"
                val index = PluginCatalog.currentIndex(context, source).index ?: return false to "the catalog could not be read"
                val origin = index.origins.firstOrNull { it.origin == p.getValue("origin") }
                    ?: return false to "the catalog lists no publisher \"${p.getValue("origin")}\""
                val key = origin.keyBase64 ?: return false to "the catalog gives no usable key for \"${origin.origin}\""
                val sha = UserOriginKeys.keySha256(key)?.lowercase()
                if (sha != p.getValue("sha256")) {
                    return false to "the catalog names a different key for \"${origin.origin}\" (${sha?.let(AppCatalogScreen::grouped)}), not the one in the link. Nothing was trusted"
                }
                val line = PluginCatalog.trustOrigin(context, source, origin.origin, key)
                (line.startsWith("Trusted") || line.contains("already trusted")) to line
            }
            "plugin.install" -> {
                val source = p["catalog"]?.let { catalogSource(context, it) } ?: PluginCatalogSources.OFFICIAL.takeIf { p["catalog"] == null }
                    ?: return false to "the catalog is not added"
                val index = PluginCatalog.currentIndex(context, source).index ?: return false to "the catalog could not be read"
                if (!PluginCatalog.listable(source, index)) return false to "the catalog has a new notice to accept first"
                val origin = index.origins.firstOrNull { o -> o.plugins.any { it.id == p.getValue("id") } }
                    ?: return false to "the catalog does not list ${p.getValue("id")}"
                val plugin = origin.plugins.first { it.id == p.getValue("id") }
                val state = PluginCatalog.originState(source, origin, UserOriginKeys.load(UserOriginKeys.storeFile(context)))
                if (state != PluginCatalog.OriginState.Offered) return false to "its publisher's key is not trusted, so droidtop does not install it"
                val release = PluginCatalog.latestStable(plugin) ?: return false to "it has no stable release"
                val outcome = PluginCatalog.installOutcome(context, plugin, release, onStatus)
                outcome.installed to outcome.line
            }
            "apps.source.add" -> {
                val (record, appReview) = review.appReview ?: return false to "nothing to add"
                val line = AppCatalogs.accept(context, record, appReview, onStatus)
                !line.startsWith("Not added") to line
            }
            else -> {
                val record = step.spec.plugin ?: return false to "no plugin"
                val args = JSONObject().put("action", step.spec.id.substringAfter(':')).put("params", JSONObject(p as Map<*, *>))
                val reply = PluginViews.call(context, record, step.spec.entry!!.point, "run_action", args)
                if (reply.ok) true to reply.data.optString("message").ifBlank { "done" } else false to (reply.message ?: "it failed")
            }
        }
    }

    /** Runs the approved steps in order; a step whose dependency was refused or failed does not run. Returns one line per step. */
    private suspend fun runPlan(context: Context, plan: Plan, onStatus: (String) -> Unit): List<String> {
        val denied = plan.steps.filter { plan.approved[it.number] != true }.map { it.number }.toSet()
        val refused = refused(plan.steps, denied)
        val failed = HashSet<Int>()
        return plan.steps.map { step ->
            val review = plan.reviews.getValue(step.number)
            val title = "${step.number}. ${review.title}"
            when {
                step.number in denied -> "$title: denied"
                step.number in refused -> "$title: not run, it needs step ${step.dependsOn.filter { it in refused }.joinToString(", ")}, which you denied"
                step.dependsOn.any { it in failed } -> {
                    failed += step.number
                    "$title: not run, it needs step ${step.dependsOn.filter { it in failed }.joinToString(", ")}, which did not succeed"
                }
                else -> {
                    onStatus("Step ${step.number}: ${review.title}...")
                    val (ok, line) = runCatching { run(context, step, review, onStatus) }.getOrElse { false to (it.message ?: "it failed") }
                    if (!ok) failed += step.number
                    "$title: " + (if (ok) "done. " else "failed: ") + line
                }
            }
        }
    }

    /** The review: every step with its own Approve switch, what it needs, any notice in full, and Run. Registered for the router. */
    fun screen(): CatalogScreen = CatalogScreen(
        id = SCREEN_ID,
        title = "Open a droidtop link",
        subtitle = "A link asks droidtop to do the steps below. Approve or deny each; nothing runs until you press Run",
        groups = { _ ->
            val current = plan
            if (current == null) {
                listOf(CatalogGroup("links_actions_none", null, listOf(ActionItem("links_actions_none_row", "No link is waiting", run = {}))))
            } else {
                groups(current)
            }
        },
        indexGroups = { emptyList() },
    )

    private fun groups(plan: Plan): List<CatalogGroup> {
        plan.results?.let { results ->
            return listOf(
                CatalogGroup(
                    "links_actions_results",
                    "What happened",
                    results.mapIndexed { i, line -> TextBlockItem("links_actions_result_$i", text = line) } +
                        ActionItem("links_actions_done", "Done", run = { _ -> if (this.plan === plan) this.plan = null }),
                ),
            )
        }
        val denied = plan.steps.filter { plan.approved[it.number] != true }.map { it.number }.toSet()
        val refused = refused(plan.steps, denied)
        val gate = "links_actions_read_${plan.serial}"
        val noticeSteps = plan.steps.filter { plan.reviews[it.number]?.notice != null && it.number !in refused }
        val groups = mutableListOf<CatalogGroup>()
        groups += CatalogGroup(
            "links_actions_link",
            null,
            listOf(TextBlockItem("links_actions_link_text", title = "${plan.steps.size} step${if (plan.steps.size == 1) "" else "s"}", text = plan.link.take(300))),
        )
        for (step in plan.steps) {
            val review = plan.reviews.getValue(step.number)
            val items = buildList<CatalogItem> {
                review.lines.forEachIndexed { i, line -> add(TextBlockItem("links_actions_${step.number}_line_$i", text = line)) }
                if (step.dependsOn.isNotEmpty()) {
                    add(ActionItem("links_actions_${step.number}_needs", "Needs step ${step.dependsOn.sorted().joinToString(", ")}", run = {}))
                }
                review.notice?.let { notice ->
                    val paragraphs = notice.trim().split(Regex("\\n+")).map { it.trim() }.filter { it.isNotEmpty() }
                    val lastNotice = noticeSteps.lastOrNull()?.number == step.number
                    paragraphs.forEachIndexed { i, text ->
                        add(
                            TextBlockItem(
                                id = "links_actions_${step.number}_notice_$i",
                                title = if (i == 0) "Notice from the catalog" else "",
                                text = text,
                                gate = if (step.number in refused) null else gate,
                                last = lastNotice && i == paragraphs.lastIndex,
                            ),
                        )
                    }
                }
                when {
                    review.problem != null -> add(ActionItem("links_actions_${step.number}_problem", "Cannot run", subtitle = review.problem, run = {}))
                    review.done -> add(ActionItem("links_actions_${step.number}_done", "Already done on this device", run = {}))
                    step.number in refused && step.number !in denied ->
                        add(ActionItem("links_actions_${step.number}_refused", "Refused", subtitle = "It needs a step you denied", run = {}))
                    else -> add(
                        ToggleItem(
                            id = "links_actions_${step.number}_approve",
                            title = "Do this step",
                            subtitle = step.spec.risk.label,
                            current = plan.approved[step.number] == true,
                            onToggle = { _, value -> plan.approved[step.number] = value },
                        ),
                    )
                }
            }
            groups += CatalogGroup(
                "links_actions_step_${step.number}",
                "${step.number}. ${review.title}",
                items,
                chips = listOfNotNull(step.spec.plugin?.let { CatalogChip("From ${it.manifest.label}") }),
            )
        }
        val toRun = plan.steps.count { it.number !in refused && plan.reviews[it.number]?.done != true }
        groups += CatalogGroup(
            "links_actions_run",
            null,
            listOf(
                AsyncActionItem(
                    id = "links_actions_run_${plan.serial}",
                    title = if (toRun == 0) "Nothing to run" else "Run the approved steps",
                    subtitle = if (noticeSteps.isEmpty()) null else "Opens once you have read the notice above",
                    gate = if (noticeSteps.isEmpty()) null else gate,
                    confirmTitle = "Run $toRun step${if (toRun == 1) "" else "s"} of this link?",
                    run = { ctx, onStatus ->
                        val results = runPlan(ctx, plan, onStatus)
                        plan.results = results
                        results.joinToString("\n")
                    },
                ),
                ActionItem("links_actions_cancel", "Cancel", subtitle = "Run nothing", run = { _ -> if (this.plan === plan) this.plan = null }),
            ),
        )
        return groups
    }
}
