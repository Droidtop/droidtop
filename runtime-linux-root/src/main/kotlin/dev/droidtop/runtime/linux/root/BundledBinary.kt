package dev.droidtop.runtime.linux.root

import android.content.Context
import android.os.Build
import java.io.File

/**
 * Shared extract-a-bundled-binary-from-APK-assets implementation behind
 * [CraneBinary] and [DroidSpacesBinary] (one mechanism, not two copies of
 * it -- they only differ by asset/destination name).
 *
 * Extraction is keyed on the APK's own `lastUpdateTime` (same real
 * pattern, for the same confirmed-live reason, as ThemeAssets'
 * bundled-theme extraction): a bare `dest.exists()` check kept serving a
 * binary extracted by an OLD install forever. The real case that forced
 * this: the crane cgo-DNS fix shipped in a new APK while the device kept
 * executing the three-day-old no-cgo extraction, reproducing the exact
 * failure the new build had fixed. versionCode is pinned at 1 in this
 * project, so it can't be the key.
 */
internal object BundledBinary {
    /**
     * Extracts `assets/bin/<assetBaseName>-<abi>` to
     * `filesDir/<dirName>/bin/<binaryName>` (re-extracting when the APK
     * changed) and returns the executable's absolute path.
     */
    fun ensureExtracted(context: Context, dirName: String, binaryName: String, assetBaseName: String): String {
        val dest = File(context.filesDir, "$dirName/bin/$binaryName")
        val marker = File(context.filesDir, "$dirName/bin/.extracted")
        val installStamp = context.packageManager
            .getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        if (!dest.exists() || marker.takeIf { it.exists() }?.readText() != installStamp) {
            dest.parentFile?.mkdirs()
            context.assets.open("bin/$assetBaseName-${resolveAssetAbi(assetBaseName)}").use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            dest.setExecutable(true, /* ownerOnly = */ true)
            marker.writeText(installStamp)
        }
        return dest.absolutePath
    }

    /**
     * [Build.SUPPORTED_ABIS] is ordered by preference -- the first entry is
     * the device's actual primary ABI. Only `arm64-v8a`/`x86_64` have
     * cross-compiled assets (see build-scripts/build-vendor-deps.sh); any
     * other primary ABI (e.g. a 32-bit-only device) has nothing to extract
     * and fails loudly here rather than silently picking the wrong binary.
     */
    private fun resolveAssetAbi(assetBaseName: String): String {
        val supported = setOf("arm64-v8a", "x86_64")
        return Build.SUPPORTED_ABIS.firstOrNull { it in supported }
            ?: error(
                "No $assetBaseName asset for this device's ABIs (${Build.SUPPORTED_ABIS.joinToString()}) " +
                    "— only arm64-v8a and x86_64 are cross-compiled."
            )
    }
}
