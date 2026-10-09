package dev.droidtop.library.scraper

/**
 * The games a scrape pass did not fill, by name (docs/SPEC.md 7h, "A scrape names what it
 * missed", Droidtop/tracker#374). A count of "no match for 2" cannot be followed up; the
 * titles and the source's reason can. The sentence a person reads names up to [MAX_NAMED] per
 * bucket and says how many more there are; scan.log has one `scrape:` line for every game.
 * Pure, so the wording is unit-tested rather than only observable on hardware.
 */
internal class ScrapeMisses {
    enum class Kind(val heading: String) {
        NO_MATCH("No match"),
        NEEDS_PICKING("Needs your pick (Choose match on the game)"),
        FAILED("Failed"),
    }

    private val byKind = Kind.entries.associateWith { mutableListOf<String>() }

    fun add(kind: Kind, name: String, detail: String? = null) {
        byKind.getValue(kind) += if (detail.isNullOrBlank()) name else "$name ($detail)"
    }

    val isEmpty: Boolean get() = byKind.values.all { it.isEmpty() }

    /** One line per non-empty bucket, each starting with a newline, or "" when there is nothing to name. */
    fun sentences(): String = Kind.entries.joinToString("") { kind ->
        val names = byKind.getValue(kind)
        if (names.isEmpty()) {
            ""
        } else {
            val shown = names.take(MAX_NAMED).joinToString("; ")
            val more = names.size - MAX_NAMED
            "\n${kind.heading}: $shown" + if (more > 0) " and $more more (all are in scan.log)." else "."
        }
    }

    companion object {
        const val MAX_NAMED = 6
    }
}
