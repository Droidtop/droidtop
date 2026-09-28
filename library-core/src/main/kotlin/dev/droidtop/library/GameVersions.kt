package dev.droidtop.library

/**
 * One game, its versions and its segments -- the model behind "a game is
 * ONE entry in the library, however many folders it occupies" (docs/SPEC.md
 * 7m).
 *
 * Two real shapes from the user's own library, and the two normative
 * examples the spec carries:
 *
 * - `adult/renpy/Fetish Locator/{Week 1, Week 2, Week 3}` is ONE game
 *   called Fetish Locator with three SEGMENTS. Before this it was three
 *   entries that shared a cover and sorted apart from each other.
 * - `Anomalous_Coffee_Machine_2-1.0.00_deluxe_linux.x86_64` beside
 *   `Anomalous_Coffee_Machine_2_v1.2-deluxe_windows` is ONE game with two
 *   VERSIONS, the newer of which is what Play starts.
 *
 * The version/copy split is Pythia's (`versions[] -> variants[]`,
 * `pythia/onboarding.py::_merge_version`): a version is what the game is,
 * and a copy is one install of it, so two copies of the same version that
 * differ by mods, language or where they came from stay two copies of one
 * version rather than two versions.
 */
data class GroupedGame(
    /** The game's name, derived from the folders it was found in ([GameNaming.derive]). */
    val name: String,
    /** Versions of the game itself -- empty when every folder found was a segment. */
    val versions: List<GameVersion> = emptyList(),
    /** The game's parts, in the order their own names give ([GameNaming.Segment]). */
    val segments: List<GameSegment> = emptyList(),
    /**
     * What an update source says the game's newest version is, as it said
     * it (docs/SPEC.md 7g, "Where an update comes from"); null until
     * something has looked.
     */
    val latestKnown: String? = null,
    /** All ownerships of this game: stores, local folders, F95 threads. */
    val ownerships: Set<Ownership> = emptySet(),
) {
    /** What the game's detail opens on: its first segment, or none when it has no parts. */
    val defaultSegment: GameSegment? get() = segments.firstOrNull()

    /**
     * What Play starts: the newest version of [defaultSegment], or of the
     * game itself. Every version list is held newest first, so this is its
     * head rather than a second ordering rule that could disagree with it.
     */
    val defaultVersion: GameVersion?
        get() = (defaultSegment?.versions ?: versions).firstOrNull()

    /** Every version this game has anywhere, segments included. */
    val allVersions: List<GameVersion> get() = versions + segments.flatMap { it.versions }

    /** Whether any copy of this game is installed on this device. */
    val installed: Boolean get() = allVersions.any { version -> version.copies.any { it.installed } }

    /** The version a source knows of that none of this game's folders is, or null ([GameUpdates.available]). */
    val availableUpdate: String? get() = GameUpdates.available(latestKnown, allVersions.map { it.version })

    /** Whether some source knows of a version newer than the one that is here. */
    val updateAvailable: Boolean get() = availableUpdate != null
}

/**
 * Whether a version an update source names is one this device does not
 * have, and the one wording for it (docs/SPEC.md 7g). The card, the
 * detail's update row and every "Parts and versions" row say it with
 * [line], so "an update is available" reads the same wherever it shows.
 */
object GameUpdates {

    /**
     * [latest] when it names a version none of [versions] is, else null.
     *
     * Pythia's rule (`f95_update_check.run_update_check`): a different
     * string is an update, compared as the two sources write them, less a
     * leading `v` (a folder says `0.9.5`, a thread says `v0.9.5`). Two
     * cases say nothing: a source with no version to give (blank, or
     * F95Checker's own `N/A`), and a game none of whose folders names a
     * version, where there is nothing to compare with -- Pythia skips
     * that case too (`if installed_version and ...`).
     */
    fun available(latest: String?, versions: Collection<String>): String? {
        val newest = latest?.let(::normalize)?.takeIf { it.isNotEmpty() && !it.equals("N/A", ignoreCase = true) }
            ?: return null
        val here = versions.map(::normalize).filter { it.isNotEmpty() }
        if (here.isEmpty()) return null
        return if (here.any { it.equals(newest, ignoreCase = true) }) null else newest
    }

    /** "v0.9.6 is available": a version that starts with a digit gets its `v`, a name ("Final") does not. */
    fun line(available: String): String =
        (if (available.firstOrNull()?.isDigit() == true) "v$available" else available) + " is available"

