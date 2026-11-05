package dev.droidtop.stores

import dev.droidtop.stores.epic.EpicAchievements
import dev.droidtop.stores.gog.GOGAchievements
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** GOG's and Epic's achievements as the game page shows them (Droidtop/tracker#230). */
class StoreAchievementsTest {
    @Test
    fun `GOG's achievements carry their date, their icon, secrecy and rarity`() {
        val list = GOGAchievements.parse(
            """{"total_count":3,"items":[
              {"achievement_id":"1","achievement_key":"first","visible":true,"name":"First steps","description":"Finish the tutorial",
               "image_url_unlocked":"https://img/u1.png","image_url_locked":"https://img/l1.png","date_unlocked":"2026-03-01T10:00:00+0000","rarity":54.2},
              {"achievement_id":"2","achievement_key":"secret","visible":false,"name":"True ending","description":"See it",
               "image_url_unlocked":"https://img/u2.png","image_url_locked":"https://img/l2.png","date_unlocked":null,"rarity":2.5},
              {"achievement_id":"3","achievement_key":"","name":"  ","description":"no name"}
            ]}""",
        )
        assertEquals(2, list.size)
        val first = list[0]
        assertEquals("first", first.id)
        assertEquals("https://img/u1.png", first.iconUrl)
        assertEquals(Instant.parse("2026-03-01T10:00:00Z").toEpochMilli(), first.earnedAtMs)
        assertEquals(54.2f, first.rarityPercent!!, 0.001f)
        val secret = list[1]
        assertTrue(secret.hidden)
        assertNull(secret.earnedAtMs)
        assertEquals("https://img/l2.png", secret.iconUrl)
        assertEquals(emptyList<Any>(), GOGAchievements.parse(""))
    }

    @Test
    fun `GOG's dates read with or without a colon in the offset`() {
        val withColon = GOGAchievements.parse("""{"items":[{"name":"A","date_unlocked":"2026-03-01T10:00:00+00:00"}]}""").single()
        assertEquals(Instant.parse("2026-03-01T10:00:00Z").toEpochMilli(), withColon.earnedAtMs)
    }

    private val definitions = """{"data":{"Achievement":{"productAchievementsRecordBySandbox":{"productId":"prod1","achievements":[
        {"achievement":{"name":"A1","hidden":false,"unlockedDisplayName":"First","lockedDisplayName":"First?","unlockedDescription":"Did it",
         "lockedDescription":"Do it","unlockedIconLink":"https://e/u1","lockedIconLink":"https://e/l1","rarity":{"percent":12.5}}},
        {"achievement":{"name":"A2","hidden":true,"unlockedDisplayName":"Secret","lockedDisplayName":"Hidden","unlockedDescription":"","lockedDescription":"",
         "unlockedIconLink":"https://e/u2","lockedIconLink":"https://e/l2","rarity":{"percent":1.0}}},
        {"achievement":{"name":"A3","unlockedDisplayName":"Counter","unlockedDescription":"Count to 10","lockedIconLink":"https://e/l3"}}
      ]}}}}"""

    @Test
    fun `Epic's definitions name the product and every achievement`() {
        val (product, list) = EpicAchievements.parseDefinitions(definitions)
        assertEquals("prod1", product)
        assertEquals(listOf("A1", "A2", "A3"), list.map { it.name })
        assertEquals("First", list[0].achievement.title)
        assertTrue(list[1].achievement.hidden)
        assertEquals(12.5f, list[0].achievement.rarityPercent!!, 0.001f)
        assertEquals("https://e/l1", list[0].achievement.iconUrl)
        assertEquals(null to emptyList<Any>(), EpicAchievements.parseDefinitions("""{"data":{"Achievement":null}}"""))
    }

    @Test
    fun `Epic's records put in the earned date, the earned icon and a counted one's progress`() {
        val records = EpicAchievements.parsePlayer(
            """{"data":{"PlayerProfile":{"playerProfile":{"productAchievements":{"__typename":"PlayerProductAchievementsResponseSuccess","data":{
              "playerAchievements":[
                {"playerAchievement":{"achievementName":"A1","unlocked":true,"unlockDate":"2026-02-03T04:05:06.000Z","progress":100}},
                {"playerAchievement":{"achievementName":"A3","unlocked":false,"progress":40}}
              ]}}}}}}""",
        )
        val merged = EpicAchievements.merge(EpicAchievements.parseDefinitions(definitions).second, records)
        assertEquals(Instant.parse("2026-02-03T04:05:06Z").toEpochMilli(), merged[0].earnedAtMs)
        assertEquals("https://e/u1", merged[0].iconUrl)
        assertNull(merged[0].progress)
        assertNull(merged[1].earnedAtMs)
        assertEquals("https://e/l2", merged[1].iconUrl)
        assertEquals(40f, merged[2].progress!!, 0.001f)
        assertEquals(100f, merged[2].progressMax!!, 0.001f)
        assertEquals(emptyMap<String, Any>(), EpicAchievements.parsePlayer("""{"data":{"PlayerProfile":{"playerProfile":{"productAchievements":{"__typename":"Error"}}}}}"""))
    }
}
