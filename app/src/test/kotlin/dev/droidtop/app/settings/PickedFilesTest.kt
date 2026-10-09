package dev.droidtop.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A picked document names a real path only on the shared storage (docs/SPEC.md 7c, "Prefix tools"). */
class PickedFilesTest {
    private val primary = "/storage/emulated/0"

    @Test
    fun `a file on the primary volume is under its mount`() {
        assertEquals(
            "/storage/emulated/0/Download/setup_game.exe",
            PickedFiles.pathOf("com.android.externalstorage.documents", "primary:Download/setup_game.exe", primary),
        )
    }

    @Test
    fun `a file on a card is under the card's mount`() {
        assertEquals(
            "/storage/1234-ABCD/Installers/setup.exe",
            PickedFiles.pathOf("com.android.externalstorage.documents", "1234-ABCD:Installers/setup.exe", primary),
        )
    }

    @Test
    fun `a downloads document with a raw path names it`() {
        assertEquals(
            "/storage/emulated/0/Download/setup.exe",
            PickedFiles.pathOf("com.android.providers.downloads.documents", "raw:/storage/emulated/0/Download/setup.exe", primary),
        )
    }

    @Test
    fun `a document from any other provider, or without a path, names none`() {
        assertNull(PickedFiles.pathOf("com.android.providers.downloads.documents", "msf:1234", primary))
        assertNull(PickedFiles.pathOf("com.example.cloud.documents", "primary:Download/setup.exe", primary))
        assertNull(PickedFiles.pathOf("com.android.externalstorage.documents", "primary:", primary))
        assertNull(PickedFiles.pathOf(null, "primary:x.exe", primary))
    }
}
