package dev.droidtop.library.theme

/**
 * What KIND of system one list entry belongs to, which real ES-DE needs
 * because two of its letter-case properties apply only to collections:
 * `letterCaseAutoCollections` to the auto-collections (All games,
 * Favorites, Last played) and `letterCaseCustomCollections` to the
 * user's own (SystemView.cpp:835-849 picks between them per entry).
 */
enum class EsDeCollectionKind { NONE, AUTO, CUSTOM }

/**
 * One list entry's real displayed name, as ES-DE assembles it.
 *
 * Two real rules, in this order (GamelistBase.cpp:826-855 and :946-957,
 * SystemView.cpp:835-857 -- the same pair in both views):
 *
 * 1. The letter case applied to the name is the element's own `letterCase`,
 *    EXCEPT for a collection entry, where `letterCaseAutoCollections` or
 *    `letterCaseCustomCollections` takes over -- and each of those falls
 *    back to `letterCase` when the theme left it UNDEFINED, which is why
 *    that is a distinct enum value from NONE rather than a null.
 * 2. `systemNameSuffix` then appends " [SYSTEM]" to a GAME entry shown
 *    inside a collection, naming the system the game really comes from
 *    (GamelistBase.cpp:789-806). Its case comes from
 *    `letterCaseSystemNameSuffix`, whose real default is UPPERCASE, and it
 *    is applied to the suffix ALONE: the suffix is appended after the name
 *    has already been cased, so an element with letterCase=lowercase and
 *    the default suffix case really does render "sonic [MEGADRIVE]".
 *    LOWERCASE is not one of the three branches there (:793-803) -- the
 *    else case leaves the system name as written, which is what this
 *    reproduces.
 *
 * [sourceSystemName] is null for an entry that is not inside a collection,
 * which is the same condition as ES-DE's own `isCollection &&
 * mSystemNameSuffix` guard.
 */
fun esDeEntryLabel(
    name: String,
    letterCase: EsDeLetterCase,
    collectionKind: EsDeCollectionKind = EsDeCollectionKind.NONE,
    letterCaseAutoCollections: EsDeLetterCase = EsDeLetterCase.UNDEFINED,
    letterCaseCustomCollections: EsDeLetterCase = EsDeLetterCase.UNDEFINED,
    systemNameSuffix: Boolean = true,
    letterCaseSystemNameSuffix: EsDeLetterCase = EsDeLetterCase.UPPERCASE,
    sourceSystemName: String? = null,
): String {
    val effective = when (collectionKind) {
        EsDeCollectionKind.AUTO -> letterCaseAutoCollections.takeUnless { it == EsDeLetterCase.UNDEFINED } ?: letterCase
        EsDeCollectionKind.CUSTOM -> letterCaseCustomCollections.takeUnless { it == EsDeLetterCase.UNDEFINED } ?: letterCase
        EsDeCollectionKind.NONE -> letterCase
    }
    val cased = effective.applyTo(name)
    if (!systemNameSuffix || sourceSystemName.isNullOrEmpty()) return cased
    val suffix = when (letterCaseSystemNameSuffix) {
        EsDeLetterCase.UPPERCASE -> sourceSystemName.uppercase()
        EsDeLetterCase.CAPITALIZE -> sourceSystemName.split(" ").joinToString(" ") { word ->
            word.replaceFirstChar { it.uppercaseChar() }
        }
        else -> sourceSystemName
    }
    return "$cased [$suffix]"
}
