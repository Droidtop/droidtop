package dev.droidtop.shell.gamepad.pc

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import dev.droidtop.shell.gamepad.Corners
import dev.droidtop.shell.gamepad.ShellChip
import dev.droidtop.shell.gamepad.ShoulderGlyph
import dev.droidtop.shell.gamepad.Space
import dev.droidtop.shell.gamepad.input.HintBinding
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
import dev.droidtop.library.GameUpdates
import dev.droidtop.library.Library
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.HostOrder
import dev.droidtop.library.DownloadPick
import dev.droidtop.library.UpdateSources
import dev.droidtop.library.RecordAction
import dev.droidtop.library.RecordRow
import dev.droidtop.library.RECORD_PREFS
import dev.droidtop.library.SYSTEM_KEY_PREFIX
import dev.droidtop.library.checkRow
import dev.droidtop.library.controlRows
import dev.droidtop.library.downloadRows
import dev.droidtop.library.downloadSystems
import dev.droidtop.library.firstFocusable
import dev.droidtop.library.hostChoices
import dev.droidtop.library.nextFocusable
import dev.droidtop.library.pressedState
import dev.droidtop.library.ratingLine
import dev.droidtop.library.signInRow
import dev.droidtop.pluginhost.DownloadSystems
import dev.droidtop.shell.gamepad.LocalShellWindow
import dev.droidtop.shell.gamepad.MenuPanel
import dev.droidtop.shell.gamepad.MenuRow
import dev.droidtop.shell.gamepad.MenuSectionLabel
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
import dev.droidtop.shell.gamepad.ShellDialog

/*
 * A linked source's record, drawn by droidtop from what the source tells it (docs/SPEC.md 7g, "Getting an update";
 * docs/plugin-api.md 3 A6 `record`): F95Checker's four tabs, Overview, Changelog, Downloads and Info, which L1 and R1
 * move between. Picking a download asks twice and then starts the source's `download` job, which places that version
 * as a new version folder of the game. The source sends data, never a screen.
 */

internal enum class RecordTab(val label: String) { OVERVIEW("Overview"), CHANGELOG("Changelog"), DOWNLOADS("Downloads"), INFO("Info") }

/**
 * Gets [picked], one of [record]'s downloads of [source], as the new version of the game whose newest version is
 * [newest]: the one sequence the record page and the record screen ([SourceRecordScreen]) both run. [say] carries the
 * progress lines; the return is the last line. Suspends for the source's check and sign-in when its site asks.
 */
