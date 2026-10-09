package dev.droidtop.library

/**
 * Real, persisted play-history record for one [LibraryEntry.id] --
 * last-played timestamp and how many times it's actually been launched.
 * Deliberately does NOT track real playtime duration ([LibraryEntry.playtimeSeconds]
 * stays untouched, always 0) -- that needs foreground/process-lifecycle
 * observation, a genuinely different mechanism per launch path (an Intent
 * launch, a container `exec()`, a generic app-open all have different real
 * ways -- or no way at all -- to detect when the user actually stopped
 * playing), not something this same change should also half-build.
 */
data class PlayHistoryRecord(val lastPlayedEpochMs: Long, val playCount: Int)

/**
 * [Library]'s own play-history dependency, kept as a plain interface (not
 * a direct Room/Context dependency on [Library] itself) so [Library] stays
 * constructible with fake providers in a plain JVM unit test
 * (`LibraryTest.kt`) -- same reasoning already established elsewhere in
 * this codebase for keeping core logic Android/Context-free
 * (`GameLaunchStrategyResolver`'s own plain-boolean-params design).
 * [RoomPlayHistoryStore] (`PlayHistoryDatabase.kt`) is the real,
 * on-device implementation; [NoOpPlayHistoryStore] is [Library]'s default
 * so every existing single-argument `Library(providers)` call site (tests
 * included) keeps compiling unchanged.
 */
interface PlayHistoryStore {
    suspend fun recordPlay(id: String, epochMs: Long)
    suspend fun getAll(ids: Collection<String>): Map<String, PlayHistoryRecord>

    /**
     * Carries [fromId]'s history onto [toId] when a missing game is
     * folded into the game that replaced it (docs/SPEC.md 7g,
     * [Library.replaceMissing]). The two are the same game at two paths,
     * so the counts ADD and the later last-played wins -- the same
     * arithmetic Pythia's `record_ownership` does when a new copy of a
     * game it already tracks is onboarded.
     */
    suspend fun moveTo(fromId: String, toId: String)
}

object NoOpPlayHistoryStore : PlayHistoryStore {
    override suspend fun recordPlay(id: String, epochMs: Long) {}
    override suspend fun getAll(ids: Collection<String>): Map<String, PlayHistoryRecord> = emptyMap()
    override suspend fun moveTo(fromId: String, toId: String) {}
}

/**
 * Favourites for the entries that are not console ROMs. A ROM's favourite
 * is ES-DE metadata ([dev.droidtop.library.consoles.GameMetadataEntity],
 * written back to `gamelist.xml`) and stays there; an engine game, a PC
 * game or an app has no gamelist, so its favourite lives here, keyed by
 * [LibraryEntry.id] like play history. Until this existed the gamelist's
 * "X FAVORITE" did nothing for 151 of the rig's 158 games (build 549):
 * [Library.toggleFavorite] answered "not applicable" for every kind but
 * one.
 */
interface FavoritesStore {
    suspend fun setFavorite(id: String, favorite: Boolean)
    suspend fun getAll(ids: Collection<String>): Set<String>

    /**
     * Carries a favourite from the missing game to the one that replaced
     * it (see [PlayHistoryStore.moveTo]). A favourite is a statement
     * about the GAME, so it survives the game changing folder.
     */
    suspend fun moveTo(fromId: String, toId: String)
}

object NoOpFavoritesStore : FavoritesStore {
    override suspend fun setFavorite(id: String, favorite: Boolean) {}
    override suspend fun getAll(ids: Collection<String>): Set<String> = emptySet()
    override suspend fun moveTo(fromId: String, toId: String) {}
}

/**
 * What the user has said about one entry's game, and what its update
 * sources last answered (docs/SPEC.md 7g and 7m).
 */
data class GameLinks(
    /** The game the user made this folder part of; null is the name the folder derives. */
    val gameName: String? = null,
    /** The sources' records the user linked this game to, one per source, by source key. */
    val sources: List<SourceLink> = emptyList(),
) {
    /** The link to [source], when there is one. */
    fun link(source: String): SourceLink? = sources.firstOrNull { it.source == source }

    /** The newest version a linked source gives, when one has one to give (sources in key order, so it is stable). */
    val latestKnown: String?
        get() = sources.sortedBy { it.source }.firstNotNullOfOrNull { it.answer?.takeUnless { a -> a.gone }?.version }
}

/**
 * The library's own store of [GameLinks], keyed by [LibraryEntry.id]
 * like play history and favourites, and joined onto every list the
 * library hands out the same way.
 */
interface GameLinksStore {
    suspend fun getAll(ids: Collection<String>): Map<String, GameLinks>

    /**
     * [ids] are all the game called [name] from now on (docs/SPEC.md 7m, "The
     * same game"; 7n, the person's own title), or go back to the name the
     * folder derives when [name] is null.
     */
    suspend fun setGameName(ids: Collection<String>, name: String?)

    /** Links every one of [ids] to [externalId] at [source], or unlinks them from [source] when it is null. */
    suspend fun setSourceLink(ids: Collection<String>, source: String, externalId: String?)

    /**
     * Carries [fromId]'s links to [toId] when a missing game is folded
     * into the game that replaced it ([Library.replaceMissing]). Only into
     * an empty place: a game that already has its own name, or its own
     * link to a source, keeps it.
     */
    suspend fun moveTo(fromId: String, toId: String)

    /** Every record any entry links, with what its source last said about it. */
    suspend fun linkedSources(): Map<SourceKey, SourceAnswer?>

    /** The entries that link [key]. */
    suspend fun idsLinkedTo(key: SourceKey): List<String>

    suspend fun saveAnswer(key: SourceKey, answer: SourceAnswer)
}

object NoOpGameLinksStore : GameLinksStore {
    override suspend fun getAll(ids: Collection<String>): Map<String, GameLinks> = emptyMap()
    override suspend fun setGameName(ids: Collection<String>, name: String?) {}
    override suspend fun setSourceLink(ids: Collection<String>, source: String, externalId: String?) {}
    override suspend fun moveTo(fromId: String, toId: String) {}
    override suspend fun linkedSources(): Map<SourceKey, SourceAnswer?> = emptyMap()
    override suspend fun idsLinkedTo(key: SourceKey): List<String> = emptyList()
    override suspend fun saveAnswer(key: SourceKey, answer: SourceAnswer) {}
}
