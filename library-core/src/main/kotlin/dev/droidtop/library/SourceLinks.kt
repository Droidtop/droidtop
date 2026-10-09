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
