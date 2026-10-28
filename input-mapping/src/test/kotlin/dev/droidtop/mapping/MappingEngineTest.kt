package dev.droidtop.mapping

import dev.droidtop.mapping.MappingEngine.Output
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The mapping engine's rules (Droidtop/tracker#618 C3), each on [LAYOUT]. */
class MappingEngineTest {
    private fun engine() = MappingEngine(ControllerLayout.parse(LAYOUT))

    @Test
    fun `a full press is held while the button is`() {
        val e = engine()
        assertEquals(listOf(Output.KeyDown("SPACE")), e.press("button_diamond", "button_a", 0))
        assertEquals(listOf(Output.KeyUp("SPACE")), e.release("button_diamond", "button_a", 50))
    }

    @Test
    fun `with a long press, a quick release taps the full press`() {
        val e = engine()
        assertEquals(emptyList<Output>(), e.press("button_diamond", "button_b", 0))
        assertEquals(listOf(Output.KeyDown("ESCAPE"), Output.KeyUp("ESCAPE")), e.release("button_diamond", "button_b", 100))
    }

    @Test
    fun `held past its time, the long press is held until release`() {
        val e = engine()
        e.press("button_diamond", "button_b", 0)
        assertEquals(emptyList<Output>(), e.tick(299))
        assertEquals(listOf(Output.KeyDown("TAB")), e.tick(300))
        assertEquals(emptyList<Output>(), e.tick(400))
        assertEquals(listOf(Output.KeyUp("TAB")), e.release("button_diamond", "button_b", 500))
    }

    @Test
    fun `a long press released with no tick in between still counts`() {
        val e = engine()
        e.press("button_diamond", "button_b", 0)
        assertEquals(listOf(Output.KeyDown("TAB"), Output.KeyUp("TAB")), e.release("button_diamond", "button_b", 500))
    }

    @Test
    fun `start and release presses tap`() {
        val e = engine()
        assertEquals(listOf(Output.KeyDown("F5"), Output.KeyUp("F5")), e.press("button_diamond", "button_x", 0))
        assertEquals(listOf(Output.KeyDown("F6"), Output.KeyUp("F6")), e.release("button_diamond", "button_x", 10))
    }

    @Test
    fun `a stick in dpad mode presses up to two directions`() {
        val e = engine()
        assertEquals(setOf(Output.KeyDown("D"), Output.KeyDown("W")), e.stick("joystick", 0.9f, -0.9f, 0).toSet())
        assertEquals(emptyList<Output>(), e.stick("joystick", 0.8f, -0.7f, 10))
        assertEquals(listOf(Output.KeyUp("W")), e.stick("joystick", 0.8f, 0.1f, 20))
        assertEquals(listOf(Output.KeyUp("D")), e.stick("joystick", 0.1f, 0.1f, 30))
        assertEquals(setOf("dpad_south", "dpad_west"), MappingEngine.directions(-0.6f, 0.6f))
    }

    @Test
    fun `a held mode shift puts its group on the source`() {
        val e = engine()
        assertEquals(1, e.groupOn("joystick")!!.id)
        assertEquals(emptyList<Output>(), e.press("switch", "left_bumper", 0))
        assertEquals(3, e.groupOn("joystick")!!.id)
        assertEquals(listOf(Output.KeyDown("UP")), e.stick("joystick", 0f, -1f, 10))
        assertEquals(listOf(Output.KeyUp("UP")), e.stick("joystick", 0f, 0f, 20))
        e.release("switch", "left_bumper", 30)
        assertEquals(1, e.groupOn("joystick")!!.id)
    }

    @Test
    fun `changing preset lets go of what the old one held`() {
        val e = engine()
        e.press("button_diamond", "button_a", 0)
        assertEquals(listOf(Output.KeyUp("SPACE"), Output.PresetChanged(1)), e.press("button_diamond", "button_y", 10))
        assertEquals(1, e.presetId)
        assertEquals(emptyList<Output>(), e.release("button_diamond", "button_a", 20))
        e.release("button_diamond", "button_y", 30)
        assertEquals(listOf(Output.KeyDown("RETURN")), e.press("button_diamond", "button_a", 40))
        e.release("button_diamond", "button_a", 50)
        assertEquals(listOf(Output.PresetChanged(0)), e.press("button_diamond", "button_y", 60))
    }

