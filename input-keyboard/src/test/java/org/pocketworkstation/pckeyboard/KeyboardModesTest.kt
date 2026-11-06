package org.pocketworkstation.pckeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The input method's modes as pure rules (Droidtop/tracker#903 H12c). */
class KeyboardModesTest {
    @Test fun eachOrientationKeepsItsOwnKeys() {
        assertEquals("pref_keyboard_view_mode", KeyboardModes.key(KeyboardModes.PREF_MODE, landscape = false))
        assertEquals("pref_keyboard_view_mode_landscape", KeyboardModes.key(KeyboardModes.PREF_MODE, landscape = true))
        assertEquals("pref_keyboard_one_handed_width_landscape", KeyboardModes.key(KeyboardModes.PREF_ONE_HANDED_WIDTH, true))
        assertEquals("pref_keyboard_float_place", KeyboardModes.key(KeyboardModes.PREF_FLOAT_PLACE, false))
        // A change of mode, width or size builds the keys again in either orientation; moving the frame does not.
        assertEquals(6, KeyboardModes.REBUILDING_KEYS.size)
        assertTrue("pref_keyboard_float_size_landscape" in KeyboardModes.REBUILDING_KEYS)
        assertFalse(KeyboardModes.REBUILDING_KEYS.any { it.startsWith(KeyboardModes.PREF_FLOAT_PLACE) })
    }

    @Test fun modesAreStoredByIdAndAnythingElseIsNormal() {
        KeyboardModes.Mode.entries.forEach { assertSame(it, KeyboardModes.Mode.of(it.id)) }
        assertSame(KeyboardModes.Mode.NORMAL, KeyboardModes.Mode.of(null))
        assertSame(KeyboardModes.Mode.NORMAL, KeyboardModes.Mode.of(""))
        assertSame(KeyboardModes.Mode.NORMAL, KeyboardModes.Mode.of("nonsense"))
    }

    @Test fun theOneHandedWidthIsClamped() {
        assertEquals(0.8f, KeyboardModes.oneHandedWidth(null), 0f)
        assertEquals(0.8f, KeyboardModes.oneHandedWidth("wide"), 0f)
        assertEquals(0.5f, KeyboardModes.oneHandedWidth("0.1"), 0f)
        assertEquals(0.9f, KeyboardModes.oneHandedWidth("1.5"), 0f)
        assertEquals(0.66f, KeyboardModes.oneHandedWidth("0.66"), 0f)
        assertEquals(0.8f, KeyboardModes.clampOneHanded(Float.NaN), 0f)
    }

    @Test fun theHandleWidensTowardTheFreeSide() {
        // Keys at the left: the handle dragged 108 px right on a 1080 px screen adds a tenth.
        assertEquals(0.8f, KeyboardModes.oneHandedAfterDrag(0.7f, 108f, 1080, atEnd = false), 1e-5f)
        // Keys at the right: dragging left widens them.
        assertEquals(0.8f, KeyboardModes.oneHandedAfterDrag(0.7f, -108f, 1080, atEnd = true), 1e-5f)
        assertEquals(0.5f, KeyboardModes.oneHandedAfterDrag(0.7f, -1000f, 1080, atEnd = false), 0f)
        assertEquals(0.9f, KeyboardModes.oneHandedAfterDrag(0.7f, 1000f, 1080, atEnd = false), 0f)
        assertEquals(0.7f, KeyboardModes.oneHandedAfterDrag(0.7f, 50f, 0, atEnd = false), 0f)
    }

    @Test fun theFloatingSizeIsClampedPerOrientation() {
        assertEquals(KeyboardModes.floatDefault(false), KeyboardModes.floatSize(null, false))
        assertEquals(KeyboardModes.floatDefault(true), KeyboardModes.floatSize("garbage", true))
        assertEquals(KeyboardModes.FloatSize(0.55f, 0.5f), KeyboardModes.floatSize("0.1,0.1", false))
        assertEquals(KeyboardModes.FloatSize(0.75f, 1.2f), KeyboardModes.floatSize("1,3", true))
        val size = KeyboardModes.FloatSize(0.6f, 0.9f)
        assertEquals(size, KeyboardModes.floatSize(KeyboardModes.encode(size), false))
        // The corner follows the finger: 108 px across a 1080 px screen, 40 px down keys 400 px tall.
        val grown = KeyboardModes.resized(size, 108f, 40f, 1080, 400, false)
        assertEquals(0.7f, grown.width, 1e-5f)
        assertEquals(1.0f, grown.heightScale, 1e-5f)
    }

