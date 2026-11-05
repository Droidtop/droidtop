package dev.droidtop.library.diagnostics

/**
 * Whether a shown frame is a black screen (docs/SPEC.md 7e2, "A black screen is noticed"; Droidtop/tracker#601): one
 * flat dark colour, a pointer allowed. The frame is a small copy of what the person sees, read whichever renderer drew
 * it. Pure.
 */
object FrameDarkness {
    /** The brightest a pixel may be and still count as dark (0..255 luma). */
    const val DARK_LUMA = 24

    /** How much of the frame may be something else and still be a black screen: the pointer, a stray pixel. */
    const val NOT_DARK_ALLOWANCE = 0.02

    /**
     * Whether [argb] is not a frame the renderer drew at all: every pixel fully transparent. droidtop's renderers
     * clear to opaque black and present opaque frames, so even a black screen they drew has alpha 255; a copy that
     * comes back transparent read nothing (an emulator's GPU buffers that cannot be read back: Droidtop/tracker#896,
     * Cult of the Lamb's bright title screen on BlueStacks noticed as black). Such a frame says nothing. Pure.
     */
    fun isUnreadable(argb: IntArray): Boolean = argb.isNotEmpty() && argb.all { (it ushr 24) == 0 }

    /** Whether [argb] (packed ARGB pixels of one frame) is a black screen. An empty or unreadable frame is not. */
    fun isFlatDark(argb: IntArray): Boolean {
        if (argb.isEmpty() || isUnreadable(argb)) return false
        val allowed = (argb.size * NOT_DARK_ALLOWANCE).toInt()
        var bright = 0
        for (pixel in argb) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            // Rec. 601 luma, in integers.
            if ((r * 299 + g * 587 + b * 114) / 1000 > DARK_LUMA && ++bright > allowed) return false
        }
        return true
    }
}

/**
 * When a running game's screen has been black long enough to say so (docs/SPEC.md 7e2, "A black screen is noticed"):
 * after the game showed its window, every sampled frame flat dark for [holdMs]. One bright frame starts the count again,
 * so a game that fades through black or loads on a black screen for a while is left alone. It says so once; Keep
 * waiting ([keepWaiting]) starts a new count. Pure: the caller samples and gives the time.
 */
class BlackScreenWatch(private val holdMs: Long = HOLD_MS) {
    private var darkSince: Long? = null
    private var said = false

    /** One sampled frame at [nowMs]; true exactly when the black screen should be said now. */
    fun sample(nowMs: Long, windowShown: Boolean, flatDark: Boolean): Boolean {
        if (!windowShown || !flatDark) {
            darkSince = null
            return false
        }
        val since = darkSince ?: nowMs.also { darkSince = it }
        if (said || nowMs - since < holdMs) return false
        said = true
        return true
    }

    /** The person chose to keep waiting: the count starts again from the next frame. */
    fun keepWaiting() {
        darkSince = null
        said = false
    }

    companion object {
        /** How long the screen stays black before droidtop says so. */
        const val HOLD_MS = 45_000L

        /** How often a frame is sampled. */
        const val SAMPLE_MS = 2_000L

        /** The sampled copy's size: enough to tell a flat screen from a picture, small enough to read every time. */
        const val SAMPLE_WIDTH = 48
        const val SAMPLE_HEIGHT = 27
    }
}
