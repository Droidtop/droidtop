package dev.droidtop.runtime.windows

import com.winlator.xserver.XKeycode
import dev.droidtop.mapping.Binding
import dev.droidtop.mapping.ControllerLayout
import dev.droidtop.mapping.MappingEngine
import dev.droidtop.mapping.MappingEngine.Output
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The bundled "Keyboard (WASD) and Mouse" layout (Droidtop/tracker#618) and the X keys its names become. */
class KeyboardMouseLayoutTest {
    private val layout = ControllerLayout.parse(File("src/main/res/raw/controls_keyboard_mouse.vdf").readText())

    @Test
    fun `it reads back as the same tree and names itself`() {
        assertEquals(layout.root, ControllerLayout.parse(layout.write()).root)
        assertEquals("Keyboard (WASD) and Mouse", layout.localized(layout.title))
    }

    @Test
    fun `every key it names is a key of the X server`() {
        val keys = layout.groups.flatMap { g -> g.inputs.flatMap { i -> i.activators.flatMap { it.bindings } } }
            .filterIsInstance<Binding.KeyPress>()
        assertTrue(keys.isNotEmpty())
        for (k in keys) assertNotNull(k.key, WineKeyNames.keycode(k.key))
    }

    @Test
    fun `the left stick is WASD, A jumps, the triggers click and the right stick is the mouse`() {
        val e = MappingEngine(layout)
        assertEquals(listOf(Output.KeyDown("W")), e.stick("joystick", 0f, -1f, 0))
        assertEquals(listOf(Output.KeyDown("SPACE")), e.press("button_diamond", "button_a", 10))
        assertEquals(listOf(Output.MouseDown("LEFT")), e.press("right_trigger", "click", 20))
        assertEquals(emptyList<Output>(), e.stick("right_joystick", 1f, 0f, 30))
        assertTrue(e.tick(130).single() is Output.MouseMove)
    }

    @Test
    fun `Steam's key names become X keys`() {
        assertEquals(XKeycode.KEY_SPACE, WineKeyNames.keycode("SPACE"))
        assertEquals(XKeycode.KEY_W, WineKeyNames.keycode("w"))
        assertEquals(XKeycode.KEY_1, WineKeyNames.keycode("1"))
        assertEquals(XKeycode.KEY_F5, WineKeyNames.keycode("F5"))
        assertEquals(XKeycode.KEY_CTRL_L, WineKeyNames.keycode("LEFT_CONTROL"))
        assertEquals(null, WineKeyNames.keycode("NO_SUCH_KEY"))
    }

    @Test
    fun `Mouse only reads back, its keys are X keys, and the left stick is the pointer`() {
        val mouse = ControllerLayout.parse(File("src/main/res/raw/controls_mouse_only.vdf").readText())
        assertEquals(mouse.root, ControllerLayout.parse(mouse.write()).root)
        assertEquals("Mouse Only", mouse.localized(mouse.title))
        val keys = mouse.groups.flatMap { g -> g.inputs.flatMap { i -> i.activators.flatMap { it.bindings } } }
            .filterIsInstance<Binding.KeyPress>()
        for (k in keys) assertNotNull(k.key, WineKeyNames.keycode(k.key))
        val e = MappingEngine(mouse)
        assertEquals(listOf(Output.MouseDown("LEFT")), e.press("button_diamond", "button_a", 0))
        assertEquals(listOf(Output.Wheel("SCROLL_DOWN")), e.press("switch", "right_bumper", 10))
        assertEquals(emptyList<Output>(), e.stick("joystick", 1f, 0f, 20))
        assertTrue(e.tick(120).single() is Output.MouseMove)
    }
}
