package dev.droidtop.shell.gamepad.input

/**
 * The words a hint uses for pressing confirm (docs/SPEC.md, controller layouts): the confirm button's printed glyph on
 * the active pad while a pad drives ([GamepadKeyMap.labelFor], so a swapped or PlayStation pad is named right), and
 * "tap" under touch. No hint spells a button letter itself.
 */
object PressWords {
    /** "press A", "press ✕", or "tap". */
    val confirm: String
        get() = if (PadModality.showsFocus) "press ${GamepadKeyMap.labelFor(GamepadAction.A)}" else "tap"

    /** "press A again" or "tap again": an armed row's second step. */
    val again: String get() = "$confirm again"

    /** [again] to start a sentence. */
    val againSentence: String get() = again.replaceFirstChar { it.uppercaseChar() }
}
