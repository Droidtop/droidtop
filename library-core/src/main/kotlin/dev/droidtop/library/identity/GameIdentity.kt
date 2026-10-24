package dev.droidtop.library.identity

import java.util.UUID

/*
 * Which real game a library entry is (docs/SPEC.md 7h, "One identity model"; the matching and metadata proposal,
 * Droidtop/tracker#318, approved 2026-10-24). Every game has a stable local uid, minted at first sight, and a set of
 * claims: a store id, a content hash, a database id, a plugin's thread. A path is only where the game lives on a device
 * (a locator), never what it is, so a ROM that moves or a game seen on another device keeps its identity. Claims are
 * append-only: one is retired by naming what replaced it, never deleted.
 *
 * This is the model and its rules, pure, with the storage behind [IdentityStore]. Nothing reads a uid yet: this step
 * adds identities beside today's path keys, and the readers move to them one at a time.
 */

/** How much a claim says: a strong one names the game for sure; a weak one only suggests. */
enum class ClaimStrength { STRONG, MEDIUM, WEAK }

/** The claim namespaces and the strength each carries. */
object ClaimNamespaces {
    /** A store's id ("steam:440", "gog:1207658924"), from the store's install row. */
    const val STORE = "store"

    /** A ROM's content hash ("rom:snes:md5:<hex>"), from the file. */
    const val HASH = "hash"

    /** RetroAchievements' hash of the ROM. */
    const val RA = "ra"

    /** An id the person picked (the review queue, Fix match). */
    const val MANUAL = "manual"

    /** A compact title the person's pick taught ("sevendaywyanderegirl" -> an id). */
    const val ALIAS = "alias"

    const val IGDB = "igdb"
    const val SGDB = "sgdb"
    const val LUTRIS = "lutris"

    /** A ScreenScraper record. */
    const val SS = "ss"

    /** A plugin's own item ("<plugin>:<id>", an F95zone thread). */
    const val THREAD = "thread"

    /** The compact title the parser read: only ever a suggestion. */
    const val TITLE = "title"

    private val STRONG = setOf(STORE, HASH, RA, MANUAL, ALIAS)
    private val MEDIUM = setOf(IGDB, SGDB, LUTRIS, SS, THREAD)

    fun strength(namespace: String): ClaimStrength = when (namespace) {
        in STRONG -> ClaimStrength.STRONG
        in MEDIUM -> ClaimStrength.MEDIUM
        else -> ClaimStrength.WEAK
    }
}

/** One claim about which game something is. */
data class Claim(val namespace: String, val value: String) {
    val strength: ClaimStrength get() = ClaimNamespaces.strength(namespace)

    /** "store:steam:440". */
    override fun toString(): String = "$namespace:$value"

    companion object {
        /** A store id as droidtop carries it ([dev.droidtop.library.PcInfo.storeId], "steam:440"); null when blank. */
        fun store(storeId: String?): Claim? = storeId?.trim()?.takeIf { it.contains(':') && !it.endsWith(':') }?.let { Claim(ClaimNamespaces.STORE, it) }

        /** A ROM's MD5 on [system] ("snes"), lower-case hex. */
        fun romMd5(system: String, md5: String): Claim? =
            md5.trim().lowercase().takeIf { it.matches(Regex("[0-9a-f]{32}")) && system.isNotBlank() }
                ?.let { Claim(ClaimNamespaces.HASH, "rom:${system.trim().lowercase()}:md5:$it") }
    }
}

/** A claim as kept: who minted it, when, and what replaced it, if anything did. */
data class StoredClaim(val claim: Claim, val origin: String, val sinceMs: Long, val supersededBy: String? = null) {
    val live: Boolean get() = supersededBy == null
}

/**
 * A game's sync key: its highest claim, in the order store > hash > manual > alias > igdb > sgdb, else its uid
 * ("uid:<uid>"). Computed, never stored, so a game gaining a better claim re-keys nothing that points at its uid.
 */
object GameKeys {
    private val ORDER = listOf(ClaimNamespaces.STORE, ClaimNamespaces.HASH, ClaimNamespaces.MANUAL, ClaimNamespaces.ALIAS, ClaimNamespaces.IGDB, ClaimNamespaces.SGDB)

    fun of(uid: String, claims: List<StoredClaim>): String {
        val live = claims.filter { it.live }
        for (namespace in ORDER) {
            // Within a namespace the oldest live claim, so the key stays put while a newer one is added beside it.
            live.filter { it.claim.namespace == namespace }.minWithOrNull(compareBy({ it.sinceMs }, { it.claim.value }))?.let { return it.claim.toString() }
        }
        return "uid:$uid"
    }

