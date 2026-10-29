package org.pocketworkstation.pckeyboard

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals the clipboard history for its file (Droidtop/tracker#772): AES-GCM under a key that lives in the Android
 * Keystore and never leaves it, so the file holds no copied text in the clear. A sealed blob is a version byte, the
 * 12-byte IV and the ciphertext with its tag ([frame]). Background thread only (the Keystore does disk and IPC).
 */
internal object ClipboardVault {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "hk-clipboard-history"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128
    const val VERSION: Byte = 1
    const val IV_BYTES = 12

    fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return frame(cipher.iv, cipher.doFinal(plain))
    }

    /** [sealed]'s plain bytes; null when it is not a blob [seal] made with this device's key (the key was reset). */
    fun open(sealed: ByteArray): ByteArray? {
        val (iv, body) = unframe(sealed) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            cipher.doFinal(body)
        } catch (e: java.security.GeneralSecurityException) {
            null
        }
    }

    /** The version byte, then [iv], then [body]. Pure. */
    fun frame(iv: ByteArray, body: ByteArray): ByteArray = byteArrayOf(VERSION) + iv + body

    /** The IV and body of a [frame]d blob; null for another version or one too short to hold an IV. Pure. */
    fun unframe(blob: ByteArray): Pair<ByteArray, ByteArray>? {
        if (blob.size <= 1 + IV_BYTES || blob[0] != VERSION) return null
        return blob.copyOfRange(1, 1 + IV_BYTES) to blob.copyOfRange(1 + IV_BYTES, blob.size)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
