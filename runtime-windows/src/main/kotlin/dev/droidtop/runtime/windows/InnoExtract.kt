package dev.droidtop.runtime.windows

import android.content.Context
import android.os.Build
import dev.droidtop.runtime.SafeDelete
import dev.droidtop.runtime.windows.utils.ComponentCatalog
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Unpack an installer" (docs/SPEC.md 7c): innoextract takes the files out of a Windows installer (a
 * GOG offline installer, any Inno Setup program) without running it, so no Wine and no prefix is
 * involved. innoextract is built by Droidtop/droidtop-components from its upstream source
 * (tools/innoextract) for arm64-v8a and x86_64 and offered in the catalog by path; it is fetched on
 * first use, checked against the SHA-256 the catalog lists, and kept in app storage.
 *
 * Android does not let an app start a program it has written to its own storage, so the binary is
 * started the way Wine is: through `/system/bin/linker64`, which loads it (it is a position-independent
 * executable needing only the system's libc, libm and libdl; Boost, xz, zlib and bzip2 are linked in).
 * Never with root.
 */
object InnoExtract {

    const val VERSION = "1.9"

    private const val LINKER = "/system/bin/linker64"

    /** The catalog path of the build for the first of [abis] this device runs, or null when there is none. */
    internal fun catalogPath(abis: Array<String>): String? =
        abis.firstOrNull { it == "arm64-v8a" || it == "x86_64" }?.let { "tools/innoextract-$VERSION-$it" }

    /** The command that starts [binary] with [arguments]. */
    internal fun command(binary: File, arguments: List<String>): List<String> = listOf(LINKER, binary.absolutePath) + arguments

    /** innoextract's arguments to take everything out of [installer] into [outputDir]. */
    internal fun unpackArguments(installer: File, outputDir: File): List<String> =
        listOf("--extract", "--output-dir", outputDir.absolutePath, installer.absolutePath)

    /**
     * A folder in [parent] named for the installer ([installerName], without its extension), not yet
     * taken: the name, else the name with " 2", " 3", ... Nothing existing is ever extracted into.
     */
    internal fun freshFolder(parent: File, installerName: String): File {
        val base = installerName.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").trim().ifEmpty { "Unpacked game" }
        var candidate = File(parent, base)
        var n = 2
        while (candidate.exists()) candidate = File(parent, "$base $n").also { n++ }
        return candidate
    }

    /** The last non-blank line innoextract printed; its progress bar redraws one line with carriage returns. */
    internal fun lastLine(output: String): String =
        output.split('\r', '\n').map { it.trim() }.lastOrNull { it.isNotEmpty() }.orEmpty()

    /**
     * The binary, fetched from the catalog the first time and whenever the copy kept does not match the
     * catalog's SHA-256. Throws with a line a person can read. Disk and network work.
     */
    suspend fun ensure(context: Context, onStatus: (String) -> Unit): File = withContext(Dispatchers.IO) {
        val path = catalogPath(Build.SUPPORTED_ABIS)
            ?: error("There is no unpacker build for this device's processor")
        val listed = ComponentCatalog.file(context, path)
        val expected = listed.sha256 ?: error("The component catalog does not list the unpacker; try again once it has been checked (Wine builds and sources, Check for new builds)")
        val dest = File(File(context.filesDir, "tools").also { it.mkdirs() }, path.substringAfterLast('/'))
        if (dest.isFile && sha256Of(dest).equals(expected, ignoreCase = true)) return@withContext dest
        onStatus("Downloading the unpacker…")
        RuntimeDownloads.fetch(context, path, dest) { onStatus("Downloading the unpacker… ${(it.coerceIn(0f, 1f) * 100).toInt()}%") }
        dest.setReadable(true, false)
        dest
    }

    /** What the unpacker says it is: proves it was fetched and starts on this device. Disk, network and process work. */
    suspend fun version(context: Context, onStatus: (String) -> Unit): String = withContext(Dispatchers.IO) {
        val binary = runCatching { ensure(context, onStatus) }.getOrElse { return@withContext it.message ?: it.toString() }
        val result = runProcess(command(binary, listOf("--version")), context.filesDir) {}
        if (result.exit == 0) (result.output.lines().firstOrNull { it.isNotBlank() }?.trim() ?: "no answer") else "The unpacker did not start (exit ${result.exit}): ${lastLine(result.output)}"
    }

    /**
     * Takes the files out of [installer] into a new folder in [parent] and returns that folder, or a
     * line saying why not. The installer is only read. A failed unpack removes the folder it made.
     */
    suspend fun unpack(context: Context, installer: File, parent: File, onStatus: (String) -> Unit): Pair<File?, String> = withContext(Dispatchers.IO) {
        if (!installer.isFile) return@withContext null to "${installer.name} is not a file on this device"
        val binary = runCatching { ensure(context, onStatus) }.getOrElse { return@withContext null to (it.message ?: it.toString()) }
        parent.mkdirs()
        val out = freshFolder(parent, installer.nameWithoutExtension)
        if (!out.mkdirs()) return@withContext null to "Couldn't make the folder ${out.path}"
        onStatus("Unpacking ${installer.name}…")
        val result = runProcess(command(binary, unpackArguments(installer, out)), context.filesDir) { line -> onStatus("Unpacking ${installer.name}: $line") }
        if (result.exit != 0 || out.list().isNullOrEmpty()) {
            SafeDelete.deleteWithin(parent, out)
            val why = lastLine(result.output).ifEmpty { "it extracted nothing" }
            return@withContext null to "Couldn't unpack ${installer.name}: $why"
        }
        out to "Unpacked ${installer.name} into ${out.path}"
    }

    private class Result(val exit: Int, val output: String)

    /** Runs [command], handing each line of its output to [onLine]; returns the exit code and everything it printed. */
    private fun runProcess(command: List<String>, workingDir: File, onLine: (String) -> Unit): Result {
        val process = ProcessBuilder(command).directory(workingDir).redirectErrorStream(true).start()
        val all = StringBuilder()
        val line = StringBuilder()
        process.inputStream.use { input ->
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                val text = String(buffer, 0, count, Charsets.UTF_8)
                all.append(text)
                for (c in text) {
                    if (c == '\n' || c == '\r') {
                        if (line.isNotBlank()) onLine(line.toString().trim())
                        line.setLength(0)
                    } else {
                        line.append(c)
                    }
                }
                if (all.length > MAX_OUTPUT) all.delete(0, all.length - MAX_OUTPUT)
            }
        }
        return Result(process.waitFor(), all.toString())
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private const val MAX_OUTPUT = 32 * 1024
}
