package dev.droidtop.library.romdetect

/**
 * Real, forked wholesale (unmodified) from Lemuroid (github.com/Swordfish90/
 * Lemuroid, GPL-3.0, itself continuing the Retrograde project -- see
 * SerialScanner.kt's own doc comment for the full real fork rationale).
 * Deliberately a separate id space from [dev.droidtop.library.consoles.
 * ConsoleSystemDef.id] (ES-DE's own system ids) -- they don't line up 1:1
 * (e.g. this uses "scd"/"md", ES-DE uses "segacd"/"genesis"); [toConsoleSystemId]
 * is the real mapping between them, cross-checked against
 * EsDeConsoleSystems.kt's own real ids and display names one at a time,
 * not guessed. `null` for [MAME2003PLUS] specifically -- it's a libretro
 * *core* name, not a real distinct ES-DE system id on its own (ES-DE's
 * own arcade systems are split by board/platform, not by which MAME
 * version emulates them), so there's no single correct id to map it to.
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
    PS2("ps2"),
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

/** Real [dev.droidtop.library.consoles.ConsoleSystemDef.id] this [SystemID] corresponds to, or null when none exists (see [SystemID]'s own doc comment). */
fun SystemID.toConsoleSystemId(): String? = when (this) {
    SystemID.NES -> "nes"
    SystemID.SNES -> "snes"
    SystemID.GENESIS -> "genesis"
    SystemID.GB -> "gb"
    SystemID.GBC -> "gbc"
    SystemID.GBA -> "gba"
    SystemID.N64 -> "n64"
    SystemID.SMS -> "mastersystem"
    SystemID.PSP -> "psp"
    SystemID.NDS -> "nds"
    SystemID.GG -> "gamegear"
    SystemID.ATARI2600 -> "atari2600"
    SystemID.PSX -> "psx"
    SystemID.PS2 -> "ps2"
    SystemID.FBNEO -> "fbneo"
    SystemID.MAME2003PLUS -> null
    SystemID.PC_ENGINE -> "tg16"
    SystemID.LYNX -> "atarilynx"
    SystemID.ATARI7800 -> "atari7800"
    SystemID.SEGACD -> "segacd"
    SystemID.NGP -> "ngp"
    // Real naming quirk kept as-is from the Lemuroid fork: this enum
    // constant is named NGC but its own real dbname ("ngc") and Lemuroid's
    // own real usage is Neo Geo Pocket COLOR, not Nintendo GameCube.
    SystemID.NGC -> "ngpc"
    SystemID.WS -> "wonderswan"
    SystemID.WSC -> "wonderswancolor"
    SystemID.DOS -> "dos"
    SystemID.NINTENDO_3DS -> "n3ds"
}
