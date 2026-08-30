package dev.droidtop.runtime.linux.root

import android.content.Context

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
    fun ensureExtracted(context: Context): String =
        BundledBinary.ensureExtracted(context, dirName = "droidspaces", binaryName = "droidspaces", assetBaseName = "droidspaces")
}
