package dev.droidtop.library.integrations

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.CatalogScreenLink
import dev.droidtop.library.settings.TextInputItem
import dev.droidtop.library.settings.ToggleItem
import dev.droidtop.pluginhost.GrantState
import dev.droidtop.pluginhost.LinkPattern
import dev.droidtop.pluginhost.PluginGrants
import dev.droidtop.pluginhost.PluginLinks
import dev.droidtop.pluginhost.PluginRecord
import dev.droidtop.pluginhost.PluginStore
import dev.droidtop.pluginhost.PluginView
import dev.droidtop.pluginhost.ProvidedPoint
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** One of droidtop's own links: returns null when the link is not its kind. */
typealias LinkBuiltIn = suspend (context: Context, link: String) -> LinkRouter.Result?

/** How one kind of link handler opens a link ([LinkRouter.registerKind]). */
typealias LinkKind = suspend (context: Context, handler: LinkRouter.Handler, link: String) -> LinkRouter.Result

/** How a point presents a plugin's answer to `open_link`. */
typealias LinkPointHandler = suspend (context: Context, record: PluginRecord, entry: ProvidedPoint, link: String) -> LinkRouter.Result

/**
 * The one link router (docs/SPEC.md 12a "Links", docs/plugin-api.md 3 F3, Droidtop/tracker#459). droidtop owns every
 * Android entry point (`LinkActivity` in `:app`: droidtop's own action links, every https link, shared links and the
 * scheme aliases plugins switch on); every link it receives comes here, and so does a link read inside droidtop (a QR
 * code). droidtop's own links are built-in handlers ([registerBuiltIn]: [ActionLinks]). Every other link goes to the
 * registered handlers whose pattern matches it, whatever kind of handler each is ([LinkKind]):
 *
 * - **plugin**: a plugin's manifest declares the pattern on a point it may provide, and it holds `intents.in`; the link
 *   reaches it through the broker as an ordinary call (op `open_link {link}`) on that point, which decides what its reply
 *   shows ([registerPoint]: an app catalog's reply is a review of the source it would add; any other point's reply is a
 *   page in the view schema). A plugin never gets an exported component.
 * - **android**: an Android app the person chose for a pattern (Link handlers, [handlersScreen]); the link is forwarded
 *   to that app's activity as a VIEW intent.
 * - **container**, **wine**, **peer**: an app in a Linux container (its `.desktop` x-scheme-handler), a Windows app under
 *   Wine, a paired computer's agent. Their kinds are registered by those runtimes ([registerKind]) and their handlers by
 *   [registerSource]; this router only matches and dispatches.
 *
 * One match is handed over at once; several ask the person which ([CHOICE_ID]), and the choice can be remembered per
 * kind of link. An https link nothing claims goes on to the browser. A link never adds, trusts or installs anything by
 * itself: what it opens is a review the person accepts or not.
 */
object LinkRouter {
    /** The chooser shown when more than one handler takes a link. */
    const val CHOICE_ID = "links_choose"

    /** The page a plugin's reply to a link is drawn on (a view-schema page). */
    const val RESULT_ID = "links_result"

    /** Link handlers: what is registered, and sending a kind of link to an Android app. */
    const val HANDLERS_ID = "links_handlers"

    private const val PREFS = "link_router"

    /** What routing a link came to: a registered screen to open (with a line to show over it), or only a line. */
    sealed class Result {
        data class Open(val screenId: String, val message: String? = null) : Result()
        data class Message(val text: String) : Result()

        /** A web link nothing in droidtop claims: it goes on to the person's browser. */
        data class Browser(val link: String) : Result()
    }

    /**
     * One registered handler: which [kind] runs it, its [key] (unique: the kind and what it names), the [label] the
     * chooser shows, the [pattern] it takes, and [target], the kind's own data (a plugin id, an Android component).
     */
    data class Handler(val kind: String, val key: String, val label: String, val pattern: LinkPattern, val target: String)


    /** Where handlers come from: plugins' manifests, the person's choices, a runtime's integrations. Disk; off the main thread. */
    fun interface Source {
        fun handlers(context: Context): List<Handler>
    }

    private val builtIns = CopyOnWriteArrayList<LinkBuiltIn>()
    private val pointHandlers = ConcurrentHashMap<String, LinkPointHandler>()
    private val kinds = ConcurrentHashMap<String, LinkKind>()
    private val sources = CopyOnWriteArrayList<Source>()

    fun registerBuiltIn(handler: LinkBuiltIn) {
        builtIns += handler
    }

    fun registerPoint(point: String, handler: LinkPointHandler) {
        pointHandlers[point] = handler
    }

    /** A kind of handler (the container, Wine and peer runtimes add theirs); a handler of an unregistered kind is never matched. */
    fun registerKind(id: String, kind: LinkKind) {
        kinds[id] = kind
    }

