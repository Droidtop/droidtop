package dev.droidtop.library

import java.io.IOException
import kotlinx.coroutines.sync.Mutex

/**
 * Which record of a game one source keeps: the source's key (a
 * `library.updates` provider's entry id, docs/plugin-api.md 3 A6) and the
 * source's own id for the game (a forum thread, a project page, a release
 * feed). The person makes the link (docs/SPEC.md 7g, "Where an update
 * comes from"); nothing on disk says it.
 */
data class SourceKey(val source: String, val externalId: String)

/** What a source last said about one of its records, kept so a round asks only when it is due. */
data class SourceAnswer(
    /** The newest version as the source wrote it; null when it gives none or the record is gone. */
    val version: String?,
    val checkedAtEpochMs: Long,
    /** The source said the record is private, moved or deleted. */
    val gone: Boolean = false,
    /** The record's own page, when the source names one. */
    val url: String? = null,
)

/** One game's link to one source, with the source's last answer (null until it has been asked). */
data class SourceLink(val source: String, val externalId: String, val answer: SourceAnswer? = null) {
    val key: SourceKey get() = SourceKey(source, externalId)
}

/**
 * The update sources droidtop can ask (docs/plugin-api.md 3 A6,
 * `library.updates`): the plugins that provide the point, through
 * [dev.droidtop.library.integrations.PluginUpdateSources]. An interface so
 * a round can be tested without a plugin. Nothing here is called from list
 * rendering: [sources] reads manifests, the rest are plugin calls.
 */
interface UpdateSources {
    /** One source: [key] is what its links are stored under, [label] the row's title, [hint] what to paste. */
    data class Source(val key: String, val label: String, val hint: String?)

    /** A record of the source that may be the game: offered by [match] or [resolve], picked by a person. */
    data class Found(
        val externalId: String,
        val title: String?,
        val version: String?,
        val url: String?,
        /** Why the source offers it ("on your watch list"), shown under the title. */
        val note: String?,
    )

    sealed interface Answer {
        data class Version(val version: String?, val url: String?) : Answer
        data object Gone : Answer
    }

    /** The sources there are now. Reads manifests: call it off the main thread. */
    fun sources(): List<Source>

    /**
     * The newest version of each of [externalIds]; an id missing from the
     * answer was not answered this time. Throws [IOException] when the
     * source cannot be asked at all.
     */
    suspend fun check(source: String, externalIds: List<String>): Map<String, Answer>

    /** The record a person's pasted text names (a link, a number), or null when it names none. */
    suspend fun resolve(source: String, text: String): Found?

    /** Records that may be the game called [title] with [versions], best first, for a person to pick from. */
    suspend fun match(source: String, title: String, versions: List<String>): List<Found>

    /**
     * One link of a record's Downloads tab: the source's heading, the file host, what the link says, and either the
     * address droidtop opens itself in its web view ([url], Droidtop/tracker#599) or the token the source's `download`
     * job takes ([link]). [systems] are the systems it is for (dev.droidtop.pluginhost.DownloadSystems), empty for
     * any: the Downloads tab shows the picked system's links (Droidtop/tracker#617).
     */
    data class Download(
        val section: String,
        val host: String,
        val label: String,
        val link: String,
        val url: String? = null,
        val systems: List<String> = emptyList(),
        /** A split release's pieces (docs/plugin-api.md 1.10, `download` `parts`): one row, fetched as one job. */
        val parts: List<Part> = emptyList(),
        /** `build`, `patch` or `extra` (Droidtop/tracker#838): droidtop's own pick takes only a build. */
        val role: String = "build",
        /** False for an item the source marks unofficial. */
        val official: Boolean = true,
        /** The build it is for, as the source names it ("Steam Build", Droidtop/tracker#845); null for the default build. */
        val build: String? = null,
    ) {
        /** One piece: its page ([url]) or the source's token ([link]), and its size when the source knows it. */
        data class Part(val label: String, val url: String?, val link: String?, val size: Long?)
    }

