package dev.droidtop.pluginhost

import org.json.JSONArray
import org.json.JSONObject

/**
 * Facets (docs/plugin-api.md 1.10, contract 3): small, typed pieces of UI made of data and actions, which droidtop draws
 * on its own targets with its own components. This is the model and its reading; [FacetGuards] decides which facets a
 * target takes from a plugin. Pure, so both are tested on the JVM.
 *
 * Reading never fails on a plugin's mistake, as a view's does not (§1.6): a facet with no id, an unknown kind or
 * version, a missing required field or a bad action is dropped, and anything over a limit (the limits file's `facets`,
 * docs/view-limits/plugin-view-limits.json) is cut or dropped. Each of these is said in [Reading.messages] in plain
 * words for the plugin's message log.
 */
data class Facet(
    /** The plugin's own stable name; a later facet with the same id replaces this one wherever it is shown. */
    val id: String,
    /** The kind's or bundle's name, as [body] is. */
    val kind: String,
    val v: Int,
    val subject: FacetSubject,
    /** Where the plugin would like it; droidtop shows it on these at most, and only on the ones it takes ([FacetGuards]). */
    val targets: List<String>,
    val priority: Priority,
    /** Seconds it stays true without asking again; 0 asks each time its target opens. */
    val ttlSeconds: Long,
    /** A permission of the plugin's (or `app`) that, while off, draws this facet inert (§1.6's `needs`). */
    val needs: String?,
    val body: FacetBody,
) {
    enum class Priority { LOW, NORMAL, HIGH }

    /** Item bundles: kept per item by droidtop and used beyond drawing (filters, updates, Downloads). */
    val isBundle: Boolean get() = kind in BUNDLES

    companion object {
        /** The kinds this build reads, each at the versions it reads. Advertised in `host.info().facets`. */
        val KINDS: Map<String, Set<Int>> = listOf(
            "info", "status", "badge", "progress", "meter", "chart", "action", "toggle", "choice", "slider", "input",
            "list", "collection", "media", "text", "link", "notice", "account", "group", "secret",
            "metadata", "description", "image", "download", "links", "store", "update", "identity",
        ).associateWith { setOf(1) }

        val BUNDLES = setOf("metadata", "description", "image", "download", "links", "store", "update", "identity")
    }
}

/** What a facet is about (docs/plugin-api.md 1.10, "The envelope"). */
sealed interface FacetSubject {
    object Plugin : FacetSubject

    object Device : FacetSubject

    /** An item of one of the plugin's `provides` entries ([source] is that entry's id). */
    data class Item(val source: String, val id: String) : FacetSubject

    /** A library game droidtop asked the plugin about, by droidtop's id. */
    data class Game(val gameId: String) : FacetSubject

    val type: String
        get() = when (this) {
            Plugin -> "plugin"
            Device -> "device"
            is Item -> "item"
            is Game -> "game"
        }
}

/**
 * An action a facet sends back to the plugin that gave it: a quick call, a job, or a page of facets. A [Kind.GRANT]
 * goes to droidtop instead: [op] is one of the plugin's declared permissions, and droidtop asks the person for it.
 */
data class FacetCall(val op: String, val argsJson: String = "{}", val kind: Kind = Kind.CALL) {
    enum class Kind { CALL, JOB, PAGE, GRANT }

    fun args(): JSONObject = runCatching { JSONObject(argsJson) }.getOrDefault(JSONObject())
}

/** The kinds (UI building blocks) and the bundles (standard sets for items). Texts are already cut to their limits. */
sealed interface FacetBody {
    data class Info(val label: String, val value: String?, val sub: String?, val filterable: Boolean, val sortable: Boolean) : FacetBody

    data class Status(val label: String, val state: String, val tone: String, val value: String?) : FacetBody

    data class Badge(val text: String, val tone: String) : FacetBody

    /** [value] is 0..100, or -1 when unknown. */
    data class Progress(val label: String, val value: Int, val sub: String?, val cancel: FacetCall?) : FacetBody

    data class Meter(val label: String, val value: Double, val max: Double, val unit: String?, val warnAt: Double?, val dangerAt: Double?) : FacetBody

    /** A sparkline ([points]) or a small bar chart ([bars]). */
    data class Chart(val label: String, val style: String, val points: List<Double>, val bars: List<Pair<String, Double>>, val unit: String?) : FacetBody

