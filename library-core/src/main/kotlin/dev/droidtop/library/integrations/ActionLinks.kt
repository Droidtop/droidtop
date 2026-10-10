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
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * droidtop's action links (docs/SPEC.md 12a "Action links", Droidtop/tracker#459, grammar 2): literal API calls,
 * joined by `&`, read by one parser, shown step by step and run only on the person's press. Owner, 2026-10-10:
 * "They should be literal api call strings through the URL parser. They can be strung together using ampersands,
 * and we should support base64".
 *
 * ```
 * droidtop://call?v=2&<call>[&<call>]...
 * https://droidtop.github.io/call?v=2&<call>...      (the same strings)
 * <call> := <api>.<op>(<json args>) | <plugin id>:<op>(<json args>) | b64:<base64url of one call or several joined by &>
 * ```
 *
 * A call names a host op as docs/plugin-api.md names it ([hostOps]: `catalog.add`, `key.trust`,
 * `plugin.install`, `app_source.add`) or an op a plugin declares callable from links (`linkOps`, [pluginSpecs]),
 * with the op's own argument object. Every argument is checked against the op's declaration, and a malformed call,
 * an unknown op, an unknown, missing or malformed argument, a wrong version, more than [MAX_STEPS] calls or more
 * than [MAX_LENGTH] characters refuses the WHOLE link with a plain reason ([parse], pure). A step depends on an
 * earlier step that provides what it uses (a `catalog.add` for the catalog a `key.trust` or
 * `plugin.install` names; a `key.trust` for the catalog a `plugin.install` names) and on the steps in its
 * `"$needs"` argument, which is removed before the call.
 *
 * The review ([screen]) gives every call a plain-language line, its risk and its own switch; denying a step refuses
 * its dependants before anything runs; a step that fails stops its dependants, and independent steps still run.
 * The older `droidtop://add-catalog` and `droidtop://install-plugin` links (and their https forms) are read as one
 * call each.
 */
object ActionLinks {
    const val SCREEN_ID = "links_actions"
    const val VERSION = 2
    const val MAX_STEPS = 10
    const val MAX_LENGTH = 8192
    const val HOST = "call"
    const val WEB_HOST = "droidtop.github.io"
    const val WEB_PATH = "/call"

    /** The argument any call may carry: earlier call numbers it depends on. Removed before the call is made. */
    const val NEEDS = "\$needs"

    /** How an argument is checked; [check] returns the value to use, or null when it is not valid. */
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

    enum class Risk(val label: String) {
        LOW("Low risk"), MEDIUM("Medium risk"), HIGH("High risk");

        companion object {
            fun of(word: String?): Risk = entries.firstOrNull { it.name.equals(word, ignoreCase = true) } ?: MEDIUM
        }
    }

    /**
     * One op a link may call: its name, the plain-language line for the review, its arguments, its risk, the
     * permission it carries when a plugin makes the same call, and what a step of it provides to later steps and
     * needs from earlier ones ([provides], [uses], keys like `catalog:<index url>`).
     */
    class Spec(
        val id: String,
        val label: String,
        val risk: Risk,
        val params: List<Param>,
        val permission: String? = null,
        val provides: (Map<String, String>) -> Set<String> = { emptySet() },
        val uses: (Map<String, String>) -> Set<String> = { emptySet() },
        /** The plugin that declared it, null for a host op. */
        val plugin: PluginRecord? = null,
        val entry: ProvidedPoint? = null,
        /** A plugin op's own description of what it does; the review shows it rather than trusting the op's name. */
        val description: String? = null,
    )

