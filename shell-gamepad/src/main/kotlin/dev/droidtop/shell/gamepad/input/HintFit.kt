package dev.droidtop.shell.gamepad.input

/**
 * The hint bar's fit rule (docs/SPEC.md 7j, "The hint bar fits"; Droidtop/tracker#485 F2a, final design
 * C.1 item 8). The bar names every button that acts; when they do not fit on one line at the current text
 * size, it gives up room in a fixed order rather than cutting a label or scrolling a hint out of sight:
 *
 * 1. Start and R2 become their glyphs alone (a count such as "3 new" stays beside R2);
 * 2. L1/R1 drop (the strip they step shows its own shoulder glyphs);
 * 3. Select drops (the Options it opens is also on long press).
 *
 * A and B never drop. Start and R2 always sit first, on the left. What still does not fit after the last
 * step wraps (a sheet) or scrolls (a full-width bar), as before. Pure, so the order is a JVM test.
 */
object HintFit {

    /** One hint as drawn: its button, and its label (null draws the glyph alone). */
    data class Shown(val action: GamepadAction, val label: String?)

    /** The stages, in the order they are tried. */
    enum class Stage { FULL, MENUS_AS_GLYPHS, NO_SHOULDERS, NO_SELECT }

    private val MENUS = setOf(GamepadAction.START, GamepadAction.R2)
    private val SHOULDERS = setOf(GamepadAction.L, GamepadAction.R)

    /** The separator a hint's label uses before a count it keeps when it shrinks ("Quick Menu · 3 new"). */
    const val COUNT_SEPARATOR = " · "

    /** [hints] at [stage]: menus first, then the rest in their own order, with that stage's collapses. */
    fun at(hints: List<Pair<GamepadAction, String>>, stage: Stage): List<Shown> {
        val ordered = hints.filter { it.first in MENUS } + hints.filter { it.first !in MENUS }
        return ordered.mapNotNull { (action, label) ->
            when {
                stage >= Stage.NO_SHOULDERS && action in SHOULDERS -> null
                stage >= Stage.NO_SELECT && action == GamepadAction.SELECT -> null
                stage >= Stage.MENUS_AS_GLYPHS && action in MENUS -> Shown(action, label.substringAfter(COUNT_SEPARATOR, "").ifEmpty { null })
                else -> Shown(action, label)
            }
        }
    }

    /**
     * The first stage whose hints fit [available] by [width] (the drawn width of a list of hints), or the
     * last stage when none does (the bar then wraps or scrolls what is left).
     */
    fun fit(hints: List<Pair<GamepadAction, String>>, available: Float, width: (List<Shown>) -> Float): List<Shown> {
        for (stage in Stage.entries) {
            val shown = at(hints, stage)
            if (width(shown) <= available) return shown
        }
        return at(hints, Stage.entries.last())
    }

    /** The whole set, as a screen reader hears it ("Controls: A Open, B Back"), however much the bar had to shrink. */
    fun spoken(hints: List<Pair<GamepadAction, String>>, name: (GamepadAction) -> String): String =
        "Controls: " + at(hints, Stage.FULL).joinToString(", ") { "${name(it.action)} ${it.label}" }
}
