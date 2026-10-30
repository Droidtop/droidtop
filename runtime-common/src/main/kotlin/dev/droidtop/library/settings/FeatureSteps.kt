package dev.droidtop.library.settings

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A feature that needs explaining once, to someone who had droidtop before it arrived (docs/SPEC.md 7b, "Update
 * onboarding", Droidtop/tracker#727). Declared in code where the feature is wired up and registered with
 * [FeatureSteps]; the changelog says what changed, a step says what to do about it.
 *
 * [arrived] is the changelog fragment it came with (`changelog.d/<arrived>.md`): the version it arrived in, named the
 * way every build's history names it; null for a feature from before builds carried a changelog. [place] is the catalog screen where the feature lives (a [CatalogScreen.id]),
 * where the step shows when that screen opens; null is an app-wide step, shown right after What's new. [action] is the
 * step's one button ([actionLabel]), run off the main thread when it is pressed; without one the step only says something. [applies] is
 * read just before the step would show: false means there is nothing left to say (the feature is already on), and the
 * step counts as seen.
 */
class FeatureStep(
    val id: String,
    val arrived: String?,
    val place: String?,
    val title: String,
    val body: String,
    val actionLabel: String? = null,
    val action: ((Context) -> Unit)? = null,
    val applies: (Context) -> Boolean = { true },
)

/**
 * The step registry onboarding and update onboarding share (docs/SPEC.md 7b, "Update onboarding"). Onboarding marks
 * every registered step seen when it finishes, so a fresh install is never told about features it was set up with;
 * after an update, each step not yet seen shows once, at its place, as the shell's confirm card ([NoticeDialogs]):
 * "Not now" on the left, the step's action on the right.
 */
object FeatureSteps {
    private const val PREFS = "feature_steps"
    private const val KEY_SEEN = "seen"

    private val registered = CopyOnWriteArrayList<FeatureStep>()

    /** Adds [step], replacing a step registered under the same id. */
    fun register(step: FeatureStep) {
        registered.removeAll { it.id == step.id }
        registered += step
    }

    val all: List<FeatureStep> get() = registered.toList()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun seen(context: Context): Set<String> = prefs(context).getStringSet(KEY_SEEN, emptySet()) ?: emptySet()

    fun markSeen(context: Context, ids: Collection<String>) {
        if (ids.isEmpty()) return
        prefs(context).edit().putStringSet(KEY_SEEN, seen(context) + ids).apply()
    }

    /** Every registered step counts as seen: onboarding's end, where the person was set up with all of them. */
    fun markAllSeen(context: Context) = markSeen(context, registered.map { it.id })

    /** The steps for [place] (null: app-wide) that [seen] does not hold, in registration order. Pure. */
    fun unseenAt(steps: List<FeatureStep>, place: String?, seen: Set<String>): List<FeatureStep> =
        steps.filter { it.place == place && it.id !in seen }

    /** Whether an app-wide step is waiting, for What's new to stay up for it. Reads preferences: off the main thread. */
    fun anyAppWide(context: Context): Boolean = unseenAt(all, null, seen(context)).isNotEmpty()

    /**
     * Shows each unseen step for [place] in turn and suspends until they are answered. A step is seen once it is shown,
     * whatever the answer; one with nothing left to say is seen without showing. With no surface to draw the card on,
     * nothing is shown and nothing is marked, so the steps wait for the next time.
     */
    suspend fun showAt(context: Context, place: String?) {
        val app = context.applicationContext
        val waiting = withContext(Dispatchers.IO) { unseenAt(all, place, seen(app)) }
        for (step in waiting) {
            if (!withContext(Dispatchers.IO) { step.applies(app) }) {
                withContext(Dispatchers.IO) { markSeen(app, listOf(step.id)) }
                continue
            }
            if (!NoticeDialogs.awaitHost(HOST_WAIT_MS)) return
            withContext(Dispatchers.IO) { markSeen(app, listOf(step.id)) }
            val answer = NoticeDialogs.confirm(
                Notice(NoticeTone.Info, step.title, step.body),
                cancelLabel = if (step.action != null) "Not now" else "Close",
                proceedLabel = step.actionLabel ?: "Got it",
            )
            if (answer == ConfirmAnswer.Proceed) step.action?.let { action -> withContext(Dispatchers.IO) { action(context) } }
        }
    }

    private const val HOST_WAIT_MS = 2_000L
}
