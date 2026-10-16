package dev.droidtop.stores

import `in`.dragonbra.javasteam.util.crypto.CryptoHelper
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Steam's depot manifests carry their file names encrypted, and JavaSteam decrypts them (DepotManifest.decryptFilenames)
 * through CryptoHelper, which needs Bouncy Castle's provider in the build (console, build 1767: every Steam download
 * died with NoClassDefFoundError: org.bouncycastle.jce.provider.BouncyCastleProvider; Droidtop/tracker#313).
 */
class SteamCryptoTest {
    @Test
    fun `the provider JavaSteam names is in the build`() {
        val provider = Class.forName("org.bouncycastle.jce.provider.BouncyCastleProvider")
        assertEquals("org.bouncycastle.jce.provider.BouncyCastleProvider", provider.name)
    }

    @Test
    fun `a file name encrypted the way a manifest carries it decrypts back`() {
        val key = ByteArray(32) { it.toByte() }
        val name = "game/data/Love and Sex Second Base.exe".toByteArray()
        val encrypted = CryptoHelper.symmetricEncrypt(name, key)
        assertArrayEquals(name, CryptoHelper.symmetricDecrypt(encrypted, key))
    }
}
