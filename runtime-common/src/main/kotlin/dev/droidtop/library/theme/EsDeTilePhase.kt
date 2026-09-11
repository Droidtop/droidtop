package dev.droidtop.library.theme

/**
 * Real `tileHorizontalAlignment` / `tileVerticalAlignment`
 * (ImageComponent.cpp:684-718), as the offset the repeating pattern
 * starts at.
 *
 * ES-DE expresses this in `updateVertices` by choosing which CORNER the
 * tile grid is flush with: `left`/`top` (the defaults) start the first
 * whole tile at the box's own origin and leave the partial tile at the
 * far edge; `right`/`bottom` do the opposite, so the partial tile is at
 * the near edge instead. There is no centre option on either axis --
 * ImageComponent.cpp:690-701 and :707-718 accept exactly two literals
 * each and fall back to left/top with a warning.
 *
 * A repeating shader cannot pick a corner, but it can be phase-shifted,
 * and the two are the same thing: shifting the pattern back by the size
 * of the leftover strip puts the partial tile at the near edge. The
 * result is always in `(-tile, 0]` -- zero when the tiles divide the box
 * evenly, in which case the two alignments are identical and ES-DE draws
 * the same pixels either way.
 */
fun esDeTilePhaseOffset(boxPx: Float, tilePx: Float, alignToFarEdge: Boolean): Float {
    if (!alignToFarEdge || tilePx <= 0f || boxPx <= 0f) return 0f
    val leftover = boxPx % tilePx
    if (leftover == 0f) return 0f
    return leftover - tilePx
}
