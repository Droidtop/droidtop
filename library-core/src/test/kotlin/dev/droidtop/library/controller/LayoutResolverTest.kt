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
            mapOf("0" to ToggleValue(keysSwapped = false, confirmOnRight = false), "1" to ToggleValue(keysSwapped = true, confirmOnRight = true)),
        ),
    )

    @Test
    fun theOnlyPadOnAConsoleIsItsOwnEvenUnderAnotherName() {
        // The console's pad re-presented as "Xbox Wireless Controller" by its layout toggle (build 1386).
        val reason = LayoutResolver.isBuiltIn(rp5, activeMatchesTable = false, attachedGamepads = 1, anyAttachedMatchesTable = false)
        assertEquals(BuiltInReason.ONLY_PAD, reason)
        val layout = LayoutResolver.resolve(
            PadFacts(rp5, reason.builtIn, GlyphFamily.XBOX, toggleValue = "1", capture = null, signature = ""),
        )
        assertEquals(LayoutSource.CONSOLE, layout.source)
        assertEquals(true, layout.confirmOnRight)
        assertEquals(true, layout.keysSwapped)
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
