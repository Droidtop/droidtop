package dev.droidtop.library.controller

import dev.droidtop.library.controller.LayoutResolver.BuiltInReason
import org.junit.Assert.assertEquals
import org.junit.Test

class LayoutResolverTest {
    private val rp5 = ConsoleDef(
        id = "retroid-pocket-5",
        name = "Retroid Pocket 5",
        model = "Retroid Pocket 5",
        manufacturer = null,
        pad = BuiltInPad("Retroid Pocket Controller", 0x2022, 0x3001),
        // Printed Nintendo-style whatever the toggle says (owner, 2026-10-08).
        glyphFamily = GlyphFamily.NINTENDO,
        toggle = LayoutToggle(
            "persist.sys.gamepad.type",
            // As the hardware row has it: 1 is the toggle's "xbox" position, 0 its "Retro" (Nintendo) one.
            mapOf("0" to ToggleValue(keysSwapped = true, confirmOnRight = true), "1" to ToggleValue(keysSwapped = false, confirmOnRight = false)),
        ),
    )

    @Test
    fun theRetroidPocket5DrawsItsPrintedLettersAndConfirmsAsTheToggleSays() {
        fun layout(value: String) = LayoutResolver.resolve(PadFacts(rp5, true, null, toggleValue = value, capture = null, signature = ""))
        // "Retro" (0): the right button is printed A, reports BUTTON_A and confirms; no key is swapped by droidtop.
        val retro = layout("0")
        assertEquals("A", retro.glyph(FaceRole.CONFIRM))
        assertEquals("B", retro.glyph(FaceRole.CANCEL))
        assertEquals("X", retro.glyph(FaceRole.X))
        assertEquals("Y", retro.glyph(FaceRole.Y))
        assertEquals(FacePosition.RIGHT, retro.positionOf(FaceRole.CONFIRM))
        assertEquals(false, retro.swapped)
        // "xbox" (1): the bottom button, printed B, reports BUTTON_A and confirms, so the pill says B.
        val xbox = layout("1")
        assertEquals("B", xbox.glyph(FaceRole.CONFIRM))
        assertEquals("A", xbox.glyph(FaceRole.CANCEL))
        assertEquals(FacePosition.BOTTOM, xbox.positionOf(FaceRole.CONFIRM))
        assertEquals(false, xbox.swapped)
    }

    @Test
    fun aToggleValuesKeyCodesSayWhetherItsKeysAreSwapped() {
        fun toggle(value: String) = HardwareDatabase.parse(
            """{"devices": {"d": {"match": {"model": "M"}, "layoutToggle": {"property": "p", "values": {"v": $value}}}}}""",
        ).single().toggle!!.values.getValue("v")
        val retro = """{"keysSwapped": false, "confirmOn": "right", "keycodes": {"bottom": "BUTTON_B", "right": "BUTTON_A", "top": "BUTTON_X", "left": "BUTTON_Y"}}"""
        assertEquals(ToggleValue(keysSwapped = true, confirmOnRight = true), toggle(retro))
        val xbox = """{"keysSwapped": true, "confirmOn": "bottom", "keycodes": {"bottom": "BUTTON_A", "right": "BUTTON_B", "top": "BUTTON_Y", "left": "BUTTON_X"}}"""
        assertEquals(ToggleValue(keysSwapped = false, confirmOnRight = false), toggle(xbox))
        // A row with no key codes is read by its keysSwapped.
        assertEquals(ToggleValue(keysSwapped = true, confirmOnRight = false), toggle("""{"keysSwapped": true, "confirmOn": "bottom"}"""))
    }

    @Test
    fun theOnlyPadOnAConsoleIsItsOwnEvenUnderAnotherName() {
        // The console's pad re-presented as "Xbox Wireless Controller" by its layout toggle (build 1386),
        // an identity SDL's list does not classify either.
        val reason = LayoutResolver.isBuiltIn(rp5, activeMatchesTable = false, attachedGamepads = 1, anyAttachedMatchesTable = false)
        assertEquals(BuiltInReason.ONLY_PAD, reason)
        val xbox = LayoutResolver.resolve(PadFacts(rp5, reason.builtIn, null, toggleValue = "1", capture = null, signature = ""))
        assertEquals(FaceLayout(GlyphFamily.NINTENDO, confirmOnRight = false, keysSwapped = false, LayoutSource.CONSOLE), xbox)
        val other = LayoutResolver.resolve(PadFacts(rp5, reason.builtIn, null, toggleValue = "0", capture = null, signature = ""))
        assertEquals(FaceLayout(GlyphFamily.NINTENDO, confirmOnRight = true, keysSwapped = true, LayoutSource.CONSOLE), other)
    }

    @Test
    fun aSecondPadThatIsNotTheTablesIsExternal() {
        assertEquals(
            BuiltInReason.OTHER_PAD,
            LayoutResolver.isBuiltIn(rp5, activeMatchesTable = false, attachedGamepads = 2, anyAttachedMatchesTable = true),
        )
        assertEquals(
            BuiltInReason.OTHER_PAD,
            LayoutResolver.isBuiltIn(rp5, activeMatchesTable = false, attachedGamepads = 2, anyAttachedMatchesTable = false),
        )
    }

    @Test
    fun theTablesIdentityAndNoConsole() {
        assertEquals(BuiltInReason.TABLE_IDENTITY, LayoutResolver.isBuiltIn(rp5, true, 2, true))
        assertEquals(BuiltInReason.NO_PAD_YET, LayoutResolver.isBuiltIn(rp5, null, 0, false))
        assertEquals(BuiltInReason.NO_CONSOLE, LayoutResolver.isBuiltIn(null, false, 1, false))
    }

    @Test
    fun anUnreadableToggleIsUnknownNotGuessed() {
        val layout = LayoutResolver.resolve(PadFacts(rp5, true, GlyphFamily.XBOX, toggleValue = null, capture = null, signature = ""))
        assertEquals(LayoutSource.UNKNOWN, layout.source)
    }
}
