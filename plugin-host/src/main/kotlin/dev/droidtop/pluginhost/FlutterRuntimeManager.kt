package dev.droidtop.pluginhost

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.json.JSONObject

/**
 * Downloads, verifies, stores and removes the Flutter engine runtime the
 * `flutter_embed` plugin kind embeds (docs/SPEC.md 12a) -- the [PluginKind.FLUTTER_EMBED]
 * analogue of [PythonRuntimeManager], same shape by design: never
 * bundled in the base APK, downloaded on first use of a flutter_embed
 * plugin, hash-verified against [ASSET_PATH]'s pinned manifest before a
 * single byte of it is trusted.
 *
 * Unlike CPython's official per-version archives (which python.org keeps
 * indefinitely, per [PythonRuntimeManager]'s own header), the source here
 * -- `storage.googleapis.com/flutter_infra_release`, the same bucket
 * `flutter precache` itself downloads from -- was confirmed to retain
 * every engine build this project could find going back to 2022 (a GCS
 * bucket listing query against a handful of old engine hashes all still
 * returned 200, 2026-09-26), so the same "download straight from
 * upstream, never re-host" posture holds. That said, this is one
 * upstream's storage retention policy, not a published guarantee the way
 * python.org's release archives are -- if a future pinned version's
 * artifact ever disappears, the fix is re-hosting that exact version's
 * bytes under a droidtop-controlled URL with the same pinned hash, not
 * silently drifting to a newer, unverified version.
 *
 * Each per-ABI artifact at `flutter_infra_release/flutter/<version>/android-<arch>-release/artifacts.zip`
 * is a zip containing one file, `flutter.jar` -- itself a zip (an Android
 * native-library jar, not a dex/class jar) holding exactly
 * `lib/<abi>/libflutter.so` plus a license file. [ensureInstalled]
 * verifies the OUTER `artifacts.zip`'s own sha256 against the pinned
 * value (matching [PythonRuntimeManager]'s "verify the archive, then
 * extract" order) before opening either zip layer, and extracts ONLY the
 * `.so` -- the license text is kept in NOTICE.md instead, the same
 * "extract only what's actually needed" call [PythonRuntimeManager]
 * makes for CPython's `prefix/include`.
 *
 * Storage layout: `filesDir/flutter-runtime/<version>/<abi>/libflutter.so`.
 * The compiled-in Java embedding classes (`io.flutter.*`, from the
 * `flutter_embedding_release` Maven artifact at this same pinned version
 * -- `plugin-host/build.gradle.kts`) are NOT downloaded here: they are a
 * few hundred KB of plain JVM bytecode with no Dart/native engine code in
 * them, the same "small glue code ships in the base APK" call
 * [PythonBridge]'s `libdroidtoppy.so` already makes -- what must never be
 * bundled, and isn't, is the actual multi-hundred-MB Dart/Skia/Impeller
 * engine binary this class downloads.
 */
object FlutterRuntimeManager {
    private const val ASSET_PATH = "flutter-runtimes.json"
    private const val MARKER_FILE = ".verified"

    sealed interface Progress {
        data class Downloading(val bytesRead: Long, val totalBytes: Long) : Progress
        object Verifying : Progress
        object Extracting : Progress
        object Done : Progress
    }

    private data class ArtifactSpec(val url: String, val sha256: String, val jarEntry: String, val soEntryInJar: String)
    private data class RuntimeSpec(val version: String, val libflutterSoName: String, val artifact: ArtifactSpec)