    private fun normalize(version: String): String = version.trim().removePrefix("v").removePrefix("V").trim()
}

/** One part of a game: `Week 1` of Fetish Locator, `Part 3` of Thief of Hearts. */
data class GameSegment(
    val label: String,
    val order: Int?,
    val versions: List<GameVersion> = emptyList(),
)

/**
 * One version of a game, and every copy of that version on this device.
 *
 * [latestKnown] is the newer version a source knows of that the game has
 * in none of its folders ([GroupedGame.availableUpdate]; Pythia keeps the
 * same fact per entry as `available_updates[]`, written by its F95 update
 * check). It is the GAME's fact, so every version of the game carries it,
 * and it is null both until something has looked and when the newest
 * version is already here: a row for `v0.8` does not offer `v0.9` while
 * `v0.9` is the row beneath it.
 */
data class GameVersion(
    val version: String,
    val copies: List<GameCopy> = emptyList(),
    val latestKnown: String? = null,
) {
    /** Whether a source knows of a version of this game that is not here. */
    val updateAvailable: Boolean get() = latestKnown != null

    /**
     * The copy this version plays as: an installed one, else the first
     * found. Pythia picks the most recently played or installed variant
     * and falls back to the most recently detected; a folder scan has no
     * such timestamps in hand, so the fallback is discovery order, which
     * is name order and therefore the same on every scan.
     */
    val playable: GameCopy? get() = copies.firstOrNull { it.installed } ?: copies.firstOrNull()

    companion object {
        /**
         * Newest first. Dotted components compare as numbers where they
         * are numbers (`0.10` is newer than `0.9`, which a string compare
         * gets backwards) and as text where they are not (`0.8.3b` after
         * `0.8.3`); a missing component is zero, so `1.2` is newer than
         * `1.1.9`. A version nobody could derive a number from ("") is the
         * oldest, because a folder that says nothing about its version
         * must not outrank one that does.
         */
        val NEWEST_FIRST: Comparator<GameVersion> = Comparator { a, b -> compareVersions(b.version, a.version) }

        internal fun compareVersions(a: String, b: String): Int {
            if (a.isEmpty() || b.isEmpty()) return a.length.compareTo(b.length)
            val left = a.split('.', '_', '-')
            val right = b.split('.', '_', '-')
            for (index in 0 until maxOf(left.size, right.size)) {
                val l = left.getOrElse(index) { "0" }
                val r = right.getOrElse(index) { "0" }
                val ln = l.takeWhile { it.isDigit() }
                val rn = r.takeWhile { it.isDigit() }
                val byNumber = (ln.toLongOrNull() ?: 0L).compareTo(rn.toLongOrNull() ?: 0L)
                if (byNumber != 0) return byNumber
                val byText = l.dropWhile { it.isDigit() }.compareTo(r.dropWhile { it.isDigit() })
                if (byText != 0) return byText
            }
            return 0
        }
    }
}

/** One install of one version: where it is, what it carries, where it came from. */
data class GameCopy(
    val path: String,
    val mods: List<String> = emptyList(),
    val language: String? = null,
    val platforms: List<String> = emptyList(),
    val source: String? = null,
    val installed: Boolean = true,
)

/**
 * How strongly an entry's identity is known. Used to decide whether a
 * cross-store link is certain (auto-merged) or suggested (user confirms).
 */
enum class IdentityConfidence {
    /** A stable id (Steam appid, GOG id, IGDB id, DLsite code, F95 thread) makes this certain. */
    CERTAIN,
    /** Normalized title + developer + year match; user must confirm. */
    SUGGESTED,
}

/**
 * A stable identity for a game across stores and local folders.
 * Used by [LibraryGrouping] to fold entries into one [GroupedGame].
 */
sealed interface GameIdentity {
    /** The primary key used for grouping. */
    val key: String

    /** Human-readable label for the identity source. */
    val sourceLabel: String

    /** Confidence level of this identity. */
    val confidence: IdentityConfidence

    /** All store ids this identity knows about (for CERTAIN identities). */
    val knownStoreIds: Set<String>

    data class BySteamAppId(val appId: Int, override val knownStoreIds: Set<String> = emptySet()) : GameIdentity {
        override val key = "steam:$appId"
        override val sourceLabel = "Steam"
        override val confidence = IdentityConfidence.CERTAIN
    }

    data class ByGogId(val id: String, override val knownStoreIds: Set<String> = emptySet()) : GameIdentity {
        override val key = "gog:$id"
        override val sourceLabel = "GOG"
        override val confidence = IdentityConfidence.CERTAIN
    }