    @Test fun theFloatingFrameStaysOnTheScreen() {
        val clamp = { x: Int, y: Int -> KeyboardModes.clampFrame(KeyboardModes.Frame(x, y, 400, 300), 1080, 1920) }
        assertEquals(KeyboardModes.Frame(0, 0, 400, 300), clamp(-50, -20))
        assertEquals(KeyboardModes.Frame(680, 1620, 400, 300), clamp(5000, 5000))
        assertEquals(KeyboardModes.Frame(100, 200, 400, 300), clamp(100, 200))
        // A frame bigger than the screen is cut to it, at the corner.
        assertEquals(KeyboardModes.Frame(0, 0, 1080, 1920), KeyboardModes.clampFrame(KeyboardModes.Frame(30, 30, 2000, 3000), 1080, 1920))
        // Nowhere to put it: nothing negative.
        assertEquals(KeyboardModes.Frame(0, 0, 0, 0), KeyboardModes.clampFrame(KeyboardModes.Frame(10, 10, 40, 40), 0, 0))
    }

    @Test fun theFloatingPlaceIsRememberedAsSharesOfTheScreen() {
        // Nothing stored: centred at the bottom.
        assertEquals(KeyboardModes.Frame(340, 1620, 400, 300), KeyboardModes.place(null, 400, 300, 1080, 1920))
        val frame = KeyboardModes.Frame(108, 960, 400, 300)
        val stored = KeyboardModes.encodePlace(frame, 1080, 1920)
        assertEquals(frame, KeyboardModes.place(stored, 400, 300, 1080, 1920))
        // A place kept on a bigger screen is brought back onto this one.
        assertEquals(KeyboardModes.Frame(680, 1620, 400, 300), KeyboardModes.place("0.99,0.99", 400, 300, 1080, 1920))
        assertEquals(KeyboardModes.Frame(340, 1620, 400, 300), KeyboardModes.place("x,y", 400, 300, 1080, 1920))
    }

    @Test fun eachModeDrawsItsForm() {
        val normal = KeyboardForm.SPLIT
        val size = KeyboardModes.FloatSize(0.6f, 0.9f)
        val thumb = KeyboardForm.thumb(2560, 256f)
        assertSame(normal, KeyboardModes.form(KeyboardModes.Mode.NORMAL, normal, 0.8f, size, thumb))
        assertEquals(KeyboardForm.Form("left", 0.8f, 0f, false), KeyboardModes.form(KeyboardModes.Mode.LEFT, normal, 0.8f, size, thumb))
        assertEquals(KeyboardForm.Form("right", 0.9f, 0f, true), KeyboardModes.form(KeyboardModes.Mode.RIGHT, normal, 2f, size, thumb))
        assertEquals(KeyboardForm.Form("floating", 0.6f, 0f, false, 0.9f), KeyboardModes.form(KeyboardModes.Mode.FLOATING, normal, 0.8f, size, thumb))
        assertSame(thumb, KeyboardModes.form(KeyboardModes.Mode.THUMB, normal, 0.8f, size, thumb))
        // No thumb split on this screen: H1's split.
        assertSame(KeyboardForm.SPLIT, KeyboardModes.form(KeyboardModes.Mode.THUMB, KeyboardForm.FULL, 0.8f, size, null))
        assertSame(KeyboardForm.FULL, KeyboardModes.form(KeyboardModes.Mode.NUMBER_PAD, normal, 0.8f, size, thumb))
    }

    @Test fun theChooserOffersTheThumbSplitOnlyWhereItFits() {
        assertEquals(7, KeyboardModes.offered(true).size)
        assertFalse(KeyboardModes.Mode.THUMB in KeyboardModes.offered(false))
        assertSame(KeyboardModes.Mode.NORMAL, KeyboardModes.offered(false).first())
    }

    @Test fun aHandheldsChooserOffersH1sSplit() {
        // The Retroid Pocket 5 sideways has no thumb split, but H1's split is right after Normal.
        val handheld = KeyboardModes.offered(KeyboardForm.thumb(1920, 401f) != null)
        assertEquals(
            listOf("normal", "split", "left", "right", "floating", "numpad"),
            handheld.map { it.id },
        )
        // Split is H1's KeyboardForm split, not a form of its own.
        assertSame(KeyboardForm.SPLIT, KeyboardModes.form(KeyboardModes.Mode.SPLIT, KeyboardForm.SPLIT, 0.8f, KeyboardModes.floatDefault(true), null))
    }

