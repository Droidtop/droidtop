package dev.droidtop.pluginhost

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.json.JSONObject
import java.util.zip.GZIPInputStream

/**
 * Downloads, verifies, stores and removes the CPython runtime the
 * `python` plugin kind embeds (docs/SPEC.md 12a). This is the ONLY
 * source of libpython/the stdlib droidtop ever has: the official
 * per-ABI Android builds python.org itself publishes
 * (python.org/downloads/android/, PEP 738) -- never bundled in the base
 * APK, downloaded here on first use of a python-kind plugin and
 * hash-verified against [ASSET_PATH]'s pinned manifest before a single
 * byte of it is trusted.
 *
 * Storage layout: `filesDir/python-runtime/<version>/<abi>/`, laid out
 * exactly like the official archive's own `prefix/` directory (minus
 * that name) so the extracted tree can be handed straight to
 * [PythonBridge.nativeInit] as `PYTHONHOME` with no further rewriting:
 *
 *   <that dir>/lib/libpython3.14.so
 *   <that dir>/lib/python3.14/...   (stdlib, including lib-dynload)
 *
 * A `.verified` marker (containing the runtime version + the sha256 it
 * was verified against) is the one thing [isInstalled] trusts; its
 * absence -- a partial extraction from a previous crash, an interrupted
 * download -- means "not installed", so a half-written tree is never
 * mistaken for a usable one.
 */
object PythonRuntimeManager {
    private const val ASSET_PATH = "python-runtimes.json"
    private const val MARKER_FILE = ".verified"

    sealed interface Progress {
        data class Downloading(val bytesRead: Long, val totalBytes: Long) : Progress
        object Verifying : Progress
        object Extracting : Progress
        object Done : Progress
    }

    private data class RuntimeSpec(val version: String, val url: String, val sha256: String, val libpythonSoName: String, val stdlibDirName: String)

    /** The ABI this device actually needs -- same 64-bit-first choice [PluginRuntimeService.nativeLibraryDirFor] already makes for native_bundle plugins, so both runners agree on which of a plugin's two shipped ABIs is "this device's". */
    fun currentAbi(): String =
        if (Build.SUPPORTED_64_BIT_ABIS.contains("x86_64") && !Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a")) "x86_64" else "arm64-v8a"

    private fun readSpec(context: Context): RuntimeSpec? {
        val json = runCatching {
            context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
        }.getOrNull() ?: return null
        return runCatching {
            val obj = JSONObject(json)
            val version = obj.getString("version")
            val artifact = obj.getJSONObject("artifacts").getJSONObject(currentAbi())
            RuntimeSpec(
                version = version,
                url = artifact.getString("url"),
                sha256 = artifact.getString("sha256"),
                libpythonSoName = obj.getString("libpythonSoName"),
                stdlibDirName = obj.getString("stdlibDirName"),
            )
        }.getOrNull()
    }

    private fun rootDir(context: Context): File = File(context.filesDir, "python-runtime")

    private fun installDirFor(context: Context, spec: RuntimeSpec): File =
        File(rootDir(context), "${spec.version}/${currentAbi()}")

    /** Null when no pinned spec is readable at all (a packaging bug, never a normal state); otherwise the version this build would install/has installed. */
    fun pinnedVersion(context: Context): String? = readSpec(context)?.version

    fun isInstalled(context: Context): Boolean {
        val spec = readSpec(context) ?: return false
        return File(installDirFor(context, spec), MARKER_FILE).isFile
    }

    fun installedVersion(context: Context): String? {
        val spec = readSpec(context) ?: return null
        val marker = File(installDirFor(context, spec), MARKER_FILE)
        if (!marker.isFile) return null
        return spec.version
    }

    fun pythonHomeDir(context: Context): File? {
        val spec = readSpec(context) ?: return null
        val dir = installDirFor(context, spec)
        return if (File(dir, MARKER_FILE).isFile) dir else null
    }

    fun libpythonSoPath(context: Context): File? {
        val spec = readSpec(context) ?: return null
        val home = pythonHomeDir(context) ?: return null
        return File(home, "lib/${spec.libpythonSoName}")
    }

    /** Deletes every installed runtime version -- the plugin host's own "remove" action, independent of uninstalling any one plugin (a python-kind plugin without the runtime installed simply fails to load with a clear reason, same shape as any other missing-precondition load failure). */
    fun remove(context: Context) {
        rootDir(context).deleteRecursively()
    }

    /**
     * Downloads (if not already verified-installed), verifies and
     * extracts the pinned runtime, reporting [onProgress] along the way
     * -- meant to be driven from an [dev.droidtop.library.settings.AsyncActionItem]'s
     * `onStatus`, droidtop's existing "long action with live text"
     * settings-row shape, not a bespoke dialog. Must be called off the
     * main thread (network + extraction); throws nothing -- returns null
     * on success, an error message otherwise.
     */
    suspend fun ensureInstalled(context: Context, onProgress: (Progress) -> Unit): String? {
        val spec = readSpec(context) ?: return "no pinned Python runtime for this build (missing/broken $ASSET_PATH)"
        val installDir = installDirFor(context, spec)
        if (File(installDir, MARKER_FILE).isFile) {
            onProgress(Progress.Done)
            return null
        }

        val tmpFile = File(context.cacheDir, "python-runtime-download.tar.gz")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val connection = (URL(spec.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 30_000
            }
            connection.connect()
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return "download failed: HTTP ${connection.responseCode}"
            }
            val total = connection.contentLengthLong
            var readSoFar = 0L
            connection.inputStream.use { input ->
                FileOutputStream(tmpFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        readSoFar += n
                        onProgress(Progress.Downloading(readSoFar, total))
                    }
                }
            }

            onProgress(Progress.Verifying)
            val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actualSha.equals(spec.sha256, ignoreCase = true)) {
                return "downloaded runtime failed SHA-256 verification (got $actualSha, expected ${spec.sha256}) -- refusing to install it"
            }

