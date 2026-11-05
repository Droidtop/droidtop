package dev.droidtop.library.controller

/**
 * Which glyph family an external pad belongs to, from its USB or Bluetooth vendor and product id with its name as the
 * fallback: Droidtop/lib-input's `PadFamilies.classify` (SDL's ids and the rules SDL applies around them), the function
 * this file was, shared with Enginehost (Droidtop/tracker#867 D4: one shared controller model). An id SDL does not know
 * answers null, which is "unknown", never a guess.
 */
typealias ControllerClassifier = dev.droidtop.lib.input.PadFamilies
