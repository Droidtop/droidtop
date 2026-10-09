package dev.droidtop.shell.gamepad.pc

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.droidtop.library.GameLinks
import dev.droidtop.library.GameUpdates
import dev.droidtop.library.Library
import dev.droidtop.library.SourceKey
import dev.droidtop.library.SourceLink
import dev.droidtop.library.UpdateSources
import dev.droidtop.shell.gamepad.TextEditDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/*
 * A game's links to its update sources (docs/SPEC.md 7g, "Where an update
 * comes from"; docs/plugin-api.md 3 A6): one row per `library.updates`
 * source on a folder game's page and in its options menu, the sheet that
 * links one (the source's own matches for the game's name, a person's
 * pasted link, or unlink, each confirmed with a second press), and the one
 * short line a check answers with. The source is a plugin; everything here
 * is droidtop's own UI.
 */

/**
 * The page's row for a linked source's own page (Droidtop/tracker#397 slice
 * E): it opens the link in the browser and fetches nothing. The page shows
 * only the link's host; the whole link is the row's tip.
 */
internal const val OPEN_SOURCE_ROW = "Open source page"

/** "f95zone.to" of a link, for a row's value; null when [url] is not a web link. */
internal fun linkHost(url: String?): String? =
    url?.let { runCatching { java.net.URI(it.trim()).host }.getOrNull() }?.removePrefix("www.")?.takeIf { it.isNotBlank() }

/** What the paste field says when a source gives no hint of its own. */
internal const val SOURCE_LINK_HELP = "A link or id; blank unlinks"

/**
 * The game's update sources as rows under Versions: one per source (A
 * links or changes it) and, for each linked one whose page is known,
 * "Open source page" with the link's host, which opens it in the browser and
 * fetches nothing ([onOpen]). Pure, for the tests.
 */
internal fun sourceRows(
    sources: List<UpdateSources.Source>,
    links: GameLinks?,
    onEdit: (UpdateSources.Source) -> Unit,
    onOpen: (String) -> Unit,
): List<PageFact> = sources.flatMap { source ->
    val link = links?.link(source.key)
    listOfNotNull(
        PageFact(
            source.label,
            value = link?.let { "#${it.externalId}" } ?: "Link",
            subtitle = if (link?.answer?.gone == true) "Gone: private, moved or deleted" else null,
            onActivate = { onEdit(source) },
            tip = link?.answer?.url,
            tab = PageTab.VERSIONS,
        ),
        link?.answer?.url?.let { url ->
            linkHost(url)?.let { host ->
                PageFact(
                    OPEN_SOURCE_ROW,
                    value = host,
                    subtitle = source.label.takeIf { sources.size > 1 },
                    onActivate = { onOpen(url) },
                    tip = url,
                    tab = PageTab.VERSIONS,
                )
            }
        },
    )
}

/**
 * What a source's row says in the options menu: whether the game is
 * linked, and what the source last answered, in the one wording for an
 * update ([GameUpdates.line]).
 */
internal fun sourceLine(link: SourceLink?, available: String?, versions: List<String>): String {
    link ?: return "Not linked. Link it to be told when a new version is out"
    val answer = link.answer
    val id = "#${link.externalId}"
    val newest = answer?.version
    return when {
        answer == null -> "$id - not checked yet"
        answer.gone -> "$id is gone: private, moved or deleted"
        available != null -> "${GameUpdates.line(available)} - $id"
        newest == null -> "$id gives no version"
        versions.none { it.isNotEmpty() } -> "The newest is $newest; this game's folders name no version to compare"
        else -> "Up to date: $newest is the newest - $id"
    }
}

/**
 * What a check found, in one short line: why it failed, "Up to date", or
 * "v1.2 is available" ([GameUpdates.line]). Pure, for the tests.
 */
internal fun checkOutcomeLine(failure: String?, link: SourceLink?, versions: List<String>, fallbackLatest: String?): String {
    if (failure != null) return failure
    val answer = link?.answer
    if (answer?.gone == true) return "Gone: private, moved or deleted"
    GameUpdates.available(answer?.version ?: fallbackLatest, versions)?.let { return GameUpdates.line(it) }
    val newest = answer?.version ?: return "The source gives no version"
    return if (versions.none { it.isNotEmpty() }) "Newest is $newest" else "Up to date"
}

/** "Check now" for [key], then the one short line for what it found ([checkOutcomeLine]). */
internal suspend fun checkSourceAndSay(
    library: Library,
    gameIds: Collection<String>,
    key: SourceKey,
    versions: List<String>,
    fallbackLatest: String?,
): String {
    val failure = library.checkSourceNow(key)
    return checkOutcomeLine(failure, library.gameLinks(gameIds)?.link(key.source), versions, fallbackLatest)
}

