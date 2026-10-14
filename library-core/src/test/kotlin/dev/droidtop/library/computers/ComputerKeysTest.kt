package dev.droidtop.library.computers

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The keys droidtop sends must be the ones droidtop-agent makes, or the same
 * game would be two games: the agent core's `library::title_key` and its
 * `host::file_key` (Droidtop/droidtop-agent), with the agent's own test cases.
 */
class ComputerKeysTest {
    @Test
    fun `title keys match the agent's`() {
        assertEquals("title:the game deluxe", ComputerLibrary.titleKey("The Game: Deluxe!"))
        assertEquals(ComputerLibrary.titleKey("The Game: Deluxe!"), ComputerLibrary.titleKey("the  game deluxe"))
        assertEquals("title:super game usa", ComputerLibrary.titleKey("Super Game (USA)"))
    }

    /**
     * A folder named as one run of words is read as its words here
     * ([dev.droidtop.library.GameTitleParser]), so the agent's scanner reads it
     * the same way (`split_run_together`): "DtSyncTest v1.0" here and "DtSyncTest v1.1"
     * on the computer are one game, `title:dt sync test` (Droidtop/tracker#529).
     */
    @Test
    fun `a folder game is the same key here and on the computer`() {
        val here = dev.droidtop.library.GameTitleParser.parseName("DtSyncTest v1.0")
        assertEquals("Dt Sync Test", here.title)
        assertEquals("title:dt sync test", ComputerLibrary.titleKey(here.title))
    }

    @Test
    fun `file keys match the agent's`() {
        assertEquals("steam_440", ComputerSaves.fileKey("steam:440"))
        assertEquals("title_a_b", ComputerSaves.fileKey("title:a/b"))
    }
}
