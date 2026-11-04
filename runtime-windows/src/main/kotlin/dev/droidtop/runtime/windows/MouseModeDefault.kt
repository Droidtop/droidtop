package dev.droidtop.runtime.windows

/**
 * Whether a Wine game starts in controller mouse mode before the person has chosen (docs/SPEC.md 5b; Droidtop/tracker#618
 * C2, from the controls review: mouse mode never turned on by default, so a game that wants a pointer looked dead on a
 * pad-only device; the owner, 2026-10-23: "Yep. Go for it"). Pure.
 */
object MouseModeDefault {
    /**
     * The words in a game's genre that mean it is played with a pointer: point-and-click and hidden-object games, visual
     * novels, strategy (real-time and turn-based), card and board games. A genre is the scraped one, often several
     * joined ("Adventure, Point-and-click"), in any source's wording.
     */
    private val POINTER_GENRES = listOf(
        "point-and-click", "point and click", "point & click", "hidden object", "visual novel",
        "strategy", "rts", "card & board", "card game", "board game",
    )

    private val patterns = POINTER_GENRES.map { word -> Regex("(^|[^a-z])" + Regex.escape(word) + "($|[^a-z])") }

    /** Whether a game of [genre] needs a mouse, so mouse mode starts on until the person says otherwise. */
    fun genreNeedsMouse(genre: String?): Boolean {
        val g = genre?.lowercase() ?: return false
        return patterns.any { it.containsMatchIn(g) }
    }
}
