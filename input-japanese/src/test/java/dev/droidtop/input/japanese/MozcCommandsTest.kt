package dev.droidtop.input.japanese

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Capability
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.CompositionMode
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Context
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Input
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.KeyEvent
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Request
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.SessionCommand
import org.pocketworkstation.pckeyboard.InputEngine

/** Droidtop/tracker#903 H7c: each key of the seam as the Mozc command SPEC 6a's H7a list names. */
class MozcCommandsTest {
    private fun special(input: Input): Pair<KeyEvent.SpecialKey, List<KeyEvent.ModifierKey>> {
        assertEquals(Input.CommandType.SEND_KEY, input.type)
        return input.key.specialKey to input.key.modifierKeysList
    }

    private fun session(input: Input): SessionCommand {
        assertEquals(Input.CommandType.SEND_COMMAND, input.type)
        return input.command
    }

    @Test fun `every engine key maps to Mozc's key or session command`() {
        val keys = mapOf(
            InputEngine.Key.SPACE to (KeyEvent.SpecialKey.SPACE to emptyList()),
            InputEngine.Key.ENTER to (KeyEvent.SpecialKey.ENTER to emptyList()),
            InputEngine.Key.LEFT to (KeyEvent.SpecialKey.LEFT to emptyList()),
            InputEngine.Key.RIGHT to (KeyEvent.SpecialKey.RIGHT to emptyList()),
            InputEngine.Key.UP to (KeyEvent.SpecialKey.UP to emptyList()),
            InputEngine.Key.DOWN to (KeyEvent.SpecialKey.DOWN to emptyList()),
            InputEngine.Key.SHIFT_LEFT to (KeyEvent.SpecialKey.LEFT to listOf(KeyEvent.ModifierKey.SHIFT)),
            InputEngine.Key.SHIFT_RIGHT to (KeyEvent.SpecialKey.RIGHT to listOf(KeyEvent.ModifierKey.SHIFT)),
            InputEngine.Key.ESCAPE to (KeyEvent.SpecialKey.ESCAPE to emptyList<KeyEvent.ModifierKey>()),
        )
        for ((key, want) in keys) assertEquals(key.name, want, special(MozcCommands.key(key)))
        assertEquals(SessionCommand.CommandType.UNDO_OR_REWIND, session(MozcCommands.key(InputEngine.Key.UNDO)).type)
        assertEquals(SessionCommand.CommandType.STOP_KEY_TOGGLING, session(MozcCommands.key(InputEngine.Key.TOGGLE)).type)
        assertEquals("every key has a mapping", InputEngine.Key.values().size, keys.size + 2)
    }

    @Test fun `a character is SEND_KEY with its code point, astral ones too`() {
        val n = MozcCommands.char('n'.code)
        assertEquals(Input.CommandType.SEND_KEY, n.type)
        assertEquals('n'.code, n.key.keyCode)
        assertFalse(n.key.hasSpecialKey())
        assertEquals(0x20BB7, MozcCommands.char(0x20BB7).key.keyCode)
        assertEquals(KeyEvent.SpecialKey.BACKSPACE, MozcCommands.backspace().key.specialKey)
    }

    @Test fun `pick commit reset and modes are session commands`() {
        val pick = session(MozcCommands.submitCandidate(-3))
        assertEquals(SessionCommand.CommandType.SUBMIT_CANDIDATE, pick.type)
        assertEquals(-3, pick.id)
        assertEquals(SessionCommand.CommandType.SUBMIT, session(MozcCommands.submit()).type)
        assertEquals(SessionCommand.CommandType.RESET_CONTEXT, session(MozcCommands.resetContext()).type)
        val mode = session(MozcCommands.switchMode(CompositionMode.FULL_KATAKANA))
        assertEquals(SessionCommand.CommandType.SWITCH_COMPOSITION_MODE, mode.type)
        assertEquals(CompositionMode.FULL_KATAKANA, mode.compositionMode)
    }

    @Test fun `the field's end saves and forgetting clears history and predictions`() {
        assertEquals(Input.CommandType.SYNC_DATA, MozcCommands.syncData().type)
        assertEquals(listOf(Input.CommandType.CLEAR_USER_HISTORY, Input.CommandType.CLEAR_USER_PREDICTION), MozcCommands.forget().map { it.type })
    }

    @Test fun `a session may delete before the caret, for undo`() {
        val create = MozcCommands.createSession()
        assertEquals(Input.CommandType.CREATE_SESSION, create.type)
        assertEquals(Capability.TextDeletionCapabilityType.DELETE_PRECEDING_TEXT, create.capability.textDeletion)
    }

    @Test fun `the soft keyboard's request is Mozc's mobile one, with romaji from QWERTY`() {
        val kana = MozcCommands.request(CompositionMode.HIRAGANA)
        assertTrue(kana.mixedConversion)
        assertTrue("zero-query suggestions after a commit", kana.zeroQuerySuggestion)
        assertTrue(kana.autoPartialSuggestion)
        assertFalse(kana.updateInputModeFromSurroundingText)
        assertEquals(Request.SpecialRomanjiTable.QWERTY_MOBILE_TO_HIRAGANA, kana.specialRomanjiTable)
        assertEquals(Request.SpaceOnAlphanumeric.SPACE_OR_CONVERT_KEEPING_COMPOSITION, kana.spaceOnAlphanumeric)
        assertEquals(kana, MozcCommands.request(CompositionMode.FULL_KATAKANA))
        val letters = MozcCommands.request(CompositionMode.HALF_ASCII)
        assertEquals(Request.SpecialRomanjiTable.QWERTY_MOBILE_TO_HALFWIDTHASCII, letters.specialRomanjiTable)
        assertEquals(Request.SpaceOnAlphanumeric.COMMIT, letters.spaceOnAlphanumeric)
        assertEquals(Request.CrossingEdgeBehavior.COMMIT_WITHOUT_CONSUMING, letters.crossingEdgeBehavior)
        assertEquals(Input.CommandType.SET_REQUEST, MozcCommands.setRequest(kana).type)
    }

    @Test fun `the context carries the text before the caret and the field's kind`() {
        val plain = MozcCommands.context("今日は", suggestionsAllowed = true, password = false)
        assertEquals("今日は", plain.precedingText)
        assertFalse(plain.suppressSuggestion)
        assertFalse(plain.hasInputFieldType())
        val quiet = MozcCommands.context("", suggestionsAllowed = false, password = true)
        assertTrue(quiet.suppressSuggestion)
        assertEquals(Context.InputFieldType.PASSWORD, quiet.inputFieldType)
    }
}
