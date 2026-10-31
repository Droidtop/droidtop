package dev.droidtop.library

import dev.droidtop.pluginhost.DownloadSystems

/*
 * The rows of a source's record, as droidtop draws them wherever one is shown (docs/SPEC.md 7g, "Getting an update";
 * docs/SPEC.md 12a, "A source's item page"): the update record page and its settings form in Gaming, and a source
 * result's detail page (Droidtop/tracker#737), so a download, a release in parts and a source's own controls read the
 * same on each. Pure.
 */

/** What A on a record row does. */
sealed interface RecordAction {
    data class Download(val download: UpdateSources.Download) : RecordAction

    /** droidtop's own pick (Droidtop/tracker#838): the build, the system and the host chosen, the mirrors tried in turn. */
    data object GetBest : RecordAction

    /**
     * One build for one platform (Droidtop/tracker#838): its mirrors, in the source's order, tried in turn in the person's
     * host order. The hosts are the row's to show (a chip each, where the screen draws chips).
     */
    data class Group(val downloads: List<UpdateSources.Download>) : RecordAction
    data class Open(val url: String) : RecordAction
    data object Relink : RecordAction

    /** The Downloads tab's system row: A, Left and Right move to the next or the previous system. */
    data object PickSystem : RecordAction

    /** The source's sign-in, when its download links are for signed-in people and no one is (Droidtop/tracker#599). */
    data object SignIn : RecordAction

    /** The source's site checks the browser first: its check, in droidtop's web view (Droidtop/tracker#600). */
    data object PassCheck : RecordAction

    /** One of the source's own controls (docs/plugin-api.md 1.10, `item.page`): a switch flips, a button runs. */
    data class Control(val control: UpdateSources.Control) : RecordAction
}

/**
 * The source's own controls, as the Overview tab's rows: a switch is drawn as a switch ([RecordRow.on]) and A flips
 * it, a button runs, a link opens, and a notice says, running its fix when it has one. Pure.
 */
fun controlRows(record: UpdateSources.Record): List<RecordRow> = record.controls.map { c ->
    when (c.kind) {
        UpdateSources.Control.Kind.TOGGLE -> RecordRow(c.title, subtitle = c.subtitle, action = RecordAction.Control(c), on = c.on)
        UpdateSources.Control.Kind.ACTION -> RecordRow(c.title, subtitle = c.subtitle, action = RecordAction.Control(c))
        UpdateSources.Control.Kind.LINK -> RecordRow(c.title, c.subtitle, action = c.url?.let { RecordAction.Open(it) })
        UpdateSources.Control.Kind.NOTICE -> RecordRow(c.title, subtitle = c.subtitle, action = c.action?.let { RecordAction.Control(c) })
    }
}

/** A switch's new state when [control] is pressed; null for anything that is not a switch. */
fun pressedState(control: UpdateSources.Control): Boolean? = if (control.kind == UpdateSources.Control.Kind.TOGGLE) !control.on else null

/** The row every tab starts with while [sourceLabel]'s site waits for the person to pass its browser check. */
fun checkRow(sourceLabel: String): RecordRow =
    RecordRow("Open $sourceLabel to pass its check", subtitle = "The site checks the browser first", action = RecordAction.PassCheck)

/**
 * The row the Downloads tab starts with while [sourceLabel]'s site wants the person signed in and the record has links
 * with no address: the source only gives those to signed-in people. Null otherwise. Pure.
 */
fun signInRow(sourceLabel: String, record: UpdateSources.Record, signedOut: Boolean): RecordRow? =
    RecordRow("Sign in to $sourceLabel", subtitle = "to get download links", action = RecordAction.SignIn)
        .takeIf { signedOut && record.downloads.any { it.url == null } }

/** One row; [on] is an on/off row's state, drawn as a switch rather than said (show, then say: Droidtop/tracker#738). */
data class RecordRow(val title: String, val value: String? = null, val subtitle: String? = null, val action: RecordAction? = null, val on: Boolean? = null)

/** "4.3 of 5 (1,234 votes)" for a 0-to-1 rating; null when the source gives none. */
fun ratingLine(rating: Double?, votes: Int?): String? {
    rating ?: return null
    val stars = String.format(java.util.Locale.ROOT, "%.1f", rating * 5)
    return if (votes != null && votes > 0) "$stars of 5 (${String.format(java.util.Locale.getDefault(), "%,d", votes)} votes)" else "$stars of 5"
}

/**
 * The downloads in the order the tab shows them: the source's sections in its own order, and within one the links
 * grouped by file host (hosts in the order they first appear), each host's links in the source's order.
 */
fun groupedDownloads(downloads: List<UpdateSources.Download>): List<UpdateSources.Download> {
    val sections = downloads.map { it.section }.distinct()
    return sections.flatMap { section ->
        val inSection = downloads.filter { it.section == section }
        inSection.map { it.host.lowercase() }.distinct().flatMap { host -> inSection.filter { it.host.lowercase() == host } }
    }
}

/**
 * The System choices the record's links give (Droidtop/tracker#617, #845), by key, in the order the Downloads tab's
 * picker offers them: the thread's own download groupings ("windows+linux", "android"), [DownloadLines.options].
 */
fun downloadSystems(record: UpdateSources.Record): List<String> = DownloadLines.options(record.downloads)

/**
 * One link's row: the host and its label, and under it the heading the source gave it, which says what it is. A
 * release in parts is one row, its parts counted beside it ("4 parts, 12 GB").
 */
