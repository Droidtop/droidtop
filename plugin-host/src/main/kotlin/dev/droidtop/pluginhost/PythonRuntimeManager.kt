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
    private const val DOWNLOAD_POST = "python_runtime"

    private data class RuntimeSpec(val artifact: ArtifactSpec, val libpythonSoName: String, val stdlibDirName: String)

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
                artifact = ArtifactSpec(artifact.getString("url"), artifact.getString("sha256"), version),
                libpythonSoName = obj.getString("libpythonSoName"),
                stdlibDirName = obj.getString("stdlibDirName"),
            )
        }.getOrNull()
    }

    private fun rootDir(context: Context): File = File(context.filesDir, "python-runtime")

    private fun installDirFor(context: Context, spec: RuntimeSpec): File =
        File(rootDir(context), "${spec.artifact.version}/${currentAbi()}")

    /** Null when no pinned spec is readable at all (a packaging bug, never a normal state); otherwise the version this build would install/has installed. */
    fun pinnedVersion(context: Context): String? = readSpec(context)?.artifact?.version

    fun isInstalled(context: Context): Boolean {
        val spec = readSpec(context) ?: return false
        return RuntimeArtifactInstaller.isInstalled(installDirFor(context, spec))
    }

    fun installedVersion(context: Context): String? {
        val spec = readSpec(context) ?: return null
        if (!RuntimeArtifactInstaller.isInstalled(installDirFor(context, spec))) return null
        return spec.artifact.version
    }

    fun pythonHomeDir(context: Context): File? {
        val spec = readSpec(context) ?: return null
        val dir = installDirFor(context, spec)
        return if (RuntimeArtifactInstaller.isInstalled(dir)) dir else null
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
        if (RuntimeArtifactInstaller.isInstalled(installDirFor(context, spec))) {
            onStatus("Done")
            return null
        }
        val result = DownloadJobs.run(
            context, "Python runtime", DOWNLOAD_POST, spec.artifact.url, "python-runtime.tar.gz",
            onStatus = onStatus,
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
        RuntimeArtifactInstaller.install(archive, installDir, spec.artifact, ::extractRuntime)
        // A contained plugin's first start should not wait for this (docs/plugin-api.md 5.3); a failure here is retried then.
        runCatching { stdlibZip(installDir, spec) }
        return "Python runtime installed"
    }

    /**
     * What a contained python plugin's isolated process is handed (docs/plugin-api.md 5.3), by [ContainedFiles] name:
     * libpython, the standard library as one zip, the runtime's other libraries (OpenSSL, SQLite) and every `lib-dynload`
     * extension module by module name. Null when the runtime is not installed. Builds the zip the first time: off the main thread.
     */
    fun containedFiles(context: Context): List<Pair<String, File>>? {
        val spec = readSpec(context) ?: return null
        val home = installDirFor(context, spec)
        if (!RuntimeArtifactInstaller.isInstalled(home)) return null
        val lib = File(home, "lib")
        val libpython = File(lib, spec.libpythonSoName).takeIf { it.isFile } ?: return null
        val zip = stdlibZip(home, spec)
        val deps = lib.listFiles { f -> f.isFile && f.name.endsWith(".so") && f.name != spec.libpythonSoName }.orEmpty().sortedBy { it.name }
        val extensions = File(lib, "${spec.stdlibDirName}/lib-dynload").listFiles { f -> f.isFile && f.name.endsWith(".so") }.orEmpty().sortedBy { it.name }
        return buildList {
            add(ContainedFiles.PYTHON_LIBPYTHON + libpython.name to libpython)
            add(ContainedFiles.PYTHON_STDLIB to zip)
            deps.forEach { add(ContainedFiles.PYTHON_DEP + it.name to it) }
            // `_json.cpython-314-x86_64-linux-android.so` is the module `_json`.
            extensions.forEach { add(ContainedFiles.PYTHON_EXT + it.name.substringBefore('.') + "/" + it.name to it) }
        }
    }

    private val zipLock = Any()

    /**
     * The standard library as one zip, `python-stdlib.zip` beside the runtime: a contained process can open no folder, and
     * `zipimport` reads a zip through one descriptor. Pure Python only (the extension modules are handed over one by one),
     * without the test suite, IDLE and Tk, which no plugin can use. Entries are stored, not compressed: building it is a
     * copy, and an import decompresses nothing. Built once per runtime install, through a temporary file.
     */
    private fun stdlibZip(home: File, spec: RuntimeSpec): File {
        val out = File(home, "python-stdlib.zip")
        synchronized(zipLock) {
            if (out.isFile) return out
            val root = File(home, "lib/${spec.stdlibDirName}")
            val skip = setOf("lib-dynload", "test", "tests", "idlelib", "tkinter", "turtledemo", "ensurepip", "site-packages", "__pycache__")
            val tmp = File(home, "python-stdlib.zip.tmp")
            java.util.zip.ZipOutputStream(tmp.outputStream().buffered()).use { zip ->
                root.walkTopDown()
                    .onEnter { it == root || it.name !in skip }
                    .filter { it.isFile && !it.name.endsWith(".pyc") && !it.name.endsWith(".so") }
                    .forEach { file ->
                        val bytes = file.readBytes()
                        val crc = java.util.zip.CRC32().apply { update(bytes) }
                        val entry = java.util.zip.ZipEntry(file.relativeTo(root).path.replace(File.separatorChar, '/')).apply {
                            method = java.util.zip.ZipEntry.STORED
                            size = bytes.size.toLong()
                            compressedSize = bytes.size.toLong()
                            this.crc = crc.value
                        }
                        zip.putNextEntry(entry)
                        zip.write(bytes)
                        zip.closeEntry()
                    }
            }
            check(tmp.renameTo(out)) { "couldn't move the standard library zip into place" }
        }
        return out
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
