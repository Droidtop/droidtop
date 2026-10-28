package dev.droidtop.mapping

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Steam's layout format, read and written (Droidtop/tracker#618 C3). [LAYOUT] is droidtop's own, written for this test
 * in Steam's structure: a WASD stick, a face-button pad with a long press, the right stick as a mouse, a chord, a
 * mode shift and a layer, a key Steam has that this reader does not know, a condition and a comment.
 */
class ControllerLayoutTest {
    @Test
    fun `a layout written and read back is the same tree`() {
        val tree = KeyValues.parse(LAYOUT)
        val again = KeyValues.parse(KeyValues.write(tree))
        assertEquals(tree, again)
        assertEquals(KeyValues.write(tree), KeyValues.write(again))
    }

    @Test
    fun `repeated keys keep their order, and a key the reader does not know is kept`() {
        val layout = ControllerLayout.parse(LAYOUT)
        assertEquals(listOf(0, 1, 2, 3), layout.groups.map { it.id })
        val written = ControllerLayout.parse(layout.write())
        assertEquals("1", written.root.first("group")!!.string("droidtop_unknown_to_reader"))
        assertEquals("[\$WIN32]", written.root.first("settings")!!.first("left_trackpad_mode")!!.condition)
    }

    @Test
    fun `the header, the localized title and the action sets`() {
        val layout = ControllerLayout.parse(LAYOUT)
        assertEquals("3", layout.version)
        assertEquals("controller_xboxone", layout.controllerType)
        assertEquals("Keyboard (WASD) and Mouse", layout.localized(layout.title))
        assertEquals("Keyboard (WASD) and Mouse", layout.localized(layout.title, "french"))
        assertEquals("plain", layout.localized("plain"))
        assertEquals(listOf("Default", "Slow"), layout.actionSets.map { it.name })
        assertEquals("Default", layout.actionSets.single { it.layer }.parent)
    }

    @Test
    fun `groups, inputs, activators and bindings`() {
        val layout = ControllerLayout.parse(LAYOUT)
        val stick = layout.group(0)!!
        assertEquals("dpad", stick.mode)
        assertEquals(Binding.KeyPress("W", "key_press W, Forward, "), stick.input("dpad_north")!!.activators.single().bindings.single())
        val face = layout.group(1)!!
        assertEquals("four_buttons", face.mode)
        val a = face.input("button_a")!!
        assertEquals(listOf("Full_Press", "Long_Press"), a.activators.map { it.type })
        assertEquals("Jump", a.activators[0].bindings.single().label)
        assertEquals(Binding.KeyPress("E", "key_press E"), a.activators[1].bindings.single())
        assertEquals("400", a.activators[1].settings["long_press_time"])
        val mouse = layout.group(2)!!
        assertEquals("joystick_mouse", mouse.mode)
        assertEquals("150", mouse.settings["sensitivity"])
        assertEquals(Binding.MouseButton("LEFT", "mouse_button LEFT, , "), mouse.input("click")!!.activators.single().bindings.single())
        val chord = face.input("button_y")!!.activators.single()
        assertEquals("chord", chord.type)
        assertEquals(
            listOf<Binding>(Binding.KeyPress("LEFT_CONTROL", "key_press LEFT_CONTROL"), Binding.KeyPress("C", "key_press C")),
            chord.bindings,
        )
    }

    @Test
    fun `presets say which group is on which source`() {
        val layout = ControllerLayout.parse(LAYOUT)
        val default = layout.presets.single { it.name == "Default" }
        assertEquals(
            listOf(
                ControllerLayout.SourceBinding(0, "joystick", active = true, modeShift = false),
                ControllerLayout.SourceBinding(1, "button_diamond", active = true, modeShift = false),
                ControllerLayout.SourceBinding(2, "right_joystick", active = true, modeShift = false),
                ControllerLayout.SourceBinding(3, "right_joystick", active = true, modeShift = true),
            ),
            default.sources,
        )
        assertFalse(layout.presets.single { it.name == "Slow" }.sources.single().active)
    }

    @Test
    fun `binding strings`() {
        assertEquals(Binding.GameAction("Default", "jump", "game_action Default jump, , "), Binding.parse("game_action Default jump, , "))
        assertEquals(Binding.XinputButton("A", "xinput_button A"), Binding.parse("xinput_button A"))
        assertEquals(Binding.MouseWheel("SCROLL_UP", "mouse_wheel SCROLL_UP"), Binding.parse("mouse_wheel SCROLL_UP"))
        assertEquals(
            Binding.ControllerAction(listOf("CHANGE_PRESET", "2", "0", "0"), "controller_action CHANGE_PRESET 2 0 0, , "),
            Binding.parse("controller_action CHANGE_PRESET 2 0 0, , "),
        )
        assertEquals(Binding.ModeShift("joystick", 3, "mode_shift joystick 3"), Binding.parse("mode_shift joystick 3"))
        assertEquals(Binding.Other("mode_shift joystick"), Binding.parse("mode_shift joystick"))
        assertEquals(Binding.Other(""), Binding.parse(""))
        assertNull(Binding.parse("key_press W").label)
    }