    data class ByEpicId(val namespace: String, val id: String, override val knownStoreIds: Set<String> = emptySet()) : GameIdentity {
        override val key = "epic:$namespace:$id"
        override val sourceLabel = "Epic"
        override val confidence = IdentityConfidence.CERTAIN
    }

    data class ByAmazonAsin(val asin: String, override val knownStoreIds: Set<String> = emptySet()) : GameIdentity {
        override val key = "amazon:$asin"
        override val sourceLabel = "Amazon"
        override val confidence = IdentityConfidence.CERTAIN
    }

    data class ByItchSlug(val slug: String, override val knownStoreIds: Set<String> = emptySet()) : GameIdentity {
        override val key = "itch:$slug"
        override val sourceLabel = "itch.io"
        override val confidence = IdentityConfidence.CERTAIN
    }

    data class ByDlsiteCode(val rjCode: String, override val knownStoreIds: Set<String> = emptySet()) : GameIdentity {
        override val key = "dlsite:$rjCode"
        override val sourceLabel = "DLsite"
        override val confidence = IdentityConfidence.CERTAIN
    }

    data class ByIgdbId(val igdbId: Long, override val knownStoreIds: Set<String> = emptySet()) : GameIdentity {
        override val key = "igdb:$igdbId"
        override val sourceLabel = "IGDB"
        override val confidence = IdentityConfidence.CERTAIN
    }

    data class BySteamGridDbId(val id: Int, override val knownStoreIds: Set<String> = emptySet()) : GameIdentity {
        override val key = "steamgriddb:$id"
        override val sourceLabel = "SteamGridDB"
        override val confidence = IdentityConfidence.CERTAIN
    }

    data class ByF95Thread(val threadId: Long) : GameIdentity {
        override val key = "f95:$threadId"
        override val sourceLabel = "F95zone"
        override val confidence = IdentityConfidence.CERTAIN
        override val knownStoreIds = emptySet()
    }

    /** Weak identity from normalized title + developer + year. Never auto-merges unless confirmed by user. */
    data class ByTitleDeveloperYear(
        val titleKey: String,
        val developerKey: String,
        val releaseYear: String?,
        val confidence: IdentityConfidence = IdentityConfidence.SUGGESTED,
    ) : GameIdentity {
        override val key = "titledevyear:$titleKey|$developerKey|${releaseYear ?: "unknown"}"
        override val sourceLabel = "Title match"
        override val knownStoreIds = emptySet()
    }
}

/**
 * One ownership of a game: a store, a local folder, or an F95 thread.
 * Collected on [GroupedGame] to show "Owned on Steam and GOG".
 */
sealed interface Ownership {
    /** Short label for the ownership (e.g. "Steam", "GOG", "Local folder"). */
    val label: String

    /** The stable id this ownership represents, if any. */
    val storeId: String?

    data class Steam(val appId: Int) : Ownership {
        override val label = "Steam"
        override val storeId = "steam:$appId"
    }
    data class GOG(val id: String) : Ownership {
        override val label = "GOG"
        override val storeId = "gog:$id"
    }
    data class Epic(val namespace: String, val id: String) : Ownership {
        override val label = "Epic"
        override val storeId = "epic:$namespace:$id"
    }
    data class Amazon(val asin: String) : Ownership {
        override val label = "Amazon"
        override val storeId = "amazon:$asin"
    }
    data class Itch(val slug: String) : Ownership {
        override val label = "itch.io"
        override val storeId = "itch:$slug"
    }
    data class DLsite(val rjCode: String) : Ownership {
        override val label = "DLsite"
        override val storeId = "dlsite:$rjCode"
    }
    data class LocalFolder(val path: String) : Ownership {
        override val label = "Local folder"
        override val storeId = null
    }
    data class F95Thread(val threadId: Long) : Ownership {
        override val label = "F95zone"
        override val storeId = "f95:$threadId"
    }
}

/**
 * Computes the [GameIdentity] for a library entry, using its [PcInfo],
 * scraped metadata, and [GameLinksStore] links.
 */
