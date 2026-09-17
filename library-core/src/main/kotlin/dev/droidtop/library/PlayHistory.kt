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
}

object NoOpPlayHistoryStore : PlayHistoryStore {
    override suspend fun recordPlay(id: String, epochMs: Long) {}
    override suspend fun getAll(ids: Collection<String>): Map<String, PlayHistoryRecord> = emptyMap()
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
}

object NoOpFavoritesStore : FavoritesStore {
    override suspend fun setFavorite(id: String, favorite: Boolean) {}
    override suspend fun getAll(ids: Collection<String>): Set<String> = emptySet()
}