    @Test
    fun `quotes, backslashes and bare tokens`() {
        val nodes = KeyValues.parse("root { bare value \"q\" \"say \\\"hi\\\"\" \"path\" \"C:\\Games\\x\" \"tab\" \"a\\tb\" }")
        val root = nodes.single()
        assertEquals("value", root.string("bare"))
        assertEquals("say \"hi\"", root.string("q"))
        assertEquals("C:\\Games\\x", root.string("path"))
        assertEquals("a\tb", root.string("tab"))
        assertEquals(nodes, KeyValues.parse(KeyValues.write(nodes)))
    }

    @Test(expected = KeyValuesException::class)
    fun `an unclosed block is refused`() {
        KeyValues.parse("\"controller_mappings\" { \"version\" \"3\"")
    }

    @Test(expected = KeyValuesException::class)
    fun `a stray closing brace is refused`() {
        KeyValues.parse("\"a\" \"b\" }")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a file without controller_mappings is not a layout`() {
        ControllerLayout.parse("\"something_else\" { }")
    }

    companion object {
        val LAYOUT = """
            // droidtop test layout
            "controller_mappings"
            {
            	"version"		"3"
            	"revision"		"1"
            	"title"		"#title"
            	"description"		"#description"
            	"creator"		"0"
            	"controller_type"		"controller_xboxone"
            	"actions"
            	{
            		"Default"
            		{
            			"title"		"#set_default"
            			"legacy_set"		"1"
            		}
            	}
            	"action_layers"
            	{
            		"Slow"
            		{
            			"title"		"#layer_slow"
            			"parent_set_name"		"Default"
            		}
            	}
            	"localization"
            	{
            		"english"
            		{
            			"title"		"Keyboard (WASD) and Mouse"
            			"description"		"Move with WASD, look with the mouse"
            			"set_default"		"Default"
            			"layer_slow"		"Slow aim"
            		}
            	}
            	"group"
            	{
            		"id"		"0"
            		"mode"		"dpad"
            		"droidtop_unknown_to_reader"		"1"
            		"inputs"
            		{
            			"dpad_north"
            			{
            				"activators"
            				{
            					"Full_Press"
            					{
            						"bindings"
            						{
            							"binding"		"key_press W, Forward, "
            						}
            					}
            				}
            			}
            			"dpad_south"
            			{
            				"activators"
            				{
            					"Full_Press"
            					{
            						"bindings"
            						{
            							"binding"		"key_press S, Back, "
            						}
            					}
            				}
            			}
            		}
            		"settings"
            		{
            			"requires_click"		"0"
            		}
            	}
            	"group"
            	{
            		"id"		"1"
            		"mode"		"four_buttons"
            		"inputs"
            		{
            			"button_a"
            			{
            				"activators"
            				{
            					"Full_Press"
            					{
            						"bindings"
            						{
            							"binding"		"key_press SPACE, Jump, "
            						}
            					}
            					"Long_Press"
            					{
            						"bindings"
            						{
            							"binding"		"key_press E"
            						}
            						"settings"
            						{
            							"long_press_time"		"400"
            						}
            					}
            				}
            			}
            			"button_y"
            			{
            				"activators"
            				{
            					"chord"
            					{
            						"bindings"
            						{
            							"binding"		"key_press LEFT_CONTROL"
            							"binding"		"key_press C"
            						}
            						"settings"
            						{
            							"chord_button"		"3"
            						}
            					}
            				}
            			}
            		}
            	}
            	"group"
            	{
            		"id"		"2"
            		"mode"		"joystick_mouse"
            		"inputs"
            		{
            			"click"
            			{
            				"activators"
            				{
            					"Full_Press"
            					{
            						"bindings"
            						{
            							"binding"		"mouse_button LEFT, , "
            						}
            					}
            				}
            			}
            		}
            		"settings"
            		{
            			"sensitivity"		"150"
            		}
            	}
            	"group"
            	{
            		"id"		"3"
            		"mode"		"joystick_mouse"
            		"settings"
            		{
            			"sensitivity"		"50"
            		}
            	}
            	"preset"
            	{
            		"id"		"0"
            		"name"		"Default"
            		"group_source_bindings"
            		{
            			"0"		"joystick active"
            			"1"		"button_diamond active"
            			"2"		"right_joystick active"
            			"3"		"right_joystick active modeshift"
            		}
            	}
            	"preset"
            	{
            		"id"		"1"
            		"name"		"Slow"
            		"group_source_bindings"
            		{
            			"3"		"right_joystick inactive"
            		}
            	}
            	"settings"
            	{
            		"left_trackpad_mode"		"0" [${'$'}WIN32]
            	}
            }
        """.trimIndent()
    }
}