    /**
     * A record's page, as the source tells it (docs/plugin-api.md 3 A6 `record`); droidtop draws it (docs/SPEC.md 7g,
     * "Getting an update"). [rating] is 0 to 1; [updatedEpochMs] when the source last changed it.
     */
    data class Record(
        val externalId: String,
        val title: String,
        val url: String? = null,
        val version: String? = null,
        val developer: String? = null,
        val status: String? = null,
        val rating: Double? = null,
        val votes: Int? = null,
        val tags: List<String> = emptyList(),
        /** The platforms the game is for, its languages and its content notes, as the source states them (#845). */
        val platforms: List<String> = emptyList(),
        val languages: List<String> = emptyList(),
        val contentNotes: List<String> = emptyList(),
        val updatedEpochMs: Long? = null,
        val cover: String? = null,
        val overview: String? = null,
        val changelog: String? = null,
        /** The source's other named texts ("Installation", "Developer notes"), in its order (Droidtop/tracker#845). */
        val sections: List<Section> = emptyList(),
        /** The source page's main text as it reads, for the game page's Show thread; null when the source gives none. */
        val thread: String? = null,
        val downloads: List<Download> = emptyList(),
        /** The source's own switches, buttons, links and notices for this item (docs/plugin-api.md 1.10, `item.page`). */
        val controls: List<Control> = emptyList(),
    )

    /** One named text of a source's page: its heading as the source writes it, and the text under it. */
    data class Section(val title: String, val body: String)

    /**
     * One of the source's controls on the record page: a switch (watch this thread), a button, a link or a notice.
     * [action] is what pressing it sends the source; a link opens [url]; a notice without an action only says.
     */
    data class Control(
        val id: String,
        val kind: Kind,
        val title: String,
        val subtitle: String? = null,
        val on: Boolean = false,
        val url: String? = null,
        val action: dev.droidtop.pluginhost.ViewAction? = null,
        val confirm: Boolean = false,
    ) {
        enum class Kind { TOGGLE, ACTION, LINK, NOTICE }
    }

    /**
     * What a record page asks for: the record, or why there is none ([unsupported] when the source has no `record`).
     * [savedAtMs] is set when the record is droidtop's saved copy, not the source's answer just now.
     */
    data class RecordAnswer(val record: Record?, val failure: String? = null, val unsupported: Boolean = false, val savedAtMs: Long? = null)

    /** The record's page; a source that cannot answer says why, and droidtop's saved copy stands in when it has one. */
    suspend fun record(source: String, externalId: String): RecordAnswer = RecordAnswer(null, unsupported = true)

    /**
     * Presses [control] on [source]'s record [externalId]; [on] is a switch's new state. Returns the sentence to show.
     * The page asks for the record again afterwards, so a switch shows what the source now says.
     */
    suspend fun runControl(source: String, externalId: String, control: Control, on: Boolean?): String = "The source cannot do that"

    /** droidtop's saved copy of the record's page, shown at once while the source is asked; null when it has none. */
    fun savedRecord(source: String, externalId: String): RecordAnswer? = null

    /**
     * Whether [source] needs the person signed in to its site and no one is (its `web.session` account, docs/SPEC.md 12a
     * "A plugin's site account"): a record read then carries no download addresses. Reads the vault: off the main thread.
     */
    fun signedOut(source: String): Boolean = false

    /** Opens [source]'s sign-in in droidtop's web view; true when a session is kept. Blocks until the view closes. */
    fun signIn(source: String): Boolean = false

    /** Whether [source]'s site met droidtop with a browser check the person has not passed yet (docs/SPEC.md 12a). */
    fun needsCheck(source: String): Boolean = false

    /** Opens [source]'s browser check in droidtop's web view; true when the site cleared it. Blocks until the view closes. */
    fun passCheck(source: String): Boolean = false

    /**
     * Opens [url], a download link the record gave, in droidtop's web view with the source's session; the person starts
     * the download there and droidtop takes it over and places it as [download] does. When the page starts nothing (an
     * expired link), [fallbackUrl], the record's own page, is opened for the person to pick again. Returns the last line.
     */
    suspend fun openDownload(
        source: String,
        externalId: String,
        url: String,
        fallbackUrl: String?,
        version: String?,
        gameName: String,
        newest: LibraryEntry,
        onStatus: (String) -> Unit,
    ): String = "This source cannot open a download"

    /**
     * Downloads the version [link] names (the person picked it on the record page) as the source's `download` job and
     * places it as a new version folder of the game whose newest version is [newest], named for [gameName] and
     * [version]. Returns the job's last line.
     */
    suspend fun download(
        source: String,
        externalId: String,
        link: String,
        version: String?,
        gameName: String,
        newest: LibraryEntry,
        onStatus: (String) -> Unit,
    ): String = "This source cannot download an update"

