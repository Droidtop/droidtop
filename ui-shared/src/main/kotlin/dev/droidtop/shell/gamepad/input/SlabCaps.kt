package dev.droidtop.shell.gamepad.input

import dev.droidtop.library.controller.FaceRole
import org.pocketworkstation.pckeyboard.Slab

/**
 * What the slab's keycaps print (docs/SPEC.md 4c, "The text-entry slab"): a face button's name as the pad in the
 * person's hands prints it (ControllerLayouts, which follows the console's layout and Swap A and B), so a Nintendo
 * pad's Backspace keycap reads Y; the shoulders and Select by their names.
 */
object SlabCaps {
    fun label(cap: Slab.PadButton): String = when (cap) {
        Slab.PadButton.X -> ControllerLayouts.layout.glyph(FaceRole.X)
        Slab.PadButton.Y -> ControllerLayouts.layout.glyph(FaceRole.Y)
        Slab.PadButton.B -> ControllerLayouts.layout.glyph(FaceRole.CANCEL)
        Slab.PadButton.L1 -> "L1"
        Slab.PadButton.R1 -> "R1"
        Slab.PadButton.R2 -> "R2"
        Slab.PadButton.SELECT -> "Select"
    }
}
