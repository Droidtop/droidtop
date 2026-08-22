package dev.droidtop.library.romdetect

/**
 * Real, forked wholesale (unmodified) from Lemuroid (github.com/Swordfish90/
 * Lemuroid, GPL-3.0, itself continuing the Retrograde project -- see
 * SerialScanner.kt's own doc comment for the full real fork rationale).
 * Deliberately a separate id space from [dev.droidtop.library.consoles.
 * ConsoleSystemDef.id] (ES-DE's own system ids) -- they don't line up 1:1
 * (e.g. this uses "scd"/"md", ES-DE uses "segacd"/"genesis") and mapping
 * between them is real, unfinished work, not assumed done here.
 */
enum class SystemID(val dbname: String) {
    NES("nes"),
    SNES("snes"),
    GENESIS("md"),
    GB("gb"),
    GBC("gbc"),
    GBA("gba"),
    N64("n64"),
    SMS("sms"),
    PSP("psp"),
    NDS("nds"),
    GG("gg"),
    ATARI2600("atari2600"),
    PSX("psx"),
    FBNEO("fbneo"),
    MAME2003PLUS("mame2003plus"),
    PC_ENGINE("pce"),
    LYNX("lynx"),
    ATARI7800("atari7800"),
    SEGACD("scd"),
    NGP("ngp"),
    NGC("ngc"),
    WS("ws"),
    WSC("wsc"),
    DOS("dos"),
    NINTENDO_3DS("3ds"),
}