private fun linkRow(d: UpdateSources.Download): RecordRow =
    RecordRow("${d.host} · ${d.label}".takeIf { d.label != d.host } ?: d.host, value = partsLine(d), subtitle = d.section, action = RecordAction.Download(d))

/** "4 parts, 12 GB" for a release in parts (the size when the source gave every part's); null for a single link. Pure. */
fun partsLine(d: UpdateSources.Download): String? {
    if (d.parts.isEmpty()) return null
    val sizes = d.parts.map { it.size }
    val total = if (sizes.all { it != null && it > 0 }) dev.droidtop.library.format.ValueFormat.size(sizes.sumOf { it!! }) else null
    val count = if (d.parts.size == 1) "1 part" else "${d.parts.size} parts"
    return listOfNotNull(count, total).joinToString(", ")
}

/** The build a download's heading names ("Steam Build · Win/Linux": "Steam Build"); null for the thread's default build. */
fun buildOf(section: String): String? = section.substringBefore(" · ", "").trim().ifEmpty { null }

/**
 * A build's links as rows (Droidtop/tracker#838): one row per build and platform, its hosts listed under it, A trying
 * them in turn; a heading with one link is that link's row. Most games have one build and get just that. When a thread
 * has several ("Steam Build" beside the default one), the copy's build comes first, marked "Your copy" when droidtop
 * knows the copy ([steamCopy]; null on a page with no copy), and the others follow under "Other builds". Pure.
 */
fun buildRows(downloads: List<UpdateSources.Download>, steamCopy: Boolean?): List<RecordRow> {
    val groups = downloads.groupBy { it.section }
    fun row(section: String, mine: Boolean): RecordRow {
        val list = groups.getValue(section)
        val base = if (list.size == 1) {
            linkRow(list.single())
        } else {
            RecordRow(section, subtitle = list.map { it.host }.distinctBy { it.lowercase() }.joinToString(", "), action = RecordAction.Group(list))
        }
        return if (mine) base.copy(value = "Your copy") else base
    }
    val builds = groups.keys.map(::buildOf).distinct()
    if (builds.size < 2) return groups.keys.map { row(it, mine = false) }
    val yours = steamCopy?.let { steam -> builds.firstOrNull { (it?.contains("steam", ignoreCase = true) == true) == steam } } ?: builds.first()
    val (first, rest) = groups.keys.partition { buildOf(it) == yours }
    return first.map { row(it, mine = steamCopy != null) } +
        RecordRow("Other builds", subtitle = rest.map { buildOf(it) ?: "The default build" }.distinct().joinToString(", ")) +
        rest.map { row(it, mine = false) }
}

/**
 * The Downloads tab's rows: when the links differ by system, a System row first, droidtop's word on the picked system
 * if it has one (Android: not trackable), then only the picked system's builds ([buildRows]). The links for any system
 * follow whichever system is picked, under their own heading (the owner, 2026-10-23, Droidtop/tracker#617): "Patches"
 * and "Extras" (a walkthrough, a mod, a save file) apart from the game, "Other" for the rest, each with its own heading
 * under it as its description. [steamCopy] is whether the copy being updated is a Steam copy, null with no copy.
 */
fun downloadRows(record: UpdateSources.Record, picked: String?, steamCopy: Boolean? = null): List<RecordRow> {
    if (record.downloads.isEmpty()) return listOf(RecordRow("No downloads listed", subtitle = "The source lists no links for this version"))
    val lines = DownloadLines.of(record.downloads, picked)
    // droidtop's own pick first (Droidtop/tracker#838): one press, no link to choose; the rows below stay for picking by hand.
    val get = listOfNotNull(
        RecordRow("Get the update", subtitle = "droidtop picks the build and the host, and tries the next if one fails", action = RecordAction.GetBest)
            .takeIf { DownloadPick.offers(record.downloads) },
    )
    // Links that do not differ by system: the source's own list, as it gives it.
    val choice = lines.picked ?: return get + buildRows(lines.builds, steamCopy) + apart(lines.apart, subtitle = null)
    val label = DownloadLines.label(choice)
    val head = listOfNotNull(
        if (lines.options.size >= 2) {
            RecordRow("System", label, subtitle = "Left and Right show another system's links", action = RecordAction.PickSystem)
        } else {
            null
        },
        lines.notice?.let { RecordRow(it) },
    )
    val links = buildRows(lines.builds, steamCopy)
    return get + head + links.ifEmpty { listOf(RecordRow("No links for $label")) } + apart(lines.apart, subtitle = "For any system")
}

/** Patches, extras and the rest, each under its own heading, link by link: offered, never part of a build's row. */
private fun apart(downloads: List<UpdateSources.Download>, subtitle: String?): List<RecordRow> {
    val patches = downloads.filter { it.role == "patch" }
    val (extras, other) = (downloads - patches.toSet()).partition { it.role == "extra" || DownloadSystems.isExtra(it.section) || DownloadSystems.isExtra(it.label) }
    fun group(title: String, list: List<UpdateSources.Download>): List<RecordRow> =
        if (list.isEmpty()) emptyList() else listOf(RecordRow(title, subtitle = subtitle)) + groupedDownloads(list).map(::linkRow)
    return group("Patches", patches) + group("Extras", extras) + group("Other", other)
}

/** Where a record's Downloads keep the system last picked per source. */
const val RECORD_PREFS = "source_record_page"
const val SYSTEM_KEY_PREFIX = "system:"
