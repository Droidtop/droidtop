package dev.droidtop.runtime

import dev.droidtop.runtime.WideAccess.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

class WideAccessTest {
    private val volumes = listOf("/storage/emulated/0", "/storage/1234-ABCD")
    private val private = "/data/user/0/dev.droidtop"
    private fun kind(path: String) = WideAccess.classify(path, volumes, private)

    @Test
    fun `the device root and the folders above droidtop's own are system folders`() {
        assertEquals(Kind.DEVICE_SYSTEM, kind("/"))
        assertEquals(Kind.DEVICE_SYSTEM, kind("/data"))
        assertEquals(Kind.DEVICE_SYSTEM, kind("/data/user/0/"))
        assertEquals(Kind.DEVICE_SYSTEM, kind(private))
    }

    @Test
    fun `a volume root or a folder above one is all storage`() {
        assertEquals(Kind.ALL_STORAGE, kind("/storage/emulated/0"))
        assertEquals(Kind.ALL_STORAGE, kind("/storage/emulated/0/"))
        assertEquals(Kind.ALL_STORAGE, kind("/storage/1234-ABCD"))
        assertEquals(Kind.ALL_STORAGE, kind("/storage"))
        assertEquals(Kind.ALL_STORAGE, kind("/storage/emulated"))
        assertEquals(Kind.ALL_STORAGE, kind("/sdcard"))
        assertEquals(Kind.ALL_STORAGE, kind("/storage/emulated/10"))
    }

    @Test
    fun `a folder inside a volume is an ordinary share`() {
        assertEquals(Kind.NARROW, kind("/storage/emulated/0/Games"))
        assertEquals(Kind.NARROW, kind("/storage/1234-ABCD/GOG/Reus"))
        assertEquals(Kind.NARROW, kind("/storage/emulated/0/Download"))
        assertEquals(Kind.NARROW, kind("/data/user/0/dev.droidtop/storage/game"))
    }
}
