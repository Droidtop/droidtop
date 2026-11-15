package dev.droidtop.shell.gamepad.pc

/**
 * A card's title as it breaks across its two lines (Droidtop/tracker#942: "HeartOfTheMachine" showed as "HeartOfTheMachin"
 * and an orphaned "e"). A title made of words run together (a folder name in camel case, "HeartOfTheMachine",
 * "Stardew_Valley", "game.v2") gets a break opportunity where each word starts, so the line breaks between words. Nothing
 * visible is added: the opportunity is a zero-width space. One word with nowhere to break stays whole for the
 * renderer to end with an ellipsis ([cardTitleLines]). Pure.
 */
internal fun cardTitle(title: String): String {
    if (title.length < 2) return title
    val out = StringBuilder(title.length + 8)
    for (i in title.indices) {
        val c = title[i]
        if (i > 0) {
            val prev = title[i - 1]
            val wordStart = (prev.isLowerCase() && c.isUpperCase()) ||
                (prev.isLetter() && c.isDigit()) || (prev.isDigit() && c.isLetter()) ||
                // "XMLParser": the last capital of a run starts the next word.
                (prev.isUpperCase() && c.isUpperCase() && i + 1 < title.length && title[i + 1].isLowerCase())
            if (wordStart) out.append(ZERO_WIDTH_SPACE)
        }
        out.append(c)
        // After a separator a break is always fine.
        if ((c == '_' || c == '.' || c == '-' || c == '/') && i + 1 < title.length) out.append(ZERO_WIDTH_SPACE)
    }
    return out.toString()
}

/**
 * How many lines a card's title may use: two where it can break between words, one where it is a single word with no
 * break at all, so it ends with an ellipsis instead of splitting a letter onto the second line. Pure.
 */
internal fun cardTitleLines(title: String, max: Int = 2): Int {
    val breakable = cardTitle(title)
    return if (breakable.any { it.isWhitespace() || it == ZERO_WIDTH_SPACE }) max else 1
}

private const val ZERO_WIDTH_SPACE = '​'
