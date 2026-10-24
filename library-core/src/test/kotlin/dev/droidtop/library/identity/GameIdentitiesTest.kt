package dev.droidtop.library.identity

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which real game a library entry is: a uid, claims and locators (Droidtop/tracker#318, "One identity model"). */
class GameIdentitiesTest {
    /** The store as maps, the way the database holds it. */
    private class Memory : IdentityStore {
        val games = LinkedHashMap<String, String?>()
        val locators = LinkedHashMap<Pair<String, String>, String>()
        val claims = mutableListOf<Pair<String, StoredClaim>>()

        override suspend fun uidAt(device: String, locator: String) = locators[device to locator]
        override suspend fun uidsWithLiveClaim(claim: Claim) = claims.filter { it.second.claim == claim && it.second.live }.map { it.first }.distinct()
        override suspend fun claimsOf(uid: String) = claims.filter { it.first == uid }.map { it.second }
        override suspend fun mint(uid: String, kind: String?, nowMs: Long) {
            games.putIfAbsent(uid, kind)
        }
        override suspend fun putLocator(device: String, locator: String, uid: String) {
            locators[device to locator] = uid
        }
        override suspend fun addClaim(uid: String, claim: StoredClaim) {
            claims += uid to claim
        }
    }

    private var clock = 1_000L
    private var next = 0
    private val store = Memory()
    private val identities = GameIdentities(store, now = { clock++ }, newUid = { "u" + (++next) })
    private val here = GameIdentities.THIS_DEVICE

    @Test
    fun `a path seen again keeps its uid, and a new path is a new game`() = runBlocking {
        val a = identities.register(here, "/roms/snes/Game.sfc", "CONSOLE_ROM", emptyList(), "library")
        assertEquals(a, identities.register(here, "/roms/snes/Game.sfc", "CONSOLE_ROM", emptyList(), "library"))
        assertNotEquals(a, identities.register(here, "/roms/snes/Other.sfc", "CONSOLE_ROM", emptyList(), "library"))
        assertEquals(2, store.games.size)
    }

    @Test
    fun `a ROM moved or renamed keeps its identity through its hash`() = runBlocking {
        val hash = Claim.romMd5("snes", "0123456789ABCDEF0123456789ABCDEF")!!
        val before = identities.register(here, "/roms/snes/Game.sfc", "CONSOLE_ROM", listOf(hash), "scrape")
        val after = identities.register(here, "/roms/snes/Game (USA).sfc", "CONSOLE_ROM", listOf(hash), "scrape")
        assertEquals(before, after)
        assertEquals(1, store.games.size)
        assertEquals("the claim is held once", 1, store.claims.count { it.second.claim == hash })
    }

    @Test
    fun `a weak or medium claim never joins two games`() = runBlocking {
        val title = Claim(ClaimNamespaces.TITLE, "sevendaywyanderegirl")
        val igdb = Claim(ClaimNamespaces.IGDB, "1942")
        val a = identities.register(here, "/games/SevenDayWYandereGirlV0.80", "RENPY", listOf(title, igdb), "library")
        val b = identities.register(here, "/games/Seven Day W Yandere Girl v0.81", "RENPY", listOf(title, igdb), "library")
        assertNotEquals(a, b)
    }

    @Test
    fun `claims are added, never replaced or repeated`() = runBlocking {
        val uid = identities.register(here, "/games/Game", "RENPY", emptyList(), "library")
        identities.addClaims(uid, listOf(Claim(ClaimNamespaces.STORE, "steam:440")), "library")
        identities.addClaims(uid, listOf(Claim(ClaimNamespaces.STORE, "steam:440"), Claim(ClaimNamespaces.IGDB, "7")), "scrape")
        assertEquals(listOf("store:steam:440", "igdb:7"), store.claimsOf(uid).map { it.claim.toString() })
    }

    @Test
    fun `the sync key is the best claim and never the path`() {
        fun held(vararg claims: String) = claims.mapIndexed { i, c -> StoredClaim(Claim(c.substringBefore(':'), c.substringAfter(':')), "x", i.toLong()) }
        assertEquals("uid:u9", GameKeys.of("u9", emptyList()))
        assertEquals("igdb:7", GameKeys.of("u9", held("igdb:7", "title:game")))
        assertEquals("hash:rom:snes:md5:aa", GameKeys.of("u9", held("igdb:7", "hash:rom:snes:md5:aa")))
        assertEquals("store:steam:440", GameKeys.of("u9", held("hash:rom:snes:md5:aa", "store:steam:440", "manual:igdb:7")))
        val retired = listOf(StoredClaim(Claim(ClaimNamespaces.STORE, "steam:1"), "x", 0, supersededBy = "store:steam:2"))
        assertEquals("a retired claim is no longer the key", "uid:u9", GameKeys.of("u9", retired))
        assertTrue(GameKeys.isStrong("store:steam:440"))
        assertFalse(GameKeys.isStrong("igdb:7"))
        assertFalse(GameKeys.isStrong("uid:u9"))
    }

    @Test
    fun `games whose ids contradict are never one game`() {
        val steam440 = Claim(ClaimNamespaces.STORE, "steam:440")
        val steam620 = Claim(ClaimNamespaces.STORE, "steam:620")
        val gog = Claim(ClaimNamespaces.STORE, "gog:1")
        assertTrue(GameIdentities.conflict(listOf(steam440), listOf(steam620)))
        assertFalse("another store is not a contradiction", GameIdentities.conflict(listOf(steam440), listOf(gog)))
        assertFalse(GameIdentities.conflict(listOf(steam440), listOf(steam440, gog)))
        assertTrue(GameIdentities.conflict(listOf(Claim(ClaimNamespaces.IGDB, "1")), listOf(Claim(ClaimNamespaces.IGDB, "2"))))
        assertFalse("titles never contradict", GameIdentities.conflict(listOf(Claim(ClaimNamespaces.TITLE, "a")), listOf(Claim(ClaimNamespaces.TITLE, "b"))))
    }

    @Test
    fun `claims are read from what droidtop carries`() {
        assertEquals(Claim(ClaimNamespaces.STORE, "steam:440"), Claim.store(" steam:440 "))
        assertNull(Claim.store("steam:"))
        assertNull(Claim.store(null))
        assertEquals("rom:snes:md5:0123456789abcdef0123456789abcdef", Claim.romMd5("SNES", "0123456789ABCDEF0123456789ABCDEF")!!.value)
        assertNull(Claim.romMd5("snes", "not a hash"))
    }
}
