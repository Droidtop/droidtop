package dev.droidtop.pluginhost.hosts

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/**
 * Mega's end-to-end encryption as its published SDK does it (https://github.com/meganz/sdk, BSD-2: `SymmCipher::ctr_crypt`,
 * `ChunkedHash`, `chunkmac_map::macsmac`), written over javax.crypto alone. A file's key is 256 bits: eight 32-bit words
 * k0..k7. The AES-128 key is k0..k3 xor k4..k7, the CTR nonce is k4 k5 (the counter starts at 0), and k6 k7 is the file's
 * meta-MAC. The key comes from the link's fragment and never leaves the device.
 */
object MegaCrypto {
    /** A file's 32-byte key taken apart. */
    class FileKey(val aes: ByteArray, val nonce: ByteArray, val metaMac: ByteArray)

    /** The key's bytes from Mega's URL-safe base64 (padding left off), or null. */
    fun decode(text: String): ByteArray? = runCatching { Base64.getUrlDecoder().decode(text) }.getOrNull()

    fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** [key] (32 bytes) taken apart, or null when it is not a file's key. */
    fun fileKey(key: ByteArray): FileKey? {
        if (key.size != 32) return null
        return FileKey(
            aes = ByteArray(16) { (key[it].toInt() xor key[16 + it].toInt()).toByte() },
            nonce = key.copyOfRange(16, 24),
            metaMac = key.copyOfRange(24, 32),
        )
    }

    /** A node's key (16 bytes for a folder, 32 for a file) opened with the folder's key: AES-ECB, block by block. Null when it is not whole blocks. */
    fun nodeKey(encrypted: ByteArray, folderKey: ByteArray): ByteArray? {
        if (encrypted.isEmpty() || encrypted.size % 16 != 0 || folderKey.size != 16) return null
        return runCatching {
            Cipher.getInstance("AES/ECB/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(folderKey, "AES"))
                doFinal(encrypted)
            }
        }.getOrNull()
    }