    /** Whether the key is a strong one: only then do devices merge on it, and saves follow it on their own. */
    fun isStrong(key: String): Boolean = ClaimNamespaces.strength(key.substringBefore(':')) == ClaimStrength.STRONG
}

/** Where identities are kept: the database on the device, a map in the tests. */
interface IdentityStore {
    suspend fun uidAt(device: String, locator: String): String?
    suspend fun uidsWithLiveClaim(claim: Claim): List<String>
    suspend fun claimsOf(uid: String): List<StoredClaim>
    suspend fun mint(uid: String, kind: String?, nowMs: Long)
    suspend fun putLocator(device: String, locator: String, uid: String)
    suspend fun addClaim(uid: String, claim: StoredClaim)

    /** Marks [uid] missing since [sinceMs] when it is not marked yet; null takes the mark off (Droidtop/tracker#318 §7.3). */
    suspend fun missingSince(uid: String, sinceMs: Long?)
}

/**
 * Registers what a library entry is, keeping its identity across moves and devices. Never folds two games on a weak
 * claim, and never deletes a claim.
 */
class GameIdentities(
    private val store: IdentityStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val newUid: () -> String = { UUID.randomUUID().toString() },
) {
    /**
     * The uid of what lives at [locator] on [device] (a path, or a store row), with [presented] claims added to it.
     * A locator seen before keeps its uid. A new one that presents a live strong claim another game already holds is
     * that game moved or copied: it joins that uid (the oldest, should several hold it). Otherwise a uid is minted.
     */
    suspend fun register(device: String, locator: String, kind: String?, presented: List<Claim>, origin: String): String {
        val claims = presented.distinct()
        val uid = store.uidAt(device, locator)
            ?: reattach(claims)?.also { store.putLocator(device, locator, it) }
            ?: newUid().also {
                store.mint(it, kind, now())
                store.putLocator(device, locator, it)
            }
        addClaims(uid, claims, origin)
        return uid
    }

    /**
     * The library's view of [present] and [missing] locators on [device] (Droidtop/tracker#318 §7.3; owner,
     * 2026-10-24: missing games are kept, never deleted automatically): a game none of whose locators is present is
     * marked missing from now, a mark it already has keeps its first date, and a game seen again loses the mark.
     * Nothing is deleted. A locator never registered is left alone.
     */
    suspend fun reconcileMissing(device: String, present: Collection<String>, missing: Collection<String>) {
        val here = present.mapNotNull { store.uidAt(device, it) }.toSet()
        here.forEach { store.missingSince(it, null) }
        missing.mapNotNull { store.uidAt(device, it) }.distinct().filter { it !in here }.forEach { store.missingSince(it, now()) }
    }

    /** Adds [claims] to [uid] that it does not hold yet; one it holds already is left as it was. */
    suspend fun addClaims(uid: String, claims: List<Claim>, origin: String) {
        if (claims.isEmpty()) return
        val held = store.claimsOf(uid).filter { it.live }.map { it.claim }.toSet()
        for (claim in claims) {
            if (claim !in held) store.addClaim(uid, StoredClaim(claim, origin, now()))
        }
    }

    private suspend fun reattach(claims: List<Claim>): String? {
        for (claim in claims.filter { it.strength == ClaimStrength.STRONG }) {
            store.uidsWithLiveClaim(claim).minOrNull()?.let { return it }
        }
        return null
    }

    companion object {
        /** The locator device for this device's own paths; sync gives each device its own. */
        const val THIS_DEVICE = "this"

        /**
         * Whether two games' claims contradict each other: a namespace where both hold a live strong or medium claim
         * and those claims differ (steam:440 and steam:620). Such games are never folded into one, whatever their
         * titles say. Pure.
         */
        fun conflict(a: Collection<Claim>, b: Collection<Claim>): Boolean {
            val byNamespace = b.filter { it.strength != ClaimStrength.WEAK }.groupBy({ it.namespace }, { it.value })
            return a.filter { it.strength != ClaimStrength.WEAK }.any { claim ->
                val other = byNamespace[claim.namespace] ?: return@any false
                // A store claim names the store too: steam:440 and gog:1 are different stores, not a contradiction.
                if (claim.namespace == ClaimNamespaces.STORE) {
                    val store = claim.value.substringBefore(':')
                    other.any { it.substringBefore(':') == store && it != claim.value }
                } else {
                    claim.value !in other
                }
            }
        }
    }
}
