package dev.droidtop.runtime

/**
 * One physical or virtual output the primary container's compositor can
 * present a window on: the device's built-in screen, the Retroid-style
 * second screen, or an external lapdock monitor over USB-C DP alt mode.
 * Each maps 1:1 to an Android [android.view.Display] on the host side and
 * a headless wlroots output inside the container.
 */
data class DisplayOutput(
    val id: String,
    val androidDisplayId: Int,
    val kind: DisplayOutputKind,
    val widthPx: Int,
    val heightPx: Int,
    /**
     * Android's own name for the panel ("Built-in Screen", "DP Screen").
     * Carried so a screen-mapping UI can name what the user is looking
     * at instead of showing a display id, which means nothing to anyone.
     */
    val name: String = "",
    /**
     * True when Android flags this display as presentation-capable, which
     * on real hardware means an external/secondary panel rather than the
     * built-in one. A real signal, unlike enumeration order -- see
     * [DisplayOutputRepository] for the device dump that established it.
     */
    val isPresentation: Boolean = false,
)

enum class DisplayOutputKind { PRIMARY_SCREEN, SECOND_SCREEN, EXTERNAL }

/**
 * Which [DisplayOutput] a given window is currently assigned to. Windows
 * default to [WindowPlacement.merged] on the primary screen's shared desktop
 * — the "PC-in-a-box" experience — but any window can be reassigned to its
 * own output at runtime without restarting the app/container that owns it.
 * This is a compositor-side operation (see host-bridge), not a per-app one.
 */
data class WindowPlacement(
    val windowId: String,
    val output: DisplayOutput,
    val fullscreen: Boolean,
) {
    companion object {
        fun merged(windowId: String, primary: DisplayOutput) =
            WindowPlacement(windowId, primary, fullscreen = false)
    }
}