    fun registerSource(source: Source) {
        sources += source
    }

    const val KIND_PLUGIN = "plugin"
    const val KIND_ANDROID = "android"

    init {
        kinds[KIND_PLUGIN] = { context, handler, link -> openInPlugin(context, handler, link) }
        kinds[KIND_ANDROID] = { context, handler, link -> openInAndroidApp(context, handler, link) }
        sources += Source { context -> pluginHandlers(context) }
        sources += Source { context -> PersonHandlers.load(PersonHandlers.file(context)) }
    }

    /** The handlers [uri] matches, one per handler key, each of a registered kind (pure over its inputs). */
    fun matching(uri: URI, handlers: List<Handler>, knownKinds: Set<String>): List<Handler> =
        handlers.filter { it.kind in knownKinds && it.pattern.matches(uri) }.distinctBy { it.key }

    /** Every handler of every source. Disk; off the main thread. */
    fun handlers(context: Context): List<Handler> = sources.flatMap { runCatching { it.handlers(context) }.getOrDefault(emptyList()) }

    /**
     * Plugin handlers: one per plugin and pattern, for runnable plugins holding `intents.in` whose declared point is
     * allowed and whose pattern droidtop lets a plugin claim ([PluginLinks.supported]). Target: "<plugin id>|<point>".
     */
    private fun pluginHandlers(context: Context): List<Handler> {
        val grants = PluginGrants.forContext(context)
        return PluginStore.installed(context).filter { it.runnable() }.flatMap { record ->
            val snapshot = grants.read(record.manifest.id)
            if (PluginGrants.stateOf(record, snapshot, PluginLinks.PERMISSION) != GrantState.GRANTED) return@flatMap emptyList()
            record.manifest.v2.provides.filter { PluginGrants.pointRefusal(record, snapshot, it.point) == null }.flatMap { entry ->
                PluginLinks.declared(entry).filter { PluginLinks.supported(it, record.manifest.id) }.map { pattern ->
                    Handler(KIND_PLUGIN, "$KIND_PLUGIN:${record.manifest.id}", record.manifest.label, pattern, "${record.manifest.id}|${entry.point}")
                }
            }
        }
    }

    /** Routes [link]. Off the main thread (it reads the plugin store and may call a plugin). */
    suspend fun route(context: Context, link: String): Result = withContext(Dispatchers.IO) {
        for (handler in builtIns) handler(context, link)?.let { return@withContext it }
        val uri = PluginLinks.parse(link) ?: return@withContext Result.Message("That isn't a link droidtop can open")
        val found = matching(uri, handlers(context), kinds.keys)
        when {
            found.isEmpty() -> if (uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) {
                Result.Browser(link)
            } else {
                Result.Message("Nothing in droidtop opens this link. A plugin that does can be added in Plugins > Add")
            }
            found.size == 1 -> handOver(context, found.single(), link)
            else -> {
                val remembered = prefs(context).getString(found.first().pattern.key, null)
                found.firstOrNull { it.key == remembered }?.let { return@withContext handOver(context, it, link) }
                pendingChoice = link to found
                Result.Open(CHOICE_ID)
            }
        }
    }

    /** Hands [link] to [handler] by its kind. */
    suspend fun handOver(context: Context, handler: Handler, link: String): Result =
        kinds[handler.kind]?.invoke(context, handler, link) ?: Result.Message("${handler.label} cannot open links in this build")

    private suspend fun openInPlugin(context: Context, handler: Handler, link: String): Result {
        val pluginId = handler.target.substringBefore('|')
        val point = handler.target.substringAfter('|')
        val record = PluginStore.installed(context).firstOrNull { it.manifest.id == pluginId && it.runnable() }
            ?: return Result.Message("${handler.label} is not installed or not running")
        val entry = record.manifest.v2.provides.firstOrNull { it.point == point } ?: return Result.Message("${handler.label} no longer opens these links")
        return pointHandlers[point]?.invoke(context, record, entry, link) ?: viewReply(context, record, entry, link)
    }

    private fun openInAndroidApp(context: Context, handler: Handler, link: String): Result {
        val component = android.content.ComponentName.unflattenFromString(handler.target) ?: return Result.Message("${handler.label} is not a valid app")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.fold(
            onSuccess = { Result.Message("Opened in ${handler.label}") },
            onFailure = { Result.Message("${handler.label} could not open the link: ${it.message ?: "it is not installed"}") },
        )
    }

    @Volatile private var lastResult: CatalogScreen? = null