    @Test fun theChooserWritesTheShapeAsTheLayoutButtonDoes() {
        assertEquals("normal" to "full", KeyboardModes.writes(KeyboardModes.Mode.NORMAL))
        assertEquals("normal" to "split", KeyboardModes.writes(KeyboardModes.Mode.SPLIT))
        assertEquals("normal" to "left", KeyboardModes.writes(KeyboardModes.Mode.LEFT))
        assertEquals("normal" to "right", KeyboardModes.writes(KeyboardModes.Mode.RIGHT))
        // The others leave the shape for when Normal or a shape is chosen again.
        assertEquals("floating" to null, KeyboardModes.writes(KeyboardModes.Mode.FLOATING))
        assertEquals("thumb" to null, KeyboardModes.writes(KeyboardModes.Mode.THUMB))
        assertEquals("numpad" to null, KeyboardModes.writes(KeyboardModes.Mode.NUMBER_PAD))
    }

    @Test fun theFormAndTheModeAlwaysAgree() {
        // Whatever the chooser stores reads back as the same choice, from any shape stored before.
        for (choice in KeyboardModes.Mode.entries) {
            for (before in listOf(null, "auto", "full", "split", "left", "right")) {
                val (mode, shape) = KeyboardModes.writes(choice)
                assertSame("$choice over $before", choice, KeyboardModes.chosen(mode, shape ?: before))
            }
        }
        // A shape picked in the settings or by an embedded keyboard's Layout button is what the chooser lights.
        assertSame(KeyboardModes.Mode.SPLIT, KeyboardModes.chosen(null, "split"))
        assertSame(KeyboardModes.Mode.LEFT, KeyboardModes.chosen("normal", "left"))
        assertSame(KeyboardModes.Mode.RIGHT, KeyboardModes.chosen(null, "right"))
        assertSame(KeyboardModes.Mode.NORMAL, KeyboardModes.chosen(null, "full"))
        // Floating, the thumb split and the number pad are the mode's, whatever the shape.
        assertSame(KeyboardModes.Mode.FLOATING, KeyboardModes.chosen("floating", "left"))
    }

    @Test fun oneHandedIsOneThingInTheInputMethod() {
        // The shape setting's one-handed choice draws the resizable one-handed mode, at the kept width.
        val chosen = KeyboardModes.chosen(null, "left")
        assertEquals(KeyboardForm.Form("left", 0.8f, 0f, false), KeyboardModes.form(chosen, KeyboardForm.LEFT, 0.8f, KeyboardModes.floatDefault(false), null))
    }

    @Test fun theMigrationMovesOneHandedIntoTheShapeAndKeepsItsWidth() {
        // H12c's first build kept one-handed in the mode setting: it moves to the shape, its width as it was.
        assertEquals(Triple("normal", "left", null), KeyboardModes.migrated("left", "split", widthSet = true))
        assertEquals(Triple("normal", "right", null), KeyboardModes.migrated("right", null, widthSet = false))
        // A one-handed shape chosen before H12c keeps its 70%.
        assertEquals(Triple(null, null, "0.7000"), KeyboardModes.migrated(null, "right", widthSet = false))
        assertEquals(null, KeyboardModes.migrated("normal", "left", widthSet = true))
        // Nothing else changes: Hacker's and H1's values stay as they are.
        assertEquals(null, KeyboardModes.migrated(null, "split", widthSet = false))
        assertEquals(null, KeyboardModes.migrated("floating", "auto", widthSet = false))
        assertEquals(null, KeyboardModes.migrated(null, null, widthSet = false))
        assertEquals(0.7f, KeyboardModes.oneHandedWidth("0.7000"), 0f)
    }

    @Test fun theAddOnScreenOfAHandheldIsCompact() {
        assertTrue(KeyboardModes.compactPanel(onDefaultDisplay = false, handheld = true))
        assertFalse(KeyboardModes.compactPanel(onDefaultDisplay = true, handheld = true))
        // An external monitor is another display, but not a handheld's.
        assertFalse(KeyboardModes.compactPanel(onDefaultDisplay = false, handheld = false))
    }

    @Test fun theCompactHeightNeverMakesARowShorterThanAFinger() {
        // A 1920 px tall screen at 400 dpi: five rows of 0.3 inch are 600 px, 31.25%.
        assertEquals(31.25f, KeyboardModes.compactHeightPercent(35f, 1920, 400f), 1e-3f)
        // Room to spare: 80% of what was asked.
        assertEquals(36f, KeyboardModes.compactHeightPercent(45f, 1920, 160f), 1e-3f)
        // Never taller than what was asked.
        assertEquals(20f, KeyboardModes.compactHeightPercent(20f, 1920, 400f), 1e-3f)
        // No believable dpi: the share alone.
        assertEquals(28f, KeyboardModes.compactHeightPercent(35f, 1920, 0f), 1e-3f)
    }
}
