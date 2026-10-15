package dev.droidtop.hostbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardModifiersTest {
    // evdev codes (linux/input-event-codes.h)
    private val leftShift = 42
    private val rightShift = 54
    private val leftCtrl = 29
    private val leftAlt = 56
    private val rightAlt = 100
    private val leftMeta = 125
    private val capsLock = 58
    private val numLock = 69
    private val keyA = 30

    // Shift 1, Lock 2, Control 4, Mod1 8, Mod2 16, Mod4 64, Mod5 128
    private val us = XkbModifierMap.parse(keymap(rightAltSymbol = "Alt_R", rightAltMap = "Mod1"))

    @Test
    fun `the US map's modifiers are read from its modifier_map`() {
        assertEquals(1, us.modifiersOf(leftShift))
        assertEquals(1, us.modifiersOf(rightShift))
        assertEquals(4, us.modifiersOf(leftCtrl))
        assertEquals(8, us.modifiersOf(leftAlt))
        assertEquals(8, us.modifiersOf(rightAlt))
        assertEquals(64, us.modifiersOf(leftMeta))
        assertEquals(2, us.modifiersOf(capsLock))
        assertEquals(16, us.modifiersOf(numLock))
        assertEquals(0, us.modifiersOf(keyA))
        assertTrue(us.isLock(capsLock))
        assertTrue(us.isLock(numLock))
        assertFalse(us.isLock(leftShift))
    }

    @Test
    fun `a right Alt that is ISO_Level3_Shift sets what LVL3 is mapped to`() {
        val de = XkbModifierMap.parse(keymap(rightAltSymbol = "ISO_Level3_Shift", rightAltMap = null))
        assertEquals(128, de.modifiersOf(rightAlt))
        assertEquals(8, de.modifiersOf(leftAlt))
    }

    @Test
    fun `a keysym entry in modifier_map maps every key with that symbol`() {
        val map = XkbModifierMap.parse(
            """
            xkb_keymap {
            xkb_keycodes "x" { <LWIN> = 133; <RWIN> = 134; };
            xkb_symbols "x" {
                key <LWIN> { [ Super_L ] };
                key <RWIN> { [ Super_R ] };
                modifier_map Mod4 { Super_L, Super_R };
            };
            };
            """.trimIndent(),
        )
        assertEquals(64, map.modifiersOf(125))
        assertEquals(64, map.modifiersOf(126))
    }

    @Test
    fun `text with no modifier map is the empty map`() {
        assertEquals(0, XkbModifierMap.parse("not a keymap").modifiersOf(leftShift))
        assertEquals(0, XkbModifierMap.parse("").modifiersOf(leftShift))
    }

    @Test
    fun `holding Shift sends Shift until the last Shift is up`() {
        val mods = KeyboardModifiers(us)
        assertEquals(KeyboardModifiers.Masks(1, 0), mods.onKey(leftShift, true))
        assertNull(mods.onKey(keyA, true))
        assertNull(mods.onKey(keyA, false))
        assertNull(mods.onKey(rightShift, true))
        assertNull(mods.onKey(leftShift, false))
        assertEquals(KeyboardModifiers.Masks(0, 0), mods.onKey(rightShift, false))
    }

    @Test
    fun `Ctrl and Super combine`() {
        val mods = KeyboardModifiers(us)
        mods.onKey(leftCtrl, true)
        assertEquals(KeyboardModifiers.Masks(4 or 64, 0), mods.onKey(leftMeta, true))
        assertEquals(KeyboardModifiers.Masks(64, 0), mods.onKey(leftCtrl, false))
    }

    @Test
    fun `Caps Lock toggles the locked set on each press, not on repeats`() {
        val mods = KeyboardModifiers(us)
        assertEquals(KeyboardModifiers.Masks(0, 2), mods.onKey(capsLock, true))
        assertNull(mods.onKey(capsLock, true)) // auto-repeat while held
        assertNull(mods.onKey(capsLock, false))
        assertEquals(KeyboardModifiers.Masks(0, 0), mods.onKey(capsLock, true))
    }

    @Test
    fun `a new map re-reads what held keys set`() {
        val mods = KeyboardModifiers(us)
        assertEquals(KeyboardModifiers.Masks(8, 0), mods.onKey(rightAlt, true))
        val de = XkbModifierMap.parse(keymap(rightAltSymbol = "ISO_Level3_Shift", rightAltMap = null))
        assertEquals(KeyboardModifiers.Masks(128, 0), mods.replaceMap(de))
        assertEquals(KeyboardModifiers.Masks(0, 0), mods.onKey(rightAlt, false))
    }

    @Test
    fun `reset forgets held and locked modifiers`() {
        val mods = KeyboardModifiers(us)
        mods.onKey(leftShift, true)
        mods.onKey(capsLock, true)
        mods.reset()
        assertEquals(KeyboardModifiers.Masks(4, 0), mods.onKey(leftCtrl, true))
    }

    /** The parts of an xkbcommon-printed keymap this reads, in its layout. */
    private fun keymap(rightAltSymbol: String, rightAltMap: String?): String =
        """
        xkb_keymap {
        xkb_keycodes "(unnamed)" {
        	minimum = 8;
        	maximum = 708;
        	<LCTL>               = 37;
        	<AC01>               = 38;
        	<LFSH>               = 50;
        	<RTSH>               = 62;
        	<LALT>               = 64;
        	<CAPS>               = 66;
        	<NMLK>               = 77;
        	<LVL3>               = 92;
        	<RALT>               = 108;
        	<LWIN>               = 133;
        	alias <ALGR>         = <RALT>;
        };
        xkb_types "(unnamed)" {
        	virtual_modifiers NumLock,Alt,LevelThree;
        };
        xkb_compat "(unnamed)" {
        	interpret ISO_Level3_Shift+AnyOf(all) {
        		action= SetMods(modifiers=LevelThree,clearLocks);
        	};
        };
        xkb_symbols "(unnamed)" {
        	key <LCTL>               {	[       Control_L ] };
        	key <AC01>               {	[               a,               A ] };
        	key <LFSH>               {	[         Shift_L ] };
        	key <RTSH>               {	[         Shift_R ] };
        	key <LALT>               {	[           Alt_L,          Meta_L ] };
        	key <CAPS>               {	[       Caps_Lock ] };
        	key <NMLK>               {	[        Num_Lock ] };
        	key <LVL3>               {	[ ISO_Level3_Shift ] };
        	key <RALT>               {
        		type= "ONE_LEVEL",
        		symbols[Group1]= [ $rightAltSymbol ]
        	};
        	key <LWIN>               {	[         Super_L ] };
        	modifier_map Shift { <LFSH>, <RTSH> };
        	modifier_map Lock { <CAPS> };
        	modifier_map Control { <LCTL> };
        	modifier_map Mod1 { <LALT>${if (rightAltMap == "Mod1") ", <RALT>" else ""} };
        	modifier_map Mod2 { <NMLK> };
        	modifier_map Mod4 { <LWIN> };
        	modifier_map Mod5 { <LVL3> };
        };
        };
        """.trimIndent()
}
