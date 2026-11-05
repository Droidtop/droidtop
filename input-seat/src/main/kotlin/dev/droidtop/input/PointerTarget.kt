package dev.droidtop.input

/**
 * Where a surface's absolute pointer motion (a finger, a stylus, a mouse Android places) goes in the compositor
 * (Droidtop/tracker#908, step D3).
 */
sealed interface PointerTarget {
    /**
     * The unnamed call (HostBridge's injectPointerMotionAbsolute without a name), which spreads the point over the
     * compositor's whole layout: exactly the primary while it is the only output, which is how the one-screen
     * desktop has always worked.
     */
    data object Layout : PointerTarget

    /** The named call: the point lands on the output named [name], wherever it sits in the layout. */
    data class Output(val name: String) : PointerTarget

    /** Nowhere: a surface whose output does not exist (yet, or any more) sends no position at all. */
    data object Nowhere : PointerTarget

    companion object {
        /**
         * The target for a surface: [primary] for the desktop's own surface, else one on another display;
         * [surfaceOutput] the compositor output it shows (null while it has none, "" for a primary the compositor has
         * not named); [outputCount] how many outputs the desktop has.
         *
         * The primary alone keeps the unnamed call, byte for byte what it sent before. Once a second output exists
         * the unnamed call would spread a touch over both, so every surface uses its own output's name. A primary
         * the compositor never named keeps the unnamed call (there is no name to give; sway always names its
         * outputs, so this is the labwc case, where nothing adds a second output). A second surface never takes the
         * unnamed call: without its output it sends nothing.
         */
        fun choose(primary: Boolean, surfaceOutput: String?, outputCount: Int): PointerTarget = when {
            primary && outputCount <= 1 -> Layout
            !surfaceOutput.isNullOrEmpty() -> Output(surfaceOutput)
            primary -> Layout
            else -> Nowhere
        }
    }
}