    data class Action(val label: String, val sub: String?, val call: FacetCall, val confirm: String, val icon: String?) : FacetBody

    // An input's [call] is null inside a form group: the form's submit sends its value (docs/plugin-api.md 1.10, `group`).
    data class Toggle(val label: String, val value: Boolean, val sub: String?, val call: FacetCall?) : FacetBody

    data class Choice(val label: String, val options: List<Pair<String, String>>, val value: String, val sub: String?, val call: FacetCall?) : FacetBody

    data class Slider(val label: String, val min: Double, val max: Double, val step: Double?, val value: Double, val unit: String?, val call: FacetCall?) : FacetBody

    /** [optional]: a form's submit may be pressed with it empty. */
    data class Input(val label: String, val value: String, val hint: String?, val inputKind: String, val call: FacetCall?, val optional: Boolean = false) : FacetBody

    data class ListEntry(val id: String, val title: String, val sub: String?, val value: String?, val badges: List<String>, val media: String?, val call: FacetCall?)

    data class ListBody(val title: String?, val entries: List<ListEntry>, val more: FacetCall?) : FacetBody

    data class CollectionEntry(val id: String, val title: String, val media: String, val badges: List<String>, val call: FacetCall?)

    data class Collection(val title: String?, val entries: List<CollectionEntry>, val shape: String) : FacetBody

    data class Media(val role: String, val url: String, val poster: String?, val width: Int?, val height: Int?, val alt: String) : FacetBody

    data class Text(val body: String, val format: String) : FacetBody

    data class Link(val label: String, val url: String, val linkKind: String) : FacetBody

    data class Notice(val title: String, val sub: String?, val details: String?, val tone: String, val placement: String, val call: FacetCall?) : FacetBody

    data class Account(val site: String, val signedIn: Boolean, val name: String?) : FacetBody

    /** A value the person reads or copies elsewhere: [call] gives it only when they press Show, and droidtop never keeps it. */
    data class Secret(val label: String, val sub: String?, val call: FacetCall) : FacetBody

    /**
     * [slot] opens a place in this group that another plugin may target as `<this plugin>:<slot>`. A group with a
     * [submit] is a form: its inputs send nothing of their own, and [submit] sends them all as `args.values`.
     */
    data class Group(val title: String?, val facets: List<Facet>, val slot: String?, val submit: Action? = null) : FacetBody {
        val isForm: Boolean get() = submit != null
    }

    data class Metadata(
        val title: String?,
        val version: String?,
        val developer: String?,
        val publisher: String?,
        val engine: String?,
        val status: String?,
        val released: String?,
        val updated: Long?,
        val rating: Double?,
        val votes: Long?,
        val tags: List<String>,
        val genres: List<String>,
    ) : FacetBody

    data class Description(val overview: String?, val changelog: String?) : FacetBody

    data class Image(val media: Media) : FacetBody

    /** [systems]: the systems the link is for ([DownloadSystems]), named by the plugin or read from [section]; empty for any. */
    data class DownloadEntry(
        val section: String,
        val host: String,
        val label: String,
        val url: String?,
        val link: String?,
        val size: Long?,
        val version: String?,
        val entryKind: String,
        val systems: List<String> = emptyList(),
        /** The pieces of one split release on one host (2026-10-29): picked together, as one Downloads job. */
        val parts: List<DownloadPart> = emptyList(),
        /** `build` (the game), `patch` or `extra` (Droidtop/tracker#838): only a build is ever picked by droidtop itself. */
        val role: String = "build",
        /** False for an item the source marks unofficial (a thread's `*`). */
        val official: Boolean = true,
        /** The build a game's link is for ("Steam Build", Droidtop/tracker#845); null for the default build. */
        val build: String? = null,
    )

    /** One piece of a split release: an address, or a token only the plugin's `download` job understands. */
    data class DownloadPart(val label: String, val url: String?, val link: String?, val size: Long?)

    data class Download(val entries: List<DownloadEntry>) : FacetBody

    data class Links(val entries: List<Link>) : FacetBody

    data class Store(val store: String, val url: String, val price: String?, val currency: String?, val owned: Boolean?, val free: Boolean, val onSale: Boolean) : FacetBody

    data class Update(val version: String?, val url: String?, val gone: Boolean) : FacetBody

    data class Identity(val ids: Map<String, String>) : FacetBody
}

