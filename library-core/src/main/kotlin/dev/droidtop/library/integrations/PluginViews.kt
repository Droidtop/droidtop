package dev.droidtop.library.integrations

import android.content.Context
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.ChoiceItem
import dev.droidtop.library.settings.ChoiceOption
import dev.droidtop.library.settings.NestedScreenItem
import dev.droidtop.library.settings.Notice
import dev.droidtop.library.settings.NoticeAction
import dev.droidtop.library.settings.NoticeItem
import dev.droidtop.library.settings.NoticePlacement
import dev.droidtop.library.settings.NoticeTone
import dev.droidtop.library.settings.SettingsScreenRegistry
import dev.droidtop.library.settings.SliderItem
import dev.droidtop.library.settings.TextInputItem
import dev.droidtop.library.settings.ToggleItem
import dev.droidtop.pluginhost.PluginWebAccount
import dev.droidtop.pluginhost.PluginPermissions
import dev.droidtop.pluginhost.PluginGrants
import dev.droidtop.pluginhost.GrantState
import dev.droidtop.pluginhost.LegacyManifest
import dev.droidtop.pluginhost.PluginCrashPolicy
import dev.droidtop.pluginhost.PluginErrorCode
import dev.droidtop.pluginhost.PluginRefusals
import dev.droidtop.pluginhost.PluginJobsCenter
import dev.droidtop.pluginhost.PluginMessages
import dev.droidtop.pluginhost.PluginRecord
import dev.droidtop.pluginhost.PluginReply
import dev.droidtop.pluginhost.SourceFailures
import dev.droidtop.pluginhost.PluginResult
import dev.droidtop.pluginhost.PluginRunner
import dev.droidtop.pluginhost.PluginView
import dev.droidtop.pluginhost.PluginViewCall
import dev.droidtop.pluginhost.AcquireDownloads
import dev.droidtop.pluginhost.ViewAction
import dev.droidtop.pluginhost.ViewNode
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The one host renderer for plugin UI (docs/plugin-api.md 1.6): a plugin's view
 * document ([PluginView]) becomes a settings-catalog [CatalogScreen], which every
 * mode already draws -- Gaming's `CatalogNavigator` (pad and touch through the one
 * `onPad` pipeline), Standard's Preference surface and Desktop's Settings app. So a
 * plugin page is themed, navigable and touch-equal with no renderer of its own, and
 * the plugin never draws anything.
 *
 * A page belongs to one extension point. Its actions go back to that point through
 * `handle` with the page's input values and a host-filled `context`; a `view` action
 * opens the reply as a page on top, a `call` runs a quick op and a `job` runs a
 * tracked job ([PluginJobsCenter]), after which the page is fetched again (or
 * replaced by a view the reply carried). Nothing here runs on the main thread, and
 * the settings search index never opens a plugin page (it would call the plugin).
 */
object PluginViews {
    /** Where droidtop draws plugin pages, sent as the call's `surface.place`. */
    const val SURFACE = "plugin.view"

    /**
     * A page whose view comes from [op] on [point]. [hostContext] is the `context`
     * every call of this page carries (filled by droidtop only). [fallback] is the
     * view droidtop draws when the plugin answers UNSUPPORTED (an optional op).
     * [onJobDone] runs after any job this page started finishes, for the page's own business; the library hears of a source's files from the job
     * ([AcquireIndexing], the download job), never from a page that may be gone by then.
     * [extraWhenFailed] adds rows under a load failure, for a way out the host knows.
     * [extraGroups] appends host data built from committed page values (source results).
     * [leadGroups] puts host rows above the plugin's own (a Quick Menu panel's tiles).
     * [itemPage] reads a reply that is an item's page (a source result's `detail`, [SourceItemPage]): its groups lead the
     * page and the rest of its facets are the view; a reply it does not take is read as any view.
     */
    fun screen(
        record: PluginRecord,
        point: String,
        op: String,
        id: String,
        title: String = record.manifest.label,
        args: JSONObject = JSONObject(),
        hostContext: JSONObject = JSONObject(),
        fallback: (() -> PluginView)? = null,
        onJobDone: suspend (Context, PluginResult) -> Unit = { _, _ -> },
        extraWhenFailed: () -> List<CatalogItem> = { emptyList() },
        extraGroups: suspend (Context, Map<String, String>) -> List<CatalogGroup> = { _, _ -> emptyList() },
        leadGroups: suspend (Context) -> List<CatalogGroup> = { emptyList() },
        // A plugin's panel ([CatalogScreen.panel]) with its icon; false for any other plugin page.
        panel: Boolean = false,
        iconFile: String? = null,
        // Told of each input value the person commits, for a page whose values droidtop keeps (a source's search options).
        onCommit: ((Context, ViewNode, String) -> Unit)? = null,
        itemPage: ((Context, JSONObject, MutableList<String>) -> SourceItemPage.Page?)? = null,
    ): CatalogScreen = PluginPage(record, point, op, args, hostContext, emptyMap(), null, fallback, onJobDone, extraWhenFailed, extraGroups, leadGroups, onCommit, itemPage).screen(id, title, panel, iconFile)

