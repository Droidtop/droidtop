package dev.droidtop.library.theme

import dev.droidtop.notices.ColorMath

/**
 * Standard's taskbar inks (`StandardTaskbar`), from the colours outside Gaming (docs/SPEC.md 7k, "Colours outside
 * Gaming"): [plate] under everything, [text] on it, the [divider] between pinned and open apps, the [focusFill] of a
 * pressed or focused button, and the [accent] of the running dot and the focus ring. They were fixed dark-plate
 * constants, so a light theme never reached the bar (Droidtop/tracker#701).
 */
data class TaskbarInks(val plate: Int, val text: Int, val divider: Int, val focusFill: Int, val accent: Int) {
    companion object {
        /** The plate is 90 percent opaque: the app behind shows through a little, as before. */
        const val PLATE_ALPHA = 0xE6

        /**
         * The plate is the theme's overlay surface over whatever app is behind it, so the text is held to 4.5:1 and
         * the accent (a mark, not text) to 3:1 against the plate over both black and white: one bar reads over a
         * dark game and a light document alike.
         */
        fun of(colors: GamingThemeColors): TaskbarInks {
            val plate = ColorMath.withAlpha(colors.overlaySurface, PLATE_ALPHA)
            val behind = listOf(ColorMath.over(plate, ColorMath.BLACK), ColorMath.over(plate, ColorMath.WHITE))
            val text = ColorMath.ensure(colors.onSurface, behind, 4.5)
            return TaskbarInks(
                plate = plate,
                text = text,
                divider = ColorMath.withAlpha(colors.onSurfaceMuted, 0x55),
                focusFill = ColorMath.withAlpha(text, 0x33),
                accent = ColorMath.ensure(colors.accent, behind, 3.0),
            )
        }
    }
}
