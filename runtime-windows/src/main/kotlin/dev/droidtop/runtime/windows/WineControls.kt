package dev.droidtop.runtime.windows

import dev.droidtop.library.GamePadLayouts

/** The file of each [GamePadLayouts] layout, in Steam's format (Droidtop/tracker#618 C5; docs/SPEC.md 5b "Controller layouts"). */
internal val GamePadLayouts.raw: Int
    get() = when (this) {
        GamePadLayouts.KEYBOARD_MOUSE -> R.raw.controls_keyboard_mouse
        GamePadLayouts.MOUSE_ONLY -> R.raw.controls_mouse_only
    }
