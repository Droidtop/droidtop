package org.pocketworkstation.pckeyboard

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/**
 * Offline voice typing's engine and model, downloaded on request (Droidtop/tracker#343; owner: "offline voice is a
 * download on request thing"). Nothing of it is in the APK: Settings > Voice input > Offline voice typing fetches the
 * sherpa-onnx engine (this device's two native libraries out of the release's AAR) and one English streaming model,
 * each checked against the SHA-256 pinned here, into the keyboard's own no-backup storage. Each download is hashed
 * and unpacked as it streams, so the archive is never stored, and nothing is kept unless the whole file matched.
 */
internal object OfflineVoice {
    const val ENGINE_VERSION = "1.13.8"
    const val ENGINE_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar"
    const val ENGINE_SHA256 = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
    const val ENGINE_DOWNLOAD_MB = 50

    const val MODEL_NAME = "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17"
    const val MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$MODEL_NAME.tar.bz2"
    const val MODEL_SHA256 = "9c559283e8498d3fe95913c79ca1cb454bb26281ac2b102b41306c7d752765d9"
    const val MODEL_DOWNLOAD_MB = 128

    /** What stays on the device: the engine for one ABI (27 to 31 MB) and the model's int8 files (44 MB). */
    const val INSTALLED_MB = 75

    val ENGINE_LIBS = listOf("libonnxruntime.so", "libsherpa-onnx-jni.so")
    const val TOKENS = "tokens.txt"
    const val ENCODER = "encoder-epoch-99-avg-1.int8.onnx"
    const val DECODER = "decoder-epoch-99-avg-1.int8.onnx"
    const val JOINER = "joiner-epoch-99-avg-1.int8.onnx"
    val MODEL_FILES = listOf(TOKENS, ENCODER, DECODER, JOINER)

    /** Written last into a finished directory; a directory without it is a download that did not finish. */
    private const val DONE = ".complete"

    fun root(context: Context): File = File(context.noBackupFilesDir, "offline-voice")
    fun engineDir(context: Context): File = File(root(context), "engine-$ENGINE_VERSION")
    fun modelDir(context: Context): File = File(root(context), MODEL_NAME)

    /** The 64-bit ABI the engine is taken for, or null on a device droidtop does not build for. */
    fun abi(): String? = Build.SUPPORTED_64_BIT_ABIS.firstOrNull { it == "arm64-v8a" || it == "x86_64" }

    /** The file an AAR entry becomes, for [abi]'s two libraries; null for everything else in the AAR. Pure. */
    fun engineEntry(name: String, abi: String): String? {
        val file = name.removePrefix("jni/$abi/")
        return file.takeIf { it != name && it in ENGINE_LIBS }
    }

    /** The file a model archive entry becomes: the four the recognizer reads, at the archive's top folder. Pure. */
    fun modelEntry(name: String): String? {
        val file = name.removePrefix("$MODEL_NAME/")
        return file.takeIf { it != name && it in MODEL_FILES }
    }

    @Volatile
    private var readyCache = false

    /** Whether both parts are in place, as last checked ([refresh]); cheap, so the voice key can ask on every press. */
    val ready: Boolean get() = readyCache

