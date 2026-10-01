package dev.droidtop.library

import dev.droidtop.library.consoles.SwitchContent
import dev.droidtop.runtime.util.Versions
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable

/**
 * What a whole Switch game has, once its files are folded together
 * (docs/SPEC.md 7m, "Switch content"): the update its folders carry
 * (which version, if a filename said), how many add-on packages it has,
 * and where those files are. Set only on the row a list draws for the
 * game -- one file cannot know this, the game can, the same shape as
 * [LibraryEntry.availableUpdate].
 */
@Serializable
data class SwitchGameFacts(
    val baseTitleId: String? = null,
    /** Whether an update file of this game is on the device, version known or not. */
    val hasUpdate: Boolean = false,
    /** The update's `[vN]` version, when its filename carried one. */
    val updateVersion: String? = null,
    /** How many add-on packages the game has, not how many files carry them. */
    val dlcCount: Int = 0,
    /** The update files themselves, so the game's row can still reach them. */
    val updatePaths: List<String> = emptyList(),
    /** The DLC files themselves. */
    val dlcPaths: List<String> = emptyList(),
    /**
     * This row is ITSELF an update or a DLC whose base game is not in
     * the library: it stayed its own row because there was no base row
     * to fold into, not because it is a game.
     */
    val loose: Boolean = false,
) {
    /**
     * The one line that says what this game's files add up to -- "Update
     * v131072", "2 DLC", both when it has both -- or, for a loose row,
     * that it is a part whose game is missing ("DLC without base game").
     * Empty when there is nothing to say, so a plain base game with no
     * update and no DLC draws no line at all.
     */
    fun line(): String = when {
        loose && dlcCount > 0 -> "DLC without base game"
        loose -> "Update" + (updateVersion?.takeIf { it.isNotEmpty() }?.let { " v$it" } ?: "") + " without base game"
        else -> buildList {
            if (hasUpdate) add("Update" + (updateVersion?.takeIf { it.isNotEmpty() }?.let { " v$it" } ?: ""))
            if (dlcCount > 0) add("$dlcCount DLC")
        }.joinToString(" · ")
    }
}

/**
 * Switch updates and DLC are parts of ONE game, never games of their own
 * (docs/SPEC.md 7m, "Switch content"): [fold] turns the scan's Switch
 * rows into the rows a list draws -- one row per base game, carrying
 * [SwitchGameFacts], with its update and DLC files folded away -- the
 * same job [LibraryGrouping] does for PC folders, keyed by what a Switch
 * package says about itself instead of by a folder name.
 *
 * Which file is which comes from [SwitchContent.classify]: a title ID is
 * a 16-hex-character string whose low 13 bits say base (clear) /
 * update (`0x800`) / add-on (bit 12 plus its index), read from a filename tag or
 * from the PFS0 file table's ticket name, never from encrypted content
 * and never a console key. An update or DLC folds into the row whose own
 * title ID IS its base; a name never matches anything, because `Zelda
 * [01007ef00011e800]` and `Breath of the Wild [01007ef00011e000]` are the
 * same game under two names and no naming rule may be allowed to think
 * otherwise.
 *
 * What has no base to fold into stays its own row, marked
 * [SwitchGameFacts.loose] -- droidtop has nowhere to put it and deleting
 * it would hide a file the person owns. Files classification says
 * nothing about (no tag, no readable ticket) stay untouched rows too:
 * the fold never guesses.
 *
 * Classification reads each container's file table, so [fold] runs off
 * the main thread (its caller in shell-gamepad uses the same
 * `produceState` + `Dispatchers.Default` shape the PC fold uses) and
 * caches what it read per file by modification time, so re-running the
 * fold over an unchanged library -- which every library publish does --
 * costs a stat per file, not a header read.
 */
object SwitchGameGrouping {

