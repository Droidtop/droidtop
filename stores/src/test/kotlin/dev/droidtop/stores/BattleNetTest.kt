package dev.droidtop.stores

import dev.droidtop.stores.battlenet.BattleNetStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Battle.net: the account's games and the sign-in's return page (Droidtop/tracker#335). Answers written here in the shape Playnite's BattleNetLibrary reads. */
class BattleNetTest {
    @Test
    fun `the account's games are each title once, by Blizzard's title id`() {
        val games = BattleNetStore.gamesOf(
            """{"gameAccounts":[
              {"titleId":5730135,"localizedGameName":"World of Warcraft","gameAccountName":"WoW1"},
              {"titleId":5730135,"localizedGameName":"World of Warcraft","gameAccountName":"WoW2"},
              {"titleId":4613486,"localizedGameName":"Diablo IV"},
              {"titleId":0,"localizedGameName":"No id"},
              {"titleId":7,"localizedGameName":"  "}
            ],"subscriptions":[]}""",
        )
        assertEquals(listOf("5730135" to "World of Warcraft", "4613486" to "Diablo IV"), games.map { it.id to it.title })
        assertEquals(emptyList<Any>(), BattleNetStore.gamesOf("""{"gameAccounts":[],"subscriptions":[]}"""))
    }

    @Test
    fun `an answer without the account's games is a sign-in gone`() {
        for (answer in listOf("""{"authenticated":false}""", "<html>sign in</html>")) {
            try {
                BattleNetStore.gamesOf(answer)
                fail("$answer is not an account's games")
            } catch (e: BattleNetStore.SignedOut) {
                assertEquals("Sign in to Battle.net again", e.message)
            }
        }
    }

    @Test
    fun `classic games are keyed by their franchise, and the kept list round trips`() {
        val classics = BattleNetStore.classicsOf(
            """{"classicGames":[{"localizedGameName":"Warcraft III","regionalGameFranchiseIconFilename":"warcraft-iii-reforged.png"},
               {"localizedGameName":"Diablo II","regionalGameFranchiseIconFilename":"diablo-ii.png"}]}""",
        )
        assertEquals(listOf("classic-w3", "classic-d2"), classics.map { it.id })
        assertEquals(emptyList<Any>(), BattleNetStore.classicsOf("{}"))
        assertEquals(classics, BattleNetStore.decode(BattleNetStore.encode(classics)))
        assertEquals(emptyList<Any>(), BattleNetStore.decode("x"))
    }

    @Test
    fun `the sign-in returns to the account overview`() {
        assertTrue(BattleNetStore.isReturnPage("https://account.battle.net/overview"))
        assertTrue(BattleNetStore.isReturnPage("https://account.battle.net/"))
        assertTrue(BattleNetStore.isReturnPage("https://account.battle.net/overview?x=1"))
        assertFalse(BattleNetStore.isReturnPage("https://account.battle.net/login"))
        assertFalse(BattleNetStore.isReturnPage("https://eu.battle.net/login/en/"))
    }
}
