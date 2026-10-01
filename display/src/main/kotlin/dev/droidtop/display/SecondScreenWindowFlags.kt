package dev.droidtop.display

import android.view.WindowManager

/** Window flags shared by the companion's activity and presentation hosts. */
object SecondScreenWindowFlags {
    /** Companion surfaces take touch input, while key and pad input stay with the shell. */
    fun touchOnly(includeAltFocusableIm: Boolean = false): Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            (if (includeAltFocusableIm) WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM else 0)
}
