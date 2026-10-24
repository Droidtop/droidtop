package dev.droidtop.shell.gamepad.pc

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.droidtop.library.Library
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.UpdateSources
import dev.droidtop.pluginhost.DownloadSystems
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

    /** The Downloads tab's system row: A, Left and Right move to the next or the previous system. */
    data object PickSystem : RecordAction

    /** The source's sign-in, when its download links are for signed-in people and no one is (Droidtop/tracker#599). */
    data object SignIn : RecordAction

    /** The source's site checks the browser first: its check, in droidtop's web view (Droidtop/tracker#600). */
    data object PassCheck : RecordAction

    /** The game's own page, when the record was opened from somewhere else (an update row, Droidtop/tracker#639). */
    data object OpenGame : RecordAction
}

/** The row every tab starts with while [sourceLabel]'s site waits for the person to pass its browser check. */
internal fun checkRow(sourceLabel: String): RecordRow =
    RecordRow("Open $sourceLabel to pass its check", subtitle = "The site checks the browser first", action = RecordAction.PassCheck)

/**
 * The row the Downloads tab starts with while [sourceLabel]'s site wants the person signed in and the record has links
 * with no address: the source only gives those to signed-in people. Null otherwise. Pure.
 */
internal fun signInRow(sourceLabel: String, record: UpdateSources.Record, signedOut: Boolean): RecordRow? =
    RecordRow("Sign in to $sourceLabel", subtitle = "to get download links", action = RecordAction.SignIn)
        .takeIf { signedOut && record.downloads.any { it.url == null } }

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

/** The systems the record's links are for (Droidtop/tracker#617), in the order the Downloads tab's picker offers them. */
internal fun downloadSystems(record: UpdateSources.Record): List<String> = DownloadSystems.offered(record.downloads.map { it.systems })

/** One link's row: the host and its label, and under it the heading the source gave it, which says what it is. */
private fun linkRow(d: UpdateSources.Download): RecordRow =
    RecordRow("${d.host} · ${d.label}".takeIf { d.label != d.host } ?: d.host, subtitle = d.section, action = RecordAction.Download(d))

/**
 * The Downloads tab's rows: when the links differ by system, a System row first, droidtop's word on the picked system
 * if it has one (Android: not trackable), then only the picked system's links. The links for any system follow
 * whichever system is picked, under their own heading (the owner, 2026-10-23, Droidtop/tracker#617): "Extras" for a
 * walkthrough, a patch or a save file, "Other" for the rest, each with its own heading under it as its description.
 */
internal fun downloadRows(record: UpdateSources.Record, picked: String?): List<RecordRow> {
    if (record.downloads.isEmpty()) return listOf(RecordRow("No downloads listed", subtitle = "The source lists no links for this version"))
    val offered = downloadSystems(record)
    // Links that do not differ by system: the source's own list, as it gives it.
    if (offered.isEmpty()) return groupedDownloads(record.downloads).map(::linkRow)
    val system = picked?.takeIf { it in offered } ?: DownloadSystems.first(offered)
    val head = listOfNotNull(
        if (offered.size >= 2) {
            RecordRow("System", DownloadSystems.LABELS[system] ?: system, subtitle = "Left and Right show another system's links", action = RecordAction.PickSystem)
        } else {
            null
        },
        system?.let { DownloadSystems.notice(it) }?.let { RecordRow(it) },
    )
    val label = DownloadSystems.LABELS[system] ?: system
    val links = groupedDownloads(record.downloads.filter { it.systems.isNotEmpty() && system in it.systems }).map(::linkRow)
    val anySystem = record.downloads.filter { it.systems.isEmpty() }
    val (extras, other) = anySystem.partition { DownloadSystems.isExtra(it.section) || DownloadSystems.isExtra(it.label) }
    fun group(title: String, list: List<UpdateSources.Download>): List<RecordRow> =
        if (list.isEmpty()) emptyList() else listOf(RecordRow(title, subtitle = "For any system")) + groupedDownloads(list).map(::linkRow)
    return head + links.ifEmpty { listOf(RecordRow("No links for $label")) } + group("Extras", extras) + group("Other", other)
}

