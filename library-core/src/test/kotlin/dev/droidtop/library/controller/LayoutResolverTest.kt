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
        glyphFamily = GlyphFamily.XBOX,
        toggle = LayoutToggle(
            "persist.sys.gamepad.type",
            // As the hardware row has it: 1 is the toggle's "xbox" position, 0 the swapped one.
            mapOf("0" to ToggleValue(keysSwapped = true, confirmOnRight = true), "1" to ToggleValue(keysSwapped = false, confirmOnRight = false)),
        ),
    )

    @Test
    fun theOnlyPadOnAConsoleIsItsOwnEvenUnderAnotherName() {
        // The console's pad re-presented as "Xbox Wireless Controller" by its layout toggle (build 1386),
        // an identity SDL's list does not classify either.
        val reason = LayoutResolver.isBuiltIn(rp5, activeMatchesTable = false, attachedGamepads = 1, anyAttachedMatchesTable = false)
        assertEquals(BuiltInReason.ONLY_PAD, reason)
        val xbox = LayoutResolver.resolve(PadFacts(rp5, reason.builtIn, null, toggleValue = "1", capture = null, signature = ""))
        assertEquals(FaceLayout(GlyphFamily.XBOX, confirmOnRight = false, keysSwapped = false, LayoutSource.CONSOLE), xbox)
        val other = LayoutResolver.resolve(PadFacts(rp5, reason.builtIn, null, toggleValue = "0", capture = null, signature = ""))
        assertEquals(FaceLayout(GlyphFamily.XBOX, confirmOnRight = true, keysSwapped = true, LayoutSource.CONSOLE), other)
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