    /**
     * The scan's rows with every Switch update and DLC folded into its
     * base game's row. Non-Switch rows pass through untouched, and the
     * input's order is kept: a folded row sits where its base game sat.
     */
    fun fold(entries: List<LibraryEntry>): List<LibraryEntry> {
        val rows = entries.map { Row(it, contentOf(it)) }
        val presentBases = rows.mapNotNull { (it.content as? SwitchContent.BaseGame)?.baseTitleId }.toSet()

        val factsByBase = HashMap<String, Parts>()
        for (row in rows) {
            val content = row.content ?: continue
            val base = content.baseTitleId?.takeIf { it in presentBases } ?: continue
            factsByBase.getOrPut(base) { Parts() }.add(content, row.entry.id)
        }

        return rows.mapNotNull { row ->
            when (val content = row.content) {
                null -> row.entry
                is SwitchContent.BaseGame -> {
                    val base = content.baseTitleId ?: content.titleId ?: return@mapNotNull row.entry
                    row.entry.copy(switchFacts = factsByBase[base]?.facts(base) ?: SwitchGameFacts(baseTitleId = base))
                }
                // Its base game is one of this library's rows: the file is
                // that row's to carry, and no row of its own remains.
                is SwitchContent.Update -> if (content.baseTitleId in presentBases) {
                    null
                } else {
                    row.entry.copy(
                        switchFacts = SwitchGameFacts(
                            baseTitleId = content.baseTitleId,
                            hasUpdate = true,
                            updateVersion = content.version,
                            updatePaths = listOf(row.entry.id),
                            loose = true,
                        ),
                    )
                }
                is SwitchContent.Dlc -> if (content.baseTitleId in presentBases) {
                    null
                } else {
                    row.entry.copy(
                        switchFacts = SwitchGameFacts(
                            baseTitleId = content.baseTitleId,
                            dlcCount = 1,
                            dlcPaths = listOf(row.entry.id),
                            loose = true,
                        ),
                    )
                }
            }
        }
    }

    /** One scan row and what its file says it is. */
    private data class Row(val entry: LibraryEntry, val content: SwitchContent?)

    /** The update and DLC files of one base game, before they become [SwitchGameFacts]. */
    private class Parts {
        private val updates = mutableListOf<Pair<String, String?>>() // path to [vN] version
        private val dlcs = mutableListOf<Pair<String, Int?>>() // path to add-on index

        fun add(content: SwitchContent, path: String) {
            when (content) {
                is SwitchContent.Update -> updates += path to content.version
                is SwitchContent.Dlc -> dlcs += path to content.addOnIndex
                is SwitchContent.BaseGame -> Unit
            }
        }

        fun facts(baseTitleId: String): SwitchGameFacts = SwitchGameFacts(
            baseTitleId = baseTitleId,
            hasUpdate = updates.isNotEmpty(),
            // The newest of what the filenames said, by the one version
            // comparison the library already has (GameVersion's); an
            // update with no version tag is still an update, just an
            // unnumbered one.
            updateVersion = updates.mapNotNull { it.second?.takeIf(String::isNotEmpty) }
                .maxWithOrNull { a, b -> Versions.compareLoose(a, b) },
            // Add-on packages, not files: two files of the same add-on
            // index are one package twice. A file whose index could not
            // be read is counted by itself, because no two of those may
            // be merged on a guess.
            dlcCount = dlcs.map { it.second }.filterNotNull().distinct().size +
                dlcs.count { it.second == null },
            updatePaths = updates.map { it.first },
            dlcPaths = dlcs.map { it.first },
        )
    }

    /**
     * What [entry]'s file says it is, cached by path and modification
     * time. Non-Switch rows are answered before the filesystem is
     * touched at all: the fold runs over the WHOLE games list, and a
     * stat per ROM of an 18,000-file folder per re-fold (every library
     * publish) is exactly the disk work the spec forbids. The cache is
     * what keeps a re-fold of a Switch library at one stat per file
     * instead of one PFS0 header read, and it is capped because it is a
     * cache, not a store: cleared whole when it outgrows any real Switch
     * library.
     */
    private fun contentOf(entry: LibraryEntry): SwitchContent? {
        if (entry.kind != LibraryEntryKind.CONSOLE_ROM || entry.systemId != SwitchContent.SYSTEM_ID) return null
        val file = File(entry.id)
        val modified = file.lastModified()
        cache[entry.id]?.takeIf { it.modified == modified }?.let { return it.content }
        val content = SwitchContent.classify(file)
        if (cache.size >= MAX_CACHED_FILES) cache.clear()
        cache[entry.id] = Cached(modified, content)
        return content
    }

    private data class Cached(val modified: Long, val content: SwitchContent?)

    private val cache = ConcurrentHashMap<String, Cached>()

    /** Any real Switch library is a few hundred files; past that the cache is a leak, not a cache. */
    private const val MAX_CACHED_FILES = 1024
}
