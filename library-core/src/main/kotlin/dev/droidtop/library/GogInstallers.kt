package dev.droidtop.library

import kotlinx.serialization.Serializable

/**
 * A GOG offline installer found in a games folder, not yet installed (docs/SPEC.md 7g, "Installers in a games
 * folder", Droidtop/tracker#405): the folder's row is an Install row, and Install takes the game out of [file]
 * without running it.
 */
@Serializable
data class FolderInstaller(
    /** The installer's file name in the row's folder (`setup_<slug>_<version>_(<build>).exe`). */
    val file: String,
    /** The game's name as the file names it: the slug, its words capitalised. */
    val title: String,
    /** The version the file names (`1.32`), or null when it names none. */
    val version: String? = null,
    /** GOG's build number, the last number in brackets (`10709`), or null when there is none. */
    val build: String? = null,
)

/**
 * GOG's offline installers by their file names, the one way they can be told apart without opening them: a
 * `setup_<slug>_<version>_(<build>).exe` and the `setup_<slug>_<version>_(<build>)-<n>.bin` parts beside it.
 *
 * A folder whose files hold a GOG installer and no program of its own is that game's installer folder
 * ([baseOf]). Several installers in one folder are one game and its DLC only when they all share the base game's
 * first two words (GOG names a DLC after its game: `setup_the_witcher_3_wild_hunt_-_hearts_of_stone_...`); a
 * folder of different games' installers is not read as any one game. Pure, for the tests.
 */
object GogInstallers {
    private val SETUP = Regex("""setup_(.+)\.exe""", RegexOption.IGNORE_CASE)
    private val BRACKETED = Regex("""\((.*)\)""")
    private val BUILD = Regex("""\d+""")
    private val VERSION = Regex("""v?\d+(\.\d+)+[a-z0-9.\-]*""", RegexOption.IGNORE_CASE)

    /** Whether [name] is a GOG installer program. */
    fun isSetupName(name: String): Boolean = SETUP.matches(name)

    /** The installer [name] stands for, or null when it is not a GOG installer's name. */
    fun parse(name: String): FolderInstaller? {
        val stem = SETUP.matchEntire(name)?.groupValues?.get(1) ?: return null
        val tokens = stem.split('_').filter { it.isNotEmpty() }
        // GOG puts the build last, in brackets; other bracketed words ("(64bit)", "(a)") say nothing a person reads.
        val build = tokens.lastOrNull()?.let { BRACKETED.matchEntire(it)?.groupValues?.get(1) }?.takeIf { BUILD.matches(it) }
        val words = tokens.filterNot { BRACKETED.matches(it) }
        // The version is the first dotted number after the slug's first word; a bare number ("3") is part of the name.
        val versionAt = words.indices.firstOrNull { it > 0 && VERSION.matches(words[it]) }
        val slug = if (versionAt != null) words.take(versionAt) else words
        if (slug.isEmpty()) return null
        return FolderInstaller(
            file = name,
            title = slug.joinToString(" ") { word -> word.replaceFirstChar { it.uppercaseChar() } },
            version = versionAt?.let { words[it] },
            build = build,
        )
    }

    /**
     * The game installer among a folder's file [names], or null when there is none or the installers there are not
     * one game: the one with the shortest name, when every other installer shares its first two words (its DLC).
     */
    fun baseOf(names: List<String>): FolderInstaller? {
        val installers = names.mapNotNull(::parse)
        if (installers.isEmpty()) return null
        val base = installers.minWith(compareBy({ it.title.length }, { it.file.lowercase() }))
        val key = base.title.lowercase().split(' ').take(2)
        return base.takeIf { installers.all { it.title.lowercase().split(' ').take(2) == key } }
    }
}