    /** One step of a link: 1-based [number], its op, its checked arguments, and the earlier steps it depends on. */
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
        if (callQuery(link) != null) return true
        val uri = PluginLinks.parse(link) ?: return false
        return when (uri.scheme.lowercase()) {
            PluginLinks.OWN_SCHEME -> uri.host?.lowercase() in setOf(HOST, PluginCatalogSources.LINK_HOST, PluginCatalogSources.INSTALL_LINK_HOST)
            "https" -> uri.host.equals(WEB_HOST, true) &&
                uri.path.orEmpty().trimEnd('/') in setOf(WEB_PATH, PluginCatalogSources.WEB_LINK_PATH, PluginCatalogSources.WEB_INSTALL_PATH)
            else -> false
        }
    }

    /** A host op (`catalog.add`, `web.session.get_status`) or a plugin's (`droidtop.fdroid:refresh`). */
    private val OP_NAME = Regex("([a-z][a-z0-9_]*([.][a-z][a-z0-9_]*)+)|([A-Za-z0-9][A-Za-z0-9._-]{0,127}:[a-z][a-z0-9_]*([.][a-z][a-z0-9_]*)*)")

    /**
     * The raw query of a grammar 2 link, or null when [link] is not one. Read by hand rather than by java.net.URI: a
     * link handed over by Android or typed into a page may carry `{`, `"` or spaces unescaped, which URI refuses.
     */
    private fun callQuery(link: String): String? {
        val text = link.trim()
        val head = text.substringBefore('?').trimEnd('/').lowercase()
        if (head != "droidtop://$HOST" && head != "https://$WEB_HOST$WEB_PATH") return null
        return text.substringAfter('?', "").substringBefore('#')
    }

    /** A call as written: its op name and its argument object. */
    data class RawCall(val op: String, val args: JSONObject)

    /**
     * Reads [link] into its steps, or refuses it whole. [lookup] finds an op's spec by name (a host op or a plugin's).
     * The older `add-catalog` and `install-plugin` links become one `catalog.add` or `plugin.install` call.
     */
    fun parse(link: String, lookup: (String) -> Spec?): Parsed {
        if (link.length > MAX_LENGTH) return Parsed.Refused("The link is longer than $MAX_LENGTH characters")
        if (!isActionLink(link)) return Parsed.Refused("That isn't a droidtop action link")
        PluginCatalogSources.installFromLink(link)?.let { install ->
            val args = JSONObject().put("id", install.pluginId).apply { install.catalog?.let { put("catalog", it) } }
            return parseSteps(listOf(RawCall(PLUGIN_INSTALL, args)), lookup)
        }
        PluginCatalogSources.addressFromLink(link)?.let { address ->
            return parseSteps(listOf(RawCall(CATALOG_ADD, JSONObject().put("address", address))), lookup)
        }
        val rawQuery = callQuery(link) ?: return Parsed.Refused("The link names no catalog or plugin droidtop can read")
        val query = percentDecode(rawQuery) ?: return Parsed.Refused("The link is not readable text")
        val version = "v=$VERSION"
        if (query != version && !query.startsWith("$version&")) {
            return Parsed.Refused("The link is for another version of droidtop's links (it must start with v=$VERSION)")
        }
        val calls = when (val read = readCalls(query.removePrefix(version).removePrefix("&"), allowBase64 = true)) {
            is CallsRead.Ok -> read.calls
            is CallsRead.Bad -> return Parsed.Refused(read.reason)
        }
        return parseSteps(calls, lookup)
    }

    private sealed class CallsRead {
        data class Ok(val calls: List<RawCall>) : CallsRead()
        data class Bad(val reason: String) : CallsRead()
    }

    /**
     * Reads `<call>&<call>...` (already percent-decoded): an op name, then a JSON object in parentheses, read so that
     * an `&` or `)` inside a JSON string belongs to the string. A `b64:` call is decoded and read the same way, once.
     */
    private fun readCalls(text: String, allowBase64: Boolean): CallsRead {
        val calls = mutableListOf<RawCall>()
        var i = 0
        while (i < text.length) {
            if (text.startsWith("b64:", i)) {
                if (!allowBase64) return CallsRead.Bad("A base64 call may not hold another base64 call")
                val end = text.indexOf('&', i).let { if (it < 0) text.length else it }
                val decoded = runCatching {
                    String(java.util.Base64.getUrlDecoder().decode(text.substring(i + 4, end).trimEnd('=')), Charsets.UTF_8)
                }.getOrNull() ?: return CallsRead.Bad("A b64: call is not base64url")
                when (val inner = readCalls(decoded, allowBase64 = false)) {
                    is CallsRead.Ok -> calls += inner.calls
                    is CallsRead.Bad -> return inner
                }
                i = end + 1
                continue
            }
            val open = text.indexOf('(', i)
            if (open < 0) return CallsRead.Bad("\"${text.substring(i).take(60)}\" is not a call (op(arguments))")
            val op = text.substring(i, open).trim()
            if (!op.matches(OP_NAME)) {
                return CallsRead.Bad("\"${op.take(60)}\" is not an op name")
            }
            val close = closingParen(text, open) ?: return CallsRead.Bad("The arguments of $op are not closed")
            val body = text.substring(open + 1, close).trim()
            val args = if (body.isEmpty()) JSONObject() else runCatching { JSONObject(body) }.getOrNull()
                ?: return CallsRead.Bad("The arguments of $op are not a JSON object")
            calls += RawCall(op, args)
            i = close + 1
            if (i < text.length) {
                if (text[i] != '&') return CallsRead.Bad("Calls are joined with &; found \"${text[i]}\" after $op")
                i++
            }
        }
        return CallsRead.Ok(calls)
    }

    /** The index of the `)` that closes the `(` at [open], skipping JSON strings; null when there is none. */
    private fun closingParen(text: String, open: Int): Int? {
        var inString = false
        var escaped = false
        var depth = 0
        for (j in open + 1 until text.length) {
            val c = text[j]
            when {
                escaped -> escaped = false
                inString && c == '\\' -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' || c == '[' -> depth++
                c == '}' || c == ']' -> depth--
                c == ')' && depth == 0 -> return j
            }
        }
        return null
    }

    /** Percent-decoding that leaves `+` as it is (a JSON string may hold one); null for a broken escape. */
    internal fun percentDecode(raw: String): String? {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '%') {
                if (i + 2 >= raw.length) return null
                val byte = raw.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
                out.write(byte)
                i += 3
            } else {
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        return out.toString("UTF-8")
    }

    private fun parseSteps(calls: List<RawCall>, lookup: (String) -> Spec?): Parsed {
        if (calls.isEmpty()) return Parsed.Refused("The link asks for nothing")
        if (calls.size > MAX_STEPS) return Parsed.Refused("The link has more than $MAX_STEPS calls")
        val steps = mutableListOf<Step>()
        for ((i, call) in calls.withIndex()) {
            val number = i + 1
            val spec = lookup(call.op) ?: return Parsed.Refused("Call $number: ${call.op} is not something a link can call")
            val params = LinkedHashMap<String, String>()
            var needs = emptySet<Int>()
            for (name in call.args.keys()) {
                val value = call.args.get(name)
                if (name == NEEDS) {
                    val list = value as? JSONArray ?: return Parsed.Refused("Call $number: $NEEDS is a list of call numbers")
                    needs = (0 until list.length()).map { list.optInt(it, -1) }.toSet()
                    if (needs.any { it < 1 || it >= number }) return Parsed.Refused("Call $number: $NEEDS names a call that does not come before it")
                    continue
                }
                val param = spec.params.firstOrNull { it.name == name } ?: return Parsed.Refused("Call $number: ${spec.id} takes no \"$name\"")
                if (value is JSONObject || value is JSONArray || value == JSONObject.NULL) {
                    return Parsed.Refused("Call $number: \"$name\" is not a single value")
                }
                params[name] = param.kind.check(value.toString()) ?: return Parsed.Refused("Call $number: \"$name\" is not a valid ${param.kind.name.lowercase()}")
            }
            spec.params.firstOrNull { it.required && it.name !in params }?.let {
                return Parsed.Refused("Call $number: ${spec.id} needs \"${it.name}\"")
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
        val parsed = parse(text.trim()) { id -> hostOps[id] } as? Parsed.Ok ?: return PluginCatalogSources.addressFromLink(text)
        return parsed.steps.firstOrNull { it.spec.id == CATALOG_ADD }?.params?.get("address")
    }

    /** One call as a link writes it, for a page or a QR code: `op(args)` (pure). */
    fun call(op: String, args: JSONObject): String = "$op($args)"

    // ------------------------------------------------------------------
    // The host ops a link may call.
    // ------------------------------------------------------------------

    const val CATALOG_ADD = "catalog.add"
    const val KEY_TRUST = "key.trust"
    const val PLUGIN_INSTALL = "plugin.install"
    const val SOURCE_ADD = "app_source.add"

    /** The key a catalog goes by in [Spec.provides] and [Spec.uses]: its index address, or droidtop's own. */
    internal fun catalogKey(address: String?): String =
        "catalog:" + (address?.let { PluginCatalogSources.indexUrlFor(it) ?: it } ?: PluginCatalogSources.OFFICIAL_ID)

    private fun keyKey(address: String?): String = "key:" + catalogKey(address).removePrefix("catalog:")

    /**
     * The host ops a link may call (docs/plugin-api.md 3 J "Ops a link may call"). Each carries the permission a
     * plugin would need to make the same call; no plugin is offered these yet, so a link (the person approving each
     * step) is their only caller. None falls under a Risky actions class.
     */
    val hostOps: Map<String, Spec> = listOf(
        Spec(
            id = CATALOG_ADD,
            label = "Add a plugin catalog",
            risk = Risk.MEDIUM,
            params = listOf(Param("address", Kind.URL)),
            permission = "plugins.manage",
            provides = { setOf(catalogKey(it["address"])) },
        ),
        Spec(
            id = KEY_TRUST,
            label = "Trust a publisher's signing key",
            risk = Risk.HIGH,
            params = listOf(Param("catalog", Kind.URL), Param("origin", Kind.ID), Param("sha256", Kind.SHA256)),
            permission = "plugins.manage",
            provides = { setOf(keyKey(it["catalog"])) },
            uses = { setOf(catalogKey(it["catalog"])) },
        ),
        Spec(
            id = PLUGIN_INSTALL,
            label = "Install a plugin",
            risk = Risk.HIGH,
            params = listOf(Param("id", Kind.ID), Param("catalog", Kind.URL, required = false)),
            permission = "plugins.manage",
            uses = { setOf(catalogKey(it["catalog"]), keyKey(it["catalog"])) },
        ),
        Spec(
            id = SOURCE_ADD,
            label = "Add a source of Android apps",
            risk = Risk.MEDIUM,
            params = listOf(Param("plugin", Kind.ID), Param("address", Kind.URL), Param("fingerprint", Kind.SHA256, required = false)),
            permission = "apps.manage",
        ),
    ).associateBy { it.id }

    /**
     * The ops runnable plugins declare callable from links (`provides` entry `linkOps: [{op, label, risk?, args:
     * {name: kind}}]`), by `<plugin id>:<op>`. A plugin's ops are offered only while it holds `intents.in` and may
     * provide the point the entry is on. Disk; off the main thread.
     */
    fun pluginSpecs(context: Context): Map<String, Spec> {
        val grants = PluginGrants.forContext(context)
        return PluginStore.installed(context).filter { it.runnable() }.flatMap { record ->
            val snapshot = grants.read(record.manifest.id)
            if (PluginGrants.stateOf(record, snapshot, PluginLinks.PERMISSION) != GrantState.GRANTED) return@flatMap emptyList()
            record.manifest.v2.provides.filter { PluginGrants.pointRefusal(record, snapshot, it.point) == null }.flatMap { entry ->
                declaredOps(record, entry)
            }
        }.associateBy { it.id }
    }

    /** The link-callable ops one `provides` entry declares; malformed ones are dropped. Pure. */
    fun declaredOps(record: PluginRecord, entry: ProvidedPoint): List<Spec> {
        val array = runCatching { JSONObject(entry.extra).optJSONArray("linkOps") }.getOrNull() ?: return emptyList()
        return (0 until minOf(array.length(), 32)).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val op = o.optString("op").trim().takeIf { it.matches(Regex("[a-z][a-z0-9_.]{0,63}")) } ?: return@mapNotNull null
            val argsDecl = o.optJSONObject("args") ?: JSONObject()
            val params = argsDecl.keys().asSequence().take(16).toList().map { name ->
                if (!name.matches(Regex("[a-z][a-zA-Z0-9]*"))) return@mapNotNull null
                val word = argsDecl.optString(name)
                val optional = word.endsWith("?")
                Param(name, Kind.of(word.removeSuffix("?")) ?: return@mapNotNull null, required = !optional)
            }
            // A plugin's own op is never less than medium risk: the person reads its label, which the plugin wrote.
            val risk = Risk.of(o.optString("risk")).let { if (it == Risk.LOW) Risk.MEDIUM else it }
            Spec(
                id = record.manifest.id + ":" + op,
                label = o.optString("label").trim().ifEmpty { op }.take(120),
                risk = risk,
                params = params,
                permission = PluginLinks.PERMISSION,
                plugin = record,
                entry = entry,
                description = o.optString("description").trim().takeIf { it.isNotEmpty() }?.take(500),
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
        when (val parsed = parse(link) { id -> hostOps[id] ?: plugins[id] }) {
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
            CATALOG_ADD -> when (val result = PluginCatalog.propose(context, p.getValue("address"))) {
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
            KEY_TRUST -> {
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
            PLUGIN_INSTALL -> {
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
            SOURCE_ADD -> {
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
            else -> {
                // A plugin's op name is the plugin's word, not droidtop's: the review shows what the plugin says the call
                // does, what it may use while doing it, and the values, and says so plainly when it says nothing.
                val record = step.spec.plugin!!
                val snapshot = PluginGrants.forContext(context).read(record.manifest.id)
                val allowed = record.manifest.v2.permissions
                    .filter { PluginGrants.stateOf(record, snapshot, it.id) == dev.droidtop.pluginhost.GrantState.GRANTED }
                    .map { dev.droidtop.pluginhost.PluginPermissions.find(it.id)?.label ?: it.id }
                Review(
                    title = step.spec.label,
                    lines = listOfNotNull(
                        "A call to the plugin ${record.manifest.label}, which names it \"${step.spec.id.substringAfter(':')}\"",
                        step.spec.description ?: "This plugin gave no description of what this call does",
                        if (allowed.isEmpty()) "It can use none of your permissions" else "It can use what you allowed ${record.manifest.label}: " + allowed.joinToString(", "),
                    ) + p.map { (k, v) -> "$k: $v" },
                )
            }
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
            CATALOG_ADD -> {
                val line = PluginCatalog.accept(context, review.proposal!!, trustOrigins = false)
                (line.startsWith("Added") || line.contains("already one of your catalogs")) to line
            }
            KEY_TRUST -> {
                val source = catalogSource(context, p.getValue("catalog")) ?: return false to "the catalog is not added"
                val index = PluginCatalog.currentIndex(context, source).index ?: return false to "the catalog could not be read"
                val origin = index.origins.firstOrNull { it.origin == p.getValue("origin") }
                    ?: return false to "the catalog lists no publisher \"${p.getValue("origin")}\""
                val key = origin.keyBase64 ?: return false to "the catalog gives no usable key for \"${origin.origin}\""
                // The key's SHA-256 as Keys you trust shows it, or as the catalog's index publishes it for the origin (what
                // a catalog's web page puts in its links); both name the same key.
                val sha = UserOriginKeys.keySha256(key)?.lowercase()
                if (sha != p.getValue("sha256") && origin.keySha256?.lowercase() != p.getValue("sha256")) {
                    return false to "the catalog names a different key for \"${origin.origin}\" (${sha?.let(AppCatalogScreen::grouped)}), not the one in the link. Nothing was trusted"
                }
                val line = PluginCatalog.trustOrigin(context, source, origin.origin, key)
                (line.startsWith("Trusted") || line.contains("already trusted")) to line
            }
            PLUGIN_INSTALL -> {
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
            SOURCE_ADD -> {
                val (record, appReview) = review.appReview ?: return false to "nothing to add"
                val line = AppCatalogs.accept(context, record, appReview, onStatus)
                !line.startsWith("Not added") to line
            }
            else -> {
                val record = step.spec.plugin ?: return false to "no plugin"
                // The literal call: the plugin's op, on the point that declared it, with the link's arguments.
                val reply = PluginViews.call(context, record, step.spec.entry!!.point, step.spec.id.substringAfter(':'), JSONObject(p as Map<*, *>))
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
                    review.problem != null -> {
                        // The reason is its own text block: the rig showed no reason under the "Cannot run" row (Droidtop/tracker#480).
                        add(TextBlockItem("links_actions_${step.number}_problem_reason", text = review.problem))
                        add(ActionItem("links_actions_${step.number}_problem", "Cannot run", run = {}))
                    }
                    review.done -> add(ActionItem("links_actions_${step.number}_done", "Already done on this device", run = {}))
                    step.number in refused && step.number !in denied ->
                    {
                        add(TextBlockItem("links_actions_${step.number}_refused_reason", text = "It needs a step you denied"))
                        add(ActionItem("links_actions_${step.number}_refused", "Refused", run = {}))
                    }
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
