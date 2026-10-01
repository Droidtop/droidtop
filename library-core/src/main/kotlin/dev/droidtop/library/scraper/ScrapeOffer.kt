package dev.droidtop.library.scraper

import android.content.Context
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The one-time "Fetch box art and details for your games?" question after the
 * library's first walk (docs/SPEC.md 7h; Droidtop/tracker#174). Nobody opens
 * a scrape menu to find out games can have art, so the first finished walk
 * asks once. The answer is stored the moment it is given and the question is
 * never raised again: [Answer.ASK] is the only state that ever asks.
 * Settings (Scraper > "After the first library scan") shows the stored answer
 * and can set it back to ask, or to the other answer.
 *
 * What the answers mean when a later walk finishes (a games folder added or
 * changed): [Answer.FETCH] starts the library scrape job again without
 * asking, [Answer.NEVER] does nothing, [Answer.ASK] raises the question.
 *
 * The shell draws the question from [pending] (library-core cannot reach a
 * dialog), through the same input pipeline as every other modal (docs/SPEC.md
 * 6e). A question not yet answered when the process dies is remembered in the
 * preferences and restored by [restore], so a walk that finished while the
 * shell was not on screen is still asked.
 */
object ScrapeOffer {
    enum class Answer(val id: String, val label: String) {
        ASK("ask", "Ask me"),
        FETCH("fetch", "Fetch automatically"),
        NEVER("never", "Don't fetch"),
    }

    enum class Action { ASK, START, NOTHING }

    private const val PREFS_NAME = LAUNCHER_PREFS_FILE_NAME
    private const val KEY_ANSWER = "droidtop_scrape_offer_answer"
    private const val KEY_PENDING = "droidtop_scrape_offer_pending"

    /** What a finished walk of [gameCount] games does when the stored answer is [answer]. No games, nothing to fetch for. */
    internal fun decide(answer: Answer, gameCount: Int): Action = when {
        gameCount <= 0 -> Action.NOTHING
        answer == Answer.ASK -> Action.ASK
        answer == Answer.FETCH -> Action.START
        else -> Action.NOTHING
    }

    private val pendingState = MutableStateFlow(false)

    /** True while the question is waiting to be answered on screen. */
    val pending: StateFlow<Boolean> get() = pendingState

    /** How a scrape is started; a test swaps it. */
    internal var starter: (Context) -> Unit = { LibraryScrapeJob.start(it, "Scrape all systems") }

    fun current(context: Context): Answer {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_ANSWER, null)
        return Answer.entries.firstOrNull { it.id == raw } ?: Answer.ASK
    }

    /** The Settings row's setter. Setting [Answer.ASK] re-arms the question; any answer ends one that is pending. */
    fun set(context: Context, answer: Answer) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_ANSWER, answer.id)
            .putBoolean(KEY_PENDING, false)
            .apply()
        pendingState.value = false
    }

    /** Called when a library walk finishes with [gameCount] games on it. */
    fun walkFinished(context: Context, gameCount: Int) {
        when (decide(current(context), gameCount)) {
            Action.ASK -> {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_PENDING, true).apply()
                pendingState.value = true
            }
            Action.START -> starter(context)
            Action.NOTHING -> Unit
        }
    }

    /** Raises a question left unanswered by an earlier process. Reads preferences, so call it off the main thread. */
    fun restore(context: Context) {
        if (current(context) == Answer.ASK &&
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_PENDING, false)
        ) {
            pendingState.value = true
        }
    }

    /** The person's answer to the question on screen: yes fetches now, no is remembered and not asked again. */
    fun respond(context: Context, fetch: Boolean) {
        set(context, if (fetch) Answer.FETCH else Answer.NEVER)
        if (fetch) starter(context)
    }
}
