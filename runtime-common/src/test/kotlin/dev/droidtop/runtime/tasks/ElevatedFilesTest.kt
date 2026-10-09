package dev.droidtop.runtime.tasks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ElevatedFilesTest {
    @Test
    fun `shared storage files, other apps' Android data included, are allowed`() {
        assertTrue(ElevatedFiles.allowed("/storage/emulated/0/Android/data/com.retroarch.aarch64/files/retroarch.cfg"))
        assertTrue(ElevatedFiles.allowed("/storage/emulated/0/RetroArch/system/scph5501.bin"))
        assertTrue(ElevatedFiles.allowed("/storage/7EF7-E477/bios/name with spaces.bin"))
        assertTrue(ElevatedFiles.allowed("/sdcard/PSP/SYSTEM/ppsspp.ini"))
    }

    @Test
    fun `private storage, relative parts and folders are refused`() {
        assertFalse(ElevatedFiles.allowed("/data/user/0/com.retroarch.aarch64/cores/x.so"))
        assertFalse(ElevatedFiles.allowed("/storage/emulated/0/../../data/x"))
        assertFalse(ElevatedFiles.allowed("/storage/emulated/0/./x"))
        assertFalse(ElevatedFiles.allowed("/storage/emulated//0/x"))
        assertFalse(ElevatedFiles.allowed("/storage/emulated/0/RetroArch/"))
        assertFalse(ElevatedFiles.allowed("storage/emulated/0/x"))
        assertFalse(ElevatedFiles.allowed("/storage/emulated/0/x\ny"))
    }
}
