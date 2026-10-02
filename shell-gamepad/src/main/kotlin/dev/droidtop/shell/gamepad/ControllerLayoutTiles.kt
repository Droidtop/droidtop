package dev.droidtop.shell.gamepad

import dev.droidtop.library.controller.FaceRole
import dev.droidtop.library.controller.LayoutSource
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.CatalogIcon
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.shell.gamepad.input.ControllerLayouts

/**
 * The Quick Menu's two controller tiles (docs/SPEC.md 7b, "Console and
 * controller detection"), drawn on the System tab while a pad is attached:
 *
 * - "Controller buttons" re-runs the "press the button labelled A" capture
 *   for the pad in use, whatever was detected;
 * - "Swap A and B" is the one-press escape hatch when detection is wrong
 *   and a press is not wanted.
 *
 * Both write the same per-pad capture the prompt does, so there is one
 * place an answer lives.
 */
internal object ControllerLayoutTiles {
    const val ID_SET = "pref_controller_set_buttons"
    const val ID_SWAP = "pref_controller_swap_ab"

    fun items(): List<CatalogItem> {
        if (ControllerLayouts.attachedControllers().isEmpty()) return emptyList()
        val layout = ControllerLayouts.layout
        val summary = when (layout.source) {
            LayoutSource.UNKNOWN -> "Not set"
            else -> "${layout.glyph(FaceRole.CONFIRM)} confirms"
        }
        return listOf(
            ActionItem(
                id = ID_SET,
                title = "Controller buttons",
                value = summary,
                icon = CatalogIcon.CONTROLLER,
                run = { ControllerLayouts.requestCapture() },
            ),
            ActionItem(
                id = ID_SWAP,
                title = "Swap A and B",
                subtitle = "For when the buttons are named wrong",
                // The tile showed no state (console, build 1386). On = droidtop trades the A and B
                // (and X and Y) key codes right now, whatever decided it.
                value = if (layout.swapped) "On" else "Off",
                icon = CatalogIcon.CONTROLLER,
                run = { ControllerLayouts.swapNow(it) },
            ),
        )
    }
}
