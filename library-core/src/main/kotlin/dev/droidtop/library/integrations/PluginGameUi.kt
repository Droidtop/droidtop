package dev.droidtop.library.integrations

import android.content.Context
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.pluginhost.ContextActionFilter
import dev.droidtop.pluginhost.ContextTarget
import dev.droidtop.pluginhost.GameSectionProtocol
import dev.droidtop.pluginhost.GrantState
import dev.droidtop.pluginhost.PluginCrashPolicy
import dev.droidtop.pluginhost.PluginErrorCode
import dev.droidtop.pluginhost.PluginGrants
import dev.droidtop.pluginhost.PluginModes
import dev.droidtop.pluginhost.PluginRecord
import dev.droidtop.pluginhost.PluginShelf
import dev.droidtop.pluginhost.PluginShelfProtocol
import dev.droidtop.pluginhost.PluginView
import dev.droidtop.pluginhost.PluginViewCall
import dev.droidtop.pluginhost.ProvidedPoint
import dev.droidtop.pluginhost.ShelfCandidate
import dev.droidtop.pluginhost.ViewAction
import dev.droidtop.pluginhost.ViewNode
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject

/**
 * Rows a plugin adds to a game's page (`ui.game_section@1`, docs/plugin-api.md 3 C18). Which games a section is
 * for comes from its manifest's static filter, so a page never loads a plugin to learn whether to ask it; the
 * section's view is asked for once, when the page opens, never while drawing. The page draws the view's nodes as
 * its own rows ([rows]); anything that needs a form (an input, a page of its own) opens the section as a catalog
 * page through the one renderer ([screenFor], [PluginViews]).
 */
object PluginGameSections {
    const val POINT = GameSectionProtocol.POINT

    /** How long a page waits for a section before drawing the plugin's standard empty state. */
    const val BUDGET_MS = 5_000L

    data class Section(val record: PluginRecord, val entry: ProvidedPoint) {
        val id: String get() = entry.id ?: "default"
        val label: String get() = entry.label ?: record.manifest.label
        val pluginLabel: String get() = record.manifest.label

        /** `overview`, `versions`, `extras` or `details`: where on the PC game page its rows go. */
        val tab: String get() = GameSectionProtocol.tab(entry)
    }

    /** A section's view as the page received it, or why there is none (the standard empty and error states, docs/plugin-api.md 1.6). */
    data class Loaded(val section: Section, val view: PluginView?, val error: String?)

    /** The sections whose static filter matches [target]. Reads manifests only; call it off the main thread. */
    fun sectionsFor(context: Context, target: ContextTarget): List<Section> =
        // A game's page is Gaming's (docs/plugin-api.md 1.9): Standard and Desktop have no game page yet.
        providersOf(context, POINT, PluginModes.GAMING)
            .filter { (_, entry) -> ContextActionFilter.matches(entry, target) }
            .map { (record, entry) -> Section(record, entry) }

    /** The `context` every call of a section carries: which game, under the `library.read` rule, and which section. */
    private fun hostContext(context: Context, section: Section, target: ContextTarget): JSONObject =
        JSONObject().put("target", targetArg(context, section.record, target)).put("sectionId", section.id)

    /** Asks every section for its view, in parallel, each within [BUDGET_MS]. Off the main thread. */
    suspend fun load(context: Context, sections: List<Section>, target: ContextTarget): List<Loaded> = coroutineScope {
        sections.map { section ->
            async {
                val reply = PluginViews.call(
                    context,
                    section.record,
                    POINT,
                    "section",
                    PluginViewCall.args(JSONObject(), emptyMap(), hostContext(context, section, target)),
                    timeoutMs = BUDGET_MS,
                )
                when {
                    !reply.ok && reply.code == PluginErrorCode.PERMISSION_DENIED ->
                        Loaded(section, null, "${section.pluginLabel} is not allowed to show this")
                    !reply.ok -> Loaded(section, null, reply.message?.takeIf { it.isNotBlank() } ?: "${section.pluginLabel} did not answer")
                    else -> PluginView.parse(reply.data)?.let { Loaded(section, it, null) }
                        ?: Loaded(section, null, "${section.pluginLabel} sent rows droidtop cannot draw")
                }
            }
        }.awaitAll()
    }