fun LibraryEntry.gameIdentity(
    scrapedMetadata: dev.droidtop.library.consoles.GameMetadataEntity? = null,
    linkedThread: Long? = null,
): GameIdentity {
    // 1. Stable store ids from PcInfo
    val storeId = PcStoreId.parse(pcInfo?.storeId ?: id)
    if (storeId != null) {
        return when (storeId.store) {
            PcStoreId.STEAM -> GameIdentity.BySteamAppId(storeId.id.toIntOrNull() ?: 0)
            PcStoreId.GOG -> GameIdentity.ByGogId(storeId.id)
            "epic" -> {
                val parts = storeId.id.split(':', limit = 2)
                GameIdentity.ByEpicId(parts.first(), parts.getOrElse(1) { "" })
            }
            "amazon" -> GameIdentity.ByAmazonAsin(storeId.id)
            "itch" -> GameIdentity.ByItchSlug(storeId.id)
            "dlsite" -> GameIdentity.ByDlsiteCode(storeId.id)
            else -> GameIdentity.ByTitleDeveloperYear(
                titleKey = GameNaming.nameKey(title),
                developerKey = scrapedMetadata?.developer?.let { GameNaming.nameKey(it) } ?: "",
                releaseYear = scrapedMetadata?.releaseDate?.take(4),
            )
        }
    }

    // 2. IGDB id from scraped metadata
    scrapedMetadata?.let { meta ->
        // The IGDB id is not directly stored in GameMetadataEntity yet.
        // It would come from the scrape's PcGameIds.igdbId.
        // For now, we check if the entry has an igdb-backed scrape.
    }

    // 3. SteamGridDB id from scraped metadata
    // Not directly available in GameMetadataEntity.

    // 4. F95 thread from GameLinksStore
    linkedThread?.let { return GameIdentity.ByF95Thread(it) }

    // 5. DLsite RJ code from scraped links or metadata
    // Check links for DLsite pattern
    val dlsiteLink = scrapedMetadata?.links?.firstOrNull { it.url.contains("dlsite.com", ignoreCase = true) }
        ?: links.firstOrNull { it.url.contains("dlsite.com", ignoreCase = true) }
    dlsiteLink?.let { link ->
        val rjMatch = Regex("RJ\\d{6,}").find(link.url)
        rjMatch?.let { return GameIdentity.ByDlsiteCode(it.value) }
    }

    // 6. Weak identity: normalized title + developer + year
    val developer = scrapedMetadata?.developer ?: developer
    val releaseYear = scrapedMetadata?.releaseDate?.take(4) ?: releaseDate?.take(4)
    return GameIdentity.ByTitleDeveloperYear(
        titleKey = GameNaming.nameKey(title),
        developerKey = developer?.let { GameNaming.nameKey(it) } ?: "",
        releaseYear = releaseYear,
    )
}

/**
 * Computes the [Ownership] set for a game from its entries.
 */
fun List<LibraryEntry>.ownerships(): Set<Ownership> {
    val result = mutableSetOf<Ownership>()
    for (entry in this) {
        val storeId = PcStoreId.parse(entry.pcInfo?.storeId ?: entry.id)
        storeId?.let {
            when (it.store) {
                PcStoreId.STEAM -> result += Ownership.Steam(it.id.toIntOrNull() ?: 0)
                PcStoreId.GOG -> result += Ownership.GOG(it.id)
                "epic" -> {
                    val parts = it.id.split(':', limit = 2)
                    result += Ownership.Epic(parts.first(), parts.getOrElse(1) { "" })
                }
                "amazon" -> result += Ownership.Amazon(it.id)
                "itch" -> result += Ownership.Itch(it.id)
                "dlsite" -> result += Ownership.DLsite(it.id)
            }
        }
        // Local folder (no store id, path is a folder)
        if (storeId == null && entry.id.startsWith("/")) {
            result += Ownership.LocalFolder(entry.id)
        }
        // F95 thread
        entry.f95Thread?.let { result += Ownership.F95Thread(it) }
    }
    return result
}

/**
 * Turning the folders a scan found into games ([GroupedGame]).
 *
 * The logic is Pythia's, ported (see [GameNaming] for the licence note and
 * the sources): derive a name, a version, mods and a language from each
 * folder ([GameNaming.derive]), then fold each folder into the game it
 * belongs to (`pythia/onboarding.py::_merge_version`, `find_candidates`).
 *
 * One deliberate departure, and the corpus is the reason. Pythia matches
 * an existing game by an exact path, a sync marker or a store id, and
 * anything weaker is a SUGGESTION its user accepts or rejects
 * (`find_candidates` returns `{"certain", "suggested"}`). droidtop's scan
 * has nobody to ask, so only names that are equal once punctuation and
 * case are dropped merge on their own; a name that is merely SIMILAR is
 * never merged, and is offered to a person only where one is already
 * choosing ([MissingGames.candidates], [SimilarGames.candidates]). The
 * corpus says why in three lines: `love_of_magic_book1`, `book2` and
 * `book3` are 0.94 similar and are three different games.
 *
 * What a person has said wins over what a name derives: a folder the user
 * made part of another game ([Found.name], docs/SPEC.md 7m "The same
 * game") is grouped under that game's name, which is Pythia's
 * `reconciliation.merge` recorded as the one fact it changes.
 */
