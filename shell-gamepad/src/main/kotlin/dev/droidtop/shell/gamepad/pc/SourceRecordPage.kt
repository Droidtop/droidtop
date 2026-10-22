package dev.droidtop.shell.gamepad.pc

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.droidtop.library.Library
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.UpdateSources
import dev.droidtop.shell.gamepad.LocalShellWindow
import dev.droidtop.shell.gamepad.MenuPanel
import dev.droidtop.shell.gamepad.MenuRow
import dev.droidtop.shell.gamepad.MenuTokens
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.GatePadInThisDialog
import dev.droidtop.shell.gamepad.input.menuStep
import dev.droidtop.shell.gamepad.theme.EsDeNavigationSounds
import dev.droidtop.shell.gamepad.theme.UiSound
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/*
 * A linked source's record, drawn by droidtop from what the source tells it (docs/SPEC.md 7g, "Getting an update";
 * docs/plugin-api.md 3 A6 `record`): F95Checker's four tabs, Overview, Changelog, Downloads and Info, which L1 and R1
 * move between. Picking a download asks twice and then starts the source's `download` job, which places that version
 * as a new version folder of the game. The source sends data, never a screen.
 */

internal enum class RecordTab(val label: String) { OVERVIEW("Overview"), CHANGELOG("Changelog"), DOWNLOADS("Downloads"), INFO("Info") }

/** What A on a record row does. */
internal sealed interface RecordAction {
    data class Download(val download: UpdateSources.Download) : RecordAction
    data class Open(val url: String) : RecordAction
    data object Relink : RecordAction
}

internal data class RecordRow(val title: String, val value: String? = null, val subtitle: String? = null, val action: RecordAction? = null)

/** "4.3 of 5 (1,234 votes)" for a 0-to-1 rating; null when the source gives none. */
internal fun ratingLine(rating: Double?, votes: Int?): String? {
    rating ?: return null
    val stars = String.format(java.util.Locale.ROOT, "%.1f", rating * 5)
    return if (votes != null && votes > 0) "$stars of 5 (${String.format(java.util.Locale.getDefault(), "%,d", votes)} votes)" else "$stars of 5"
}

/**
 * The downloads in the order the tab shows them: the source's sections in its own order, and within one the links
 * grouped by file host (hosts in the order they first appear), each host's links in the source's order.
 */
internal fun groupedDownloads(downloads: List<UpdateSources.Download>): List<UpdateSources.Download> {
    val sections = downloads.map { it.section }.distinct()
    return sections.flatMap { section ->
        val inSection = downloads.filter { it.section == section }
        inSection.map { it.host.lowercase() }.distinct().flatMap { host -> inSection.filter { it.host.lowercase() == host } }
    }
}

/** One tab's rows. [formatDate] turns epoch milliseconds into the date the Info tab shows. Pure, for the tests. */
internal fun recordRows(record: UpdateSources.Record, tab: RecordTab, formatDate: (Long) -> String): List<RecordRow> = when (tab) {
    RecordTab.OVERVIEW -> listOfNotNull(
        record.version?.let { RecordRow("Version", it) },
        record.status?.let { RecordRow("Status", it) },
        RecordRow("About", subtitle = record.overview ?: "The source gives no description"),
    )
    RecordTab.CHANGELOG -> listOf(RecordRow("Changelog", subtitle = record.changelog ?: "The source gives no changelog"))
    RecordTab.DOWNLOADS -> if (record.downloads.isEmpty()) {
        listOf(RecordRow("No downloads listed", subtitle = "The source lists no links for this version"))
    } else {
        groupedDownloads(record.downloads).map { d ->
            RecordRow("${d.host} · ${d.label}".takeIf { d.label != d.host } ?: d.host, subtitle = d.section, action = RecordAction.Download(d))
        }
    }
    RecordTab.INFO -> listOfNotNull(
        record.version?.let { RecordRow("Version", it) },
        record.developer?.let { RecordRow("Developer", it) },
        record.status?.let { RecordRow("Status", it) },
        ratingLine(record.rating, record.votes)?.let { RecordRow("Rating", it) },
        record.updatedEpochMs?.let { RecordRow("Updated", formatDate(it)) },
        record.tags.takeIf { it.isNotEmpty() }?.let { RecordRow("Tags", subtitle = it.joinToString(", ")) },
        record.url?.let { url -> RecordRow(OPEN_SOURCE_ROW, linkHost(url), action = RecordAction.Open(url)) },
        RecordRow("Change link", "#${record.externalId}", action = RecordAction.Relink),
    )
}

/**
 * The record page of [source]'s record [externalId], linked to the game whose newest version is [newest]. [scope] is
 * the caller's and outlives the page: a download goes on after it closes, in Downloads. [say] shows a line on the
 * game page; [onRelink] opens the link sheet; [onOpen] opens a web page.
 */