    /** The whole section as a catalog page, where its inputs and its own pages work: what a row that needs a form opens. */
    fun screenFor(context: Context, section: Section, target: ContextTarget, op: String = "section", args: JSONObject = JSONObject(), title: String = section.label): CatalogScreen =
        PluginViews.screen(
            record = section.record,
            point = POINT,
            op = op,
            id = "game_section_${section.record.manifest.id}_${section.id}_$op",
            title = title,
            args = args,
            hostContext = hostContext(context, section, target),
        )

    /** What A on a section row does. */
    enum class RowAction {
        /** Nothing: a fact. The row shows no A hint. */
        NONE,

        /** Runs the node's `call` or `job` in place, through [PluginViews.runAction]. */
        RUN,

        /** Opens the node's `view` action as a page ([screenFor] with the action's op). */
        OPEN_VIEW,

        /** Opens the whole section as a page, where an input node can be changed. */
        OPEN_SECTION,
    }

    /** One row a section adds to a game's page, renderer-neutral: the page turns it into its own row. */
    data class Row(
        val title: String,
        val subtitle: String?,
        val value: String?,
        val action: RowAction,
        val viewAction: ViewAction? = null,
        val confirm: String? = null,
    )

    /**
     * A section as the page's rows (pure). Every node becomes one row; a section that failed is one row saying so,
     * and a section with nothing to show is one row saying that, so a plugin never leaves a blank (docs/plugin-api.md
     * 1.6, "Empty and error states").
     */
    fun rows(loaded: Loaded): List<Row> {
        val name = loaded.section.pluginLabel
        loaded.error?.let { return listOf(Row(title = "$name could not show this", subtitle = it, value = null, action = RowAction.NONE)) }
        val nodes = loaded.view?.sections?.flatMap { it.nodes }.orEmpty()
        if (nodes.isEmpty()) return listOf(Row(title = "$name: nothing here", subtitle = null, value = null, action = RowAction.NONE))
        return nodes.map { node ->
            when (node) {
                is ViewNode.Info -> Row(node.title, node.subtitle, node.value, RowAction.NONE)
                is ViewNode.Progress -> Row(node.title, node.subtitle, if (node.percent >= 0) "${node.percent}%" else null, RowAction.NONE)
                is ViewNode.Row -> actionRow(node.title, node.shownSubtitle(), node.shownValue(), node.action, null)
                is ViewNode.Button -> actionRow(node.title, node.subtitle, node.value, node.action, node.confirm)
                is ViewNode.Toggle -> Row(node.title, node.subtitle, if (node.value) "On" else "Off", RowAction.OPEN_SECTION)
                is ViewNode.Choice -> Row(node.title, node.subtitle, node.options.firstOrNull { it.first == node.value }?.second ?: node.value, RowAction.OPEN_SECTION)
                is ViewNode.Slider -> Row(node.title, node.subtitle, node.value.toString(), RowAction.OPEN_SECTION)
                is ViewNode.Text -> Row(node.title, node.subtitle, node.value.ifBlank { null }, RowAction.OPEN_SECTION)
            }
        }
    }

    private fun actionRow(title: String, subtitle: String?, value: String?, action: ViewAction?, confirm: String?): Row = when (action?.kind) {
        null -> Row(title, subtitle, value, RowAction.NONE)
        ViewAction.Kind.VIEW -> Row(title, subtitle, value, RowAction.OPEN_VIEW, action)
        ViewAction.Kind.CALL, ViewAction.Kind.JOB -> Row(title, subtitle, value, RowAction.RUN, action, confirm)
    }

