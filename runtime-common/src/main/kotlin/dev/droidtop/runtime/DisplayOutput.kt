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
    /**
     * The panel's largest supported mode (`Display.getSupportedModes`),
     * orientation-free like the mode itself. Equal to the current size
     * when Android reports nothing better.
     */
    val nativeWidthPx: Int = widthPx,
    val nativeHeightPx: Int = heightPx,
) {
    /** See [DisplayModes.isFallback]. */
    val isInFallbackMode: Boolean
        get() = DisplayModes.isFallback(widthPx, heightPx, nativeWidthPx, nativeHeightPx)

    /** "480×640 of 1080×1920", for telling the user what is wrong. */
    fun modeSummary(): String = "${widthPx}\u00D7$heightPx of ${nativeWidthPx}\u00D7$nativeHeightPx"
}

/**
 * The add-on can come up in a VGA-class safe mode (480×640 against its
 * 1080×1920 native, per its own DRM mode list) and stays there until it is
 * power-cycled (docs/SPEC.md section 4). Android reports that as an
 * ordinary display, so nothing looks wrong except that everything is
 * blurry; droidtop names it instead.
 */
object DisplayModes {
    /**
     * Below 720 on the short side is safe-mode territory for anything
     * droidtop drives; a 1080p external panel that also lists 4K is a
     * choice Android made, not a fault, so it is not flagged.
     */
    private const val FALLBACK_SHORT_SIDE_PX = 720

    /**
     * True when the current mode is a low safe mode AND the panel says it
     * can do more. Area rather than width/height, so a rotated current
     * size compares correctly with an unrotated mode.
     */
    fun isFallback(widthPx: Int, heightPx: Int, nativeWidthPx: Int, nativeHeightPx: Int): Boolean {
        if (widthPx <= 0 || heightPx <= 0) return false
        val currentArea = widthPx.toLong() * heightPx
        val nativeArea = nativeWidthPx.toLong() * nativeHeightPx
        return minOf(widthPx, heightPx) < FALLBACK_SHORT_SIDE_PX && currentArea < nativeArea
    }
}

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