/** Reading a reply's `facets` (and its `templates`) into [Facet]s. */
object Facets {
    /** What was read, and what the host had to change or drop to read it, in plain words for the plugin's message log. */
    data class Reading(val facets: List<Facet>, val messages: List<String>)

    private val STATES = setOf("on", "off", "busy", "ok", "warning", "error", "unknown")
    private val TONES = setOf("neutral", "positive", "caution", "danger")
    private val CONFIRMS = setOf("none", "confirm", "danger")
    private val MEDIA_ROLES = setOf("cover", "wide", "hero", "logo", "icon", "screenshot", "banner", "video")
    private val LINK_KINDS = setOf("source", "homepage", "store", "forum", "wiki", "discord", "support", "other")
    private val INPUT_KINDS = setOf("text", "number", "url", "search")
    private val SHAPES = setOf("capsule", "wide", "square")
    private val DOWNLOAD_KINDS = setOf("page", "file", "torrent", "magnet")
    private val DOWNLOAD_ROLES = setOf("build", "patch", "extra")
    private val STATUSES = setOf("ongoing", "completed", "onhold", "abandoned")
    private val NOTICE_TONES = setOf("info", "caution", "danger")

    /**
     * The facets of a reply's `facets`. A `template` entry among them is not drawn: it names an arrangement that `use`
     * entries fill in. [defaultSubject] is the subject of a facet that names none: the plugin itself, unless the
     * asking target has a subject of its own. [held]: the target holds its inputs' values itself (`search.filter`),
     * so an input there needs no call of its own, as in a form.
     */
    fun read(json: JSONObject?, defaultSubject: FacetSubject = FacetSubject.Plugin, held: Boolean = false): Reading {
        val messages = mutableListOf<String>()
        val array = json?.optJSONArray("facets") ?: return Reading(emptyList(), messages)
        val entries = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        val templates = readTemplates(entries.filter { it.optString("kind") == "template" }, messages)
        val drawn = entries.filter { it.optString("kind") != "template" }
        val max = PluginViewLimits.facetCount("facetsPerReply")
        if (drawn.size > max) messages += "only the first $max facets of a reply were read"
        val facets = drawn.take(max).mapNotNull { parse(it, templates, defaultSubject, messages, depth = 0, inForm = held) }
        return Reading(facets, messages)
    }

    /** One facet; a `use` of a template is expanded first and read like any facet of the kinds it names. */
    fun parse(
        raw: JSONObject?,
        templates: Map<String, JSONObject> = emptyMap(),
        defaultSubject: FacetSubject = FacetSubject.Plugin,
        messages: MutableList<String> = mutableListOf(),
        depth: Int = 0,
        inForm: Boolean = false,
    ): Facet? {
        if (raw == null) return null
        val json = if (raw.optString("kind") == "use") expand(raw, templates, messages) ?: return null else raw
        val id = json.text("id")?.takeIf { it.length <= PluginViewLimits.facetLength("id") } ?: run {
            messages += "a facet without an id (or with one over ${PluginViewLimits.facetLength("id")} characters) was dropped"
            return null
        }
        val kind = json.optString("kind")
        val v = json.optInt("v", 1)
        val versions = Facet.KINDS[kind]
        if (versions == null || v !in versions) {
            messages += "facet \"$id\": kind \"$kind\" v$v is not one this droidtop draws, so it was skipped"
            return null
        }
        val body = Body(id, json, templates, defaultSubject, messages, depth, inForm).read(kind) ?: run {
            messages += "facet \"$id\" ($kind) was dropped: a required field is missing or not valid"
            return null
        }
        return Facet(
            id = id,
            kind = kind,
            v = v,
            subject = subjectOf(json.optJSONObject("subject")) ?: defaultSubject,
            targets = json.strings("targets"),
            priority = when (json.optString("priority")) {
                "low" -> Facet.Priority.LOW
                "high" -> Facet.Priority.HIGH
                else -> Facet.Priority.NORMAL
            },
            ttlSeconds = json.optLong("ttl", 0).coerceAtLeast(0),
            needs = json.text("needs"),
            body = body,
        )
    }

    fun subjectOf(json: JSONObject?): FacetSubject? = when (json?.optString("type")) {
        "plugin" -> FacetSubject.Plugin
        "device" -> FacetSubject.Device
        "item" -> {
            val source = json.text("source")
            val id = json.text("id")
            if (source != null && id != null) FacetSubject.Item(source, id) else null
        }
        "game" -> json.text("gameId")?.let { FacetSubject.Game(it) }
        else -> null
    }