@Composable
internal fun SourceRecordPage(
    library: Library,
    source: UpdateSources.Source,
    externalId: String,
    gameName: String,
    newest: LibraryEntry,
    scope: CoroutineScope,
    say: (String) -> Unit,
    onRelink: () -> Unit,
    onOpen: (String) -> Unit,
    onClose: () -> Unit,
    startTab: RecordTab = RecordTab.OVERVIEW,
) {
    // droidtop's saved copy first, so the page shows at once, then the source's answer (Droidtop/tracker#599).
    val answer by produceState<UpdateSources.RecordAnswer?>(null, source.key, externalId) {
        value = library.savedSourceRecord(source.key, externalId)
        value = library.sourceRecord(source.key, externalId)
    }
    // A source with no `record` keeps the sheet it had (docs/plugin-api.md 3 A6).
    LaunchedEffect(answer) { if (answer?.unsupported == true) onRelink() }
    var tab by remember(source.key, externalId) { mutableStateOf(startTab) }
    var focusIndex by remember(source.key, externalId, tab) { mutableIntStateOf(0) }
    var armed by remember(source.key, externalId, tab) { mutableStateOf<Int?>(null) }
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    val record = answer?.record
    val rows = remember(record, tab) { record?.let { recordRows(it, tab) { ms -> dateFormat.format(Date(ms)) } }.orEmpty() }

    fun act(index: Int) {
        val row = rows.getOrNull(index) ?: return
        when (val action = row.action) {
            null -> Unit
            is RecordAction.Open -> onOpen(action.url)
            RecordAction.Relink -> onRelink()
            is RecordAction.Download -> {
                // Two presses: the first says what the second does (the design language's two-step confirm).
                if (armed != index) {
                    armed = index
                    return
                }
                val version = record?.version
                onClose()
                say("Getting ${version?.let { "v" + it.removePrefix("v") } ?: "the new version"} from ${action.download.host}...")
                val url = action.download.url
                scope.launch {
                    // A link the source gave is opened in droidtop's web view, where the person starts the download and
                    // droidtop takes it over (Droidtop/tracker#599); a token only the source understands goes to its job.
                    say(
                        if (url != null) {
                            library.openUpdateLink(source.key, externalId, url, record?.url, version, gameName, newest) { status -> say(status) }
                        } else {
                            library.downloadUpdate(source.key, externalId, action.download.link, version, gameName, newest) { status -> say(status) }
                        },
                    )
                }
            }
        }
    }

    fun switchTab(step: Int) {
        val next = RecordTab.entries[(tab.ordinal + step + RecordTab.entries.size) % RecordTab.entries.size]
        if (next != tab) {
            tab = next
            EsDeNavigationSounds.play(UiSound.MOVE)
        }
    }

    BackHandler { onClose() }
    Dialog(onDismissRequest = onClose) {
        GatePadInThisDialog()
        MenuPanel(
            modifier = Modifier.width(LocalShellWindow.current.panelWidth(760.dp)),
            focusLabel = source.label,
            onPad = { pad ->
                when (pad.action) {
                    GamepadAction.UP, GamepadAction.DOWN -> {
                        val next = menuStep(focusIndex, rows.size, if (pad.action == GamepadAction.UP) -1 else 1)
                        if (next != focusIndex) {
                            focusIndex = next
                            armed = null
                            EsDeNavigationSounds.play(UiSound.MOVE)
                        }
                    }
                    GamepadAction.L -> switchTab(-1)
                    GamepadAction.R -> switchTab(1)
                    GamepadAction.A -> act(focusIndex)
                    GamepadAction.B -> onClose()
                    else -> Unit
                }
                true
            },
        ) {
            Text(
                record?.title ?: source.label,
                style = MaterialTheme.typography.titleLarge,
                color = MenuTokens.OnSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                RecordTab.entries.joinToString("   ") { if (it == tab) "[ ${it.label} ]" else it.label },
                style = MaterialTheme.typography.titleSmall,
                color = MenuTokens.OnSurface,
            )
            when {
                answer == null -> MenuRow(title = "Asking ${source.label}...")
                record == null -> MenuRow(title = "${source.label} has no page for #$externalId", subtitle = answer?.failure)
                else -> {
                    // A saved copy says so: its links are the ones droidtop kept, and the source may have newer ones.
                    answer?.savedAtMs?.let { saved ->
                        MenuRow(title = "Saved ${dateFormat.format(Date(saved))}", subtitle = answer?.failure ?: "Asking ${source.label} for the page as it is now...")
                    }
                    rows.forEachIndexed { index, row ->
                        MenuRow(
                            title = row.title,
                            value = row.value,
                            subtitle = if (armed == index) "Press again to download this version. It lands beside your other versions" else row.subtitle,
                            selected = index == focusIndex,
                            chevron = row.action != null,
                            onClick = {
                                focusIndex = index
                                act(index)
                            },
                        )
                    }
                }
            }
        }
    }
}