    /** Runs a row's `call` or `job` and returns the sentence to show on it. Off the main thread. */
    suspend fun run(context: Context, section: Section, target: ContextTarget, action: ViewAction, label: String, onStatus: (String) -> Unit): String =
        PluginViews.runAction(context, section.record, POINT, action, emptyMap(), hostContext(context, section, target), label, onStatus).message
}

/**
 * Shelves on Home (`gaming.rows@1`, docs/plugin-api.md 3 C11): a plugin names entries of the person's own library
 * and Home draws them as a shelf after its own. droidtop asks at most once every [REFRESH_MS] per plugin and keeps
 * the answer; working the shelves out again when the library changes only re-reads that answer, so Home never waits
 * on a plugin to draw. A plugin learns the library only with `library.read`, and play times only with
 * `library.history` ([PluginShelfProtocol.libraryContext]).
 */
object PluginShelves {
    const val POINT = PluginShelfProtocol.POINT
    const val REFRESH_MS = 15 * 60 * 1000L
    const val BUDGET_MS = 5_000L

    /** One shelf, with the plugin it came from (its title names the plugin when Home draws it). */
    data class Shelf(val pluginId: String, val pluginLabel: String, val shelf: PluginShelf)

    private class Answer(val atMs: Long, val data: JSONObject?)

    private val answers = ConcurrentHashMap<String, Answer>()

    /**
     * Every running plugin's shelves over [entries] (the entries Home can show). Off the main thread; a plugin that
     * fails, times out or is not allowed has no shelf, and is not asked again until [REFRESH_MS] has passed.
     */
    suspend fun shelvesFor(
        context: Context,
        entries: List<LibraryEntry>,
        now: Long = System.currentTimeMillis(),
        surface: String = PluginModes.Surfaces.GAMING_HOME,
    ): List<Shelf> {
        // Home in Gaming, the Start menu in Desktop (docs/plugin-api.md 1.9): the same answer shape, asked per surface.
        val providers = providersOf(context, POINT, PluginModes.ofSurface(surface)).distinctBy { it.first.manifest.id }
        if (providers.isEmpty() || entries.isEmpty()) return emptyList()
        val known = entries.mapTo(HashSet()) { it.id }
        val candidates by lazy {
            entries.sortedByDescending { maxOf(it.lastPlayedEpochMs ?: 0L, it.firstSeenEpochMs) }
                .map { ShelfCandidate(it.id, it.title, it.kind.name.lowercase(), it.systemId, it.favorite, it.lastPlayedEpochMs) }
        }
        return coroutineScope {
            providers.map { (record, _) ->
                async {
                    val id = record.manifest.id
                    val key = "$id@$surface"
                    val cached = answers[key]?.takeIf { now - it.atMs < REFRESH_MS }
                    val data = cached?.data ?: if (cached != null) null else ask(context, record, candidates, surface).also { answers[key] = Answer(now, it) }
                    data?.let { PluginShelfProtocol.shelves(it, known) }.orEmpty().map { Shelf(id, record.manifest.label, it) }
                }
            }.awaitAll().flatten()
        }
    }

    private suspend fun ask(context: Context, record: PluginRecord, candidates: List<ShelfCandidate>, surface: String): JSONObject? {
        val grants = PluginGrants.forContext(context).read(record.manifest.id)
        val read = PluginGrants.stateOf(record, grants, "library.read") == GrantState.GRANTED
        val history = PluginGrants.stateOf(record, grants, "library.history") == GrantState.GRANTED
        val hostContext = JSONObject().put("surface", surface)
        PluginShelfProtocol.libraryContext(candidates, read, history)?.let { hostContext.put("library", it) }
        val policy = PluginCrashPolicy(context.applicationContext)
        return try {
            val reply = policy.handle(
                record,
                newCall(POINT, "rows", surface, JSONObject().put("context", hostContext), BUDGET_MS),
                timeoutMs = BUDGET_MS,
                crashOnTimeout = false,
                // Home asks on its own schedule, not because the person pressed something: no permission sheet.
                userInitiated = false,
            )
            if (reply.ok) reply.data else null
        } finally {
            policy.shutdown()
        }
    }
}
