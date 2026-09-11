package dev.droidtop.library.theme

/**
 * Real `cropPos` (and its `imageCropPos` spelling on the elements that
 * have one), as the alignment bias of a cropped texture inside its own box.
 *
 * ES-DE's crop is centred by default and `cropPos` moves the kept window:
 * "the first value of the pair is the X axis where 0 means align to the
 * left and 1 means align to the right, and the second value of the pair is
 * the Y axis where 0 means align on top and 1 means align at the bottom"
 * (THEMES-DEV.md:2451-2455 for `image`, :2598-2602 for `video`), default
 * 0.5 0.5, clamped 0-1, and only meaningful together with `cropSize`
 * (ImageComponent.cpp:550-556, whose `coverFitCrop` then offsets the
 * sampled rectangle by `(cropSize * (cropPos + 0.5)) - cropSize`, i.e. a
 * shift of half the cropped-away amount at each extreme, :240-260).
 *
 * A Compose alignment bias runs -1 to 1 over the same span, so the whole
 * conversion is this one line -- which is worth having in one place and
 * under test, because the obvious wrong reading (passing the 0-1 fraction
 * straight through as a bias) silently clamps every theme's crop to the
 * right-hand / bottom half of its image.
 */
fun esDeCropBias(cropPos: Float): Float = cropPos.coerceIn(0f, 1f) * 2f - 1f
