package dev.droidtop.pluginhost

import android.content.Context
import android.os.Build
import java.io.File
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
    private const val DOWNLOAD_POST = "flutter_runtime"

    private data class RuntimeSpec(val version: String, val libflutterSoName: String, val artifact: ArtifactSpec, val jarEntry: String, val soEntryInJar: String)

    /**
     * Same ABI choice [PythonRuntimeManager.currentAbi] and
     * [PluginRuntimeService.nativeLibraryDirFor] already make -- all three
     * need to agree on which of a plugin's two shipped ABIs is "this
     * device's". x86_64 wins whenever it is present: an x86_64 device
     * that ALSO lists arm64-v8a in [Build.SUPPORTED_64_BIT_ABIS] (BlueStacks
     * does, for its ARM-translation layer) used to fall through to the old
     * `&& !contains("arm64-v8a")` guard and pick arm64-v8a, downloading and
     * loading an AArch64 libflutter.so on a real x86_64 process -- confirmed
     * on the rig: `dlopen failed: ... has unexpected e_machine: 183
     * (EM_AARCH64)`, acquire_content's first real search call ever run
     * against a real flutter_embed plugin (2026-09-26). x86_64 is always
     * this process's real native ABI when it is listed at all, so it must
     * win outright, not only when arm64-v8a is absent.
     */
    fun currentAbi(): String =
        if (Build.SUPPORTED_64_BIT_ABIS.contains("x86_64")) "x86_64" else "arm64-v8a"

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
                    version = version,
                ),
                jarEntry = artifactJson.getString("jarEntry"),
                soEntryInJar = artifactJson.getString("soEntryInJar"),
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
        return RuntimeArtifactInstaller.isInstalled(installDirFor(context, spec))
    }

    /** The extracted `libflutter.so`'s absolute path, or null when not installed -- what [FlutterDroidtopPlugin]'s custom `FlutterJNI.loadLibrary` override `System.load()`s instead of the stock APK-relative lookup. */
    fun libflutterSoPath(context: Context): File? {
        val spec = readSpec(context) ?: return null
        val dir = installDirFor(context, spec)
        if (!RuntimeArtifactInstaller.isInstalled(dir)) return null
        return File(dir, spec.libflutterSoName)
    }

    /** Deletes every installed runtime version -- the plugin host's own "remove" action, independent of uninstalling any one flutter_embed plugin, same shape as [PythonRuntimeManager.remove]. */
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
        val spec = readSpec(context) ?: return "no pinned Flutter runtime for this build (missing/broken $ASSET_PATH)"
        if (RuntimeArtifactInstaller.isInstalled(installDirFor(context, spec))) {
            onStatus("Done")
            return null
        }
        val result = DownloadJobs.run(
            context, "Flutter runtime", DOWNLOAD_POST, spec.artifact.url, "flutter-runtime.zip",
            onStatus = onStatus,
        )
        return if (result.ok) null else "couldn't install the Flutter runtime: ${result.error}"
    }

    /** Names this runtime's post-processing step with the one download runner; called at process start. */
    internal fun registerDownloadPost() {
        DownloadJobs.registerPost(DOWNLOAD_POST) { context, file, _ -> installFrom(context, file) }
    }

    /** Extracts the verified download into the install directory and writes the marker; throws with the reason on failure. */
    private fun installFrom(context: Context, archive: File): String {
        val spec = readSpec(context) ?: throw IllegalStateException("no pinned Flutter runtime for this build")
        val installDir = installDirFor(context, spec)
        RuntimeArtifactInstaller.install(archive, installDir, spec.artifact, { file, dir -> extractLibflutter(file, spec, dir) })
        return "Flutter runtime installed"
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
            val entry = outer.getEntry(spec.jarEntry)
                ?: throw IllegalStateException("artifacts.zip has no ${spec.jarEntry}")
            outer.getInputStream(entry).use { it.readBytes() }
        }
        val jarTmp = File(installDir, "flutter.jar.tmp")
        jarTmp.writeBytes(jarBytes)
        try {
            ZipFile(jarTmp).use { inner ->
                val soEntry = inner.getEntry(spec.soEntryInJar)
                    ?: throw IllegalStateException("${spec.jarEntry} has no ${spec.soEntryInJar}")
                inner.getInputStream(soEntry).use { input ->
                    File(installDir, spec.libflutterSoName).outputStream().use { output -> input.copyTo(output) }
                }
            }
        } finally {
            jarTmp.delete()
        }
    }
}
