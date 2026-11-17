package dev.droidtop.shell.gamepad.pc

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * A card's title as it breaks across its two lines (Droidtop/tracker#942: "HeartOfTheMachine" showed as "HeartOfTheMachin"
 * and an orphaned "e"). A title made of words run together (a folder name in camel case, "HeartOfTheMachine",
 * "Stardew_Valley", "game.v2") gets a break opportunity where each word starts, so the line breaks between words. Nothing
 * visible is added: the opportunity is a zero-width space. A title that already has spaces breaks at them and is left as
 * it is ("Mr. Driller", "Vol.2" stay whole). One word with nowhere to break stays whole for the renderer to end with an
 * ellipsis ([CardTitle.lines]). Pure.
 */
internal fun cardTitle(title: String): String {
    if (title.length < 2) return title
    // A spaced title still gets a break where a run-together word changes case ("RP AlphaQuest" showed as "RP AlphaQue"
    // and an orphaned "st", rig AVD dev.3354), but not at a digit or a separator: "Vol.2" and "Mr." stay whole.
    val spaced = title.any { it.isWhitespace() }
    val out = StringBuilder(title.length + 8)
    for (i in title.indices) {
        val c = title[i]
        if (i > 0) {
            val prev = title[i - 1]
            val wordStart = (prev.isLowerCase() && c.isUpperCase()) ||
                (!spaced && ((prev.isLetter() && c.isDigit()) || (prev.isDigit() && c.isLetter()))) ||
                // "XMLParser": the last capital of a run starts the next word.
                (prev.isUpperCase() && c.isUpperCase() && i + 1 < title.length && title[i + 1].isLowerCase())
            if (wordStart) out.append(ZERO_WIDTH_SPACE)
        }
        out.append(c)
        // After a separator a break is always fine.
        if (!spaced && (c == '_' || c == '.' || c == '-' || c == '/') && i + 1 < title.length) out.append(ZERO_WIDTH_SPACE)
    }
    return out.toString()
}

/**
 * A card title ready to draw: [text] with its break opportunities, and [lines], two where it can break between words and
 * one where it is a single word with no break at all, so it ends with an ellipsis instead of splitting a letter onto the
 * second line ([softWrap]).
 */
internal class CardTitle(val text: String, val lines: Int) {
    val softWrap: Boolean get() = lines > 1

    companion object {
        /** Pure. */
        fun of(title: String, max: Int = 2): CardTitle {
            val text = cardTitle(title)
            return CardTitle(text, if (text.any { it.isWhitespace() || it == ZERO_WIDTH_SPACE }) max else 1)
        }
    }
}

/** [CardTitle.of], worked out once per title rather than on every frame a card is drawn. */
@Composable
internal fun rememberCardTitle(title: String): CardTitle = remember(title) { CardTitle.of(title) }

private const val ZERO_WIDTH_SPACE = '​'
