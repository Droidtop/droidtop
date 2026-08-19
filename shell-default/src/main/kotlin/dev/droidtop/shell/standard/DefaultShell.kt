package dev.droidtop.shell.standard

import dev.droidtop.library.Library

/**
 * The default UI: a normal touch/mouse-first library grid, no gamepad
 * navigation assumed or required. This is what ships first — the gamepad
 * console shell (:shell-gamepad) is a later, optional alternative built
 * against the exact same dev.droidtop.library.Library, not a variant of this one.
 */
class DefaultShell(private val library: Library) {
    // TODO: Compose UI reading Library.scanAll(), launching via Library.launch(entry)
}
