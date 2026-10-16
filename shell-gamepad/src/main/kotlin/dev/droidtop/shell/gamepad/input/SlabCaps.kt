package dev.droidtop.shell.gamepad.input

import dev.droidtop.library.controller.FaceRole
import org.pocketworkstation.pckeyboard.Slab

/**
 * What the slab's keycaps print (docs/SPEC.md 4c, "The text-entry slab"): a face button's name as the pad in the
 * person's hands prints it (ControllerLayouts, which follows the console's layout and Swap A and B), so a Nintendo
 * pad's Backspace keycap reads Y; the shoulders and Select by their names.
 */
object SlabCaps {
    fun label(cap: Slab.Cap): String = when (cap) {
        Slab.Cap.X -> ControllerLayouts.layout.glyph(FaceRole.X)
        Slab.Cap.Y -> ControllerLayouts.layout.glyph(FaceRole.Y)
        Slab.Cap.B -> ControllerLayouts.layout.glyph(FaceRole.CANCEL)
        Slab.Cap.L1 -> "L1"
        Slab.Cap.R1 -> "R1"
        Slab.Cap.R2 -> "R2"
        Slab.Cap.SELECT -> "Select"
    }
}
