package dev.droidtop.runtime

/** Layout orientation follows the surface's measured bounds, including external displays. */
fun isPortraitWindow(width: Int, height: Int): Boolean = height > width