    /**
     * Downloads every one of [parts] (the person picked a split release on the record page) as one Downloads job,
     * joined and unpacked as droidtop's split-set path does, into the new version folder beside [newest].
     */
    suspend fun downloadParts(
        source: String,
        externalId: String,
        parts: List<Download.Part>,
        version: String?,
        gameName: String,
        newest: LibraryEntry,
        onStatus: (String) -> Unit,
    ): String = "This source cannot download a release in parts"
}

object NoUpdateSources : UpdateSources {
    override fun sources(): List<UpdateSources.Source> = emptyList()
    override suspend fun check(source: String, externalIds: List<String>): Map<String, UpdateSources.Answer> = emptyMap()
    override suspend fun resolve(source: String, text: String): UpdateSources.Found? = null
    override suspend fun match(source: String, title: String, versions: List<String>): List<UpdateSources.Found> = emptyList()
}

/**
 * One round of update checks over the source links the person made
 * (docs/SPEC.md 7g, "Where an update comes from"). The host decides WHEN
 * a record is asked about; the source decides HOW it asks its own site
 * (batching, spacing, its own caches), so a round is one `check` call per
 * source per [MAX_IDS_PER_CHECK] records.
 *
 * - A record is asked about at most once per [CHECK_INTERVAL_MS]; a round
 *   started sooner skips it. Linking, or "Check now", asks about that one
 *   record at once, but not twice within [RECHECK_MIN_MS].
 * - One round at a time: a round asked for while one runs is not queued.
 * - A source that fails stops its own part of the round and changes
 *   nothing it did not finish; the other sources still go on, and the next
 *   round asks again.
 * - Links to a source that is not installed (or not running) are kept and
 *   not asked.
 */
class SourceUpdateCheck(
    private val store: GameLinksStore,
    private val sources: UpdateSources,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** What a round did: how many records it asked about, which entries' facts changed, and why a part stopped early. */
    data class Outcome(val asked: Int, val changedIds: Set<String>, val error: String? = null)

    private val running = Mutex()

    /** A round over every due link, or over [only] alone when a person asked. Null when a round was already running. */
    suspend fun round(only: SourceKey? = null): Outcome? {
        if (!running.tryLock()) return null
        try {
            return runRound(only)
        } finally {
            running.unlock()
        }
    }

    private suspend fun runRound(only: SourceKey?): Outcome {
        val present = sources.sources().map { it.key }.toSet()
        if (only != null && only.source !in present) return Outcome(0, emptySet(), "The source for this link is not installed or not running")
        val at = now()
        val due = store.linkedSources().filter { (key, last) ->
            key.source in present && when {
                only != null -> key == only && (last == null || at - last.checkedAtEpochMs >= RECHECK_MIN_MS)
                else -> last == null || at - last.checkedAtEpochMs >= CHECK_INTERVAL_MS
            }
        }
        if (due.isEmpty()) return Outcome(0, emptySet())

        val changed = mutableSetOf<String>()
        val errors = mutableListOf<String>()
        for ((source, keys) in due.keys.groupBy { it.source }) {
            try {
                for (batch in keys.chunked(MAX_IDS_PER_CHECK)) {
                    val answers = sources.check(source, batch.map { it.externalId })
                    for (key in batch) {
                        val last = due[key]
                        val next = when (val answer = answers[key.externalId]) {
                            UpdateSources.Answer.Gone -> SourceAnswer(null, now(), gone = true, url = last?.url)
                            is UpdateSources.Answer.Version -> SourceAnswer(answer.version?.trim()?.takeIf { it.isNotEmpty() }, now(), url = answer.url ?: last?.url)
                            // Not answered this time: what it last said stands, and it is not asked again before it is due.
                            null -> (last ?: SourceAnswer(null, 0L)).copy(checkedAtEpochMs = now())
                        }
                        store.saveAnswer(key, next)
                        if (last == null || last.version != next.version || last.gone != next.gone) {
                            changed += store.idsLinkedTo(key)
                        }
                    }
                }
            } catch (e: IOException) {
                errors += e.message ?: e.javaClass.simpleName
                ScanLog.write("updates: source $source stopped: ${e.message}")
            }
        }
        ScanLog.write("updates: asked ${due.size} linked record(s); ${changed.size} entries changed")
        return Outcome(due.size, changed, errors.firstOrNull())
    }

    companion object {
        /** How long an answer stands before a round asks again. */
        const val CHECK_INTERVAL_MS = 6 * 60 * 60_000L

        /** The least time between two asks about one record, however they were asked for. */
        const val RECHECK_MIN_MS = 60_000L

        /** The most records one `check` call carries. */
        const val MAX_IDS_PER_CHECK = 50
    }
}