    fun callOf(json: JSONObject?): FacetCall? {
        if (json == null) return null
        // A grant action opens droidtop's own prompt for one permission: it has no op and no args (docs/plugin-api.md 1.10).
        if (json.optString("kind") == "grant") return json.text("permission")?.let { FacetCall(it, "{}", FacetCall.Kind.GRANT) }
        val op = json.text("op") ?: return null
        val kind = when (json.optString("kind", "call")) {
            "call" -> FacetCall.Kind.CALL
            "job" -> FacetCall.Kind.JOB
            "page" -> FacetCall.Kind.PAGE
            else -> return null
        }
        val args = json.optJSONObject("args")?.toString() ?: "{}"
        // Args cannot be cut meaningfully: over the limit the action is dropped, as a view's is (§1.6).
        if (args.toByteArray(Charsets.UTF_8).size > PluginViewLimits.length("argsBytes")) return null
        return FacetCall(op, args, kind)
    }

    // --- templates: named arrangements of built-in kinds, expanded before reading (docs/plugin-api.md 1.10, "Extending")

    /** `{"kind": "template", "name", "facets": [...]}` entries, by name: only built-in kinds, so a template draws nothing new. */
    private fun readTemplates(entries: List<JSONObject>, messages: MutableList<String>): Map<String, JSONObject> {
        val max = PluginViewLimits.facetCount("templates")
        return buildMap {
            for (t in entries) {
                val name = t.text("name") ?: continue
                val facets = t.optJSONArray("facets")?.takeIf { it.length() > 0 } ?: continue
                if (size >= max) {
                    messages += "template \"$name\" was dropped: at most $max templates"
                    continue
                }
                // One facet is used as it is; several are used as a group of them.
                put(name, if (facets.length() == 1) facets.getJSONObject(0) else JSONObject().put("kind", "group").put("facets", facets))
            }
        }
    }

    /** A `use` filled in: the template's facet with every `{"$": "field"}` replaced by that field's value. */
    private fun expand(use: JSONObject, templates: Map<String, JSONObject>, messages: MutableList<String>): JSONObject? {
        val name = use.optString("template")
        val template = templates[name] ?: run {
            messages += "a facet used template \"$name\", which the reply does not define, and was dropped"
            return null
        }
        val fields = use.optJSONObject("fields") ?: JSONObject()
        fun fill(value: Any?): Any? = when (value) {
            is JSONObject -> if (value.length() == 1 && value.has("$")) fields.opt(value.optString("$")) ?: JSONObject.NULL
            else JSONObject().also { out -> value.keys().forEach { k -> out.put(k, fill(value.get(k))) } }
            is JSONArray -> JSONArray().also { out -> for (i in 0 until value.length()) out.put(fill(value.get(i))) }
            else -> value
        }
        val filled = fill(template) as JSONObject
        // The use's own envelope (its id, subject, targets, priority) wins over the template's.
        for (key in listOf("id", "subject", "targets", "priority", "ttl", "needs")) if (use.has(key)) filled.put(key, use.get(key))
        return filled
    }