object GameGrouping {

    /** One folder a scan found, and what the scan knows about it. */
    data class Found(
        val path: String,
        val source: String? = null,
        val platforms: List<String> = emptyList(),
        val installed: Boolean = true,
        /** The newest version an update source knows of for this folder's game, when something has looked. */
        val latestKnown: String? = null,
        /** The game the user said this folder is (docs/SPEC.md 7m); null is the name the folder derives. */
        val name: String? = null,
    )

    /**
     * Every game in [found], in name order, each with its versions and
     * segments. Folder order does not change the result: a game is keyed
     * by its name, and versions and segments sort themselves.
     */
    fun group(found: List<Found>): List<GroupedGame> {
        val games = LinkedHashMap<String, Builder>()
        for (folder in found.sortedBy { it.path }) {
            val derived = GameNaming.derive(folder.path)
            val name = folder.name?.takeIf { it.isNotBlank() } ?: derived.name
            val key = GameNaming.nameKey(name).ifEmpty { folder.path.lowercase() }
            games.getOrPut(key) { Builder(name) }.merge(derived, folder)
        }
        return games.values.map { it.build() }.sortedBy { it.name.lowercase() }
    }

    /**
     * One game under construction. The merge is Pythia's `_merge_version`:
     * find-or-create the group for this version, then find this exact path
     * inside it and update it in place, or add it -- so re-scanning a
     * library updates what it already knows instead of duplicating it.
     */
    private class Builder(val name: String) {
        private val direct = VersionsBuilder()
        private val segments = LinkedHashMap<String, SegmentBuilder>()

        // The game's, not a folder's: whichever of its folders the update
        // source was asked about, it answered for the game.
        private var latestKnown: String? = null

        fun merge(derived: GameNaming.Derived, folder: Found) {
            if (latestKnown == null) latestKnown = folder.latestKnown
            val copy = GameCopy(
                path = folder.path,
                mods = derived.mods,
                language = derived.language,
                platforms = folder.platforms,
                source = folder.source,
                installed = folder.installed,
            )
            val segment = derived.segment
            val into = if (segment == null) {
                direct
            } else {
                segments.getOrPut(GameNaming.nameKey(segment.label)) { SegmentBuilder(segment) }.versions
            }
            into.merge(derived.version, copy)
        }

        fun build(): GroupedGame {
            val every = direct.versions() + segments.values.flatMap { it.versions.versions() }
            val available = GameUpdates.available(latestKnown, every)
            return GroupedGame(
                name = name,
                versions = direct.build(available),
                segments = segments.values
                    .map { it.build(available) }
                    .sortedWith(compareBy({ it.order ?: Int.MAX_VALUE }, { it.label.lowercase() })),
                latestKnown = latestKnown,
            )
        }

        private class SegmentBuilder(val segment: GameNaming.Segment) {
            val versions = VersionsBuilder()

            fun build(available: String?): GameSegment = GameSegment(segment.label, segment.order, versions.build(available))
        }
    }

    /**
     * Pythia's `_merge_version`, as a small builder: find-or-create the
     * group for this version, then find this exact path inside it and
     * update it in place, or add it. Re-scanning a library therefore
     * updates what droidtop already knows about a folder instead of
     * listing it twice.
     */
    private class VersionsBuilder {
        private val groups = LinkedHashMap<String, MutableList<GameCopy>>()

        fun merge(version: String, copy: GameCopy) {
            val copies = groups.getOrPut(version) { mutableListOf() }
            val existing = copies.indexOfFirst { it.path == copy.path }
            if (existing >= 0) copies[existing] = copy else copies += copy
        }

        fun versions(): Set<String> = groups.keys

        /** Newest first, which is the order everything downstream relies on; [available] is the game's update, on each. */
        fun build(available: String?): List<GameVersion> = groups
            .map { (version, copies) -> GameVersion(version, copies.toList(), available) }
            .sortedWith(GameVersion.NEWEST_FIRST)
    }
}