            onProgress(Progress.Extracting)
            if (installDir.isDirectory) installDir.deleteRecursively()
            installDir.mkdirs()
            extractRuntime(tmpFile, installDir)

            File(installDir, MARKER_FILE).writeText("${spec.version} ${spec.sha256}")
            onProgress(Progress.Done)
            return null
        } catch (e: Exception) {
            installDir.deleteRecursively()
            return "couldn't install the Python runtime: ${e.message}"
        } finally {
            tmpFile.delete()
        }
    }

    /**
     * Extracts only `prefix/lib/**` (libpython*.so, libssl/libcrypto/
     * libsqlite3 and their `ossl-modules`, and the stdlib tree including
     * `lib-dynload`) into [installDir], dropping `prefix/include` (C
     * headers, unused at runtime) and `prefix/lib/pkgconfig` -- the
     * archive's own layout confirmed against the real 3.14.7 artifact
     * (docs/SPEC.md 12a's verification note), not guessed.
     */
    private fun extractRuntime(archive: File, installDir: File) {
        TarArchiveInputStream(GZIPInputStream(archive.inputStream().buffered())).use { tar ->
            var entry: TarArchiveEntry? = tar.nextTarEntry
            while (entry != null) {
                val name = entry.name.removePrefix("./")
                if (!entry.isDirectory && name.startsWith("prefix/lib/") && !name.startsWith("prefix/lib/pkgconfig/")) {
                    val relative = name.removePrefix("prefix/")
                    val target = File(installDir, relative)
                    // Same zip-slip guard PluginBundleInstaller applies to plugin bundles.
                    if (!target.canonicalPath.startsWith(installDir.canonicalPath)) {
                        entry = tar.nextTarEntry
                        continue
                    }
                    target.parentFile?.mkdirs()
                    target.outputStream().use { out -> tar.copyTo(out) }
                }
                entry = tar.nextTarEntry
            }
        }
    }
}
