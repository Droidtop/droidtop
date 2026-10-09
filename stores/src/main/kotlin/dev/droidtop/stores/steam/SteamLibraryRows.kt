package dev.droidtop.stores.steam

import android.content.Context
import dev.droidtop.library.stores.StoreHolding

/**
 * The Steam rows the library lists, each with how the account holds it: the
 * one place [SteamStore.games] and the sync's summary line count from, so the
 * store page and scan.log give the same numbers (docs/SPEC.md 7g, "Stores").
 */
internal object SteamLibraryRows {
    data class Row(val app: SteamApp, val holding: StoreHolding, val install: AppInfo?)

    /** Reads the database and two small files: not for the main thread. */
    suspend fun read(context: Context, db: SteamDatabase): List<Row> {
        val installs = db.installs().all().filter { it.isDownloaded && it.installPath.isNotBlank() }.associateBy { it.id }
        // Whose licence grants each game (SteamOwnership): the account's own
        // (paid, listed by Steam, or free and installed), free and not listed
        // (apart), a family member's (apart), or only the free sub or an
        // ended licence (not listed).
        val ownership = SteamOwnership.of(db.licenses().all(), accountId(context))
        val dlcByBase = db.apps().dlcKinds().groupBy({ it.base }, { it.id })
        val answer = SteamOwnedGames.load(context)
        val owned = db.apps().owned(SteamLibrarySync.PLAYABLE_TYPES).mapNotNull { app ->
            val status = ownership.statusOf(app.id, dlcByBase[app.id].orEmpty(), listed = app.id in answer.listed, installed = app.id in installs)
            holdingOf(status)?.let { Row(app, it, installs[app.id]) }
        }
        val ownedIds = owned.mapTo(HashSet()) { it.app.id }
        // Installed and no longer owned (signed out, a licence gone) still shows, with its files.
        val installedOnly = installs.keys.filter { it !in ownedIds }
            .mapNotNull { db.apps().find(it) }
            // A DLC's own install row is not a game of its own.
            .filter { SteamLibrarySync.isLibraryGame(it, keepInstalledKinds = true) }
            .map { Row(it, StoreHolding.OWNED, installs[it.id]) }
        return (owned + installedOnly).filter { it.app.name.isNotBlank() }
    }

    /** The account id the licences name as their owner, from the kept sign-in; null when there is none. */
    fun accountId(context: Context): Int? =
        SteamCredentials.load(context)?.steamId64?.takeIf { it != 0L }?.let { (it and 0xFFFFFFFFL).toInt() }

    data class DlcCount(val held: Int, val paid: Int)

    /**
     * The DLC whose base game is one of [ownGames]: [DlcCount.held] those any
     * own live licence grants, [DlcCount.paid] those a paid one grants. What
     * the store page shows as the account's DLC is [DlcCount.held].
     */
    fun dlcOfOwnGames(kinds: List<SteamAppKind>, ownership: SteamOwnership, ownGames: Set<Int>): DlcCount {
        val dlc = kinds.filter { it.type == AppType.dlc.code && it.base in ownGames }
        return DlcCount(dlc.count { it.id in ownership.paid || it.id in ownership.free }, dlc.count { it.id in ownership.paid })
    }

    private fun holdingOf(status: SteamOwnership.Status): StoreHolding? = when (status) {
        SteamOwnership.Status.OWN -> StoreHolding.OWNED
        SteamOwnership.Status.FREE -> StoreHolding.FREE
        SteamOwnership.Status.FAMILY -> StoreHolding.FAMILY
        SteamOwnership.Status.NONE -> null
    }
}
