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
import dev.droidtop.library.settings.SettingsScreenRegistry
import dev.droidtop.library.settings.SliderItem
import dev.droidtop.library.settings.TextInputItem
import dev.droidtop.library.settings.ToggleItem
import dev.droidtop.pluginhost.LegacyManifest
import dev.droidtop.pluginhost.PluginCrashPolicy
import dev.droidtop.pluginhost.PluginErrorCode
import dev.droidtop.pluginhost.PluginJobsCenter
import dev.droidtop.pluginhost.PluginRecord
import dev.droidtop.pluginhost.PluginReply
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
    ): CatalogScreen = PluginPage(record, point, op, args, hostContext, emptyMap(), null, fallback, onJobDone, extraWhenFailed, extraGroups, leadGroups).screen(id, title)

    /** What running one view action came to: the sentence to show, a view the reply carried, and whether the page should be fetched again. */
    data class ActionOutcome(val message: String, val view: PluginView?, val refetch: Boolean)

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
        val callArgs = PluginViewCall.args(action.args(), HashMap(values), hostContext)
        return when (action.kind) {
            ViewAction.Kind.VIEW -> ActionOutcome("", null, refetch = false)
            ViewAction.Kind.CALL -> {
                val reply = call(context, record, point, action.op, callArgs)
                if (!reply.ok) return ActionOutcome("${record.manifest.label}: ${reply.message ?: "failed"}", null, refetch = false)
                val view = PluginViewCall.replyView(reply.data)
                ActionOutcome(PluginViewCall.replyMessage(reply.data) ?: "Done", view, refetch = view == null)
            }
            ViewAction.Kind.JOB -> {
                val startedAt = System.currentTimeMillis()
                var result = runJob(context, record, point, action.op, callArgs, action.title ?: label, onStatus)
                if (result.ok && point == "library.sources" && action.op == "acquire") {
                    val descriptors = AcquireDownloads.parse(result.values)
                    if (descriptors == null) {
                        // The plugin put the file there itself: it says which, or the library is told what is known.
                        val destination = hostContext.optString("destination").takeIf { it.isNotBlank() }?.let(::File)
                        withContext(Dispatchers.IO) { AcquireIndexing.afterAcquire(context, record.manifest.id, startedAt, destination, result.values) }
                    } else {
                        result = AcquireDownload.run(context, record, action.title ?: label, hostContext, result.values, descriptors, onStatus)
                    }
                }
                onJobDone(context, result)
                val replaced = result.values["view"]?.let { text -> runCatching { PluginView.parse(JSONObject(text)) }.getOrNull() }
                val message = if (result.ok) result.values["message"] ?: "Done" else "${record.manifest.label}: ${result.error ?: "failed"}"
                ActionOutcome(message, replaced, refetch = replaced == null)
            }
        }
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
     * Runs [op] on [point] as a contract 2 job (docs/plugin-api.md 1.6, "Jobs in
     * contract 2"): `startJob` with the point's contract 1 capability and the whole
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
) {
    @Volatile private var view: PluginView? = initial
    @Volatile private var error: String? = null

    /** The call was refused because the user did not allow this point: shown as "not allowed", with the way to change it, never as a plugin failure. */
    @Volatile private var denied: Boolean = false
    @Volatile private var stale: Boolean = initial == null
    @Volatile private var notice: String? = null
    @Volatile private var pending: Job? = null
    private val values = ConcurrentHashMap<String, String>().apply {
        putAll(seedValues)
        initial?.let { putAll(it.initialValues()) }
    }

    fun screen(id: String, title: String): CatalogScreen = CatalogScreen(
        id = id,
        title = title,
        subtitle = "From ${record.manifest.label}",
        groups = { context ->
            withContext(Dispatchers.IO) {
                load(context)
                leadGroups(context) + groups(id) + extraGroups(context, HashMap(values))
            }
        },
        // The settings search walks screens; it must never call a plugin to do so.
        indexGroups = { emptyList() },
    )

    private suspend fun load(context: Context) {
        pending?.join()
        val op = op ?: return
        if (!stale) return
        val reply = PluginViews.call(context, record, point, op, PluginViewCall.args(args, values, hostContext))
        stale = false
        denied = !reply.ok && reply.code == PluginErrorCode.PERMISSION_DENIED
        if (reply.ok) {
            val parsed = PluginView.parse(reply.data)
            if (parsed == null) {
                error = "${record.manifest.label} sent a page droidtop cannot draw"
            } else {
                show(parsed)
            }
        } else if (reply.code == PluginErrorCode.UNSUPPORTED && fallback != null) {
            show(fallback.invoke())
        } else {
            error = reply.message?.takeIf { it.isNotBlank() } ?: "${record.manifest.label} did not answer"
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
            notice?.let { add(ActionItem(id = "pv_${screenId}_notice", title = it, run = {})) }
            error?.let {
                if (denied) {
                    // docs/plugin-api.md 4.3, "A denied point": the standard error state, and the route to the Permissions screen.
                    add(ActionItem(id = "pv_${screenId}_error", title = "${record.manifest.label} is not allowed to show this", subtitle = "$it. Allow it under Permissions to see this page.", run = {}))
                    SettingsScreenRegistry.get(AcquireContentSources.PLUGINS_SCREEN_ID, record.manifest.id)?.let { permissions ->
                        add(NestedScreenItem(id = "pv_${screenId}_permissions", title = "Change what ${record.manifest.label} may do", subtitle = "Opens its Permissions", inline = permissions))
                    }
                } else {
                    add(ActionItem(id = "pv_${screenId}_error", title = "${record.manifest.label} could not show this page", subtitle = it, run = {}))
                    addAll(extraWhenFailed())
                }
            }
            view?.subtitle?.let { add(ActionItem(id = "pv_${screenId}_about", title = it, run = {})) }
            // The empty-state contract (docs/plugin-api.md 1.6, #179): never a blank page.
            if (error == null && view?.sections?.all { it.nodes.isEmpty() } != false) {
                add(ActionItem(id = "pv_${screenId}_empty", title = "${record.manifest.label}: nothing here", run = {}))
            }
        }
        val current = view
        val body = if (error != null || current == null) {
            emptyList()
        } else {
            current.sections.map { section ->
                CatalogGroup(
                    id = "pv_${screenId}_${section.id}",
                    title = section.title,
                    items = section.nodes.map { item(screenId, it) },
                )
            }
        }
        return (if (head.isEmpty()) emptyList() else listOf(CatalogGroup(id = "pv_${screenId}_head", title = null, items = head))) + body
    }

    private fun item(screenId: String, node: ViewNode): CatalogItem {
        val itemId = "pv_${screenId}_${node.id}"
        return when (node) {
            is ViewNode.Info -> ActionItem(id = itemId, title = node.title, subtitle = node.subtitle, value = node.value, run = {})
            is ViewNode.Progress -> ActionItem(
                id = itemId,
                title = node.title,
                subtitle = node.subtitle,
                value = if (node.percent >= 0) "${node.percent}%" else null,
                run = {},
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

    private fun actionItem(
        screenId: String,
        itemId: String,
        title: String,
        subtitle: String?,
        value: String?,
        confirm: String?,
        action: ViewAction?,
    ): CatalogItem = if (action == null) {
        ActionItem(id = itemId, title = title, subtitle = subtitle, value = value, run = {})
    } else when (action.kind) {
        ViewAction.Kind.VIEW -> NestedScreenItem(
            id = itemId,
            title = title,
            subtitle = subtitle,
            valueLabel = value?.let { shown -> { _: Context -> shown } },
            inline = PluginPage(record, point, action.op, action.args(), hostContext, HashMap(values), null, null, onJobDone, extraWhenFailed, extraGroups)
                .screen("${screenId}_${action.op}_${itemId.hashCode()}", action.title ?: title),
        )
        ViewAction.Kind.CALL, ViewAction.Kind.JOB -> AsyncActionItem(
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
        if (action == null) return
        val appContext = context.applicationContext
        pending = inputScope.launch { notice = perform(appContext, action, id) {} }
    }

    /** Runs a call or job action and returns the sentence to show; the page is fetched again afterwards unless the reply carried a view. */
    private suspend fun perform(context: Context, action: ViewAction, label: String, onStatus: (String) -> Unit): String {
        val outcome = PluginViews.runAction(context, record, point, action, HashMap(values), hostContext, label, onStatus, onJobDone)
        val replaced = outcome.view
        if (replaced != null) show(replaced) else if (outcome.refetch) stale = op != null
        return outcome.message
    }

    private companion object {
        val inputScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
