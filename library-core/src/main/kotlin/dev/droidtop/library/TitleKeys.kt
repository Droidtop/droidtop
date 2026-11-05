package dev.droidtop.library

import java.security.MessageDigest
import java.text.Normalizer
import java.util.UUID

/**
 * The one fold of a game's title (Droidtop/tracker#870; the owner, 2026-11-05: "letters and digits only, but with
 * space-to-hyphen conversion. Lutris's standard"). Before it, droidtop had four (GameNaming.nameKey, the computer
 * key's word join, StoreIdentity's fold, GameFolderIds'), and the handheld's merge key and the cross-device key
 * disagreed, so a merge made on one device did not carry to another.
 *
 * [slug] is a game's identity: the key on every device ([dev.droidtop.library.computers.ComputerLibrary.titleKey])
 * and the handheld's same-game test ([GameNaming.nameKey]). [compact] is the same fold without separators, for
 * matching a program's file name to its folder's name, where "SampleSE.exe" is "Sample SE". Pure.
 */
object TitleKeys {
    /** Python's `\s` on an ASCII string: space, \t \n \v \f \r, and the separators \x1c-\x1f. */
    private const val WS = "\\t\\n\\x0B\\f\\r\\x1C-\\x1F "
    private val NOT_KEPT = Regex("[^A-Za-z0-9_${WS}-]")
    private val SEPARATORS = Regex("[-$WS]+")
    private val EDGES = Regex("^[$WS]+|[$WS]+$")

    /** `uuid.NAMESPACE_URL`, which Lutris names a title with no Latin letters by. */
    private val NAMESPACE_URL: UUID = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8")

    /**
     * Lutris's `slugify` exactly (lutris/lutris `lutris/util/strings.py` `slugify`, at master 4d9939e468de, GPL-3.0;
     * re-implemented here from its behaviour, not copied): NFD, everything outside ASCII dropped, every character but
     * letters, digits, `_`, whitespace and `-` removed, the ends stripped of whitespace, lower case, and each run of
     * hyphens and whitespace one hyphen. "Being a DIK" is `being-a-dik`, "Assassin's Creed® II" `assassins-creed-ii`,
     * "S.T.A.L.K.E.R." `stalker`; underscores stay (`foo_bar-baz`), as do a leading or trailing hyphen
     * ("  -- Hello -- World" is `-hello-world`). A title with nothing left (no Latin letters, "ドキドキ文芸部") is the
     * UUID-5 of the title as given in the URL namespace, as Lutris names it.
     */
    fun slug(title: String): String = latinSlug(title).ifEmpty { uuid5(NAMESPACE_URL, title).toString() }

    /**
     * [slug] without its fallback: "" when the title has no Latin letters or digits. For a fold that must not call two
     * different titles in another script one game because each hashed to itself ([StoreIdentity.titleKey]).
     */
    fun latinSlug(title: String): String {
        val ascii = Normalizer.normalize(title, Normalizer.Form.NFD).filter { it.code < 0x80 }
        val kept = NOT_KEPT.replace(ascii, "").replace(EDGES, "").lowercase()
        return SEPARATORS.replace(kept, "-")
    }

    /**
     * [slug]'s fold with no separators, for matching names that may or may not be spaced ("SampleSE" and "Sample SE"):
     * accents folded, letters and digits only, any script kept (a name in Japanese still compares with itself).
     */
    fun compact(name: String): String =
        Normalizer.normalize(name, Normalizer.Form.NFD).filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
            .lowercase().filter { it.isLetterOrDigit() }

    /** RFC 4122 version 5 (SHA-1) UUID of [name] in [namespace], as Python's `uuid.uuid5`. */
    internal fun uuid5(namespace: UUID, name: String): UUID {
        val sha = MessageDigest.getInstance("SHA-1")
        sha.update(java.nio.ByteBuffer.allocate(16).putLong(namespace.mostSignificantBits).putLong(namespace.leastSignificantBits).array())
        val hash = sha.digest(name.toByteArray(Charsets.UTF_8))
        hash[6] = ((hash[6].toInt() and 0x0f) or 0x50).toByte()
        hash[8] = ((hash[8].toInt() and 0x3f) or 0x80).toByte()
        val buffer = java.nio.ByteBuffer.wrap(hash, 0, 16)
        return UUID(buffer.long, buffer.long)
    }
}
