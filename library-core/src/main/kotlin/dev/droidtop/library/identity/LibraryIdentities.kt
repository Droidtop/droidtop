package dev.droidtop.library.identity

import android.content.Context
import android.util.Log
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryIndexStore
import dev.droidtop.library.LibrarySlice
import dev.droidtop.library.consoles.GameClaimEntity
import dev.droidtop.library.consoles.GameEntity
import dev.droidtop.library.consoles.GameIdentityDao
import dev.droidtop.library.consoles.GameLocatorEntity
import dev.droidtop.library.consoles.RomDatabase
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** [IdentityStore] in the database (RomDatabase's `games`, `game_claims` and `game_locators`). */
class RoomIdentityStore(private val dao: GameIdentityDao) : IdentityStore {
    override suspend fun uidAt(device: String, locator: String): String? = dao.uidAt(device, locator)

    override suspend fun uidsWithLiveClaim(claim: Claim): List<String> = dao.uidsWithLiveClaim(claim.namespace, claim.value)

    override suspend fun claimsOf(uid: String): List<StoredClaim> =
        dao.claimsOf(uid).map { StoredClaim(Claim(it.namespace, it.value), it.origin, it.since, it.supersededBy) }

    override suspend fun mint(uid: String, kind: String?, nowMs: Long) = dao.insertGame(GameEntity(uid, kind, nowMs))

    override suspend fun putLocator(device: String, locator: String, uid: String) = dao.putLocator(GameLocatorEntity(device, locator, uid))

    override suspend fun addClaim(uid: String, claim: StoredClaim) =
        dao.insertClaim(GameClaimEntity(uid, claim.claim.namespace, claim.claim.value, claim.origin, claim.sinceMs, claim.supersededBy))
}

/**
 * The library's games registered as identities (Droidtop/tracker#318): every entry a scan saves gets a uid by its path
 * on this device, with the claims the entry carries (its store id). A scrape adds what it learns (a ROM's hash) through
 * [claim]. Nothing reads a uid yet; this fills them in beside the path keys.
 */
object LibraryIdentities {
    private const val TAG = "droidtop.Identity"

    /** What was registered this process, by entry id, with the claims it was registered with: saves repeat a lot. */
    private val registered = ConcurrentHashMap<String, List<Claim>>()

    @Volatile private var identities: GameIdentities? = null

    fun of(context: Context): GameIdentities =
        identities ?: synchronized(this) {
            identities ?: GameIdentities(RoomIdentityStore(RomDatabase.get(context).identityDao())).also { identities = it }
        }

    /** The claims an entry carries by itself: its store id. Pure. */
    fun claimsOf(entry: LibraryEntry): List<Claim> = listOfNotNull(Claim.store(entry.pcInfo?.storeId))

    /** Registers [entries] not registered with these claims yet this process. A failure is logged, never thrown. */
    suspend fun register(context: Context, entries: List<LibraryEntry>) {
        val identities = of(context)
        for (entry in entries) {
            if (entry.missing) continue
            val claims = claimsOf(entry)
            if (registered[entry.id] == claims) continue
            try {
                identities.register(GameIdentities.THIS_DEVICE, entry.id, entry.kind.name, claims, origin = "library")
                registered[entry.id] = claims
            } catch (e: Exception) {
                Log.w(TAG, "could not register ${entry.id}: ${e.message}")
                return // the database is not answering: the next save tries again
            }
        }
    }

    /** Adds [claims] a scrape learned to the game at [entryId] (registering it when it is not yet). Never throws. */
    suspend fun claim(context: Context, entryId: String, kind: String?, claims: List<Claim>, origin: String) {
        if (claims.isEmpty()) return
        try {
            of(context).register(GameIdentities.THIS_DEVICE, entryId, kind, claims, origin)
        } catch (e: Exception) {
            Log.w(TAG, "could not record ${claims.joinToString()} for $entryId: ${e.message}")
        }
    }
}

/**
 * A [LibraryIndexStore] that registers the identities of what it saves; everything else is [inner]'s. Registering
 * runs after the save, on its own, one save at a time, so a first scan of thousands of games is not slowed by it.
 */
class IdentityRegisteringIndexStore(
    private val inner: LibraryIndexStore,
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : LibraryIndexStore by inner {
    private val turn = Mutex()

    override suspend fun save(providerKey: String, slice: LibrarySlice) {
        inner.save(providerKey, slice)
        val entries = slice.entries()
        scope.launch { turn.withLock { LibraryIdentities.register(context, entries) } }
    }
}
