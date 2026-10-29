package dev.droidtop.library.consoles

import java.io.File

/**
 * The one directory walk every ROM-facing pass in droidtop uses: the
 * library scan ([ConsoleRomProvider.scanSystemFolder]) and the scraper
 * ([dev.droidtop.library.scraper.scrapeSystemArtwork]) both go through
 * here, so a file either is a game to both of them or to neither. They
 * used to call `File.walkTopDown()` separately and could disagree.
 *
 * Recursion itself is deliberate and stays: a large system can be
 * reorganised into subfolders (by first letter, by collection, whatever)
 * without that ever hiding files from droidtop. What this adds is the
 * one thing plain recursion gets wrong.
 *
 * ## Why some directories are not descended into
 *
 * Real case, on the user's own device (2026-09-01): a `Rune Factory 5`
 * DLC directory sitting beside the base game produced **twelve** library
 * entries, each with its own metadata row and its own cover, because
 * twelve add-on files carried the system's ROM extension. That is not a
 * scraper bug and not a matching bug -- the walk genuinely handed twelve
 * games to everything downstream.
 *
 * The rule chosen, and why it is a rule rather than a fix for that one
 * title:
 *
 *  1. **Add-on content is named as add-on content.** DLC, update and
 *     patch files are shipped in a directory whose name says so, because
 *     the emulator that consumes them and the human who filed them there
 *     both need to know what they are. A directory whose name *ends* in
 *     one of [ADD_ON_DIRECTORY_MARKERS] holds content that attaches to a
 *     game; it is not a shelf of games. Matching on the final token is
 *     what makes this general instead of a special case: it covers
 *     `DLC`, `dlc`, `Rune Factory 5 (DLC)`, `Rune Factory 5 [DLC]`,
 *     `Zelda - Updates` and `_patches` alike, without knowing any title.
 *  2. **The marker list is deliberately short.** Only names that can
 *     only ever mean "not a game on its own" are in it. `mods`, `hacks`
 *     and `romhacks` are pointedly absent: a ROM hack IS a playable game
 *     and a user who files them in a folder wants to see them. A rule
 *     that hides real games to tidy up a list is worse than the bug it
 *     fixes.
 *  3. **The system folder itself is never excluded**, whatever it is
 *     called, so this can never empty out a whole system.
 *  4. **The user has the real ES-DE override.** ES-DE's own
 *     `noload.txt` (SystemData::populateFolder, read at
 *     /root/es-de-reference before implementing this) skips a directory
 *     and everything under it. droidtop honours the same file, which
 *     means the general "don't scan this directory" mechanism is the one
 *     the user's other frontend already uses -- droidtop invents no
 *     second one. If the marker rule ever guesses wrong in the other
 *     direction and hides games, the fix is renaming the directory; if
 *     the user wants a directory hidden that the rule does not catch,
 *     `noload.txt` is the answer.
 *
 * Directories skipped, and BIOS images left out (rule 6 of docs/SPEC.md 7g's walk), are reported back in
 * [RomScanResult.skipped] so the caller can say so out loud rather than silently losing files.
 */
object RomScanWalk {

    /**
     * Final-token markers for a directory of content that attaches to a
     * game rather than being one. See this object's own doc comment for
     * why the list is short and why `mods`/`romhacks` are not on it.
     */
    internal val ADD_ON_DIRECTORY_MARKERS = setOf(
        "dlc", "dlcs",
        "update", "updates",
        "patch", "patches",
        "addon", "addons",
        // Not add-on content, but the same class of mistake: a BIOS or
        // firmware image carries a system's own ROM extension all the
        // time and is never something a user wants to launch.
        "bios", "firmware",
    )

    /** ES-DE's own real "do not populate this directory" marker file. */
    internal const val NO_LOAD_MARKER = "noload.txt"

    /**
     * Whether [name] names a directory of add-on content. Pure, and the
     * whole of rule 1 above: split on anything that is not a letter or a
     * digit, then test the last token -- and, so that `Add-On` and
     * `add_on` behave like `addon`, the last two tokens joined.
     */
    fun isAddOnDirectoryName(name: String): Boolean {
        val tokens = name.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return false
        if (tokens.last() in ADD_ON_DIRECTORY_MARKERS) return true
        if (tokens.size >= 2 && (tokens[tokens.size - 2] + tokens.last()) in ADD_ON_DIRECTORY_MARKERS) return true
        return false
    }

    /**
     * Why [directory] must not be descended into, or null to descend.
     * [systemFolder] is never skipped by the naming rule (rule 3).
     */
    fun skipReason(directory: File, systemFolder: File): String? = when {
        File(directory, NO_LOAD_MARKER).isFile -> "a $NO_LOAD_MARKER file is present"
        directory.absolutePath == systemFolder.absolutePath -> null
        // The one prune rule every walk shares (hidden folders, sync and
        // filesystem markers, a store's own install tree); this walk adds
        // only the two rules that are about ROMs.
        else -> dev.droidtop.library.ScanPrune.skipReason(directory)
            ?: if (isAddOnDirectoryName(directory.name)) "it holds add-on content, not games" else null
    }

