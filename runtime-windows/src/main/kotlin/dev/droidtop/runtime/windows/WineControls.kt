package dev.droidtop.runtime.windows

import dev.droidtop.library.GameControls

/** The file of each [GameControls] layout, in Steam's format (Droidtop/tracker#618 C5; docs/SPEC.md 5b "Controller layouts"). */
internal val GameControls.raw: Int
    get() = when (this) {
        GameControls.KEYBOARD_MOUSE -> R.raw.controls_keyboard_mouse
        GameControls.MOUSE_ONLY -> R.raw.controls_mouse_only
    }