    /** Same 64-bit-first ABI choice [PythonRuntimeManager.currentAbi] and [PluginRuntimeService.nativeLibraryDirFor] already make -- all three need to agree on which of a plugin's two shipped ABIs is "this device's". */
    fun currentAbi(): String =
        if (Build.SUPPORTED_64_BIT_ABIS.contains("x86_64") && !Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a")) "x86_64" else "arm64-v8a"

    private fun readSpec(context: Context): RuntimeSpec? {
        val json = runCatching {
            context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
        }.getOrNull() ?: return null
        return runCatching {
            val obj = JSONObject(json)
            val version = obj.getString("version")
            val artifactJson = obj.getJSONObject("artifacts").getJSONObject(currentAbi())
            RuntimeSpec(
                version = version,
                libflutterSoName = obj.getString("libflutterSoName"),
                artifact = ArtifactSpec(
                    url = artifactJson.getString("url"),
                    sha256 = artifactJson.getString("sha256"),
                    jarEntry = artifactJson.getString("jarEntry"),
                    soEntryInJar = artifactJson.getString("soEntryInJar"),
                ),
            )
        }.getOrNull()
    }

    private fun rootDir(context: Context): File = File(context.filesDir, "flutter-runtime")

    private fun installDirFor(context: Context, spec: RuntimeSpec): File =
        File(rootDir(context), "${spec.version}/${currentAbi()}")

    /** Null when no pinned spec is readable at all (a packaging bug); otherwise the exact engine version [PluginManifest.runtimeVersion] must match for this build to activate a flutter_embed plugin. */
    fun pinnedVersion(context: Context): String? = readSpec(context)?.version

    fun isInstalled(context: Context): Boolean {
        val spec = readSpec(context) ?: return false
        return File(installDirFor(context, spec), MARKER_FILE).isFile
    }

    /** The extracted `libflutter.so`'s absolute path, or null when not installed -- what [FlutterDroidtopPlugin]'s custom `FlutterJNI.loadLibrary` override `System.load()`s instead of the stock APK-relative lookup. */
    fun libflutterSoPath(context: Context): File? {
        val spec = readSpec(context) ?: return null
        val dir = installDirFor(context, spec)
        if (!File(dir, MARKER_FILE).isFile) return null
        return File(dir, spec.libflutterSoName)
    }

    /** Deletes every installed runtime version -- the plugin host's own "remove" action, independent of uninstalling any one flutter_embed plugin, same shape as [PythonRuntimeManager.remove]. */
    fun remove(context: Context) {
        rootDir(context).deleteRecursively()
    }

    /**
     * Downloads (if not already verified-installed), verifies and
     * extracts the pinned runtime, reporting [onProgress] along the way
     * -- meant to be driven from the same `AsyncActionItem` settings-row
     * shape [PythonRuntimeManager.ensureInstalled] already uses. Must be
     * called off the main thread; returns null on success, an error
     * message otherwise, never throws.
     */
    suspend fun ensureInstalled(context: Context, onProgress: (Progress) -> Unit): String? {
        val spec = readSpec(context) ?: return "no pinned Flutter runtime for this build (missing/broken $ASSET_PATH)"
        val installDir = installDirFor(context, spec)
        if (File(installDir, MARKER_FILE).isFile) {
            onProgress(Progress.Done)
            return null
        }

        val tmpFile = File(context.cacheDir, "flutter-runtime-download.zip")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val connection = (URL(spec.artifact.url).openConnection() as HttpURLConnection).apply {
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
            if (!actualSha.equals(spec.artifact.sha256, ignoreCase = true)) {
                return "downloaded runtime failed SHA-256 verification (got $actualSha, expected ${spec.artifact.sha256}) -- refusing to install it"
            }

            onProgress(Progress.Extracting)
            if (installDir.isDirectory) installDir.deleteRecursively()
            installDir.mkdirs()
            extractLibflutter(tmpFile, spec, installDir)

            File(installDir, MARKER_FILE).writeText("${spec.version} ${spec.artifact.sha256}")
            onProgress(Progress.Done)
            return null
        } catch (e: Exception) {
            installDir.deleteRecursively()
            return "couldn't install the Flutter runtime: ${e.message}"
        } finally {
            tmpFile.delete()
        }
    }

    /**
     * Two zip layers deep (this file's header comment): `artifacts.zip`
     * (already verified by [ensureInstalled]) contains [ArtifactSpec.jarEntry]
     * (`flutter.jar`), which itself contains [ArtifactSpec.soEntryInJar]
     * (`lib/<abi>/libflutter.so`) -- extracted straight to
     * `[installDir]/[RuntimeSpec.libflutterSoName]`, nothing else kept.
     */
    private fun extractLibflutter(archive: File, spec: RuntimeSpec, installDir: File) {
        val jarBytes = ZipFile(archive).use { outer ->
            val entry = outer.getEntry(spec.artifact.jarEntry)
                ?: throw IllegalStateException("artifacts.zip has no ${spec.artifact.jarEntry}")
            outer.getInputStream(entry).use { it.readBytes() }
        }
        val jarTmp = File(installDir, "flutter.jar.tmp")
        jarTmp.writeBytes(jarBytes)
        try {
            ZipFile(jarTmp).use { inner ->
                val soEntry = inner.getEntry(spec.artifact.soEntryInJar)
                    ?: throw IllegalStateException("${spec.artifact.jarEntry} has no ${spec.artifact.soEntryInJar}")
                inner.getInputStream(soEntry).use { input ->
                    File(installDir, spec.libflutterSoName).outputStream().use { output -> input.copyTo(output) }
                }
            }
        } finally {
            jarTmp.delete()
        }
    }
}