    /**
     * Files found, the directories deliberately not descended into, and
     * the directory a [dev.droidtop.library.ScanBudget] stopped the walk
     * in (null when the walk finished).
     */
    data class RomScanResult(
        val files: List<File>,
        val skipped: List<Pair<File, String>>,
        val stoppedAt: File? = null,
    )

    /**
     * Every file under [systemFolder] at any depth, minus the ones inside
     * directories [skipReason] rejects. Pass [extensions] (lowercase, no
     * dot) to keep only ROM files for a system; omit it for the raw file
     * set.
     *
     * [newBudget] gives each directory its own budget, bounding that
     * directory's own listing rather than the subtree below it: a
     * directory that does not come back (a real case on this device: a
     * `j2me` folder of 18,126 entries, one of them a corrupted entry that
     * hung `ls` itself) is skipped with its reason and the walk carries on
     * with its siblings. A large healthy system is never truncated for
     * being large -- see [dev.droidtop.library.ScanBudget] for why that
     * distinction decides whether a user's games appear at all.
     *
     * An explicit queue rather than [File.walkTopDown], because the budget
     * has to wrap the one `listFiles()` call it is judging, and a walk
     * whose listing happens between two of its own callbacks cannot say
     * which directory was the slow one. Children are taken in name order
     * and the files come back sorted, so two scans of one folder return
     * the same list in the same order.
     */
    fun walk(
        systemFolder: File,
        extensions: Set<String>? = null,
        // File names (lower case) that are never games wherever they sit: the BIOS images the database knows, which
        // carry a system's own ROM extension (Droidtop/tracker#665). A BIOS folder is already skipped by name.
        notGames: Set<String> = BiosDatabase.fileNames,
        newBudget: () -> dev.droidtop.library.ScanBudget? = { null },
    ): RomScanResult {
        val files = mutableListOf<File>()
        val skipped = mutableListOf<Pair<File, String>>()
        var stoppedAt: File? = null
        val queue = ArrayDeque<File>()
        skipReason(systemFolder, systemFolder)
            ?.let { return RomScanResult(emptyList(), listOf(systemFolder to it)) }
        queue += systemFolder
        while (queue.isNotEmpty()) {
            val directory = queue.removeFirst()
            val budget = newBudget()
            val children = directory.listFiles()
            if (budget != null && budget.expired) {
                if (stoppedAt == null) stoppedAt = directory
                skipped += directory to budget.reason()
                continue
            }
            if (children == null) {
                skipped += directory to "it could not be listed at all"
                continue
            }
            for (child in children.sortedBy { it.name }) {
                if (child.isDirectory) {
                    val reason = skipReason(child, systemFolder)
                    if (reason != null) skipped += child to reason else queue += child
                } else if (child.name.lowercase() in notGames) {
                    skipped += child to "it is a BIOS image, not a game"
                } else if (extensions == null || child.extension.lowercase() in extensions) {
                    files += child
                }
            }
        }
        val kept = if (extensions == null) files else files.minus(namedDiscs(files) { readLinesOrNull(it) })
        return RomScanResult(kept.sortedBy { it.absolutePath }, skipped, stoppedAt)
    }

    /**
     * The files among [files] that a playlist names, so they are that playlist's discs and not entries of their own
     * (docs/SPEC.md 7g, "A multi-disc game is its `.m3u`"): every disc an `.m3u` lists, one path per line with `#`
     * lines skipped, and every track a `.cue` names in a `FILE "..."` line. Paths are relative to the playlist's own
     * folder; either slash is accepted, and names compare without case, as a FAT or exFAT card stores them. A disc
     * nothing names stays an entry. [read] gives a playlist's lines, or null when it cannot be read (then it names
     * nothing). Pure apart from [read], for the JVM tests.
     */
    internal fun namedDiscs(files: List<File>, read: (File) -> List<String>?): Set<File> {
        val byKey = files.associateBy { key(it) }
        val named = mutableSetOf<File>()
        for (playlist in files) {
            val names = when (playlist.extension.lowercase()) {
                "m3u" -> read(playlist).orEmpty().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
                "cue" -> read(playlist).orEmpty().mapNotNull { line -> CUE_FILE.find(line.trim())?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } } }
                else -> continue
            }
            for (name in names) {
                val path = name.replace('\\', '/')
                val disc = if (path.startsWith("/")) File(path) else File(playlist.parentFile, path)
                byKey[key(disc)]?.takeIf { it != playlist }?.let { named += it }
            }
        }
        return named
    }

    // A cue sheet's track line: FILE "Game (Track 1).bin" BINARY, or an unquoted name without spaces.
    private val CUE_FILE = Regex("""^FILE\s+(?:"([^"]+)"|(\S+))""", RegexOption.IGNORE_CASE)

    private fun key(file: File): String = file.toPath().normalize().toString().lowercase()

    // A playlist is a few lines; one that is not text or not readable names nothing.
    private fun readLinesOrNull(file: File): List<String>? =
        runCatching { if (file.length() > 64 * 1024) null else file.readLines() }.getOrNull()

    /** [walk]'s file list only, for callers with nothing to report. */
    fun romFiles(systemFolder: File, extensions: Set<String>): List<File> =
        walk(systemFolder, extensions).files
}