    /**
     * What running one view action came to: the sentence to show, a view the reply carried, whether the page should be
     * fetched again, and the notice to draw instead of the sentence (the reply's `data.notice`, or an error's notice).
     */
    data class ActionOutcome(val message: String, val view: PluginView?, val refetch: Boolean, val notice: ViewNode.Notice? = null)

    /** The permissions [record] declares: an error may only point at one of these (docs/plugin-api.md 1.3). */
    internal fun declared(record: PluginRecord): Set<String> = record.manifest.v2.permissions.map { it.id }.toSet()

    /** The fallback title of a failed action's notice. */
    internal const val FAILED_TITLE = "Couldn't finish"

    /** The title of droidtop's notice for a job that finished and sent no notice of its own (Droidtop/tracker#521 item 9). */
    internal const val FINISHED_TITLE = "Finished"

    /** droidtop's notice for a finished job: [FINISHED_TITLE], and the plugin's `message` as its body unless it only says "Done". */
    internal fun finishedNotice(message: String?): ViewNode.Notice {
        val said = message?.trim()?.takeIf { it.isNotEmpty() && !it.equals("Done", ignoreCase = true) }
        return ViewNode.Notice("job_finished", FINISHED_TITLE, said?.let { cutBody(it) }, null, "info", banner = false, action = null)
    }

    private fun cutBody(text: String): String {
        val max = dev.droidtop.pluginhost.PluginViewLimits.length("noticeSubtitle")
        return if (text.length <= max) text else text.take(max - 1).trimEnd() + "\u2026"
    }

    /** The title of droidtop's notice on a plugin page with nothing in it (Droidtop/tracker#521 item 6). */
    internal const val EMPTY_TITLE = "Nothing here yet"

