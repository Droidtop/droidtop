package dev.droidtop.library.controller

/**
 * Which glyph family an external pad belongs to, from its USB or Bluetooth
 * vendor and product id with its name as the fallback.
 *
 * The ids are SDL's, not ours: [SdlControllerIds] is generated from SDL's
 * controller list, and the rules below are the ones
 * `SDL_GetJoystickGameControllerTypeFromVIDPID` (src/joystick/SDL_joystick.c,
 * zlib licence) applies before and around that list. An id SDL does not know
 * answers null, which is "unknown", never a guess.
 */
object ControllerClassifier {
    fun classify(vendorId: Int, productId: Int, name: String?): GlyphFamily? {
        // Some Switch clones are only identifiable by their name (SDL, same function).
        if (vendorId == 0 && productId == 0) {
            return if (name in NAME_ONLY_NINTENDO) GlyphFamily.NINTENDO else null
        }
        return when {
            vendorId == 0x0001 && productId == 0x0001 -> null
            // The Nintendo Online NES pad shares the right Joy-Con's id and is not a Joy-Con (SDL).
            vendorId == 0x057e && productId == 0x2007 && name?.contains("NES Controller") == true -> null
            // Pads SDL gives their own type: Amazon Luna (USB and Bluetooth ids), Google Stadia and
            // the NVIDIA Shield print the Xbox letters in the Xbox positions.
            (vendorId == 0x1949 && productId == 0x0419) || (vendorId == 0x0171 && productId == 0x0419) -> GlyphFamily.XBOX
            vendorId == 0x18d1 && productId == 0x9400 -> GlyphFamily.XBOX
            vendorId == 0x0955 && (productId == 0x7210 || productId == 0x7214) -> GlyphFamily.XBOX
            else -> SdlControllerIds.familyOf(vendorId, productId)
        }
    }

    private val NAME_ONLY_NINTENDO = setOf("Lic Pro Controller", "Nintendo Wireless Gamepad", "Wireless Gamepad")
}
