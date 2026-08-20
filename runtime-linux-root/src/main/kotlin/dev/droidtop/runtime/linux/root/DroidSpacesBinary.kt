package dev.droidtop.runtime.linux.root

import android.content.Context
import java.io.File

/**
 * Extracts the bundled `droidspaces` binary (a static musl-libc executable,
 * cross-compiled by build-scripts/build-vendor-deps.sh into
 * `runtime-linux-root/src/main/assets/bin/droidspaces-arm64-v8a` — see that
 * script for why a static musl binary needs no further Android-specific
 * treatment: no shared-library deps at all, just the Linux kernel syscall
 * ABI, which Android provides regardless of bionic vs musl userland) from
 * APK assets to a real executable file the first time it's needed.
 */
object DroidSpacesBinary {
    private const val ASSET_NAME = "bin/droidspaces-arm64-v8a"

    /** Absolute path to the extracted, executable `droidspaces` binary. */
    fun ensureExtracted(context: Context): String {
        val dest = File(context.filesDir, "droidspaces/bin/droidspaces")
        if (!dest.exists()) {
            dest.parentFile?.mkdirs()
            context.assets.open(ASSET_NAME).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            dest.setExecutable(true, /* ownerOnly = */ true)
        }
        return dest.absolutePath
    }
}