    /**
     * Runs a `call` or `job` action of a view (docs/plugin-api.md 1.6, "Actions") on [point], with [values] and the
     * host-filled [hostContext] written after the action's own args so they cannot be spoofed. The one path every
     * surface that draws plugin rows uses: a catalog page ([PluginPage]) and the rows a plugin adds to a game's page.
     * A `view` action is not run here: the surface opens it as a page ([screen] with the action's op).
     */
    suspend fun runAction(
        context: Context,
        record: PluginRecord,
        point: String,
        action: ViewAction,
        values: Map<String, String>,
        hostContext: JSONObject,
        label: String,
        onStatus: (String) -> Unit = {},
        onJobDone: suspend (Context, PluginResult) -> Unit = { _, _ -> },
    ): ActionOutcome {
        // A form is sent only once what it needs is filled in (docs/plugin-api.md 1.10, `group`); nothing reaches the plugin before.
        val missing = PluginViewCall.missing(action.args(), values)
        if (missing.isNotEmpty()) {
            val sentence = "Fill in ${missing.joinToString(", ")} first"
            return ActionOutcome(sentence, null, refetch = false, notice = ViewNode.Notice("form", "Not sent yet", sentence, null, "caution", false, null))
        }
        val callArgs = PluginViewCall.args(action.args(), HashMap(values), hostContext)
        val messages = mutableListOf<String>()
        val outcome = when (action.kind) {
            ViewAction.Kind.VIEW -> ActionOutcome("", null, refetch = false)
            // The value goes straight to droidtop's sheet: never into the outcome, a notice or the message log.
            ViewAction.Kind.SECRET -> {
                val reply = call(context, record, point, action.op, callArgs)
                val value = reply.data.optString("value").take(dev.droidtop.pluginhost.PluginViewLimits.facetLength("value"))
                when {
                    !reply.ok -> ActionOutcome(
                        "${record.manifest.label}: ${SourceFailures.forCode(reply.code, reply.message)}", null, refetch = false,
                        notice = PluginViewCall.errorNotice(reply, declared(record), FAILED_TITLE, messages),
                    )
                    value.isBlank() -> ActionOutcome("${record.manifest.label} gave nothing to show", null, refetch = false)
                    !dev.droidtop.pluginhost.PluginSecrets.show(record.manifest.label, label, value) -> ActionOutcome("It cannot be shown right now", null, refetch = false)
                    else -> ActionOutcome("", null, refetch = false)
                }
            }
            // droidtop's own prompt, in place; the page is asked again whatever the answer, so its facets show the new state.
            ViewAction.Kind.GRANT -> {
                val allowed = withContext(Dispatchers.IO) { dev.droidtop.pluginhost.PluginGrants.askPermission(context, record, action.op) }
                val label = dev.droidtop.pluginhost.PluginPermissions.labelFor(action.op) ?: action.op
                ActionOutcome(if (allowed) "Allowed: $label" else "Not allowed: $label", null, refetch = true)
            }
            ViewAction.Kind.CALL -> {
                val reply = call(context, record, point, action.op, callArgs)
                if (!reply.ok) {
                    ActionOutcome(
                        "${record.manifest.label}: ${SourceFailures.forCode(reply.code, reply.message)}", null, refetch = false,
                        notice = PluginViewCall.errorNotice(reply, declared(record), FAILED_TITLE, messages),
                    )
                } else {
                    val view = PluginViewCall.replyView(reply.data, messages)
                    ActionOutcome(
                        PluginViewCall.replyMessage(reply.data) ?: "Done", view, refetch = view == null,
                        notice = PluginViewCall.replyNotice(reply.data, messages),
                    )
                }
            }
            ViewAction.Kind.JOB -> {
                val result = if (point == "library.sources" && action.op == "acquire") {
                    acquire(context, record, action.title ?: label, hostContext, callArgs, onStatus)
                } else {
                    runJob(context, record, point, action.op, callArgs, action.title ?: label, onStatus)
                }
                onJobDone(context, result)
                val replaced = result.values["view"]?.let { text -> runCatching { PluginView.parse(JSONObject(text), messages) }.getOrNull() }
                // A download's own outcome ("Added <file>") says more than the plugin's message from before it ran (#558).
                val said = result.values["summary"] ?: result.values["message"]
                val message = if (result.ok) said ?: "Done" else "${record.manifest.label}: ${SourceFailures.plain(result.error)}"
                val notice = if (result.ok) {
                    // The plugin's own notice when it sent one; otherwise droidtop's (#521 item 9), so a finished job always
                    // says so on the page that started it, with the plugin's sentence when it gave one.
                    result.values["notice"]?.let { text -> runCatching { PluginViewCall.replyNotice(JSONObject().put("notice", JSONObject(text)), messages) }.getOrNull() }
                        ?: finishedNotice(said)
                } else {
                    PluginViewCall.errorNotice(PluginReply.error(PluginErrorCode.FAILED, result.error ?: "failed"), emptySet(), FAILED_TITLE, messages)
                }
                ActionOutcome(message, replaced, refetch = replaced == null, notice = notice)
            }
        }
        withContext(Dispatchers.IO) { PluginMessages.record(context, record.manifest.id, "$point/${action.op}", messages) }
        return outcome
    }

    /** A page drawn from a view the plugin already returned (a context action's or quick tile's reply). It has no op to fetch again; its actions may replace it. */
    fun screenFor(
        record: PluginRecord,
        point: String,
        view: PluginView,
        id: String,
        hostContext: JSONObject = JSONObject(),
    ): CatalogScreen = PluginPage(record, point, null, JSONObject(), hostContext, emptyMap(), view, null, { _, _ -> }, { emptyList() })
        .screen(id, view.title ?: record.manifest.label)

    /** One `handle` call on [point], off the main thread, never throwing. */
    suspend fun call(
        context: Context,
        record: PluginRecord,
        point: String,
        op: String,
        args: JSONObject,
        timeoutMs: Long = PluginRunner.CALL_TIMEOUT_MS,
    ): PluginReply = withContext(Dispatchers.IO) {
        val policy = PluginCrashPolicy(context.applicationContext)
        try {
            policy.handle(record, newCall(point, op, SURFACE, args, timeoutMs), timeoutMs = timeoutMs)
        } catch (e: Exception) {
            PluginReply.error(PluginErrorCode.FAILED, e.message ?: "the call failed")
        } finally {
            policy.shutdown()
        }
    }

