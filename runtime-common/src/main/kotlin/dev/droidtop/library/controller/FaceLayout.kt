package dev.droidtop.library.controller

// What is printed on a pad's face buttons, and how its face buttons behave: Droidtop/lib-input's model (Droidtop/tracker#867
// D4, the owner's "FaceRole to lib-input"), the code this file was, moved there unchanged but for the family's name. The
// names droidtop uses stay, as typealiases. GlyphFamily is lib-input's PadFamily: its entries and fromId (an enum name in
// any case, or Enginehost's stored id) are the same.
typealias GlyphFamily = dev.droidtop.lib.input.PadFamily
typealias FacePosition = dev.droidtop.lib.input.FacePosition
typealias FaceRole = dev.droidtop.lib.input.FaceRole
typealias LayoutSource = dev.droidtop.lib.input.LayoutSource
typealias FaceLayout = dev.droidtop.lib.input.FaceLayout
