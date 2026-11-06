package dev.droidtop.input.japanese

import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Mozc's dictionary on the device (Droidtop/tracker#903 H7c): `mozc.data` in the engine's profile directory
 * (noBackupFilesDir/engine-mozc). The first time Japanese is used it is downloaded from the release the pin names
 * ([MozcPin.dataUrl]), never at app start and never in the APK. The coordinator's rulings for the owner: host it on a
 * droidtop prerelease, keep the zip-code data, and the open Mozc dictionary is the default.
 *
 * The archive is checked against its SHA-256, unpacked, and the dictionary checked against its own size and SHA-256;
 * only then is it renamed into place, in the same directory, so the move is atomic. Any failure leaves no partial file
 * and says why ([Failure]). Pure (java.io and a [Fetcher]), so the steps are tested on the JVM against a fake one.
 */
object MozcData {
    const val FILE = "mozc.data"
    private const val PART_GZ = "mozc.data.gz.part"
    private const val PART = "mozc.data.part"
    private const val BUFFER = 64 * 1024

    /** What a request answered: its HTTP status, its length (-1 when not given) and its body. */
    class Response(val code: Int, val length: Long, val body: InputStream?) : Closeable {
        override fun close() {
            body?.close()
        }
    }

    /** Opens a URL. Throws [IOException] when it cannot connect at all (no network, airplane mode). */
    fun interface Fetcher {
        @Throws(IOException::class)
        fun open(url: String): Response
    }

    /** Why the dictionary is not in place; turned into words only where it is shown. */
    sealed interface Failure {
        /** No connection to [host]. */
        data class Network(val host: String) : Failure

        /** [host] answered [code] instead of the file. */
        data class Server(val code: Int, val host: String) : Failure

        /** The download stopped before the whole file came. */
        data object Truncated : Failure

        /** The archive came whole but is not the one the pin names. */
        data object Checksum : Failure

        /** The archive matched, but what it unpacked to is not the dictionary the pin names. */
        data object Unpacked : Failure

        /** It could not be written (no space left, or the directory refused it). */
        data object Storage : Failure
    }

    private class ReadFailed(cause: IOException) : IOException(cause)
    private class WriteFailed(cause: IOException) : IOException(cause)

    fun file(dir: File): File = File(dir, FILE)

    /** Whether the dictionary is in place. Only a checked file is ever moved there, so its size says enough. */
    @JvmStatic
    fun present(dir: File, pin: MozcPin): Boolean = file(dir).let { it.isFile && it.length() == pin.dataSize }

    /**
     * Makes sure the dictionary is in [dir], downloading it through [fetcher] when it is not. [progress] hears the
     * download's percent (0 to 100) each time it changes, when the server gives a length. Null when it is in place,
     * else why not; either way nothing partial is left in [dir]. Never on the main thread.
     */
    @JvmStatic
    fun ensure(dir: File, pin: MozcPin, fetcher: Fetcher, progress: (Int) -> Unit = {}): Failure? {
        if (present(dir, pin)) return null
        val target = file(dir)
        val gz = File(dir, PART_GZ)
        val part = File(dir, PART)
        try {
            return fetchInto(dir, target, gz, part, pin, fetcher, progress)
        } finally {
            // After a success the dictionary was renamed from the part, so both are gone either way.
            gz.delete()
            part.delete()
        }
    }

    private fun fetchInto(dir: File, target: File, gz: File, part: File, pin: MozcPin, fetcher: Fetcher, progress: (Int) -> Unit): Failure? {
        // A file of the wrong size there is none of ours: it goes before the download.
        if (target.exists() && !target.delete()) return Failure.Storage
        gz.delete()
        part.delete()
        if (!dir.isDirectory && !dir.mkdirs()) return Failure.Storage
        val host = hostOf(pin.dataUrl)
        val response = try {
            fetcher.open(pin.dataUrl)
        } catch (e: IOException) {
            return Failure.Network(host)
        }
        response.use {
            val body = it.body
            if (it.code != 200 || body == null) return Failure.Server(it.code, host)
            val copied = try {
                FileOutputStream(gz).use { out -> copy(body, out, it.length, progress) }
            } catch (e: ReadFailed) {
                return Failure.Truncated
            } catch (e: IOException) {
                return Failure.Storage
            }
            if (it.length >= 0 && copied.size != it.length) return Failure.Truncated
            if (copied.sha256 != pin.dataGzSha256) return Failure.Checksum
        }
        val unpacked = try {
            GZIPInputStream(FileInputStream(gz), BUFFER).use { input ->
                FileOutputStream(part).use { out -> copy(input, out, -1) {} }
            }
        } catch (e: WriteFailed) {
            return Failure.Storage
        } catch (e: IOException) {
            return Failure.Unpacked
        }
        if (unpacked.size != pin.dataSize || unpacked.sha256 != pin.dataSha256) return Failure.Unpacked
        if (!part.renameTo(target)) return Failure.Storage
        progress(100)
        return null
    }

    private class Copied(val size: Long, val sha256: String)

    /** Copies [input] to [out] through a SHA-256, telling [progress] the percent of [length] when it is known. */
    private fun copy(input: InputStream, out: OutputStream, length: Long, progress: (Int) -> Unit): Copied {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER)
        var total = 0L
        var reported = -1
        while (true) {
            val n = try {
                input.read(buffer)
            } catch (e: IOException) {
                throw ReadFailed(e)
            }
            if (n < 0) break
            digest.update(buffer, 0, n)
            try {
                out.write(buffer, 0, n)
            } catch (e: IOException) {
                throw WriteFailed(e)
            }
            total += n
            if (length > 0) {
                val percent = (total * 100 / length).toInt().coerceIn(0, 99)
                if (percent != reported) {
                    reported = percent
                    progress(percent)
                }
            }
        }
        return Copied(total, digest.digest().joinToString("") { "%02x".format(it) })
    }

    /** Removes the dictionary (the settings row's "Delete the dictionary") and anything a download left. */
    @JvmStatic
    fun delete(dir: File): Boolean {
        File(dir, PART_GZ).delete()
        File(dir, PART).delete()
        val target = file(dir)
        return !target.exists() || target.delete()
    }

    /**
     * Removes what Mozc learned, for when its library is not loaded to be told: every file in [dir] but the
     * dictionary. Mozc keeps its learning (its history, its predictions) in its profile directory, nothing else there.
     */
    @JvmStatic
    fun forgetFiles(dir: File) {
        dir.listFiles()?.forEach { if (it.name != FILE) it.deleteRecursively() }
    }

    private fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull() ?: url
}
