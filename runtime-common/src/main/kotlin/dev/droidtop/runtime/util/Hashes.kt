package dev.droidtop.runtime.util

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** SHA-256 helpers with a stable lowercase hexadecimal representation. */
object Sha256 {
    fun hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).toHex()

    fun hex(file: File): String = file.inputStream().use(::hex)

    fun hex(stream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte ->
        val value = byte.toInt() and 0xff
        "0123456789abcdef"[value shr 4].toString() + "0123456789abcdef"[value and 0x0f]
    }
}
