package dev.droidtop.library.romdetect

/** Real, forked wholesale (unmodified) from Lemuroid -- see SerialScanner.kt's own doc comment. */
class MagicNumber(val offset: Int, val numbers: ByteArray, val systemID: SystemID)
