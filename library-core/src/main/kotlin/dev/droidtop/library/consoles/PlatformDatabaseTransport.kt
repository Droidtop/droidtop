package dev.droidtop.library.consoles

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The download-and-replace half every platform database shares (docs/SPEC.md
 * 7e2). It used to be copy-pasted four times -- one connection block and one
 * temp-then-rename block per database -- which is exactly the kind of
 * duplicated mechanism that drifts: three of the four copies had the same
 * timeouts and the fourth's error text said something different for the same
 * failure. One copy here, four callers.
 *
 * The contract the callers rely on: nothing on disk changes until the
 * downloaded text has PARSED as the database it claims to be, and the
 * replacement itself is a rename, so a killed process leaves either the old
 * file or the new one and never half of either.
 */
internal object PlatformDatabaseTransport {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    /** Fetches [url], throwing with a readable message on any non-200. */
    fun get(url: String): String = getOrNull(url) ?: error("HTTP 404 from $url")

    /** Fetches [url], or null when the server says the file is not there. */
    fun getOrNull(url: String): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        return try {
            when (val code = connection.responseCode) {
                200 -> connection.inputStream.bufferedReader().use { it.readText() }
                404, 410 -> null
                else -> error("HTTP $code from $url")
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Atomically puts [text] at filesDir/[fileName]; call only after validating it. */
    fun replace(context: Context, fileName: String, text: String) {
        val dest = File(context.filesDir, fileName)
        write(dest, text)
    }

    /** Atomically puts [text] at [dest], creating parent directories. */
    fun write(dest: File, text: String) {
        dest.parentFile?.mkdirs()
        val temp = File(dest.parentFile, dest.name + ".downloading")
        temp.writeText(text)
        check(temp.renameTo(dest) || run { dest.delete(); temp.renameTo(dest) }) {
            "Couldn't move the downloaded ${dest.name} into place"
        }
    }

    fun sha256(text: String): String = sha256(text.toByteArray())

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
            val value = byte.toInt() and 0xff
            HEX[value shr 4].toString() + HEX[value and 0x0f]
        }

    private const val HEX = "0123456789abcdef"
}