internal suspend fun getDownload(
    library: Library,
    source: UpdateSources.Source,
    record: UpdateSources.Record,
    picked: UpdateSources.Download,
    gameName: String,
    newest: LibraryEntry,
    say: (String) -> Unit,
    // False while droidtop tries mirrors in turn (getBest): a link that starts nothing goes to the next mirror, not to
    // the source's own page.
    pageFallback: Boolean = true,
): String {
    val externalId = record.externalId
    // TorBox's hosts known before any page opens, so a mirror it serves goes to it (Droidtop/tracker#638).
    primeDebrid()
    // The game's own page on the source's site, opened in droidtop's web view when droidtop's own requests are turned
    // away: the web view passes the site's browser check itself (it is the browser), and the person picks the mirror
    // there, as on the site; droidtop takes the download the page starts (Droidtop/tracker#600, #599). Cloudflare
    // refused droidtop's HTTP client on F95zone even with the web view's fresh clearance and user agent (console,
    // dev.2324), so a mirror with no address is never fetched through it while the site is checking.
    val sitePage = record.url?.takeIf { it.startsWith("https://") }
    suspend fun pickOnSitePage(): String {
        say("Pick ${picked.host} on ${source.label}'s page that opens")
        return library.openUpdateLink(source.key, externalId, sitePage!!, null, record.version, gameName, newest) { status -> say(status) }
    }
    // A release in parts: every part, then one job that joins and unpacks them (docs/plugin-api.md 1.10, `download` `parts`).
    // Its parts have no one address, so the site page is never the fallback; the site's check is passed first.
    if (picked.parts.isNotEmpty()) {
        if (library.sourceNeedsCheck(source.key)) {
            say("${source.label} checks the browser first")
            if (!library.passSourceCheck(source.key)) return "${source.label}'s check was not passed, so nothing was downloaded"
        }
        return library.downloadUpdateParts(source.key, externalId, picked.parts, record.version, gameName, newest) { status -> say(status) }
    }
    if (picked.url == null && sitePage != null && library.sourceNeedsCheck(source.key)) {
        return if (pageFallback) pickOnSitePage() else DownloadPick.NOTHING_STARTED
    }
    // A site that checks the browser first: its check, then the rest (Droidtop/tracker#600).
    var checked = false
    if (library.sourceNeedsCheck(source.key)) {
        say("${source.label} checks the browser first")
        if (!library.passSourceCheck(source.key)) return "${source.label}'s check was not passed, so nothing was downloaded"
        checked = true
    }
    // A link the source gave is opened in droidtop's web view, where the person starts the download and droidtop takes it
    // over (Droidtop/tracker#599). A mirror with no link is looked up in the record asked again first: the token of a
    // saved record goes stale when the source's page changes. Only a mirror that still has no link goes to the source's
    // job, with the fresh token.
    var use = if (picked.url != null) picked else library.freshDownload(source.key, externalId, picked, record.downloads) ?: picked
    // Still no address, and the source's links are for signed-in people: its sign-in, not "no link now".
    if (use.url == null && library.sourceSignedOut(source.key)) {
        say("Sign in to ${source.label} to get download links")
        if (!library.signInToSource(source.key)) return "Not signed in to ${source.label}, so nothing was downloaded"
        use = library.freshDownload(source.key, externalId, picked, record.downloads) ?: use
    }
    val url = use.url
    val outcome = if (url != null) {
        library.openUpdateLink(source.key, externalId, url, record.url.takeIf { pageFallback }, record.version, gameName, newest) { status -> say(status) }
    } else {
        library.downloadUpdate(source.key, externalId, use.link, record.version, gameName, newest) { status -> say(status) }
    }
    // The source's job was turned away by the site's check: the mirror is picked on the site's page instead.
    if (url == null && sitePage != null && outcome.contains("checks the browser first")) {
        return if (pageFallback) pickOnSitePage() else DownloadPick.NOTHING_STARTED
    }
    // Asked again right after a pass, with no page to open instead: said once, plainly, and the check is not opened again.
    return if (checked && outcome.contains("checks the browser first")) {
        "${source.label}'s site still asked for its check after it was passed, so nothing was downloaded"
    } else {
        outcome
    }
}

/**
 * droidtop's own pick (Droidtop/tracker#838; the owner: "Downloads still don't auto-pick links"): the mirrors
 * [DownloadPick] orders for [newest] (its build, the system droidtop runs it with, the person's host order, a link droidtop
 * serves without a page first) are tried in turn. A mirror that starts nothing gives way to the next, said on the
 * status line; anything else (a download that started, a check not passed, a sign-in declined) is the answer. Only
 * when no mirror started anything does the source's own page open, with why.
 */
internal suspend fun getBest(
    library: Library,
    source: UpdateSources.Source,
    record: UpdateSources.Record,
    gameName: String,
    newest: LibraryEntry,
    hostOrder: List<String>,
    say: (String) -> Unit,
): String {
    primeDebrid()
    val candidates = DownloadPick.candidates(
        record.downloads,
        systems = DownloadPick.systemsFor(newest),
        steamCopy = DownloadPick.isSteamCopy(newest),
        order = hostOrder,
        served = ::servedHere,
    )
    if (candidates.isEmpty()) return "${source.label} lists no build for this system, so pick a link below"
    return tryInTurn(library, source, record, candidates, gameName, newest, say)
}

/** One build's row ([RecordAction.Group], Droidtop/tracker#838): its mirrors tried in turn, in [hostOrder]. */
internal suspend fun getGroup(
    library: Library,
    source: UpdateSources.Source,
    record: UpdateSources.Record,
    group: List<UpdateSources.Download>,
    gameName: String,
    newest: LibraryEntry,
    hostOrder: List<String>,
    say: (String) -> Unit,
): String {
    primeDebrid()
    val ordered = DownloadPick.hostOrdered(group, hostOrder, served = ::servedHere)
    return tryInTurn(library, source, record, ordered, gameName, newest, say)
}

/**
 * Whether droidtop serves [d] without its page: its address is on a served host, or it names a host a connected debrid
 * account serves. A masked forum link names its host ("GOFILE") before it is opened, so the name decides then
 * (Droidtop/tracker#638: with TorBox signed in, the pick never put TorBox's hosts first).
 */
