package dev.droidtop.library.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** One Add share reads the kind and the parts from the address (mytop-project/tracker#964 step 7). */
class ShareAddressTest {
    @Test
    fun `an smb address gives the server, the share and the folder`() {
        val p = ShareAddress.parse("smb://nas/games/roms/snes")!!
        assertEquals(StorageShares.SMB, p.kind)
        assertEquals("nas", p.host)
        assertEquals("games", p.share)
        assertEquals("roms/snes", p.folder)
    }

    @Test
    fun `a Windows path is smb too, with its user when given`() {
        assertEquals(ShareAddress.Parsed(StorageShares.SMB, host = "nas", share = "games"), ShareAddress.parse("""\\nas\games"""))
        assertEquals("me", ShareAddress.parse("smb://me@nas/games")!!.user)
    }

    @Test
    fun `an sftp address gives the user, the host, the port and the folder`() {
        assertEquals(
            ShareAddress.Parsed(StorageShares.SFTP, host = "box.local", port = "2222", user = "me", folder = "home/me/games"),
            ShareAddress.parse("sftp://me@box.local:2222/home/me/games"),
        )
        assertEquals(StorageShares.SFTP, ShareAddress.kindOf("ssh://box"))
    }

    @Test
    fun `s3 by scheme or by endpoint`() {
        assertEquals(ShareAddress.Parsed(StorageShares.S3, bucket = "saves", folder = "rp5"), ShareAddress.parse("s3://saves/rp5"))
        val endpoint = ShareAddress.parse("https://s3.eu-central-1.amazonaws.com/x")!!
        assertEquals(StorageShares.S3, endpoint.kind)
        assertEquals("https://s3.eu-central-1.amazonaws.com", endpoint.url)
    }

    @Test
    fun `any other web address is WebDAV, kept whole`() {
        val url = "https://cloud.example.com/remote.php/dav/files/you/"
        assertEquals(ShareAddress.Parsed(StorageShares.WEBDAV, url = url), ShareAddress.parse(url))
    }

    @Test
    fun `nothing or an unknown kind names none`() {
        assertNull(ShareAddress.parse("  "))
        assertNull(ShareAddress.kindOf("ftp://old.example"))
        assertNull(ShareAddress.kindOf("nas"))
    }
}
