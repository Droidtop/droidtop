package dev.droidtop.library.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The running game's controls and the one "does this row ask first" decision (GameControls.kt). */
class GameControlsTest {
    private val on = AskFirst(beforeStopping = true, beforeLoadAndOverwrite = true)
    private val off = AskFirst(beforeStopping = false, beforeLoadAndOverwrite = false)

    @Test fun `the confirm decision for each flag, setting and mode`() {
        val stopping = listOf(GameRow.QUIT, GameRow.RESTART, GameRow.KILL)
        for (row in GameRow.entries) {
            for (mode in UiMode.entries) {
                for (ask in listOf(on, off)) {
                    for (flag in listOf(false, true)) {
                        val expected = flag || (row in stopping && (ask.beforeStopping || mode != UiMode.FULL))
                        assertEquals("$row $mode $ask $flag", expected, GameControls.asks(row, mode, ask, flag))
                    }
                }
            }
        }
        assertFalse(GameControls.asks(GameRow.RESUME, UiMode.KID, on))
    }

    @Test fun `plugin rows and stops follow their settings, and Kid always asks`() {
        assertTrue(GameControls.asksPluginRow(confirm = true, mode = UiMode.FULL, ask = on))
        assertFalse(GameControls.asksPluginRow(confirm = true, mode = UiMode.FULL, ask = off))
        assertTrue(GameControls.asksPluginRow(confirm = true, mode = UiMode.KID, ask = off))
        assertFalse(GameControls.asksPluginRow(confirm = false, mode = UiMode.KID, ask = on))
        assertTrue(GameControls.asksStop(UiMode.FULL, on))
        assertFalse(GameControls.asksStop(UiMode.FULL, off))
        assertTrue(GameControls.asksStop(UiMode.KIOSK, off))
    }

    @Test fun `Kid gets Resume and Quit only`() {
        assertEquals(listOf(GameRow.RESUME, GameRow.QUIT), GameControls.rows(UiMode.KID, GameRunner.LOCAL))
        assertEquals(listOf(GameRow.RESUME, GameRow.QUIT), GameControls.rows(UiMode.KIOSK, GameRunner.LOCAL))
        assertEquals(GameRow.entries, GameControls.rows(UiMode.FULL, GameRunner.LOCAL))
        // The emulator choice is not Kid's to change.
        assertFalse(GameRow.EMULATOR in GameControls.rows(UiMode.KID, GameRunner.LOCAL))
    }

    @Test fun `a stream hides Restart and Kill and uses its own Quit label`() {
        val rows = GameControls.rows(UiMode.FULL, GameRunner.STREAM)
        assertFalse(GameRow.RESTART in rows || GameRow.KILL in rows)
        assertFalse(GameRow.EMULATOR in rows)
        assertEquals("Disconnect", GameControls.label(GameRow.QUIT, GameRunner.STREAM))
        assertEquals("Disconnect from the PC?", GameControls.question(GameRow.QUIT, GameRunner.STREAM, "Desktop"))
        assertEquals("Quit", GameControls.label(GameRow.QUIT, GameRunner.LOCAL))
        assertTrue(GameControls.question(GameRow.QUIT, GameRunner.LOCAL, "Metroid").startsWith("Quit Metroid?"))
        // A stream runner that says Restart and Kill apply gets them.
        assertTrue(GameRow.KILL in GameControls.rows(UiMode.FULL, GameRunner.STREAM.copy(restartAndKill = true)))
    }

    @Test fun `turning Ask before stopping off changes the Quick Menu and the companion the same way`() {
        for (row in listOf(GameRow.QUIT, GameRow.RESTART, GameRow.KILL)) {
            // The Quick Menu arms on a first A exactly when the companion would ask.
            assertEquals(GameControls.asks(row, UiMode.FULL, on), GameControls.needsSecondPress(row, armed = false, mode = UiMode.FULL, ask = on))
            assertEquals(GameControls.asks(row, UiMode.FULL, off), GameControls.needsSecondPress(row, armed = false, mode = UiMode.FULL, ask = off))
            assertFalse(GameControls.needsSecondPress(row, armed = false, mode = UiMode.FULL, ask = off))
            assertFalse(GameControls.needsSecondPress(row, armed = true, mode = UiMode.FULL, ask = on))
        }
    }
}
