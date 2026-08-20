package dev.droidtop.runtime.linux.root

import android.content.Context
import android.os.Build
import java.io.File

/**
 * Extracts the bundled `droidspaces` binary (a static musl-libc executable,
 * cross-compiled per-ABI by build-scripts/build-vendor-deps.sh into
 * `runtime-linux-root/src/main/assets/bin/droidspaces-<abi>` — see that
 * script for why a static musl binary needs no further Android-specific
 * treatment: no shared-library deps at all, just the Linux kernel syscall
 * ABI, which Android provides regardless of bionic vs musl userland) from
 * APK assets to a real executable file the first time it's needed.
 *
 * The APK is a single fat build covering both `arm64-v8a` and `x86_64`
 * (see the app's build.gradle.kts `abiFilters`), so which asset to extract
 * has to be resolved at runtime against the device's actual primary ABI,
 * not assumed to be a single hardcoded one.
 */
object DroidSpacesBinary {
    /** Absolute path to the extracted, executable `droidspaces` binary. */
    fun ensureExtracted(context: Context): String {
        val dest = File(context.filesDir, "droidspaces/bin/droidspaces")
        if (!dest.exists()) {
            dest.parentFile?.mkdirs()
            context.assets.open("bin/droidspaces-${resolveAssetAbi()}").use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            dest.setExecutable(true, /* ownerOnly = */ true)
        }
        return dest.absolutePath
    }

    /**
     * [Build.SUPPORTED_ABIS] is ordered by preference — the first entry is
     * the device's actual primary ABI. Only `arm64-v8a`/`x86_64` have
     * cross-compiled assets (see build-scripts/build-vendor-deps.sh); any
     * other primary ABI (e.g. a 32-bit-only device) has nothing to extract
     * and fails loudly here rather than silently picking the wrong binary.
     */
    private fun resolveAssetAbi(): String {
        val supported = setOf("arm64-v8a", "x86_64")
        return Build.SUPPORTED_ABIS.firstOrNull { it in supported }
            ?: error(
                "No droidspaces asset for this device's ABIs (${Build.SUPPORTED_ABIS.joinToString()}) " +
                    "— only arm64-v8a and x86_64 are cross-compiled."
            )
    }
}
