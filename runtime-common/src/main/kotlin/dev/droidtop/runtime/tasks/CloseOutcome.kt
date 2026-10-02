package dev.droidtop.runtime.tasks

/** What closing one app achieved. Never a claim that did not happen. */
sealed interface CloseOutcome {
    /** A privileged provider ended the package, and said so. */
    data object Closed : CloseOutcome

    /** droidtop asked Android to end it; nothing confirms that it did. [message] says so and what to enable. */
    data class Requested(val message: String) : CloseOutcome

    /** A provider tried and could not. [message] is its own words. */
    data class Failed(val message: String) : CloseOutcome
}

/** The one line a surface shows for [this]. */
val CloseOutcome.text: String
    get() = when (this) {
        CloseOutcome.Closed -> "Closed"
        is CloseOutcome.Requested -> message
        is CloseOutcome.Failed -> message
    }

/** What Clear all did across every app it touched, for the one line a surface shows afterwards. */
data class ClearAllSummary(val closed: Int, val requested: Int, val failed: Int) {
    val message: String
        get() = when {
            closed + requested + failed == 0 -> "Nothing to close."
            requested == 0 && failed == 0 -> "Closed $closed ${noun(closed)}."
            closed == 0 && failed == 0 -> "Asked Android to close $requested ${noun(requested)}. ${TaskPolicy.ENABLE_HINT}"
            else -> "Closed $closed, asked Android to close $requested, could not close $failed. ${TaskPolicy.ENABLE_HINT}"
        }

    private fun noun(count: Int) = if (count == 1) "app" else "apps"

    companion object {
        fun of(outcomes: List<CloseOutcome>): ClearAllSummary = ClearAllSummary(
            closed = outcomes.count { it is CloseOutcome.Closed },
            requested = outcomes.count { it is CloseOutcome.Requested },
            failed = outcomes.count { it is CloseOutcome.Failed },
        )
    }
}