    @Test
    fun `mouse buttons, and an input with no binding does nothing`() {
        val e = engine()
        assertEquals(listOf(Output.MouseDown("LEFT")), e.press("switch", "right_bumper", 0))
        assertEquals(listOf(Output.MouseUp("LEFT")), e.release("switch", "right_bumper", 10))
        assertTrue(e.press("dpad", "dpad_north", 0).isEmpty())
        assertTrue(e.release("dpad", "dpad_north", 10).isEmpty())
    }

    @Test
    fun `a stick in joystick mouse mode moves the pointer between ticks, scaled by its sensitivity`() {
        val e = engine()
        assertEquals(emptyList<Output>(), e.stick("right_joystick", 1f, 0f, 0))
        // A full push: 1.5 screen widths a second, doubled by sensitivity 200, for half a second.
        assertEquals(listOf(Output.MouseMove(1.5f, 0f)), e.tick(500))
        val last = e.stick("right_joystick", 0f, 0f, 600).single() as Output.MouseMove
        assertEquals(0.3f, last.dx, 0.0001f)
        assertEquals(emptyList<Output>(), e.tick(1000))
        assertEquals(0f to 0f, MappingEngine.mouseVelocity(0.1f, 0f))
    }

    @Test
    fun `a stick in joystick move mode is passed through as the pad's stick`() {
        val e = MappingEngine(ControllerLayout.parse(LAYOUT), presetId = 1)
        assertEquals(listOf(Output.StickMove("RIGHT", 0.5f, -0.25f)), e.stick("right_joystick", 0.5f, -0.25f, 0))
    }

    companion object {
        private fun settingsGroup(id: Int, mode: String, settings: String) =
            "\"group\" { \"id\" \"$id\" \"mode\" \"$mode\" \"settings\" { $settings } }"

        private fun press(type: String, vararg bindings: String, settings: String = ""): String =
            "\"$type\" { \"bindings\" { " + bindings.joinToString(" ") { "\"binding\" \"$it\"" } + " } " +
                (if (settings.isEmpty()) "" else "\"settings\" { $settings } ") + "}"

        private fun input(name: String, vararg activators: String) = "\"$name\" { \"activators\" { ${activators.joinToString(" ")} } }"

        private fun group(id: Int, mode: String, vararg inputs: String) =
            "\"group\" { \"id\" \"$id\" \"mode\" \"$mode\" \"inputs\" { ${inputs.joinToString(" ")} } }"

        val LAYOUT = """
            "controller_mappings" {
              "version" "3"
              ${group(
            0, "four_buttons",
            input("button_a", press("Full_Press", "key_press SPACE")),
            input(
                "button_b", press("Full_Press", "key_press ESCAPE"),
                press("Long_Press", "key_press TAB", settings = "\"long_press_time\" \"300\""),
            ),
            input("button_x", press("Start_Press", "key_press F5"), press("Release_Press", "key_press F6")),
            input("button_y", press("Full_Press", "controller_action CHANGE_PRESET 2 0 0, , ")),
        )}
              ${group(1, "dpad", input("dpad_north", press("Full_Press", "key_press W")), input("dpad_east", press("Full_Press", "key_press D")))}
              ${group(
            2, "switches",
            input("left_bumper", press("Full_Press", "mode_shift joystick 3")),
            input("right_bumper", press("Full_Press", "mouse_button LEFT")),
        )}
              ${group(3, "dpad", input("dpad_north", press("Full_Press", "key_press UP")))}
              ${group(
            4, "four_buttons",
            input("button_a", press("Full_Press", "key_press RETURN")),
            input("button_y", press("Full_Press", "controller_action CHANGE_PRESET 1 0 0")),
        )}
              ${settingsGroup(5, "joystick_mouse", "\"sensitivity\" \"200\"")}
              ${settingsGroup(6, "joystick_move", "")}
              "preset" { "id" "0" "name" "Default" "group_source_bindings" {
                "0" "button_diamond active" "1" "joystick active" "2" "switch active" "3" "joystick active modeshift"
                "5" "right_joystick active" } }
              "preset" { "id" "1" "name" "Menu" "group_source_bindings" { "4" "button_diamond active" "6" "right_joystick active" } }
            }
        """.trimIndent()
    }
}
