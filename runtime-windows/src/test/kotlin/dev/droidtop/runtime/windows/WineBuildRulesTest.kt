package dev.droidtop.runtime.windows

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The names and checks that let any Wine build run (docs/SPEC.md 5a). The
 * name cases are droidtop-components' tools/test_rules.py cases: the catalog
 * and the runtime must name a build alike.
 */
class WineBuildRulesTest {

    @Test
    fun `builds are installed under the name the runtime reads`() {
        val cases = listOf(
            // profile type, profile versionName, arch of its wine binaries, installed name
            listOf("Proton", "proton-10.0-4-x86_64", "x86_64", "proton-10.0-4-x86_64"),
            listOf("Proton", "proton-10.0-arm64ec", "arm64ec", "proton-10.0-arm64ec"),
            listOf("Proton", "11.0-2-arm64ec", "arm64ec", "proton-11.0-2-arm64ec"),
            listOf("Proton", "GE-proton-11.0-7.1-arm64ec", "arm64ec", "proton-11.0-7.1-ge-arm64ec"),
            listOf("Proton", "proton-11.0-1-beta5-custom-arm64ec", "arm64ec", "proton-11.0-1-beta5.custom-arm64ec"),
            listOf("Wine", "wine-9.2-x86_64", "x86_64", "wine-9.2-x86_64"),
            listOf("Wine", "proton-11.0-1-custom", "arm64ec", "proton-11.0-1-custom-arm64ec"),
            listOf("Proton", "proton-10.0-x86_64", "arm64ec", "proton-10.0-arm64ec"),
            listOf("Wine", "10.5-staging", "x86_64", "wine-10.5-staging-x86_64"),
            listOf("Proton", "experimental", "x86_64", null),
        )
        for ((type, ver, arch, want) in cases) {
            assertEquals("$type $ver", want, WineBuildRules.canonicalName(WineBuildRules.runtimeVerName(type!!, ver!!), arch))
        }
    }

    @Test
    fun `the version code is one digit`() {
        assertEquals(0, WineBuildRules.runtimeVerCode(0))
        assertEquals(9, WineBuildRules.runtimeVerCode(9))
        assertEquals(0, WineBuildRules.runtimeVerCode(10))
        assertEquals(0, WineBuildRules.runtimeVerCode(-1))
    }

    @Test
    fun `an ELF header gives the machine and the interpreter`() {
        val elf = WineBuildRules.elf(elf64(0xB7, "/system/bin/linker64"))
        assertEquals(WineBuildRules.Elf("aarch64", "/system/bin/linker64"), elf)
        assertEquals("x86_64", WineBuildRules.elf(elf64(0x3E, "/lib64/ld-linux-x86-64.so.2"))?.machine)
        assertNull(WineBuildRules.elf(ByteArray(128)))
    }

    @Test
    fun `which builds run where`() {
        val armBionic = WineBuildRules.Elf("aarch64", "/system/bin/linker64")
        val x86Bionic = WineBuildRules.Elf("x86_64", "/system/bin/linker64")
        val x86Glibc = WineBuildRules.Elf("x86_64", "/lib64/ld-linux-x86-64.so.2")
        assertNull(WineBuildRules.refusal(x86Host = false, armBionic, "proton-11.0-2-arm64ec"))
        assertNull(WineBuildRules.refusal(x86Host = false, x86Bionic, "proton-9.0-x86_64"))
        assertNull(WineBuildRules.refusal(x86Host = true, x86Bionic, "proton-10.0-4-x86_64"))
        assertNotNull(WineBuildRules.refusal(x86Host = true, x86Bionic, "proton-9.0-x86_64"))
        assertNotNull(WineBuildRules.refusal(x86Host = true, armBionic, "proton-11.0-2-arm64ec"))
        assertNotNull(WineBuildRules.refusal(x86Host = false, x86Glibc, "wine-11.19-x86_64"))
        assertNotNull(WineBuildRules.refusal(x86Host = false, WineBuildRules.Elf("x86", null), "wine-9.0-x86"))
        assertNotNull(WineBuildRules.refusal(x86Host = false, null, "proton-10.0-arm64ec"))
    }

    /** A minimal little-endian ELF64 header with one PT_INTERP program header. */
    private fun elf64(machine: Int, interp: String): ByteArray {
        val b = ByteArray(256)
        b[0] = 0x7f; b[1] = 'E'.code.toByte(); b[2] = 'L'.code.toByte(); b[3] = 'F'.code.toByte()
        b[4] = 2; b[5] = 1
        fun put16(o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte() }
        fun put64(o: Int, v: Long) { for (i in 0 until 8) b[o + i] = (v shr (8 * i)).toByte() }
        put16(18, machine)
        put64(32, 64) // e_phoff
        put16(54, 56) // e_phentsize
        put16(56, 1) // e_phnum
        b[64] = 3 // PT_INTERP
        put64(64 + 8, 160) // p_offset
        put64(64 + 32, (interp.length + 1).toLong()) // p_filesz
        interp.toByteArray().copyInto(b, 160)
        return b
    }
}
