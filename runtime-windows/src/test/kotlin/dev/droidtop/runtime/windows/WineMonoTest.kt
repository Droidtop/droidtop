package dev.droidtop.runtime.windows

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WineMonoTest {
    /** A minimal PE header: MZ, e_lfanew = 0x80, PE\0\0, an optional header of [magic], 16 directories. */
    private fun pe(magic: Int, clrRva: Int, clrSize: Int): ByteArray {
        val b = ByteArray(0x200)
        fun put16(at: Int, v: Int) { b[at] = v.toByte(); b[at + 1] = (v shr 8).toByte() }
        fun put32(at: Int, v: Int) { put16(at, v and 0xffff); put16(at + 2, v ushr 16) }
        put16(0, 0x5a4d)
        put32(0x3c, 0x80)
        put32(0x80, 0x00004550)
        val optional = 0x80 + 24
        put16(optional, magic)
        val dirs = optional + if (magic == 0x10b) 96 else 112
        put32(dirs - 4, 16)
        put32(dirs + 14 * 8, clrRva)
        put32(dirs + 14 * 8 + 4, clrSize)
        return b
    }

    @Test
    fun `a CLR header marks a dotnet Framework program, 32 or 64 bit`() {
        assertTrue(WineMono.hasClrHeader(pe(0x10b, 0x2008, 0x48)))
        assertTrue(WineMono.hasClrHeader(pe(0x20b, 0x2008, 0x48)))
    }

    @Test
    fun `a native program, an empty directory or something not PE is not one`() {
        assertFalse(WineMono.hasClrHeader(pe(0x20b, 0, 0)))
        assertFalse(WineMono.hasClrHeader(ByteArray(64)))
        assertFalse(WineMono.hasClrHeader("MZ but nothing else".toByteArray()))
    }
}