private fun servedHere(d: UpdateSources.Download): Boolean =
    (d.url ?: d.parts.firstOrNull()?.url)?.let { dev.droidtop.pluginhost.hosts.HostLinks.served(it) } == true ||
        dev.droidtop.pluginhost.debrid.DebridLinks.servesName(d.host)

/** The debrid accounts' host lists, fetched before a pick ranks by them: a request per account, so off the main thread. */
private suspend fun primeDebrid() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    runCatching { dev.droidtop.pluginhost.debrid.DebridLinks.prime() }
}

/**
 * [candidates] tried in turn: a mirror that starts nothing gives way to the next, said on the status line; anything
 * else is the answer. Only when none started anything does the source's own page open, with why.
 */
private suspend fun tryInTurn(
    library: Library,
    source: UpdateSources.Source,
    record: UpdateSources.Record,
    candidates: List<UpdateSources.Download>,
    gameName: String,
    newest: LibraryEntry,
    say: (String) -> Unit,
): String {
    for ((index, candidate) in candidates.withIndex()) {
        say(if (index == 0) "Trying ${candidate.host}" else "${candidates[index - 1].host} started nothing. Trying ${candidate.host}")
        val outcome = getDownload(library, source, record, candidate, gameName, newest, say, pageFallback = false)
        if (outcome != DownloadPick.NOTHING_STARTED) return outcome
    }
    val page = record.url?.takeIf { it.startsWith("https://") } ?: return "No mirror started a download (${candidates.size} tried)"
    say("No mirror started a download (${candidates.size} tried). Pick one on ${source.label}'s page")
    return library.openUpdateLink(source.key, record.externalId, page, null, record.version, gameName, newest) { status -> say(status) }
}

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
internal fun recordRows(
    record: UpdateSources.Record,
    tab: RecordTab,
    picked: String? = null,
    steamCopy: Boolean? = null,
    formatDate: (Long) -> String,
): List<RecordRow> = when (tab) {
    RecordTab.OVERVIEW -> listOfNotNull(
        record.version?.let { RecordRow("Version", it) },
        record.status?.let { RecordRow("Status", it) },
    ) + controlRows(record) + RecordRow("About", subtitle = record.overview ?: "The source gives no description")
    RecordTab.CHANGELOG -> listOf(RecordRow("Changelog", subtitle = record.changelog ?: "The source gives no changelog"))
    RecordTab.DOWNLOADS -> downloadRows(record, picked, steamCopy)
    RecordTab.INFO -> listOfNotNull(
        record.version?.let { RecordRow("Version", it) },
        record.developer?.let { RecordRow("Developer", it) },
        record.status?.let { RecordRow("Status", it) },
        ratingLine(record.rating, record.votes)?.let { RecordRow("Rating", it) },
        record.updatedEpochMs?.let { RecordRow("Updated", formatDate(it)) },
        record.tags.takeIf { it.isNotEmpty() }?.let { RecordRow("Tags", subtitle = it.joinToString(", ")) },
        record.url?.let { url -> RecordRow(OPEN_SOURCE_ROW, linkHost(url), action = RecordAction.Open(url)) },
        changeLinkRow(),
    )
}