/**
 * A person's pasted text for [source] (a link, an id, or blank to unlink)
 * applied to the game whose folders are [gameIds], with each step said
 * through [say]. The source reads the text ([Library.resolveSourceText]):
 * droidtop does not know what any one site's links look like.
 */
internal suspend fun linkSourceFromText(
    library: Library,
    gameIds: Collection<String>,
    source: UpdateSources.Source,
    text: String,
    say: (String?) -> Unit,
) {
    if (text.isBlank()) {
        say("Unlinking...")
        library.linkSource(gameIds, source.key, null)
        say("Unlinked. ${source.label} no longer checks this game for updates.")
        return
    }
    say("Looking it up...")
    val found = library.resolveSourceText(source.key, text.trim())
    if (found == null) {
        say("${source.label} does not know that link" + (source.hint?.let { ": $it" } ?: ""))
        return
    }
    linkFound(library, gameIds, source, found, say)
}

private suspend fun linkFound(
    library: Library,
    gameIds: Collection<String>,
    source: UpdateSources.Source,
    found: UpdateSources.Found,
    say: (String?) -> Unit,
) {
    val name = found.title ?: "#${found.externalId}"
    say("Linking $name...")
    val failure = library.linkSource(gameIds, source.key, found.externalId)
    say(if (failure != null) "Linked $name, but checking it failed: $failure" else "Linked $name.")
}

/**
 * The sheet that links the game to [source]: the source's own matches for
 * [gameTitle] first (best first), then "Type a link or id", then "Unlink"
 * when it is linked. Every row asks twice ([SameGamePicker]): linking
 * changes what the library tells the person about the game.
 */
@Composable
internal fun SourceLinkSheet(
    library: Library,
    gameIds: Collection<String>,
    gameTitle: String,
    versions: List<String>,
    source: UpdateSources.Source,
    current: SourceLink?,
    /** The caller's scope, which outlives the sheet: a typed link is applied after the sheet closes. */
    scope: CoroutineScope,
    say: (String?) -> Unit,
    onClose: () -> Unit,
) {
    var typing by remember(source.key) { mutableStateOf(false) }
    if (typing) {
        TextEditDialog(
            title = source.label,
            subtitle = source.hint ?: SOURCE_LINK_HELP,
            initial = current?.answer?.url ?: current?.externalId.orEmpty(),
            onCommit = { text ->
                onClose()
                scope.launch { linkSourceFromText(library, gameIds, source, text, say) }
            },
            onDismiss = { typing = false },
        )
        return
    }
    val found by produceState<List<UpdateSources.Found>?>(null, source.key, gameTitle) {
        value = library.sourceMatches(source.key, gameTitle, versions)
    }
    val matches = found.orEmpty()
    val typeIndex = matches.size
    val choices = buildList {
        matches.forEach { match ->
            add(
                SameGameChoice(
                    match.title ?: "#${match.externalId}",
                    listOfNotNull(match.version, match.note, "#${match.externalId}").joinToString(" · "),
                ),
            )
        }
        add(SameGameChoice("Type a link or id", source.hint ?: SOURCE_LINK_HELP))
        if (current != null) add(SameGameChoice("Unlink", "#${current.externalId}"))
    }
    SameGamePicker(
        focusLabel = source.label,
        question = when {
            found == null -> "Looking for $gameTitle on ${source.label}..."
            matches.isEmpty() -> "${source.label} found nothing named like $gameTitle"
            else -> "Which one is $gameTitle?"
        },
        choices = choices,
        confirmLine = { choice ->
            when (val index = choices.indexOf(choice)) {
                typeIndex -> "Press again to type it"
                typeIndex + 1 -> "Press again: ${source.label} stops checking this game"
                else -> "Press again: link this game to ${matches[index].title ?: "#" + matches[index].externalId}"
            }
        },
        workingLine = "Linking...",
        onPick = { index ->
            var said: String? = null
            when (index) {
                typeIndex -> typing = true
                typeIndex + 1 -> linkSourceFromText(library, gameIds, source, "") { said = it }
                else -> linkFound(library, gameIds, source, matches[index]) { said = it }
            }
            said.orEmpty()
        },
        onDone = { message ->
            if (!typing) {
                say(message.ifEmpty { null })
                onClose()
            }
        },
        onDismiss = onClose,
    )
}
