package dev.droidtop.stores.ea

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Ported from DroidDeck tools/linuxfs/overlay/usr/local/bin/droiddeck-store-launch at cc63b403872b (GPL-3.0; NOTICE.md).
/** Tiny PE programs execute inside the game's x86 emulator; they report no Android host CPU values. */
internal object EaGuestPrograms {
    private const val RVA = 0x1000
    private const val BASE = 0x400000
    private const val IAT = BASE + RVA + 0x138

    fun cpuid(): ByteArray {
        val out = ByteArrayOutputStream()
        fun bytes(vararg values: Int) = values.forEach { out.write(it) }
        fun u32(value: Int) { out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()) }
        bytes(0x53, 0x57, 0xBF); u32(BASE + RVA + 0x180)
        for (leaf in intArrayOf(0, 1, Int.MIN_VALUE + 2, Int.MIN_VALUE + 3, Int.MIN_VALUE + 4)) {
            bytes(0xB8); u32(leaf)
            bytes(0x31, 0xC9, 0x0F, 0xA2, 0x89, 0x07, 0x89, 0x5F, 0x04, 0x89, 0x4F, 0x08, 0x89, 0x57, 0x0C, 0x83, 0xC7, 0x10)
        }
        bytes(0x6A, 0xF5, 0xFF, 0x15); u32(IAT)
        bytes(0x6A, 0x00, 0x68); u32(BASE + RVA + 0x1D0)
        bytes(0x6A, 0x50, 0x68); u32(BASE + RVA + 0x180)
        bytes(0x50, 0xFF, 0x15); u32(IAT + 4)
        bytes(0x6A, 0x00, 0xFF, 0x15); u32(IAT + 8)
        return image(out.toByteArray(), listOf(0x148 to "GetStdHandle", 0x158 to "WriteFile", 0x164 to "ExitProcess"), console = true)
    }

    private fun image(code: ByteArray, names: List<Pair<Int, String>>, console: Boolean): ByteArray {
        require(code.size < 0x100)
        val result = ByteArray(0x400)
        val b = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
        fun u16(at: Int, value: Int) { b.putShort(at, value.toShort()) }
        fun u32(at: Int, value: Int) { b.putInt(at, value) }
        fun ascii(at: Int, text: String) { text.toByteArray(Charsets.US_ASCII).copyInto(result, at) }
        ascii(0, "MZ"); u32(0x3C, 0x40); ascii(0x40, "PE\u0000\u0000")
        u16(0x44, 0x14C); u16(0x46, 1); u16(0x54, 0xE0); u16(0x56, 0x0103)
        val opt = 0x58
        u16(opt, 0x10B); u32(opt + 4, 0x200)
        for (at in listOf(16, 20, 24)) u32(opt + at, RVA)
        u32(opt + 28, BASE); u32(opt + 32, 0x1000); u32(opt + 36, 0x200)
        u16(opt + 40, 4); u16(opt + 48, 4)
        u32(opt + 56, 0x2000); u32(opt + 60, 0x200); u16(opt + 68, if (console) 3 else 2)
        u32(opt + 72, 0x100000); u32(opt + 76, 0x1000); u32(opt + 80, 0x100000); u32(opt + 84, 0x1000); u32(opt + 92, 16)
        u32(opt + 104, RVA + 0x100); u32(opt + 108, 40)
        u32(opt + 192, RVA + 0x138); u32(opt + 196, 16)
        ascii(0x138, ".text"); u32(0x140, 0x1000); u32(0x144, RVA); u32(0x148, 0x200); u32(0x14C, 0x200); u32(0x15C, 0xE0000060.toInt())
        code.copyInto(result, 0x200)
        u32(0x300, RVA + 0x128); u32(0x30C, RVA + 0x172); u32(0x310, RVA + 0x138)
        names.forEachIndexed { index, (at, name) ->
            u32(0x328 + index * 4, RVA + at); u32(0x338 + index * 4, RVA + at); ascii(0x200 + at + 2, name)
        }
        ascii(0x372, "KERNEL32.dll")
        return result
    }
}