    /** The default presentation: the plugin's reply is a page in the view schema, or a line. */
    private suspend fun viewReply(context: Context, record: PluginRecord, entry: ProvidedPoint, link: String): Result {
        val reply = PluginViews.call(context, record, entry.point, PluginLinks.OP_OPEN, JSONObject().put("link", link))
        if (!reply.ok) return Result.Message("${record.manifest.label}: ${reply.message ?: "it did not open the link"}")
        val view = PluginView.parse(reply.data.optJSONObject("view"))
        val message = reply.data.optString("message").takeIf { it.isNotBlank() }
        if (view == null) return Result.Message(message ?: "Opened in ${record.manifest.label}")
        lastResult = PluginViews.screenFor(record, entry.point, view, "links_result_page")
        return Result.Open(RESULT_ID, message)
    }

    @Volatile private var pendingChoice: Pair<String, List<Handler>>? = null
    @Volatile private var remember = false

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** How many link choices are remembered; the row that forgets them shows it. */
    fun rememberedCount(context: Context): Int = prefs(context).all.size

    fun forgetChoices(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /** The page a plugin's reply was drawn on; registered so the link host can open it by id. */
    fun resultScreen(): CatalogScreen = CatalogScreen(
        id = RESULT_ID,
        title = "Link",
        groups = { context ->
            lastResult?.groups?.invoke(context)
                ?: listOf(CatalogGroup("links_result_none", null, listOf(ActionItem("links_result_none_row", "Nothing to show", run = {}))))
        },
        indexGroups = { emptyList() },
    )

    /** "Open this link with": one row per handler, and whether to remember the choice for this kind of link. */
    fun choiceScreen(): CatalogScreen = CatalogScreen(
        id = CHOICE_ID,
        title = "Open this link with",
        groups = { _ ->
            val pending = pendingChoice
            if (pending == null) {
                listOf(CatalogGroup("links_choose_none", null, listOf(ActionItem("links_choose_none_row", "No link is waiting", run = {}))))
            } else {
                val (link, found) = pending
                val items = buildList<CatalogItem> {
                    add(ActionItem("links_choose_link", link.take(120), subtitle = "More than one handler opens this kind of link", run = {}))
                    add(
                        ToggleItem(
                            id = "links_choose_remember",
                            title = "Remember my choice for ${found.first().pattern.label}",
                            subtitle = "Link handlers, Forget choices asks again",
                            current = remember,
                            onToggle = { _, value -> remember = value },
                        ),
                    )
                    found.forEach { handler ->
                        add(
                            AsyncActionItem(
                                id = "links_choose_${handler.key}",
                                title = "Open with ${handler.label}",
                                subtitle = kindLabel(handler.kind),
                                run = { ctx, _ ->
                                    if (remember) prefs(ctx).edit().putString(handler.pattern.key, handler.key).apply()
                                    pendingChoice = null
                                    when (val result = handOver(ctx, handler, link)) {
                                        is Result.Message -> result.text
                                        is Result.Browser -> "Opened in the browser"
                                        is Result.Open -> {
                                            ctx.startActivity(CatalogScreenLink.intent(ctx, result.screenId))
                                            result.message ?: "Opened in ${handler.label}"
                                        }
                                    }
                                },
                            ),
                        )
                    }
                }
                listOf(CatalogGroup("links_choose", null, items))
            }
        },
        indexGroups = { emptyList() },
    )

    private fun kindLabel(kind: String): String = when (kind) {
        KIND_PLUGIN -> "A droidtop plugin"
        KIND_ANDROID -> "An Android app"
        "container" -> "An app in a Linux container"
        "wine" -> "A Windows app"
        "peer" -> "On your paired computer"
        else -> kind
    }

    // ------------------------------------------------------------------
    // Link handlers: what is registered, and the person's own choices.
    // ------------------------------------------------------------------

    @Volatile private var draftPattern = ""

    /** Every registered handler, and "send links like this to an Android app". */
    fun handlersScreen(): CatalogScreen = CatalogScreen(
        id = HANDLERS_ID,
        title = "Link handlers",
        subtitle = "Which plugin or app opens which kind of link. droidtop receives the link and hands it on",
        groups = { context -> withContext(Dispatchers.IO) { handlersGroups(context) } },
    )

    private fun handlersGroups(context: Context): List<CatalogGroup> {
        val all = handlers(context)
        val file = PersonHandlers.file(context)
        val listed = CatalogGroup(
            "links_handlers_list",
            "Registered",
            buildList<CatalogItem> {
                if (all.isEmpty()) add(ActionItem("links_handlers_none", "None yet", subtitle = "Plugins register the links they open; you can add an app below", run = {}))
                all.forEachIndexed { i, handler ->
                    val mine = handler.kind == KIND_ANDROID
                    add(
                        ActionItem(
                            id = "links_handlers_$i",
                            title = handler.pattern.label.replaceFirstChar { it.uppercase() },
                            subtitle = handler.label + " - " + kindLabel(handler.kind) + if (mine) ". Press to remove" else "",
                            confirmTitle = if (mine) "Stop sending ${handler.pattern.label} to ${handler.label}?" else null,
                            run = { ctx -> if (mine) PersonHandlers.remove(PersonHandlers.file(ctx), handler) },
                        ),
                    )
                }
                if (rememberedCount(context) > 0) {
                    add(ActionItem("links_handlers_forget", "Forget choices", subtitle = "Ask again when several handlers open a link", value = rememberedCount(context).toString(), run = { ctx -> forgetChoices(ctx) }))
                }
            },
        )
        val pattern = PersonHandlers.patternFor(draftPattern)
        val apps = pattern?.let { PersonHandlers.appsFor(context, it) }.orEmpty()
        val add = CatalogGroup(
            "links_handlers_add",
            "Send a kind of link to an app",
            buildList<CatalogItem> {
                add(
                    TextInputItem(
                        id = "links_handlers_pattern",
                        title = "Links like",
                        subtitle = "A scheme (magnet:) or a site (https://example.org)",
                        value = draftPattern,
                        onChange = { _, value -> draftPattern = value.trim() },
                    ),
                )
                if (draftPattern.isNotBlank() && pattern == null) add(ActionItem("links_handlers_bad", "That is not a scheme or a site", run = {}))
                if (pattern != null && apps.isEmpty()) add(ActionItem("links_handlers_noapp", "No installed app opens these links", run = {}))
                if (pattern != null) apps.forEach { (component, label) ->
                    add(
                        ActionItem(
                            id = "links_handlers_app_$component",
                            title = "Use $label",
                            subtitle = "Sends ${pattern.label} to this app",
                            run = { ctx ->
                                PersonHandlers.add(file, Handler(KIND_ANDROID, "$KIND_ANDROID:$component", label, pattern, component))
                                draftPattern = ""
                            },
                        ),
                    )
                }
            },
        )
        return listOf(listed, add)
    }

    /** The handlers the person registered (Android apps for a pattern), in one small JSON file. */
    internal object PersonHandlers {
        fun file(context: Context) = File(context.filesDir, "link_handlers.json")

        private val lock = Any()

        fun load(file: File): List<Handler> = synchronized(lock) {
            val array = runCatching { JSONArray(file.readText()) }.getOrNull() ?: return emptyList()
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val pattern = o.optJSONObject("pattern")?.let(LinkPattern::fromJson) ?: return@mapNotNull null
                Handler(o.optString("kind"), o.optString("key"), o.optString("label"), pattern, o.optString("target"))
            }
        }

        private fun save(file: File, handlers: List<Handler>) {
            val array = JSONArray(handlers.map { h ->
                JSONObject().put("kind", h.kind).put("key", h.key).put("label", h.label).put("target", h.target).put(
                    "pattern",
                    JSONObject().put("scheme", h.pattern.scheme).apply { h.pattern.host?.let { put("host", it) } },
                )
            })
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(array.toString())
            tmp.renameTo(file)
        }

        fun add(file: File, handler: Handler) = synchronized(lock) {
            save(file, load(file).filterNot { it.key == handler.key && it.pattern == handler.pattern } + handler)
        }

        fun remove(file: File, handler: Handler) = synchronized(lock) {
            save(file, load(file).filterNot { it.key == handler.key && it.pattern == handler.pattern })
        }

        /** "magnet:", "magnet", "https://example.org" -> a pattern; null for anything else (never all of the web). Pure. */
        fun patternFor(text: String): LinkPattern? {
            val t = text.trim().lowercase()
            if (t.isEmpty()) return null
            if (t.startsWith("https://") || t.startsWith("http://")) {
                val host = runCatching { URI(t).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
                return LinkPattern(t.substringBefore("://"), host)
            }
            val scheme = t.removeSuffix("://").removeSuffix(":")
            return LinkPattern(scheme).takeIf { scheme.matches(Regex("[a-z][a-z0-9+.-]{0,31}")) && scheme != PluginLinks.OWN_SCHEME }
        }

        /** The installed apps (other than droidtop) that open a link of [pattern], as component and label. */
        fun appsFor(context: Context, pattern: LinkPattern): List<Pair<String, String>> {
            val sample = pattern.scheme + if (pattern.host != null) "://${pattern.host}/" else ":x"
            val pm = context.packageManager
            return runCatching {
                pm.queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse(sample)).addCategory(Intent.CATEGORY_BROWSABLE), 0)
            }.getOrDefault(emptyList())
                .filter { it.activityInfo.packageName != context.packageName }
                .map { info ->
                    android.content.ComponentName(info.activityInfo.packageName, info.activityInfo.name).flattenToString() to
                        info.loadLabel(pm).toString()
                }.distinctBy { it.first }
        }
    }
}
