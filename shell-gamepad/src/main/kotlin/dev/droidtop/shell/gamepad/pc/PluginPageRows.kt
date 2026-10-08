package dev.droidtop.shell.gamepad.pc

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import dev.droidtop.library.GameNaming
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.integrations.PluginContextActions
import dev.droidtop.library.integrations.PluginGameSections
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.pluginhost.ContextTarget
import dev.droidtop.shell.gamepad.CatalogNavigator
import dev.droidtop.shell.gamepad.input.GatePadInThisDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What plugins have for one game: its sections' views (or why not) and the context actions that apply. */
internal class PluginPageContent(
    val sections: List<PluginGameSections.Loaded>,
    val actions: List<PluginContextActions.Action>,
)

/** The rows plugins add to the PC game page, and the plugin page one of them opened (null when none is open). */
internal class PluginPageRows(val facts: List<PageFact>, val screen: CatalogScreen?, val closeScreen: () -> Unit)

/** The page's tab for a section's `tab` field ([dev.droidtop.pluginhost.GameSectionProtocol.TABS]). Pure. */
internal fun pageTabForSection(tab: String): PageTab = when (tab) {
    "overview" -> PageTab.OVERVIEW
    "versions" -> PageTab.VERSIONS
    "details" -> PageTab.DETAILS
    else -> PageTab.EXTRAS
}

/**
 * The PC game page's plugin rows (docs/plugin-api.md 3 C4, C18; Droidtop/tracker#316). Each node of a section's
 * view is a row of the page in droidtop's own style, under the tab the section names; each context action on this
 * game is a row under Extras. A fact takes no A; a call or job runs in place with its status in the value column
 * (a node that asks for confirmation takes a second A, like every destructive row); a node that opens a page, or
 * an input, opens the section as a catalog page over the game page. Every row's tooltip names its plugin.
 *
 * Asked for once when the page opens and again after a row ran something, never while drawing.
 */
@Composable
internal fun rememberPluginPageRows(entry: LibraryEntry): PluginPageRows {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val target = remember(entry.id) {
        ContextTarget(kind = "game", id = entry.id, title = GameNaming.displayName(entry.title), systemId = entry.systemId)
    }
    var token by remember(entry.id) { mutableIntStateOf(0) }
    var status by remember(entry.id) { mutableStateOf(emptyMap<String, String>()) }
    var armed by remember(entry.id) { mutableStateOf<String?>(null) }
    var screen by remember(entry.id) { mutableStateOf<CatalogScreen?>(null) }
    val content by produceState<PluginPageContent?>(null, entry.id, token) {
        value = withContext(Dispatchers.IO) {
            val sections = PluginGameSections.sectionsFor(context, target)
            PluginPageContent(
                sections = if (sections.isEmpty()) emptyList() else PluginGameSections.load(context, sections, target),
                actions = PluginContextActions.actionsFor(context, target, dev.droidtop.pluginhost.PluginModes.GAMING).filter { PluginContextActions.enabled(context, it, target) },
            )
        }
    }

    fun runRow(key: String, confirm: String?, work: suspend (onStatus: (String) -> Unit) -> String) {
        if (confirm != null && armed != key) {
            armed = key
            return
        }
        armed = null
        scope.launch {
            status = status + (key to "Working...")
            val message = withContext(Dispatchers.IO) { work { line -> status = status + (key to line) } }
            status = status + (key to message)
            token++
        }
    }

    fun open(build: (Context) -> CatalogScreen) {
        scope.launch { screen = withContext(Dispatchers.IO) { build(context) } }
    }

    val facts = content?.let { loaded ->
        buildList {
            loaded.sections.forEach { section ->
                val tab = pageTabForSection(section.section.tab)
                val from = "From ${section.section.pluginLabel}"
                PluginGameSections.rows(section).forEachIndexed { index, row ->
                    val key = "${section.section.record.manifest.id}/${section.section.id}/$index"
                    val action = row.viewAction
                    val onActivate: (() -> Unit)? = when (row.action) {
                        PluginGameSections.RowAction.NONE -> null
                        PluginGameSections.RowAction.RUN -> if (action == null) null else fun() {
                            runRow(key, row.confirm) { onStatus -> PluginGameSections.run(context, section.section, target, action, row.title, onStatus) }
                        }
                        PluginGameSections.RowAction.OPEN_VIEW -> if (action == null) null else fun() {
                            open { ctx -> PluginGameSections.screenFor(ctx, section.section, target, op = action.op, args = action.args(), title = action.title ?: row.title) }
                        }
                        PluginGameSections.RowAction.OPEN_SECTION -> fun() {
                            open { ctx -> PluginGameSections.screenFor(ctx, section.section, target) }
                        }
                    }
                    add(
                        PageFact(
                            title = row.title,
                            value = if (armed == key) row.confirm ?: "Press A again" else status[key] ?: row.value,
                            subtitle = row.subtitle,
                            onActivate = onActivate,
                            tip = from,
                            tab = tab,
                        ),
                    )
                }
            }
            loaded.actions.forEach { action ->
                val key = "action/${action.record.manifest.id}/${action.id}"
                add(
                    PageFact(
                        title = action.label,
                        value = status[key] ?: "Run",
                        onActivate = {
                            runRow(key, null) { _ ->
                                val outcome = PluginContextActions.run(context, action, target)
                                outcome.screen?.let { screen = it }
                                outcome.message ?: "Done"
                            }
                        },
                        tip = "From ${action.record.manifest.label}",
                        tab = PageTab.EXTRAS,
                    ),
                )
            }
        }
    }.orEmpty()
    return PluginPageRows(facts, screen) {
        screen = null
        // What the page changed (an input, a job) shows on the rows when it closes.
        token++
    }
}

/** The plugin page a row opened, over the game page, in its own window with the pad's front. */
@Composable
internal fun PluginPageScreen(rows: PluginPageRows) {
    val screen = rows.screen ?: return
    Dialog(onDismissRequest = rows.closeScreen) {
        GatePadInThisDialog()
        CatalogNavigator(root = screen, onExit = rows.closeScreen)
    }
}
