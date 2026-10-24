package dev.droidtop.library.integrations

import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** A plugin's achievements (docs/plugin-api.md 3 A8, Droidtop/tracker#143): what it is told and what is shown. */
class PluginAchievementsTest {
    @Test
    fun setAndProgressBecomeOneListAndBadEntriesAreLeftOut() {
        val set = JSONObject(
            """{"achievements": [
                {"id": "a", "title": "First steps", "desc": "Finish the tutorial", "badgeUrl": "https://x.example/a.png"},
                {"id": "b", "title": "Secret", "hidden": true, "badgeUrl": "http://x.example/b.png"},
                {"id": "", "title": "No id"},
                {"id": "c"}
            ]}""",
        )
        val progress = JSONObject("""{"unlocked": [{"id": "a", "unlockedAt": 1700000000000}, {"id": "b", "unlockedAt": "2026-10-01"}, {"id": "zz", "unlockedAt": 5}]}""")
        val list = PluginAchievements.parse(set, progress)
        assertEquals(listOf("a", "b"), list.achievements.map { it.id })
        assertEquals(1_700_000_000_000L, list.achievements[0].earnedAtMs)
        assertEquals(1_790_812_800_000L, list.achievements[1].earnedAtMs)
        assertNull("only an https badge is kept", list.achievements[1].iconUrl)
        assertEquals(2, list.earned)
        assertEquals(0, PluginAchievements.parse(set, null).earned)
    }

    @Test
    fun aProviderIsToldTheTitleSystemAndStoreNeverAFolder() {
        val folder = LibraryEntry(id = "/storage/emulated/0/Games/Eternum", title = "Eternum", kind = LibraryEntryKind.RENPY, systemId = "pc")
        val told = PluginAchievements.entryArgs(folder).getJSONObject("entry")
        assertEquals("pc", told.getString("system"))
        assertFalse(told.toString().contains("/storage"))
        assertFalse(told.has("store"))
    }
}
