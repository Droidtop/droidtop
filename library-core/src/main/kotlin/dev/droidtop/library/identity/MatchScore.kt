package dev.droidtop.library.identity

import dev.droidtop.library.scraper.PcMatching

/**
 * How sure a name match is (Droidtop/tracker#318 §3.2): each candidate a source returns gets a score from 0 to 100
 * against the local title, and the scores together give a tier that says whether droidtop takes the match itself or
 * asks the person. Only name matches are scored here; a hash, a store id or an alias is certain before any name is
 * compared, and the caller says so with [Tier.A] or [Tier.A_ALIAS]. Pure, so the calibration set runs offline.
 */
object MatchScore {
    /** What the game on disk calls itself: [title] parsed from the folder or file name, [slug] that name as found. */
    data class Local(val title: String, val slug: String? = null, val year: Int? = null, val system: String? = null)

    /** One answer from a source. [confidence] is a plugin's own 0 to 1, used in place of a score when given. */
    data class Candidate(
        val id: String,
        val name: String,
        val alternates: List<String> = emptyList(),
        val year: Int? = null,
        val system: String? = null,
        val confidence: Double? = null,
    )

    enum class Tier {
        /** A hash, a store id or an achievement hash said which game: taken, and only a manual pick replaces it. */
        A,

        /** The person picked this game for this name before (here or on a synced device): taken. */
        A_ALIAS,

        /** One clear name match: taken, and anything better replaces it. */
        B,

        /** Likely, but not clear enough to take: asked, with the best one chosen. */
        C,

        /** Unsure: asked, with the top five shown. */
        D,

        /** Nothing close: asked under "Not found". */
        E,
        ;

        /** Whether droidtop takes the match without asking. */
        val taken: Boolean get() = this == A || this == A_ALIAS || this == B
    }

    data class Scored(val candidate: Candidate, val score: Int)

    /** The tier, and the candidates best first (the ones from another system already left out). */
    data class Verdict(val tier: Tier, val ranked: List<Scored>) {
        val best: Candidate? get() = ranked.firstOrNull()?.candidate
    }

    const val TAKE_AT = 90
    const val LEAD = 15
    const val LIKELY_AT = 70
    const val UNSURE_AT = 40
    const val SUBTITLE_CAP = 85

    /** The 0 to 100 score of [candidate] for [local], before the system filter. */
    fun score(local: Local, candidate: Candidate): Int {
        candidate.confidence?.let { return (it.coerceIn(0.0, 1.0) * 100).toInt() }
        val names = listOf(candidate.name) + candidate.alternates
        val keys = listOfNotNull(local.title, local.slug).map(PcMatching::normalize).filter { it.isNotEmpty() }
        val exact = names.any { PcMatching.normalize(it) in keys }
        var score = if (exact) 100 else (names.maxOf { dice(words(local.title), words(it)) } * 100).toInt()
        // "Game" must not quietly become "Game: Part 2": a longer title that starts with the local one is capped below the bar.
        if (!exact && names.any { extends(it, local.title) }) score = minOf(score, SUBTITLE_CAP)
        if (local.year != null && candidate.year != null) {
            if (local.year == candidate.year) score += 5 else if (kotlin.math.abs(local.year - candidate.year) > 1) score -= 20
        }
        return score.coerceIn(0, 100)
    }

    /** The tier for [candidates] (the order a source gave them settles a tie) against [local]. */
    fun verdict(local: Local, candidates: List<Candidate>): Verdict {
        val kept = candidates.filter { local.system == null || it.system == null || it.system == local.system }
        val ranked = kept.map { Scored(it, score(local, it)) }.sortedByDescending { it.score }
        val best = ranked.firstOrNull()?.score ?: return Verdict(Tier.E, ranked)
        val lead = best - (ranked.getOrNull(1)?.score ?: 0)
        val tier = when {
            best >= TAKE_AT && lead >= LEAD -> Tier.B
            best >= LIKELY_AT -> Tier.C
            best >= UNSURE_AT -> Tier.D
            else -> Tier.E
        }
        return Verdict(tier, ranked)
    }

    private val ARTICLES = setOf("the", "a", "an")

    private fun words(text: String): Set<String> {
        val all = text.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").split(' ').filter { it.isNotBlank() }
        return (if (all.size > 1 && all.first() in ARTICLES) all.drop(1) else all).toSet()
    }

    private fun dice(a: Set<String>, b: Set<String>): Double =
        if (a.isEmpty() || b.isEmpty()) 0.0 else 2.0 * a.intersect(b).size / (a.size + b.size)

    private fun extends(name: String, title: String): Boolean {
        val head = title.trim().lowercase()
        val text = name.trim().lowercase()
        if (head.isEmpty()) return false
        return listOf(":", " - ").any { sep -> text.startsWith(head + sep) && text.length > head.length + sep.length }
    }
}
