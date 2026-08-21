package dev.droidtop.runtime.linux.root

import android.content.Context
import android.os.Build
import java.io.File

/**
 * Extracts the bundled `crane` binary (vendor/go-containerregistry's CLI,
 * cross-compiled per-ABI by build-scripts/build-vendor-deps.sh into
 * `runtime-linux-root/src/main/assets/bin/crane-<abi>`) from APK assets to a
 * real executable file the first time it's needed — same pattern as
 * [DroidSpacesBinary], see that class for the fat-APK/ABI-resolution
 * rationale.
 *
 * Unlike droidspaces (a static musl binary needing nothing else), crane is
 * a normal Go binary: arm64 links statically without cgo, but Android's
 * x86_64 target needs external (cgo) linking, so that ABI is built against
 * the Android NDK's clang — see build-scripts/build-vendor-deps.sh's crane
 * section for exactly how. Both are still self-contained executables once
 * built; no runtime shared-library dependency this class needs to worry
 * about.
 */
object CraneBinary {
    fun ensureExtracted(context: Context): String {
        val dest = File(context.filesDir, "crane/bin/crane")
        if (!dest.exists()) {
            dest.parentFile?.mkdirs()
            context.assets.open("bin/crane-${resolveAssetAbi()}").use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            dest.setExecutable(true, /* ownerOnly = */ true)
        }
        return dest.absolutePath
    }

    private fun resolveAssetAbi(): String {
        val supported = setOf("arm64-v8a", "x86_64")
        return Build.SUPPORTED_ABIS.firstOrNull { it in supported }
            ?: error(
                "No crane asset for this device's ABIs (${Build.SUPPORTED_ABIS.joinToString()}) " +
                    "— only arm64-v8a and x86_64 are cross-compiled."
            )
    }
}