    private val worker by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "keyboard-offline-voice").apply { isDaemon = true } } }

    /** Re-reads [ready] off the main thread, then runs [then] (on the worker) if given. */
    @JvmStatic
    @JvmOverloads
    fun refresh(context: Context, then: Runnable? = null) {
        val app = context.applicationContext
        worker.execute {
            readyCache = checkInstalled(app)
            then?.run()
        }
    }

    private fun checkInstalled(context: Context): Boolean = complete(engineDir(context)) && complete(modelDir(context))

    /** What a download says while it runs and when it ends. */
    interface Progress {
        fun step(line: String)
        fun done(error: String?)
    }

    /** Downloads what is missing, off the main thread; [progress] is called on the worker. */
    fun install(context: Context, progress: Progress) {
        val app = context.applicationContext
        worker.execute {
            val error = runCatching { installNow(app, progress) }.getOrElse { "The download failed: ${it.message ?: it}" }
            readyCache = checkInstalled(app)
            progress.done(error)
        }
    }

    private fun installNow(context: Context, progress: Progress): String? {
        val abi = abi() ?: return "This device has no 64-bit ABI the engine is built for"
        if (!complete(engineDir(context))) {
            val error = fetch(ENGINE_URL, ENGINE_SHA256, engineDir(context), ENGINE_LIBS.size, { progress.step("Downloading the engine: $it of $ENGINE_DOWNLOAD_MB MB") }) { stream, out ->
                unpackEngine(stream, out, abi)
            }
            if (error != null) return error
        }
        if (!complete(modelDir(context))) {
            val error = fetch(MODEL_URL, MODEL_SHA256, modelDir(context), MODEL_FILES.size, { progress.step("Downloading the English model: $it of $MODEL_DOWNLOAD_MB MB") }) { stream, out ->
                unpackModel(stream, out)
            }
            if (error != null) return error
        }
        return null
    }

    /** Downloads [url] into [dest] through [unpackChecked]. */
    private fun fetch(
        url: String,
        sha256: String,
        dest: File,
        expected: Int,
        megabytes: (Long) -> Unit,
        unpack: (InputStream, File) -> Int,
    ): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return "The server answered ${connection.responseCode} for ${URL(url).host}"
            return unpackChecked(connection.inputStream, sha256, dest, expected, megabytes, unpack)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Streams [input] through a SHA-256 into [unpack], which writes what it keeps into a staging folder beside [dest]
     * and says how many files it kept. The rest of the input is read through the hash too; only a whole file that
     * matches [sha256], with [expected] files kept, becomes [dest]. Anything else leaves [dest] as it was. Null when
     * it is in place, else what went wrong.
     */
    fun unpackChecked(
        input: InputStream,
        sha256: String,
        dest: File,
        expected: Int,
        megabytes: (Long) -> Unit,
        unpack: (InputStream, File) -> Int,
    ): String? {
        val staging = File(dest.parentFile, dest.name + ".part")
        clear(staging)
        staging.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        val kept = runCatching {
            HashingStream(input, digest, megabytes).use { raw ->
                unpack(UnclosedStream(raw), staging).also { raw.drain() }
            }
        }.getOrElse {
            clear(staging)
            throw it
        }
        val got = digest.digest().joinToString("") { "%02x".format(it) }
        if (got != sha256) {
            clear(staging)
            return "The download did not match its checksum, so it was not kept"
        }
        if (kept != expected) {
            clear(staging)
            return "The download did not hold the files expected ($kept of $expected)"
        }
        File(staging, DONE).writeText(sha256)
        clear(dest)
        if (!staging.renameTo(dest)) {
            clear(staging)
            return "The download could not be put in place"
        }
        return null
    }

    /** Whether [dir] holds a finished download ([unpackChecked]'s marker). */
    fun complete(dir: File): Boolean = File(dir, DONE).isFile

    /** Keeps [abi]'s engine libraries from an AAR [stream] into [out]; how many it kept. */
    fun unpackEngine(stream: InputStream, out: File, abi: String): Int {
        val zip = ZipInputStream(stream)
        var kept = 0
        while (true) {
            val entry = zip.nextEntry ?: break
            val file = engineEntry(entry.name, abi) ?: continue
            File(out, file).outputStream().use { zip.copyTo(it) }
            kept++
        }
        return kept
    }

    /** Keeps the model's four files from a .tar.bz2 [stream] into [out]; how many it kept. */
    fun unpackModel(stream: InputStream, out: File): Int {
        val tar = TarArchiveInputStream(BZip2CompressorInputStream(stream))
        var kept = 0
        while (true) {
            val entry = tar.nextEntry ?: break
            if (!entry.isFile) continue
            val file = modelEntry(entry.name) ?: continue
            File(out, file).outputStream().use { tar.copyTo(it) }
            kept++
        }
        return kept
    }

    /** Deletes the offline voice files; the voice key falls back to another keyboard's voice typing. */
    fun remove(context: Context, done: () -> Unit) {
        val app = context.applicationContext
        worker.execute {
            clear(engineDir(app))
            clear(modelDir(app))
            readyCache = checkInstalled(app)
            done()
        }
    }

    /** Removes [dir] and the files directly in it; these folders are flat by construction. */
    private fun clear(dir: File) {
        if (!dir.isDirectory) return
        dir.listFiles()?.forEach { if (it.isFile) it.delete() }
        dir.delete()
    }

    private val loaded = Any()

    @Volatile
    private var librariesLoaded = false

    /** Loads the downloaded engine into this process, once; the JNI library needs onnxruntime first. Worker only. */
    fun load(context: Context) {
        synchronized(loaded) {
            if (librariesLoaded) return
            val dir = engineDir(context)
            ENGINE_LIBS.forEach { System.load(File(dir, it).absolutePath) }
            librariesLoaded = true
        }
    }

    /** Counts and hashes every byte read, reporting whole megabytes as they pass. */
    private class HashingStream(
        input: InputStream,
        private val digest: MessageDigest,
        private val megabytes: (Long) -> Unit,
    ) : FilterInputStream(input) {
        private var count = 0L
        private var reported = -1L

        private fun seen(n: Int) {
            if (n <= 0) return
            count += n
            val mb = count / (1024 * 1024)
            if (mb != reported) {
                reported = mb
                megabytes(mb)
            }
        }

        override fun read(): Int = super.read().also { if (it >= 0) { digest.update(it.toByte()); seen(1) } }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also {
            if (it > 0) { digest.update(b, off, it); seen(it) }
        }

        override fun skip(n: Long): Long {
            val buffer = ByteArray(8192)
            var left = n
            while (left > 0) {
                val r = read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (r < 0) break
                left -= r
            }
            return n - left
        }

        /** Reads to the end, so the hash covers bytes the unpacker did not need. */
        fun drain() {
            val buffer = ByteArray(64 * 1024)
            while (read(buffer, 0, buffer.size) >= 0) Unit
        }
    }

    /** Lets an unpacker close its own streams without ending the download before [HashingStream.drain]. */
    private class UnclosedStream(input: InputStream) : FilterInputStream(input) {
        override fun close() = Unit
    }
}
