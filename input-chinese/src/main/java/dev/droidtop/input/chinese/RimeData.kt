package dev.droidtop.input.chinese

import java.io.BufferedInputStream
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
 * RIME's data on the device (Droidtop/tracker#903 H8b), in the engine's profile directory (noBackupFilesDir/engine-rime):
 * `shared/` is librime's shared_data_dir, the compiled luna_pinyin schemas and OpenCC's data, and `user/` its
 * user_data_dir, where it keeps what it learns. The first time Chinese is used `shared/` is downloaded from the
 * release the pin names ([RimePin.dataUrl]), never at app start and never in the APK, as Mozc's dictionary is
 * (MozcData), with the same policy: on first use, over whatever connection there is.
 *
 * The archive is checked against its SHA-256 and unpacked beside `shared/`; every unpacked file is checked against the
 * list of SHA-256s ([RimeManifest]), and nothing else may be there. Only then is a marker written and the directory
 * renamed into place, in the same parent, so the move is atomic. Any failure leaves no partial directory and says why
 * ([Failure]). Pure (java.io and a [Fetcher]), so the steps are tested on the JVM against a fake one.
 */
object RimeData {
    const val SHARED = "shared"
    const val USER = "user"

    /** In `shared/` once it is checked: the archive's SHA-256, so a new pin's data replaces an old one's. */
    const val MARKER = ".droidtop-data"
    private const val PART_GZ = "rime-data-luna.tar.gz.part"
    private const val PART_DIR = "unpack.part"
    private const val OLD_DIR = "shared.old"
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

    /** Why the data is not in place; turned into words only where it is shown. */
    sealed interface Failure {
        /** No connection to [host]. */
        data class Network(val host: String) : Failure

        /** [host] answered [code] instead of the file. */
        data class Server(val code: Int, val host: String) : Failure

        /** The download stopped before the whole file came. */
        data object Truncated : Failure

        /** The archive came whole but is not the one the pin names. */
        data object Checksum : Failure

        /** The archive matched, but what it unpacked to is not the list of files the pin names. */
        data object Unpacked : Failure

        /** It could not be written (no space left, or the directory refused it). */
        data object Storage : Failure
    }

    private class ReadFailed(cause: IOException) : IOException(cause)
    private class WriteFailed(cause: IOException) : IOException(cause)

    fun shared(dir: File): File = File(dir, SHARED)

    fun user(dir: File): File = File(dir, USER)

    /** Whether the checked data is in place. Only a checked directory is ever moved there, so its marker says enough. */
    @JvmStatic
    fun present(dir: File, pin: RimePin): Boolean {
        val marker = File(shared(dir), MARKER)
        return marker.isFile && runCatching { marker.readText().trim() }.getOrNull() == pin.dataGzSha256
    }

    /**
     * Makes sure the data is in [dir]/shared, downloading it through [fetcher] when it is not. [progress] hears the
     * download's percent (0 to 100) each time it changes, when the server gives a length. Null when it is in place,
     * else why not; either way nothing partial is left in [dir]. Never on the main thread.
     */
    @JvmStatic
    fun ensure(dir: File, pin: RimePin, manifest: RimeManifest, fetcher: Fetcher, progress: (Int) -> Unit = {}): Failure? {
        if (present(dir, pin)) return null
        val gz = File(dir, PART_GZ)
        val part = File(dir, PART_DIR)
        try {
            return fetchInto(dir, gz, part, pin, manifest, fetcher, progress)
        } finally {
            gz.delete()
            part.deleteRecursively()
            File(dir, OLD_DIR).deleteRecursively()
        }
    }

    private fun fetchInto(dir: File, gz: File, part: File, pin: RimePin, manifest: RimeManifest, fetcher: Fetcher, progress: (Int) -> Unit): Failure? {
        gz.delete()
        part.deleteRecursively()
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
        if (!part.mkdirs()) return Failure.Storage
        val unpacked = try {
            unpack(gz, part, manifest)
        } catch (e: WriteFailed) {
            return Failure.Storage
        } catch (e: IOException) {
            return Failure.Unpacked
        }
        if (!unpacked) return Failure.Unpacked
        val fresh = File(part, SHARED)
        try {
            File(fresh, MARKER).writeText(pin.dataGzSha256)
        } catch (e: IOException) {
            return Failure.Storage
        }
        // An older pin's data (or a broken one) goes aside first; the user's own directory is never touched.
        val target = shared(dir)
        val old = File(dir, OLD_DIR)
        old.deleteRecursively()
        if (target.exists() && !target.renameTo(old)) return Failure.Storage
        if (!fresh.renameTo(target)) return Failure.Storage
        progress(100)
        return null
    }

    /**
     * Unpacks [gz] into [into], each file checked against [manifest] as it is written: false when a file is not on the
     * list, its SHA-256 differs, or one on the list is missing.
     */
    private fun unpack(gz: File, into: File, manifest: RimeManifest): Boolean {
        val seen = HashSet<String>()
        var wrong = false
        GZIPInputStream(BufferedInputStream(FileInputStream(gz), BUFFER), BUFFER).use { input ->
            RimeTar.read(input) { path, _, body ->
                val clean = path.removePrefix("./")
                val want = manifest.files[clean]
                if (want == null || !RimeManifest.safe(clean) || !seen.add(clean)) {
                    wrong = true
                    return@read
                }
                val file = File(into, clean)
                val parent = file.parentFile
                if (parent != null && !parent.isDirectory && !parent.mkdirs()) throw WriteFailed(IOException("cannot make $parent"))
                val copied = FileOutputStream(file).use { out -> copy(body, out, -1) {} }
                if (copied.sha256 != want) wrong = true
            }
        }
        return !wrong && seen.size == manifest.files.size
    }

    /** Checks every file [manifest] lists under [dir] (the parent of `shared/`) against its SHA-256. For a test. */
    @JvmStatic
    fun verify(dir: File, manifest: RimeManifest): Boolean = manifest.files.all { (path, sha) ->
        val file = File(dir, path)
        file.isFile && runCatching { FileInputStream(file).use { copy(it, NullOutput, -1) {} }.sha256 }.getOrNull() == sha
    }

    private object NullOutput : OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
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

    /** Removes the data (the settings row's "Delete the dictionary") and anything a download left. The user's directory stays. */
    @JvmStatic
    fun delete(dir: File): Boolean {
        File(dir, PART_GZ).delete()
        File(dir, PART_DIR).deleteRecursively()
        File(dir, OLD_DIR).deleteRecursively()
        val target = shared(dir)
        return !target.exists() || target.deleteRecursively()
    }

    /**
     * Removes what RIME learned from [user] (its user_data_dir), with librime finalized so no database is open: each
     * schema's user dictionary (`<dictionary>.userdb`, a LevelDB directory, and any `.userdb.txt` snapshot or a
     * `.userdb.old` left by an upgrade) and the sync directory, which holds snapshots of the same. Its configuration
     * (installation.yaml, user.yaml) and the compiled schemas in `build/` stay, so it starts again without a rebuild.
     */
    @JvmStatic
    fun forgetFiles(user: File) {
        user.listFiles()?.forEach { if (learned(it.name)) it.deleteRecursively() }
    }

    /** Whether a name in librime's user directory is something it learned. */
    @JvmStatic
    fun learned(name: String): Boolean = name == "sync" || name.contains(".userdb")

    private fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull() ?: url
}