    /**
     * The file name in a node's attributes [at]: AES-CBC with a zero IV and the file's AES key, `MEGA{"n":"<name>",...}`
     * padded with zeros. Null when the key does not open them.
     */
    fun fileName(at: String, aes: ByteArray): String? {
        val data = decode(at)?.takeIf { it.isNotEmpty() && it.size % 16 == 0 } ?: return null
        return runCatching {
            val plain = Cipher.getInstance("AES/CBC/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(aes, "AES"), IvParameterSpec(ByteArray(16)))
                doFinal(data)
            }.toString(Charsets.UTF_8).trimEnd('\u0000')
            if (!plain.startsWith("MEGA{")) return null
            JSONObject(plain.removePrefix("MEGA")).optString("n").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /**
     * Decrypts [file] where it lies, a block at a time (no second copy of a game on a handheld's storage), and says whether
     * the meta-MAC of what came out is the one in the key. False means the file is not what the link's owner uploaded
     * and must not be kept. [alive] is called between blocks, to let a cancelled job stop.
     */
    fun decryptFile(file: File, key: FileKey, alive: () -> Unit = {}): Boolean {
        val decryptor = Decryptor(key)
        RandomAccessFile(file, "rw").use { raf ->
            val buffer = ByteArray(BLOCK_BYTES)
            while (true) {
                alive()
                val at = raf.filePointer
                val read = raf.read(buffer)
                if (read < 0) break
                decryptor.update(buffer, 0, read)
                raf.seek(at)
                raf.write(buffer, 0, read)
            }
        }
        return decryptor.verify()
    }

    /**
     * AES-128-CTR decryption of a file's bytes in order, with Mega's CBC-MAC alongside. The file is cut into chunks (128 KB,
     * 256 KB ... 1 MB, then 1 MB each); a chunk's MAC is AES-CBC over its plaintext (the last block padded with zeros) from
     * the IV nonce nonce; the file's MAC folds the chunk MACs as `mac = AES(mac xor chunkMac)`; the meta-MAC is
     * mac[0..3] xor mac[4..7], then mac[8..11] xor mac[12..15].
     */
    class Decryptor(private val key: FileKey) {
        private val spec = SecretKeySpec(key.aes, "AES")
        private val ctr = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, spec, IvParameterSpec(key.nonce + ByteArray(8)))
        }
        private val cbc = Cipher.getInstance("AES/CBC/NoPadding")
        private val ecb = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, spec) }
        private val fileMac = ByteArray(16)
        private val partial = ByteArray(16)
        private var filled = 0
        private var scratch = ByteArray(0)
        private var chunkMac = key.nonce + key.nonce
        private var position = 0L
        private var chunkStart = 0L
        private var chunkEnd = MegaCrypto.chunkCeil(0)

        /** Decrypts [length] bytes of [buffer] from [offset] in place. */
        fun update(buffer: ByteArray, offset: Int, length: Int) {
            ctr.update(buffer, offset, length, buffer, offset)
            var at = offset
            val end = offset + length
            while (at < end) {
                val take = minOf((end - at).toLong(), chunkEnd - position).toInt()
                macOf(buffer, at, take)
                at += take
                position += take
                if (position == chunkEnd) {
                    endChunk()
                    chunkStart = position
                    chunkEnd = MegaCrypto.chunkCeil(position)
                }
            }
        }

        /** Whether the meta-MAC of everything decrypted is the key's. */
        fun verify(): Boolean {
            if (position > chunkStart) {
                endChunk()
                chunkStart = position
            }
            val meta = ByteArray(8) { i ->
                val base = if (i < 4) 0 else 8
                (fileMac[base + i % 4].toInt() xor fileMac[base + 4 + i % 4].toInt()).toByte()
            }
            return MessageDigest.isEqual(meta, key.metaMac)
        }

        /** Takes plaintext bytes into the chunk's CBC-MAC; a block left over waits for the next call or the chunk's end. */
        private fun macOf(buffer: ByteArray, offset: Int, length: Int) {
            var at = offset
            val end = offset + length
            if (filled > 0) {
                while (filled < 16 && at < end) partial[filled++] = buffer[at++]
                if (filled < 16) return
                absorb(partial, 0, 16)
                filled = 0
            }
            val whole = (end - at) and 15.inv()
            if (whole > 0) {
                absorb(buffer, at, whole)
                at += whole
            }
            while (at < end) partial[filled++] = buffer[at++]
        }

        /** CBC-encrypting whole blocks from the running MAC as the IV leaves the new MAC in the last block. */
        private fun absorb(data: ByteArray, offset: Int, length: Int) {
            if (scratch.size < length) scratch = ByteArray(length)
            cbc.init(Cipher.ENCRYPT_MODE, spec, IvParameterSpec(chunkMac))
            cbc.update(data, offset, length, scratch, 0)
            chunkMac = scratch.copyOfRange(length - 16, length)
        }

        private fun endChunk() {
            if (filled > 0) {
                partial.fill(0, filled, 16)
                absorb(partial, 0, 16)
                filled = 0
            }
            for (i in 0 until 16) fileMac[i] = (fileMac[i].toInt() xor chunkMac[i].toInt()).toByte()
            ecb.doFinal(fileMac).copyInto(fileMac)
            chunkMac = key.nonce + key.nonce
        }
    }

    private const val SEGMENT = 131072L
    private const val BLOCK_BYTES = 1 shl 16

    /** The end of the chunk that holds [position] (Mega's `chunkceil`): 128 KB, 256 KB ... 1 MB, then 1 MB each. */
    internal fun chunkCeil(position: Long): Long {
        var start = 0L
        for (i in 1..8) {
            val next = start + i * SEGMENT
            if (position < next) return next
            start = next
        }
        return start + ((position - start) / (8 * SEGMENT) + 1) * 8 * SEGMENT
    }
}