    /**
     * A source's `acquire` as a job, then what it fetched to the library (docs/plugin-api.md 1.6, `acquire`): the
     * files the job placed itself are indexed, and a `download` or `downloads` reply is fetched and placed by
     * droidtop. The one path for the detail page's job action and a search row's Get ("Fetchers answer in the one
     * search", Droidtop/tracker#218). [callArgs] already carries `ref`, `values` and the host's `context`.
     */
    suspend fun acquire(
        context: Context,
        record: PluginRecord,
        title: String,
        hostContext: JSONObject,
        callArgs: JSONObject,
        onStatus: (String) -> Unit,
    ): PluginResult {
        val startedAt = System.currentTimeMillis()
        val result = runJob(context, record, "library.sources", "acquire", callArgs, title, onStatus)
        if (!result.ok) return result
        val descriptors = AcquireDownloads.parse(result.values)
        if (descriptors != null) return AcquireDownload.run(context, record, title, hostContext, result.values, descriptors, onStatus)
        // The plugin put the file there itself: it says which, or the library is told what is known.
        val destination = hostContext.optString("destination").takeIf { it.isNotBlank() }?.let(::File)
        withContext(Dispatchers.IO) { AcquireIndexing.afterAcquire(context, record.manifest.id, startedAt, destination, result.values) }
        return result
    }

    /**
     * Runs [op] on [point] as a contract 2 job (docs/plugin-api.md 1.6, "Jobs in
     * contract 2"): `startJob` with the capability the point is served as and the whole
     * envelope as `call`, tracked in [PluginJobsCenter] like every other job, and
     * awaited here so a row can show its progress through [onStatus].
     */
    suspend fun runJob(
        context: Context,
        record: PluginRecord,
        point: String,
        op: String,
        args: JSONObject,
        title: String,
        onStatus: (String) -> Unit,
    ): PluginResult {
        val capability = LegacyManifest.jobCapabilityFor(point)
        val call = newCall(point, op, SURFACE, args, 0L)
        val done = CompletableDeferred<PluginResult>()
        PluginJobsCenter.start(
            context = context,
            record = record,
            capability = capability,
            args = mapOf("call" to call.toJson().toString()),
            title = title,
            onProgress = { percent, statusLine -> onStatus(if (percent >= 0) "$statusLine ($percent%)" else statusLine) },
            onComplete = { if (!done.isCompleted) done.complete(it) },
            // A page's button: the person started it.
            userInitiated = true,
        ) ?: return PluginResult.failure("${record.manifest.label} could not start this task")
        return done.await()
    }

    /** A status facet's state as the host's status glyph; null for a quiet state or a plain fact. Pure. */
    internal fun markOf(state: String?): dev.droidtop.library.settings.StatusMark? = when (state) {
        "busy" -> dev.droidtop.library.settings.StatusMark.WORKING
        "warning" -> dev.droidtop.library.settings.StatusMark.ATTENTION
        "error" -> dev.droidtop.library.settings.StatusMark.ERROR
        else -> null
    }
}

