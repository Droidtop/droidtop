package dev.droidtop.library.scraper

import dev.droidtop.library.GenreWords
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Steam's content descriptors are not genres (Droidtop/tracker#756: a card read "Sexual Content, Visua..."). */
class SteamGenresTest {
    @Test
    fun `Steam's content descriptors are left out of a game's genres`() {
        val genres = JSONArray(
            """[{"id":"23","description":"Indie"},{"id":"71","description":"Sexual Content"},{"id":"72","description":"Nudity"},{"id":"25","description":"Adventure"}]""",
        )
        assertEquals("Indie, Adventure", SteamStoreClient.genres(genres))
        assertNull(SteamStoreClient.genres(JSONArray("""[{"id":"72","description":"Nudity"}]""")))
        assertNull(SteamStoreClient.genres(null))
    }

    @Test
    fun `a stored genre line drops them too`() {
        assertEquals("Visual Novel", GenreWords.withoutDescriptors("Sexual Content, Visual Novel"))
        assertNull(GenreWords.withoutDescriptors("Sexual Content, Nudity"))
        assertEquals("Action, Gore-free", GenreWords.withoutDescriptors("Action, Gore-free"))
    }
}
