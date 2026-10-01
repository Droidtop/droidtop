package dev.droidtop.pluginhost

import android.content.Context
import android.os.Build
import java.io.File
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
    private const val DOWNLOAD_POST = "python_runtime"

    private data class RuntimeSpec(val version: String, val url: String, val sha256: String, val libpythonSoName: String, val stdlibDirName: String)

    /** The ABI this device actually needs -- same 64-bit-first choice [PluginRuntimeService.nativeLibraryDirFor] already makes for native_bundle plugins, so both runners agree on which of a plugin's two shipped ABIs is "this device's". */
    // x86_64 wins whenever it is present -- see FlutterRuntimeManager.currentAbi's
    // own doc comment for the real device (BlueStacks) this was confirmed broken on:
    // an x86_64 process that also lists arm64-v8a (ARM translation) must not be
    // handed an AArch64 .so.
    fun currentAbi(): String =
        if (Build.SUPPORTED_64_BIT_ABIS.contains("x86_64")) "x86_64" else "arm64-v8a"

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
     * Downloads (if not already verified-installed) the pinned runtime as a job in "Downloads and
     * installs" ([DownloadJobs]: DownloadManager transfers, the SHA-256 is checked, then
     * [installFrom] extracts), narrating through [onStatus] -- driven from the
     * `AsyncActionItem` settings-row shape, droidtop's "long action with live text". Returns null
     * on success, an error message otherwise; throws nothing.
     */
    suspend fun ensureInstalled(context: Context, onStatus: (String) -> Unit): String? {
        val spec = readSpec(context) ?: return "no pinned Python runtime for this build (missing/broken $ASSET_PATH)"
        if (File(installDirFor(context, spec), MARKER_FILE).isFile) {
            onStatus("Done")
            return null
        }
        val result = DownloadJobs.run(
            context, "Python runtime", DOWNLOAD_POST, spec.url, "python-runtime.tar.gz",
            sha256 = spec.sha256, onStatus = onStatus,
        )
        return if (result.ok) null else "couldn't install the Python runtime: ${result.error}"
    }

    /** Names this runtime's post-processing step with the one download runner; called at process start. */
    internal fun registerDownloadPost() {
        DownloadJobs.registerPost(DOWNLOAD_POST) { context, file, _ -> installFrom(context, file) }
    }

    /** Extracts the verified download into the install directory and writes the marker; throws with the reason on failure. */
    private fun installFrom(context: Context, archive: File): String {
        val spec = readSpec(context) ?: throw IllegalStateException("no pinned Python runtime for this build")
        val installDir = installDirFor(context, spec)
        try {
            if (installDir.isDirectory) installDir.deleteRecursively()
            installDir.mkdirs()
            extractRuntime(archive, installDir)
            File(installDir, MARKER_FILE).writeText("${spec.version} ${spec.sha256}")
        } catch (e: Exception) {
            installDir.deleteRecursively()
            throw e
        }
        return "Python runtime installed"
    }

    /**
     * Extracts only everything under `prefix/lib/` (libpython*.so, libssl/libcrypto/
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