/**
 * The record page of [source]'s record [externalId], linked to the game whose newest version is [newest]. [scope] is
 * the caller's and outlives the page: a download goes on after it closes, in Downloads. [say] shows a line on the
 * game page; [onRelink] opens the link sheet; [onOpen] opens a web page. [installedVersion] is
 * the version here, said under the title beside the record's ("Available: v0.38", "Installed: v0.37").
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
    installedVersion: String? = null,
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
    // A build's host list, open over the page (Droidtop/tracker#859): Try in order first, focused, then each host.
    var hostsOf by remember(source.key, externalId, tab) { mutableStateOf<RecordAction.Group?>(null) }
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    val record = answer?.record
    // The system last picked on this source's pages is picked again (Droidtop/tracker#617).
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(RECORD_PREFS, Context.MODE_PRIVATE) }
    var picked by remember(source.key, externalId) { mutableStateOf(prefs.getString(SYSTEM_KEY_PREFIX + source.key, null)) }
    // With no page to show, the one thing to do is Change link: the page never relinks by itself (Droidtop/tracker#619).
    val rows = remember(record, tab, picked, answer, signedOut, needsCheck) {
        val check = listOfNotNull(checkRow(source.label).takeIf { needsCheck })
        record?.let { r ->
            val signIn = signInRow(source.label, r, signedOut).takeIf { tab == RecordTab.DOWNLOADS && !needsCheck }
            check + listOfNotNull(signIn) + recordRows(r, tab, picked, DownloadPick.isSteamCopy(newest)) { ms -> dateFormat.format(Date(ms)) }
        }
            ?: if (answer != null) check + changeLinkRow() else emptyList()
    }
    // Focus never rests on a heading: the first row that is not one, whenever the rows change.
    androidx.compose.runtime.LaunchedEffect(rows) {
        if (rows.getOrNull(focusIndex)?.heading != false) focusIndex = firstFocusable(rows)
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
            RecordAction.PickSystem -> pickSystem(1)
            RecordAction.SignIn -> scope.launch {
                if (library.signInToSource(source.key)) asked++
            }
            RecordAction.PassCheck -> scope.launch {
                if (library.passSourceCheck(source.key)) asked++
            }
            is RecordAction.Control -> {
                val control = action.control
                // A button the source marked to confirm asks twice, as a download does.
                if (control.confirm && armed != index) {
                    armed = index
                    return
                }
                armed = null
                val current = record ?: return
                scope.launch {
                    library.runSourceControl(source.key, current.externalId, control, pressedState(control)).takeIf { it.isNotBlank() }?.let(say)
                    // Asked again, so a switch shows what the source now says.
                    asked++
                }
            }
            RecordAction.GetBest -> {
                if (armed != index) {
                    armed = index
                    return
                }
                armed = null
                val current = record ?: return
                onClose()
                say("Getting ${current.version?.let { "v" + it.removePrefix("v") } ?: "the new version"}...")
                val order = HostOrder.get(context)
                scope.launch { say(getBest(library, source, current, gameName, newest, order, say)) }
            }
            // A build's hosts to choose from, Try in order first (Droidtop/tracker#859); a second A takes it.
            is RecordAction.Group -> {
                armed = null
                hostsOf = action
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
                val current = record ?: return
                scope.launch { say(getDownload(library, source, current, picked, gameName, newest, say)) }
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

    // The host list A on a build opens: picking Try in order runs the build's mirrors in turn, a host gets its link alone.
    hostsOf?.let { group ->
        val current = record
        if (current == null) {
            hostsOf = null
        } else {
            HostList(
                title = group.downloads.first().section,
                choices = hostChoices(group.downloads),
                onPick = { choice ->
                    hostsOf = null
                    onClose()
                    val version = current.version?.let { "v" + it.removePrefix("v") } ?: "the new version"
                    if (choice.inOrder) {
                        say("Getting $version (${group.downloads.first().section})...")
                        val order = HostOrder.get(context)
                        scope.launch { say(getGroup(library, source, current, choice.downloads, gameName, newest, order, say)) }
                    } else {
                        say("Getting $version from ${choice.title}...")
                        scope.launch { say(getDownload(library, source, current, choice.downloads.single(), gameName, newest, say)) }
                    }
                },
                onDismiss = { hostsOf = null },
            )
            return
        }
    }

    BackHandler { onClose() }
    ShellDialog(onDismissRequest = onClose) {
        GatePadInThisDialog()
        MenuPanel(
            modifier = Modifier.width(LocalShellWindow.current.panelWidth(760.dp)),
            focusLabel = source.label,
            hints = RECORD_HINTS,
            onPad = { pad ->
                when (pad.action) {
                    GamepadAction.UP, GamepadAction.DOWN -> {
                        val next = nextFocusable(rows, focusIndex, if (pad.action == GamepadAction.UP) -1 else 1)
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
                    GamepadAction.B -> onClose()
                    else -> Unit
                }
                true
            },
        ) {
            // The game it is about, by its cover, beside the page's title and which version is which (Droidtop/tracker#591).
            Row(horizontalArrangement = Arrangement.spacedBy(Space.Md), verticalAlignment = Alignment.CenterVertically) {
                newest.artworkUri?.let { art ->
                    AsyncImage(
                        model = art,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(width = 72.dp, height = 96.dp).clip(Corners.Crisp),
                    )
                }
                Column {
                    Text(
                        record?.title ?: source.label,
                        style = MaterialTheme.typography.titleLarge,
                        color = MenuTokens.OnSurface,
                        fontWeight = FontWeight.SemiBold,
                    )
                    // Which version is which, in words: the one on offer stands out, the one here is secondary (Droidtop/tracker#639).
                    record?.version?.takeIf { it.isNotBlank() }?.let { available ->
                        Text(
                            // In the installed version's form, so the two compare at a glance (Droidtop/tracker#859).
                            GameUpdates.availableLabel(GameUpdates.sameForm(available, installedVersion)),
                            style = MaterialTheme.typography.titleMedium,
                            color = MenuTokens.OnSurface,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            GameUpdates.installedLabel(installedVersion),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MenuTokens.OnSurfaceMuted,
                        )
                    }
                }
            }
            // The tabs as the shell's tab pills between the shoulder glyphs, the PC game page's strip: L1/R1 step them
            // and a tap picks one. They were words in brackets on one line, which touch could not reach.
            // The shoulders sit outside the pills, which scroll when the panel is narrow, so R1 is never cut off
            // (Droidtop/tracker#859), as on the game page's strip.
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShoulderGlyph("L1", badge = true, modifier = Modifier.padding(end = Space.Sm))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Space.Sm),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f).horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                ) {
                    RecordTab.entries.forEach { entry ->
                        ShellChip(entry.label, tab = true, on = entry == tab, selected = false) {
                            if (entry != tab) switchTab(entry.ordinal - tab.ordinal)
                        }
                    }
                }
                ShoulderGlyph("R1", badge = true, modifier = Modifier.padding(start = Space.Sm))
            }
            when {
                answer == null -> MenuRow(title = "Asking ${source.label}...")
                record == null -> {
                    MenuRow(
                        title = if (answer?.unsupported == true) "${source.label} has no update page" else "${source.label} has no page for this game",
                        subtitle = answer?.failure,
                    )
                    rows.forEachIndexed { index, row ->
                        MenuRow(
                            title = row.title,
                            value = row.value,
                            switchOn = row.on,
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
                        if (row.heading) {
                            MenuSectionLabel(listOfNotNull(row.title, row.subtitle).joinToString(" \u00b7 "))
                            return@forEachIndexed
                        }
                        MenuRow(
                            title = row.title,
                            value = row.value,
                            switchOn = row.on,
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

/** Change link: it opens the link sheet. The record's thread number is the site's, not something the person reads. */
private fun changeLinkRow(): RecordRow =
    RecordRow("Change link", subtitle = "Link this game to a different page", action = RecordAction.Relink)

