package dev.droidtop.library.saves

/** Saves found in one place. [paths] are the save files themselves, what [SaveCopies] copies and nothing else. */
data class FoundPlace(
    val label: String,
    val folder: String,
    val files: Int,
    val bytes: Long,
    val newestMs: Long,
    val paths: List<String> = emptyList(),
)

/**
 * What droidtop knows about one game's saves, in the three honest states the owner asked for (Droidtop/tracker#546,
 * 2026-10-14: "Let's also get all the saves detected"): no rule known, a rule known but no saves found, and saves
 * found, with where and when. A fourth, [Unknown], is a rule that exists but cannot be applied to this game yet: a
 * name the game's files hold that droidtop could not read, or a folder it may not list. It is never shown as "none".
 */
sealed interface SaveStatus {
    /** The person said not to look for this game's saves. */
    object TurnedOff : SaveStatus

    /** No save rule is known for this game; [why] says what would give one. */
    data class NoRule(val why: String) : SaveStatus

    /** A rule is known; it cannot be applied to this game yet. [looked] is what was searched anyway. */
    data class Unknown(val why: String, val looked: List<String>) : SaveStatus

    /** A rule is known and these places hold nothing. */
    data class NoneFound(val looked: List<String>, val states: List<FoundPlace> = emptyList()) : SaveStatus

    /** Saves exist. [states] are save states, found but not counted; [cards] a shared memory card, a set of its own. */
    data class Found(
        val places: List<FoundPlace>,
        val states: List<FoundPlace>,
        val cards: List<FoundPlace>,
        val truncated: Boolean,
        /** What could not be applied, when part of the rule could not (a name unread, so one place was not searched). */
        val note: String? = null,
    ) : SaveStatus {
        val files: Int get() = places.sumOf { it.files }
        val bytes: Long get() = places.sumOf { it.bytes }
        val newestMs: Long get() = places.maxOfOrNull { it.newestMs } ?: 0L
    }
}

/** A status with the rule it came from and how the game was matched to it. */
data class SaveReport(val status: SaveStatus, val rule: String?, val matchedBy: String?)

/** Runs a [SavePlan] against the disk. File work: never on the main thread. */
object SaveSearch {
    fun run(plan: SavePlan): SaveStatus {
        var truncated = false
        fun search(places: List<SavePlace>): List<FoundPlace> {
            val out = ArrayList<FoundPlace>()
            for (place in places) {
                val scan = SaveTemplates.collect(place.patterns, place.roots)
                if (scan.truncated) truncated = true
                // One entry per folder the files were found in, so "where" is a real folder.
                for ((root, files) in scan.files.groupBy { it.root }) {
                    out += FoundPlace(place.label, root.path, files.size, files.sumOf { it.size }, files.maxOf { it.modifiedMs }, files.map { it.file.path })
                }
            }
            return out
        }
        val found = search(plan.places)
        val states = search(plan.states)
        val cards = search(plan.cards)
        val looked = (plan.places + plan.states + plan.cards).map { it.label }.distinct()
        return when {
            found.isNotEmpty() || cards.isNotEmpty() ->
                SaveStatus.Found(found, states, cards, truncated, note = plan.unknown.takeIf { it.isNotEmpty() }?.let(::unreadNote))
            plan.unknown.isNotEmpty() -> SaveStatus.Unknown(unreadNote(plan.unknown), looked)
            plan.places.isEmpty() && plan.blocked.isNotEmpty() ->
                SaveStatus.Unknown("droidtop may not look inside the emulator's own folder (${plan.blocked.first()}) without all-files access or More controls", emptyList())
            plan.places.isEmpty() -> SaveStatus.Unknown("this rule names no place droidtop can search for this game", emptyList())
            else -> SaveStatus.NoneFound(looked, states)
        }
    }

    private fun unreadNote(unknown: List<String>): String = "droidtop could not work out " + unknown.distinct().joinToString(" or ")
}