    /** Reading one kind's fields, with the facet's id for the messages. */
    private class Body(
        val id: String,
        val json: JSONObject,
        val templates: Map<String, JSONObject>,
        val defaultSubject: FacetSubject,
        val messages: MutableList<String>,
        val depth: Int,
        /** Read inside a form group: an input's own `call` is not needed, and not used. */
        val inForm: Boolean = false,
    ) {
        fun length(name: String) = PluginViewLimits.facetLength(name)

        fun count(name: String) = PluginViewLimits.facetCount(name)

        fun text(key: String, limit: String, from: JSONObject = json): String? {
            val value = from.text(key) ?: return null
            val max = length(limit)
            if (value.length > max) messages += "facet \"$id\": $key was cut at $max characters"
            return value.cut(max)
        }

        fun label() = text("label", "label")

        fun call(key: String = "call") = callOf(json.optJSONObject(key))

        /** An input's call: none inside a form; outside one it is required, so null drops the input. */
        fun <T> input(make: (FacetCall?) -> T?): T? = if (inForm) make(null) else call()?.let(make)

        fun <T> capped(array: JSONArray?, limit: String, what: String, each: (JSONObject) -> T?): List<T> {
            if (array == null) return emptyList()
            val max = count(limit)
            if (array.length() > max) messages += "facet \"$id\": only the first $max $what were kept"
            return buildList { for (i in 0 until minOf(array.length(), max)) array.optJSONObject(i)?.let(each)?.let { add(it) } }
        }

        fun oneOf(key: String, allowed: Set<String>, default: String): String {
            val value = json.optString(key, default)
            if (value in allowed) return value
            messages += "facet \"$id\": $key \"$value\" is not one droidtop knows and was drawn as \"$default\""
            return default
        }

        fun url(key: String, from: JSONObject = json): String? =
            from.text(key)?.takeIf { it.length <= length("url") && NetScope.hostOf(it) != null }

        fun number(key: String, from: JSONObject = json): Double? =
            if (!from.has(key) || from.isNull(key)) null else from.optDouble(key).takeIf { it.isFinite() }

        fun read(kind: String): FacetBody? = when (kind) {
            "info" -> label()?.let { FacetBody.Info(it, ViewValue.format(json.opt("value"))?.cut(length("value")), text("sub", "sub"), json.optBoolean("filterable"), json.optBoolean("sortable")) }
            "status" -> label()?.let { FacetBody.Status(it, oneOf("state", STATES, "unknown"), oneOf("tone", TONES, "neutral"), text("value", "value")) }
            "badge" -> text("text", "badge")?.let { FacetBody.Badge(it, oneOf("tone", TONES, "neutral")) }
            "progress" -> label()?.let { FacetBody.Progress(it, json.optInt("value", -1).let { v -> if (v < 0) -1 else v.coerceAtMost(100) }, text("sub", "sub"), call("cancel")) }
            "meter" -> {
                val value = number("value")
                val max = number("max")?.takeIf { it > 0 }
                if (value == null || max == null) null
                else label()?.let { FacetBody.Meter(it, value.coerceIn(0.0, max), max, text("unit", "badge"), number("warnAt"), number("dangerAt")) }
            }
            "chart" -> label()?.let { label ->
                val style = oneOf("style", setOf("line", "bars"), "line")
                val points = json.optJSONArray("points") ?: JSONArray()
                if (style == "bars") {
                    val bars = capped(points, "chartBars", "bars") { o -> number("value", o)?.let { (o.text("label")?.cut(length("badge")) ?: "") to it } }
                    FacetBody.Chart(label, style, emptyList(), bars, text("unit", "badge"))
                } else {
                    val max = count("chartPoints")
                    if (points.length() > max) messages += "facet \"$id\": only the last $max points were kept"
                    val all = (0 until points.length()).mapNotNull { points.optDouble(it).takeIf { d -> d.isFinite() } }
                    FacetBody.Chart(label, style, all.takeLast(max), emptyList(), text("unit", "badge"))
                }
            }
            "action" -> {
                val call = call()
                if (call == null) null else label()?.let { FacetBody.Action(it, text("sub", "sub"), call, oneOf("confirm", CONFIRMS, "none"), json.text("icon")) }
            }
            "toggle" -> input { c -> label()?.let { FacetBody.Toggle(it, json.optBoolean("value"), text("sub", "sub"), c) } }
            "choice" -> input { c ->
                val options = capped(json.optJSONArray("options"), "options", "options") { o ->
                    val value = o.text("value") ?: return@capped null
                    value to (o.text("label")?.cut(length("label")) ?: value)
                }
                if (options.isEmpty()) null
                else label()?.let { FacetBody.Choice(it, options, json.text("value")?.takeIf { v -> options.any { o -> o.first == v } } ?: options.first().first, text("sub", "sub"), c) }
            }
            "slider" -> input { c ->
                val min = number("min") ?: 0.0
                val max = (number("max") ?: min).coerceAtLeast(min)
                label()?.let { FacetBody.Slider(it, min, max, number("step")?.takeIf { s -> s > 0 }, (number("value") ?: min).coerceIn(min, max), text("unit", "badge"), c) }
            }
            // Never secret: a secret stays with the vault (G1) and the web session (G3).
            "input" -> if (json.optBoolean("secret")) null else input { c ->
                label()?.let { FacetBody.Input(it, json.text("value")?.cut(length("value")).orEmpty(), text("hint", "sub"), oneOf("inputKind", INPUT_KINDS, "text"), c, json.optBoolean("optional")) }
            }
            "list" -> FacetBody.ListBody(
                text("title", "label"),
                capped(json.optJSONArray("entries"), "listEntries", "entries") { e ->
                    val entryId = e.text("id") ?: return@capped null
                    val title = text("title", "label", e) ?: return@capped null
                    FacetBody.ListEntry(entryId, title, text("sub", "sub", e), ViewValue.format(e.opt("value"))?.cut(length("value")), badges(e), url("media", e), callOf(e.optJSONObject("call")))
                },
                call("more"),
            )
            "collection" -> FacetBody.Collection(
                text("title", "label"),
                capped(json.optJSONArray("entries"), "collectionEntries", "entries") { e ->
                    val entryId = e.text("id") ?: return@capped null
                    val title = text("title", "label", e) ?: return@capped null
                    val media = url("media", e) ?: return@capped null
                    FacetBody.CollectionEntry(entryId, title, media, badges(e), callOf(e.optJSONObject("call")))
                },
                oneOf("shape", SHAPES, "capsule"),
            ).takeIf { it.entries.isNotEmpty() }
            "media" -> media(json)
            "image" -> media(json)?.let { FacetBody.Image(it) }
            "text" -> text("body", "textBody")?.let { FacetBody.Text(it, oneOf("format", setOf("plain", "basic"), "plain")) }
            "link" -> link(json)
            "notice" -> {
                val title = text("title", "label")
                // droidtop's own notice titles are droidtop's (§1.6): a plugin that reuses one gets a plain info.
                if (title == null) null
                else if (title.lowercase() in PluginViewLimits.reservedTitles) {
                    messages += "notice \"$id\" uses droidtop's own title \"$title\" and was drawn as plain information"
                    FacetBody.Info(title, null, text("sub", "sub"), filterable = false, sortable = false)
                } else {
                    val tone = json.optString("tone", "info").let { if (it in NOTICE_TONES) it else "info" }
                    FacetBody.Notice(title, text("sub", "sub"), text("details", "sub"), tone, oneOf("placement", PluginViewLimits.placements, "section"), call())
                }
            }
            // Show asks the plugin once, as a quick call: never a job, a page or a grant.
            "secret" -> call()?.takeIf { it.kind == FacetCall.Kind.CALL }?.let { c -> label()?.let { FacetBody.Secret(it, text("sub", "sub"), c) } }
            "account" -> json.text("site")?.let { FacetBody.Account(it, json.optBoolean("signedIn"), text("name", "label")) }
            "group" -> if (depth > 0) {
                messages += "group \"$id\" was dropped: a group inside a group is one level too deep"
                null
            } else {
                val facets = json.optJSONArray("facets") ?: JSONArray()
                val max = count("groupFacets")
                if (facets.length() > max) messages += "group \"$id\": only the first $max facets were kept"
                // A form needs its submit: without one there is no way to send what is entered, so it is a plain group.
                val submit = if (json.optBoolean("form")) {
                    json.optJSONObject("submit")?.let { Body(id, it, templates, defaultSubject, messages, depth).read("action") as? FacetBody.Action }
                        ?: null.also { messages += "group \"$id\": a form without a valid submit was drawn as a plain group" }
                } else null
                FacetBody.Group(
                    text("title", "label"),
                    buildList {
                        for (i in 0 until minOf(facets.length(), max)) {
                            parse(facets.optJSONObject(i), templates, defaultSubject, messages, depth + 1, inForm = submit != null)?.let { add(it) }
                        }
                    },
                    json.text("slot"),
                    submit,
                )
            }
            "metadata" -> FacetBody.Metadata(
                title = text("title", "label"),
                version = text("version", "label"),
                developer = text("developer", "label"),
                publisher = text("publisher", "label"),
                engine = json.text("engine")?.cut(length("tag")),
                status = json.text("status")?.takeIf { it in STATUSES },
                released = json.text("released")?.takeIf { Regex("""\d{4}-\d{2}-\d{2}""").matches(it) },
                updated = json.optLong("updated", 0).takeIf { it > 0 },
                rating = number("rating")?.coerceIn(0.0, 1.0),
                votes = json.optLong("votes", -1).takeIf { it >= 0 },
                tags = cappedStrings("tags", "tags", "tag"),
                genres = cappedStrings("genres", "genres", "tag"),
            )
            "description" -> {
                val overview = text("overview", "textBody")
                val changelog = text("changelog", "textBody")
                if (overview == null && changelog == null) null else FacetBody.Description(overview, changelog)
            }
            "download" -> FacetBody.Download(
                capped(json.optJSONArray("entries"), "downloadEntries", "entries") { e ->
                    val url = url("url", e)
                    val link = e.text("link")
                    val parts = capped(e.optJSONArray("parts"), "downloadParts", "parts") { p ->
                        val partUrl = url("url", p)
                        val partLink = p.text("link")?.cut(length("value"))
                        if (partUrl == null && partLink == null) return@capped null
                        FacetBody.DownloadPart(text("label", "label", p) ?: return@capped null, partUrl, partLink, p.optLong("size", -1).takeIf { it >= 0 })
                    }
                    if (url == null && link == null && parts.isEmpty()) return@capped null
                    val section = text("section", "label", e) ?: ""
                    FacetBody.DownloadEntry(
                        section = section,
                        host = text("host", "label", e) ?: url?.let { NetScope.hostOf(it) } ?: "",
                        label = text("label", "label", e) ?: return@capped null,
                        url = url,
                        link = link?.cut(length("value")),
                        size = e.optLong("size", -1).takeIf { it >= 0 },
                        version = text("version", "label", e),
                        entryKind = e.optString("kind", "page").let { if (it in DOWNLOAD_KINDS) it else "page" },
                        systems = DownloadSystems.of(DownloadSystems.read(e.optJSONArray("systems")), section),
                        parts = parts,
                        role = e.optString("role", "build").let { if (it in DOWNLOAD_ROLES) it else "build" },
                        official = e.optBoolean("official", true),
                        build = text("build", "label", e),
                    )
                },
            ).takeIf { it.entries.isNotEmpty() }
            "links" -> FacetBody.Links(capped(json.optJSONArray("entries"), "linksEntries", "links") { link(it) }).takeIf { it.entries.isNotEmpty() }
            "store" -> {
                val store = text("store", "label")
                val url = url("url")
                if (store == null || url == null) null
                else FacetBody.Store(
                    store, url,
                    price = json.text("price")?.takeIf { it.length <= length("price") },
                    currency = json.text("currency"),
                    owned = if (json.has("owned")) json.optBoolean("owned") else null,
                    free = json.optBoolean("free"),
                    onSale = json.optBoolean("onSale"),
                )
            }
            "update" -> FacetBody.Update(text("version", "label"), url("url"), json.optBoolean("gone"))
            "identity" -> {
                val ids = json.optJSONObject("ids") ?: return null
                val max = count("identityIds")
                val map = buildMap {
                    for (key in ids.keys().asSequence().take(max)) ids.text(key)?.takeIf { it.length <= length("label") }?.let { put(key, it) }
                }
                if (map.isEmpty()) null else FacetBody.Identity(map)
            }
            else -> null
        }

        fun badges(e: JSONObject): List<String> =
            e.strings("badges").take(count("listBadges")).map { it.cut(length("badge")) }

        fun cappedStrings(key: String, limit: String, each: String): List<String> {
            val all = json.strings(key)
            val max = count(limit)
            if (all.size > max) messages += "facet \"$id\": only the first $max $key were kept"
            return all.take(max).map { it.cut(length(each)) }
        }

        fun media(from: JSONObject): FacetBody.Media? {
            val url = url("url", from) ?: return null
            val role = from.optString("role").takeIf { it in MEDIA_ROLES } ?: return null
            return FacetBody.Media(
                role, url, url("poster", from),
                from.optInt("width", 0).takeIf { it > 0 }, from.optInt("height", 0).takeIf { it > 0 },
                // A picture always says what it is, for TalkBack.
                text("alt", "alt", from) ?: return null,
            )
        }

        fun link(from: JSONObject): FacetBody.Link? {
            val url = url("url", from) ?: return null
            val label = text("label", "label", from) ?: return null
            return FacetBody.Link(label, url, from.optString("kind", "other").let { if (it in LINK_KINDS) it else "other" })
        }
    }
}

private fun JSONObject.text(key: String): String? =
    if (isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() }

private fun JSONObject.strings(key: String): List<String> {
    val array = optJSONArray(key) ?: return emptyList()
    return buildList { for (i in 0 until array.length()) array.optString(i).trim().takeIf { it.isNotEmpty() && it != "null" }?.let { add(it) } }
}

private fun String.cut(max: Int): String = if (length <= max) this else take(max - 1) + "…"