/** One plugin page's state: its view, its input values and a pending input action. Lives as long as the screen object. */
private class PluginPage(
    private val record: PluginRecord,
    private val point: String,
    private val op: String?,
    private val args: JSONObject,
    private val hostContext: JSONObject,
    seedValues: Map<String, String>,
    initial: PluginView?,
    private val fallback: (() -> PluginView)?,
    private val onJobDone: suspend (Context, PluginResult) -> Unit,
    private val extraWhenFailed: () -> List<CatalogItem>,
    private val extraGroups: suspend (Context, Map<String, String>) -> List<CatalogGroup> = { _, _ -> emptyList() },
    private val leadGroups: suspend (Context) -> List<CatalogGroup> = { emptyList() },
    private val onCommit: ((Context, ViewNode, String) -> Unit)? = null,
    private val itemPage: ((Context, JSONObject, MutableList<String>) -> SourceItemPage.Page?)? = null,
) {
    @Volatile private var view: PluginView? = initial

    /** The item page's own groups ([itemPage]), drawn before the plugin's view; empty for any other reply. */
    @Volatile private var itemGroups: List<CatalogGroup> = emptyList()
    @Volatile private var error: String? = null

    /** The call was refused because the user did not allow this point: shown as "not allowed", with the way to change it, never as a plugin failure. */
    @Volatile private var denied: Boolean = false

    /** The last load failed on one declared permission droidtop refused (#518): asked again when drawn, as [denied] is. */
    @Volatile private var refusedOne: Boolean = false
    @Volatile private var stale: Boolean = initial == null
    @Volatile private var notice: String? = null

    /** The permissions this page's nodes name with `needs` that the person turned off (#521 item 5), read each time the page is drawn. */
    @Volatile private var turnedOff: Set<String> = emptySet()

    /** A reply's notice (or an action's error notice): shown until the page's next action, never cleared by a refetch. */
    @Volatile private var replyNotice: ViewNode.Notice? = null

    /** The reply a load failed with, for its error notice. */
    @Volatile private var errorReply: PluginReply? = null
    @Volatile private var pending: Job? = null
    private val values = ConcurrentHashMap<String, String>().apply {
        putAll(seedValues)
        initial?.let { putAll(it.initialValues()) }
    }

    fun screen(id: String, title: String, panel: Boolean = false, iconFile: String? = null): CatalogScreen = CatalogScreen(
        id = id,
        title = title,
        subtitle = "From ${record.manifest.label}",
        groups = { context ->
            withContext(Dispatchers.IO) {
                load(context)
                turnedOff = turnedOff(context)
                leadGroups(context) + groups(id) + extraGroups(context, HashMap(values))
            }
        },
        // The settings search walks screens; it must never call a plugin to do so.
        indexGroups = { emptyList() },
        panel = panel,
        iconFile = iconFile,
    )

    private suspend fun load(context: Context) {
        pending?.join()
        val op = op ?: return
        // A denied page asks again each time its rows are read: the person may have allowed the point on the Permissions
        // screen it links to and come back with B, and a refused call never reaches the plugin (docs/plugin-api.md 4.3).
        if (!stale && !denied && !refusedOne) return
        val started = System.currentTimeMillis()
        val answered = PluginViews.call(context, record, point, op, PluginViewCall.args(args, values, hostContext))
        // A plugin that passed on droidtop's refusal of one of its declared permissions without naming it (#518): the
        // page is about that one permission, never "not allowed to show this", which is for a refused point.
        val refused = if (!answered.ok && answered.code == PluginErrorCode.PERMISSION_DENIED && answered.permission == null) {
            PluginRefusals.refusedSince(record.manifest.id, started)?.takeIf { it in PluginViews.declared(record) }
        } else {
            null
        }
        val reply = refused?.let { permission ->
            answered.aboutPermission(
                permission,
                title = "${record.manifest.label} needs a permission for this",
                subtitle = answered.message?.takeIf { it.isNotBlank() },
            )
        } ?: answered
        stale = false
        // Asked again each time it is drawn, like a denied page: the person may allow it on the Permissions page and come back.
        refusedOne = refused != null
        // An error naming a permission the plugin declared is about that grant, not the whole point.
        denied = !reply.ok && reply.code == PluginErrorCode.PERMISSION_DENIED && reply.permission == null
        errorReply = if (reply.ok) null else reply
        itemGroups = emptyList()
        if (reply.ok) {
            val messages = mutableListOf<String>()
            // An item's page goes through the item page's guards, a target's facets through that target's
            // (docs/plugin-api.md 1.10); any other reply is a view.
            val item = itemPage?.invoke(context, reply.data, messages)
            item?.let { itemGroups = it.groups }
            val parsed = item?.rest
                ?: (if (point == PluginFacets.POINT && op == PluginFacets.OP_GET) PluginFacets.view(record, args, reply.data, messages, context) else null)
                ?: PluginView.parse(reply.data, messages)
            PluginMessages.record(context, record.manifest.id, "$point/$op", messages)
            if (parsed == null) {
                error = "${record.manifest.label} sent a page droidtop cannot draw"
            } else {
                show(parsed)
            }
        } else if (reply.code == PluginErrorCode.UNSUPPORTED && fallback != null) {
            show(fallback.invoke())
        } else {
            error = "${record.manifest.label}: ${SourceFailures.forCode(reply.code, reply.message)}"
        }
    }

    private fun show(newView: PluginView) {
        view = newView
        error = null
        values.clear()
        values.putAll(newView.initialValues())
    }

    private fun groups(screenId: String): List<CatalogGroup> {
        val head = buildList<CatalogItem> {
            // A reply's notice is drawn instead of its sentence (docs/plugin-api.md 1.6, `data.notice`).
            val shownNotice = replyNotice
            if (shownNotice != null) {
                add(noticeItem(screenId, "pv_${screenId}_reply_notice", shownNotice))
            } else {
                notice?.takeIf { it.isNotBlank() }?.let { add(ActionItem(id = "pv_${screenId}_notice", title = it)) }
            }
            error?.let {
                if (denied) {
                    // docs/plugin-api.md 4.3, "A denied point": a caution notice whose action is the Permissions screen.
                    add(
                        NoticeItem(
                            id = "pv_${screenId}_error",
                            notice = Notice(
                                tone = NoticeTone.Caution,
                                title = "Not allowed to show this",
                                body = "It needs your permission for this page.",
                                details = it,
                                action = NoticeAction("Permissions") {},
                                source = record.manifest.label,
                            ),
                            opens = SettingsScreenRegistry.get(AcquireContentSources.PLUGINS_SCREEN_ID, AcquireContentSources.permissionsLink(record.manifest.id)),
                        ),
                    )
                } else {
                    val failed = errorReply?.let { reply -> PluginViewCall.errorNotice(reply, PluginViews.declared(record), "Couldn't show this page") }
                        ?: ViewNode.Notice("error", "Couldn't show this page", null, it, "caution", banner = false, action = null)
                    add(noticeItem(screenId, "pv_${screenId}_error", failed))
                    addAll(extraWhenFailed())
                }
            }
            view?.subtitle?.let { add(ActionItem(id = "pv_${screenId}_about", title = it)) }
            // The empty-state contract (docs/plugin-api.md 1.6, #179; #521 item 6): never a blank page, and droidtop's own
            // notice, not a plugin's row: "Nothing here yet" from the plugin, its one sentence if it gave one, and Refresh.
            if (error == null && itemGroups.isEmpty() && view?.sections?.all { it.nodes.isEmpty() } != false) {
                add(
                    NoticeItem(
                        id = "pv_${screenId}_empty",
                        notice = Notice(
                            tone = NoticeTone.Info,
                            title = PluginViews.EMPTY_TITLE,
                            body = view?.empty,
                            action = if (op != null) NoticeAction("Refresh") { stale = true } else null,
                            source = record.manifest.label,
                        ),
                    ),
                )
            }
        }
        val current = view
        val body = if (error != null || current == null) {
            emptyList()
        } else {
            itemGroups + current.sections.filter { it.nodes.isNotEmpty() || itemGroups.isEmpty() }.map { section ->
                CatalogGroup(
                    id = "pv_${screenId}_${section.id}",
                    title = section.title,
                    items = section.nodes.map { node ->
                        section.needs[node.id]?.takeIf { it in turnedOff }?.let { permission -> turnedOffItem(screenId, node, permission) }
                            ?: item(screenId, node)
                    },
                )
            }
        }
        return (if (head.isEmpty()) emptyList() else listOf(CatalogGroup(id = "pv_${screenId}_head", title = null, items = head))) + body
    }

    /**
     * Which permissions named by this page's `needs` are turned off: declared by the plugin and DENIED. One that is still
     * unanswered (ASK) is asked when the node is used, as any call is; one the plugin did not declare is ignored.
     */
    private fun turnedOff(context: Context): Set<String> {
        val named = view?.sections?.flatMap { it.needs.values }?.toSet().orEmpty()
        if (named.isEmpty()) return emptySet()
        val snapshot = PluginGrants.forContext(context).read(record.manifest.id)
        val off = named.filterTo(HashSet()) { PluginGrants.stateOf(record, snapshot, it) == GrantState.DENIED }
        // `needs: "app"` (#520): the app the plugin manages is not installed.
        missingApp = if (PluginManagedApp.NEEDS in named) PluginManagedApp.missing(context, record) else null
        if (missingApp != null) off += PluginManagedApp.NEEDS
        return off
    }

    /** The managed app, while it is missing and a node of this page needs it (#520). */
    @Volatile private var missingApp: PluginManagedApp.App? = null

    /**
     * A node whose permission is turned off (#521 item 5): its own title, droidtop's sentence instead of what it does,
     * and pressing it opens the plugin's Permissions. Only this node changes; the page and its other nodes stay.
     */
    private fun turnedOffItem(screenId: String, node: ViewNode, permission: String): CatalogItem {
        missingApp?.takeIf { permission == PluginManagedApp.NEEDS }?.let { app ->
            return ActionItem(id = "pv_${screenId}_${node.id}_no_app", title = node.title, subtitle = PluginManagedApp.reason(app))
        }
        val what = PluginPermissions.labelFor(permission) ?: permission
        return NestedScreenItem(
            id = "pv_${screenId}_${node.id}_off",
            title = node.title,
            subtitle = "Off: turn on \"$what\" in its Permissions to use this",
            inline = SettingsScreenRegistry.get(AcquireContentSources.PLUGINS_SCREEN_ID, AcquireContentSources.permissionsLink(record.manifest.id)),
        )
    }

    private fun item(screenId: String, node: ViewNode): CatalogItem {
        val itemId = "pv_${screenId}_${node.id}"
        return when (node) {
            is ViewNode.Notice -> noticeItem(screenId, itemId, node)
            // A status facet's state is the host's glyph (Droidtop/tracker#644): busy turns a ring, warning asks for the
            // person, error is the danger octagon; ok, on and off are quiet.
            is ViewNode.Info -> ActionItem(id = itemId, title = node.title, subtitle = node.subtitle, value = node.value, statusMark = PluginViews.markOf(node.state))
            // A plugin's progress is the host's job bar (Droidtop/tracker#643, #644), not a percent in text: the bar glides,
            // and one with no percent is the checking shimmer.
            is ViewNode.Progress -> ActionItem(
                id = itemId,
                title = node.title,
                subtitle = node.subtitle,
                progress = if (node.percent >= 0) node.percent.coerceAtMost(100) / 100f else -1f,
            )
            is ViewNode.Row -> actionItem(screenId, itemId, node.title, node.shownSubtitle(), node.shownValue(), null, node.action)
            is ViewNode.Button -> actionItem(screenId, itemId, node.title, node.subtitle, node.value, node.confirm, node.action)
            is ViewNode.Toggle -> ToggleItem(
                id = itemId,
                title = node.title,
                subtitle = node.subtitle,
                current = values[node.id] == "true",
                onToggle = { context, on -> commit(context, node.id, on.toString(), node.action) },
            )
            is ViewNode.Choice -> ChoiceItem(
                id = itemId,
                title = node.title,
                subtitle = node.subtitle,
                options = node.options.map { (value, label) -> ChoiceOption(value, label) },
                current = values[node.id] ?: node.value,
                onSelect = { context, value -> commit(context, node.id, value, node.action) },
            )
            is ViewNode.Slider -> SliderItem(
                id = itemId,
                title = node.title,
                subtitle = node.subtitle,
                min = node.min,
                max = node.max,
                current = values[node.id]?.toIntOrNull() ?: node.value,
                onChange = { context, value -> commit(context, node.id, value.toString(), node.action) },
            )
            is ViewNode.Text -> TextInputItem(
                id = itemId,
                title = node.title,
                subtitle = node.subtitle,
                value = values[node.id] ?: node.value,
                onChange = { context, text ->
                    values[node.id] = text
                    node.action?.let { notice = perform(context, it, node.title) {} }
                },
            )
        }
    }

    /**
     * A plugin's notice as droidtop's own notice card (docs/SPEC.md 7k, "Notices"), named after the plugin. A `view`
     * action opens its page in place; a `call` or `job` runs and the page is read again; an error that named a
     * declared permission opens Permissions.
     */
    private fun noticeItem(screenId: String, itemId: String, node: ViewNode.Notice): CatalogItem {
        accountNotice(itemId, node)?.let { return it }
        val action = node.action
        val opens: CatalogScreen? = when {
            node.opensPermissions -> SettingsScreenRegistry.get(AcquireContentSources.PLUGINS_SCREEN_ID, AcquireContentSources.permissionsLink(record.manifest.id))
            action?.kind == ViewAction.Kind.VIEW ->
                PluginPage(record, point, action.op, action.args(), hostContext, HashMap(values), null, null, onJobDone, extraWhenFailed, extraGroups)
                    .screen("${screenId}_${action.op}_${itemId.hashCode()}", action.title ?: node.title)
            else -> null
        }
        val label = when {
            node.opensPermissions -> "Permissions"
            action != null -> action.title ?: "Open"
            else -> null
        }
        return NoticeItem(
            id = itemId,
            notice = Notice(
                tone = when (node.tone) {
                    "caution" -> NoticeTone.Caution
                    "danger" -> NoticeTone.Danger
                    else -> NoticeTone.Info
                },
                title = node.title,
                body = node.subtitle,
                details = node.details,
                action = label?.let { NoticeAction(it) { context -> if (opens == null && action != null) notice = perform(context, action, node.title) {} } },
                source = record.manifest.label,
            ),
            placement = if (node.banner) NoticePlacement.Page else NoticePlacement.Group,
            opens = opens,
        )
    }

    /**
     * A SIGN_IN_REQUIRED or BROWSER_CHECK_REQUIRED notice as droidtop's own action (docs/plugin-api.md 1.3, Droidtop/tracker#544):
     * Sign in or Open the site in the plugin's session, then the page is read again. Null when the plugin declares no
     * `web.session` site, and its own action (if any) is offered as before.
     */
    private fun accountNotice(itemId: String, node: ViewNode.Notice): CatalogItem? {
        val need = node.opensAccount ?: return null
        if (PluginWebAccount.checkUrl(record) == null) return null
        val label = if (need == PluginWebAccount.Need.SIGN_IN) "Sign in" else "Open the site"
        return NoticeItem(
            id = itemId,
            notice = Notice(
                tone = NoticeTone.Caution,
                title = node.title,
                body = node.subtitle,
                details = node.details,
                action = NoticeAction(label) { context ->
                    val kept = withContext(Dispatchers.IO) { PluginWebAccount.open(context, record, need) }
                    if (kept && need == PluginWebAccount.Need.SIGN_IN) notifyWebSignedIn(context, record)
                    if (kept) stale = op != null else notice = PluginWebAccount.notKept(record.manifest.label, need)
                },
                source = record.manifest.label,
            ),
            placement = if (node.banner) NoticePlacement.Page else NoticePlacement.Group,
        )
    }

    private fun actionItem(
        screenId: String,
        itemId: String,
        title: String,
        subtitle: String?,
        value: String?,
        confirm: String?,
        action: ViewAction?,
    ): CatalogItem = if (action == null) {
        ActionItem(id = itemId, title = title, subtitle = subtitle, value = value)
    } else when (action.kind) {
        ViewAction.Kind.VIEW -> NestedScreenItem(
            id = itemId,
            title = title,
            subtitle = subtitle,
            valueLabel = value?.let { shown -> { _: Context -> shown } },
            inline = PluginPage(record, point, action.op, action.args(), hostContext, HashMap(values), null, null, onJobDone, extraWhenFailed, extraGroups)
                .screen("${screenId}_${action.op}_${itemId.hashCode()}", action.title ?: title),
        )
        ViewAction.Kind.CALL, ViewAction.Kind.JOB, ViewAction.Kind.GRANT, ViewAction.Kind.SECRET -> AsyncActionItem(
            id = itemId,
            title = title,
            subtitle = subtitle,
            value = value,
            confirmTitle = confirm,
            run = { context, onStatus -> perform(context, action, title, onStatus) },
        )
    }

    /** An input's new value; its action, if any, runs in the background and the next [load] waits for it. */
    private fun commit(context: Context, id: String, value: String, action: ViewAction?) {
        values[id] = value
        onCommit?.let { tell -> view?.sections?.flatMap { it.nodes }?.firstOrNull { it.id == id }?.let { node -> tell(context.applicationContext, node, value) } }
        if (action == null) return
        val appContext = context.applicationContext
        pending = inputScope.launch { notice = perform(appContext, action, id) {} }
    }

    /** Runs a call or job action and returns the sentence to show; the page is fetched again afterwards unless the reply carried a view. */
    private suspend fun perform(context: Context, action: ViewAction, label: String, onStatus: (String) -> Unit): String {
        // A reply notice lasts until the next action (docs/plugin-api.md 1.6, `data.notice`).
        replyNotice = null
        val outcome = PluginViews.runAction(context, record, point, action, HashMap(values), hostContext, label, onStatus, onJobDone)
        val replaced = outcome.view
        if (replaced != null) show(replaced) else if (outcome.refetch) stale = op != null
        replyNotice = outcome.notice
        // With a notice to draw, the sentence is not repeated beside it.
        return if (outcome.notice != null) "" else outcome.message
    }

    private companion object {
        val inputScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
