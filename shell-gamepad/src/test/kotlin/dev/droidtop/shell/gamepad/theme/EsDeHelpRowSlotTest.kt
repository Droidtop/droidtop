package dev.droidtop.shell.gamepad.theme

import dev.droidtop.library.theme.EsDeThemeElement
import dev.droidtop.library.theme.EsDeThemeValue
import dev.droidtop.library.theme.EsDeThemeView
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where a themed view puts the one help row: the theme's own declarations
 * merged in document order, else ES-DE's component default
 * (HelpComponent.cpp:23-27). The shell's bar is placed from this same
 * answer when it is drawn over a themed canvas (docs/SPEC.md 7j).
 */
class EsDeHelpRowSlotTest {

    private fun element(type: String, vararg properties: Pair<String, EsDeThemeValue>) =
        EsDeThemeElement(type = type, key = "${type}_test", properties = properties.toMap())

    @Test
    fun `the help slot reads the theme's own declarations and its defaults`() {
        val declared = EsDeThemeView(
            mapOf(
                "helpsystem_a" to element("helpsystem", "pos" to EsDeThemeValue.Pair(0.5f, 0.9f)),
                "helpsystem_b" to element("helpsystem", "origin" to EsDeThemeValue.Pair(0.5f, 0.5f)),
            ),
        )
        val none = EsDeThemeView(emptyMap())

        // Later declarations merge over earlier ones per property, the
        // same rule the renderer's own help bar applies.
        assertEquals(0.9f, declared.helpRowSlot(vertical = false).posY)
        assertEquals(0.5f, declared.helpRowSlot(vertical = false).originY)
        // No declaration at all: ES-DE's own component default.
        assertEquals(0.9515f, none.helpRowSlot(vertical = false).posY)
        assertEquals(0.975f, none.helpRowSlot(vertical = true).posY)
    }
}
