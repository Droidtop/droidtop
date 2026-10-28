package dev.droidtop.library.achievements

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Droidtop/tracker#318 decision 7a: a RetroAchievements game found only by title waits for the person's answer. */
class RaChoicesTest {
    private val game = RaGame(42, "Super Metroid", 3, 74, 610, listOf("abc"))

    @Test fun `an answer is about one game, and one about another is no answer`() {
        assertEquals(RaChoices.Answer.CONFIRMED, RaChoices.decode("yes:42", 42))
        assertEquals(RaChoices.Answer.NOT_THIS, RaChoices.decode("no:42", 42))
        assertNull(RaChoices.decode("yes:7", 42))
        assertNull(RaChoices.decode(null, 42))
        assertNull(RaChoices.decode("garbage", 42))
    }

    @Test fun `a hash match is shown as it is, and a title match only once confirmed`() {
        assertEquals(game to "file hash", RetroAchievements.byTitle(game to "file hash", null))
        assertNull(RetroAchievements.byTitle(game to "name", null))
        assertNull(RetroAchievements.byTitle(game to "name", RaChoices.Answer.NOT_THIS))
        assertEquals(game to "name, confirmed by you", RetroAchievements.byTitle(game to "name", RaChoices.Answer.CONFIRMED))
    }
}