private val RECORD_HINTS = listOf(
    HintBinding(GamepadAction.A, "Select"),
    HintBinding(GamepadAction.L, "Switch tab"),
    HintBinding(GamepadAction.R, "Switch tab"),
    HintBinding(GamepadAction.B, "Back"),
)

/**
 * A build's hosts (Droidtop/tracker#859): Try in order first, with focus when the list opens, so A twice is the old
 * pick-for-me; then each host with its link's label, picked to download from that host alone.
 */
@Composable
private fun HostList(
    title: String,
    choices: List<dev.droidtop.library.HostChoice>,
    onPick: (dev.droidtop.library.HostChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    var focus by remember(choices) { mutableIntStateOf(0) }
    BackHandler { onDismiss() }
    ShellDialog(onDismissRequest = onDismiss) {
        GatePadInThisDialog()
        MenuPanel(
            modifier = Modifier.width(LocalShellWindow.current.panelWidth(560.dp)),
            focusLabel = title,
            hints = HOST_HINTS,
            onPad = { pad ->
                when (pad.action) {
                    GamepadAction.UP, GamepadAction.DOWN -> {
                        val next = menuStep(focus, choices.size, if (pad.action == GamepadAction.UP) -1 else 1)
                        if (next != focus) {
                            focus = next
                            EsDeNavigationSounds.play(UiSound.MOVE)
                        }
                    }
                    GamepadAction.A -> choices.getOrNull(focus)?.let(onPick)
                    GamepadAction.B -> onDismiss()
                    else -> Unit
                }
                true
            },
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = MenuTokens.OnSurface, fontWeight = FontWeight.SemiBold)
            choices.forEachIndexed { index, choice ->
                MenuRow(
                    title = choice.title,
                    subtitle = choice.subtitle,
                    selected = index == focus,
                    chevron = true,
                    onClick = {
                        focus = index
                        onPick(choice)
                    },
                )
            }
        }
    }
}

private val HOST_HINTS = listOf(
    HintBinding(GamepadAction.A, "Download"),
    HintBinding(GamepadAction.B, "Back"),
)