/** Where the record page keeps the system last picked per source. */
private const val RECORD_PREFS = "source_record_page"
private const val SYSTEM_KEY_PREFIX = "system:"

/** The system [step] away from [picked] among [offered], wrapping round. */
internal fun nextSystem(offered: List<String>, picked: String?, step: Int): String? {
    if (offered.isEmpty()) return null
    val at = offered.indexOf(picked ?: DownloadSystems.first(offered)).coerceAtLeast(0)
    return offered[(at + step + offered.size) % offered.size]
}

/**
 * One tab's rows. [formatDate] turns epoch milliseconds into the date the Info tab shows; [picked] is the Downloads
 * tab's system, null for droidtop's first choice. Pure, for the tests.
 */
internal fun recordRows(record: UpdateSources.Record, tab: RecordTab, picked: String? = null, formatDate: (Long) -> String): List<RecordRow> = when (tab) {
    RecordTab.OVERVIEW -> listOfNotNull(
        record.version?.let { RecordRow("Version", it) },
        record.status?.let { RecordRow("Status", it) },
        RecordRow("About", subtitle = record.overview ?: "The source gives no description"),
    )
    RecordTab.CHANGELOG -> listOf(RecordRow("Changelog", subtitle = record.changelog ?: "The source gives no changelog"))
    RecordTab.DOWNLOADS -> downloadRows(record, picked)
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
 * game page; [onRelink] opens the link sheet; [onOpen] opens a web page. [onOpenGame], when the page was opened from
 * outside the game (an update row), is its last row and X: the game's page, the secondary way out.
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
    onOpenGame: (() -> Unit)? = null,
) {
    // droidtop's saved copy first, so the page shows at once, then the source's answer (Droidtop/tracker#599).
    // Asked again after a sign-in, which gives the source's download links.
    var asked by remember(source.key, externalId) { mutableIntStateOf(0) }
    val answer by produceState<UpdateSources.RecordAnswer?>(null, source.key, externalId, asked) {
        if (value == null) value = library.savedSourceRecord(source.key, externalId)
        value = library.sourceRecord(source.key, externalId)
    }
    val signedOut by produceState(false, source.key, asked) { value = library.sourceSignedOut(source.key) }
    // Asked after the record: the record's own call is what meets a site's check.
    val needsCheck by produceState(false, source.key, asked, answer) { value = library.sourceNeedsCheck(source.key) }
    var tab by remember(source.key, externalId) { mutableStateOf(startTab) }
    var focusIndex by remember(source.key, externalId, tab) { mutableIntStateOf(0) }
    var armed by remember(source.key, externalId, tab) { mutableStateOf<Int?>(null) }
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    val record = answer?.record
    // The system last picked on this source's pages is picked again (Droidtop/tracker#617).
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(RECORD_PREFS, Context.MODE_PRIVATE) }
    var picked by remember(source.key, externalId) { mutableStateOf(prefs.getString(SYSTEM_KEY_PREFIX + source.key, null)) }
    // With no page to show, the one thing to do is Change link: the page never relinks by itself (Droidtop/tracker#619).
    val rows = remember(record, tab, picked, answer, signedOut, needsCheck) {
        val check = listOfNotNull(checkRow(source.label).takeIf { needsCheck })
        val game = listOfNotNull(if (onOpenGame != null) RecordRow("Open game page", gameName, action = RecordAction.OpenGame) else null)
        (
            record?.let { r ->
                val signIn = signInRow(source.label, r, signedOut).takeIf { tab == RecordTab.DOWNLOADS && !needsCheck }
                check + listOfNotNull(signIn) + recordRows(r, tab, picked) { ms -> dateFormat.format(Date(ms)) }
            }
                ?: if (answer != null) check + RecordRow("Change link", "#$externalId", action = RecordAction.Relink) else emptyList()
            ) + game
    }

    fun pickSystem(step: Int) {
        val r = record ?: return
        val next = nextSystem(downloadSystems(r), picked, step) ?: return
        if (next != picked) {
            picked = next
            prefs.edit().putString(SYSTEM_KEY_PREFIX + source.key, next).apply()
            armed = null
            EsDeNavigationSounds.play(UiSound.MOVE)
        }
    }

    fun act(index: Int) {
        val row = rows.getOrNull(index) ?: return
        when (val action = row.action) {
            null -> Unit
            is RecordAction.Open -> onOpen(action.url)
            RecordAction.Relink -> onRelink()
            RecordAction.OpenGame -> onOpenGame?.invoke()
            RecordAction.PickSystem -> pickSystem(1)
            RecordAction.SignIn -> scope.launch {
                if (library.signInToSource(source.key)) asked++
            }
            RecordAction.PassCheck -> scope.launch {
                if (library.passSourceCheck(source.key)) asked++
            }
            is RecordAction.Download -> {
                // Two presses: the first says what the second does (the design language's two-step confirm).
                if (armed != index) {
                    armed = index
                    return
                }
                val version = record?.version
                onClose()
                say("Getting ${version?.let { "v" + it.removePrefix("v") } ?: "the new version"} from ${action.download.host}...")
                val picked = action.download
                val pickedFrom = record?.downloads.orEmpty()
                scope.launch {
                    // A site that checks the browser first: its check, then the rest (Droidtop/tracker#600).
                    if (library.sourceNeedsCheck(source.key)) {
                        say("${source.label} checks the browser first")
                        if (!library.passSourceCheck(source.key)) {
                            say("${source.label}'s check was not passed, so nothing was downloaded")
                            return@launch
                        }
                    }
                    // A link the source gave is opened in droidtop's web view, where the person starts the download and
                    // droidtop takes it over (Droidtop/tracker#599). A mirror with no link is looked up in the record asked
                    // again first: the token of a saved record goes stale when the source's page changes. Only a mirror
                    // that still has no link goes to the source's job, with the fresh token.
                    var use = if (picked.url != null) picked else library.freshDownload(source.key, externalId, picked, pickedFrom) ?: picked
                    // Still no address, and the source's links are for signed-in people: its sign-in, not "no link now".
                    if (use.url == null && library.sourceSignedOut(source.key)) {
                        say("Sign in to ${source.label} to get download links")
                        if (!library.signInToSource(source.key)) {
                            say("Not signed in to ${source.label}, so nothing was downloaded")
                            return@launch
                        }
                        use = library.freshDownload(source.key, externalId, picked, pickedFrom) ?: use
                    }
                    val url = use.url
                    say(
                        if (url != null) {
                            library.openUpdateLink(source.key, externalId, url, record?.url, version, gameName, newest) { status -> say(status) }
                        } else {
                            library.downloadUpdate(source.key, externalId, use.link, version, gameName, newest) { status -> say(status) }
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
                    GamepadAction.LEFT, GamepadAction.RIGHT -> if (rows.getOrNull(focusIndex)?.action == RecordAction.PickSystem) {
                        pickSystem(if (pad.action == GamepadAction.LEFT) -1 else 1)
                    }
                    GamepadAction.L -> switchTab(-1)
                    GamepadAction.R -> switchTab(1)
                    GamepadAction.A -> act(focusIndex)
                    GamepadAction.X -> onOpenGame?.invoke()
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
                record == null -> {
                    MenuRow(
                        title = if (answer?.unsupported == true) "${source.label} has no update page" else "${source.label} has no page for #$externalId",
                        subtitle = answer?.failure,
                    )
                    rows.forEachIndexed { index, row ->
                        MenuRow(
                            title = row.title,
                            value = row.value,
                            subtitle = row.subtitle,
                            selected = index == focusIndex,
                            chevron = row.action != null,
                            onClick = {
                                focusIndex = index
                                act(index)
                            },
                        )
                    }
                }
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
