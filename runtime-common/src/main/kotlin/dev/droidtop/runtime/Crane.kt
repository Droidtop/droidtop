package dev.droidtop.runtime

import android.content.Context
import java.io.File

/**
 * vendor/go-containerregistry's `crane`: the OCI registry client both
 * container backends pull images through (docs/SPEC.md §3, §3a). No Docker
 * daemon, just registry calls, and none of them needs root: they are
 * network requests writing into app-private storage.
 *
 * Packaged as `libcrane.so` in this module's jniLibs by
 * build-scripts/build-vendor-deps.sh, and run straight out of
 * `nativeLibraryDir`. It used to be an APK asset extracted into
 * `filesDir` and executed from there, which Android refuses for an app
 * targeting an SDK above 28 (droidtop targets 34; docs/SPEC.md 5b): the
 * native library directory is the one place an unrooted app may exec from.
 *
 * Built with cgo on every ABI so DNS goes through bionic/netd; see the
 * crane section of build-scripts/build-vendor-deps.sh for why the pure-Go
 * resolver cannot work on Android.
 */
object Crane {
    fun binaryPath(context: Context): String {
        val binary = File(context.applicationInfo.nativeLibraryDir, "libcrane.so")
        check(binary.isFile) {
            "crane is not packaged for this device's ABI (${binary.path} is missing); " +
                "only arm64-v8a and x86_64 are built"
        }
        return binary.absolutePath
    }

    /**
     * The OCI platform to pull for: this install's own ABI, which is the
     * kernel's (docs/SPEC.md §3, "The installed ABI must be the kernel's
     * own"). Always passed to `crane pull`: without it, a multi-platform
     * image is stored whole, every architecture's layers included.
     */
    fun platform(context: Context): String =
        when (val abi = File(context.applicationInfo.nativeLibraryDir).name) {
            "arm64", "arm64-v8a" -> "linux/arm64"
            "x86_64" -> "linux/amd64"
            else -> error("crane is not packaged for $abi; only arm64-v8a and x86_64 are built")
        }

    /** Resolves [reference] (`registry/repo:tag`) to its immutable digest via `crane digest`. */
    suspend fun digest(binaryPath: String, reference: String): String {
        val result = ProcessRunner.run(listOf(binaryPath, "digest", reference))
        check(result.succeeded) { "crane digest failed for $reference: ${result.stderr}" }
        return result.stdout.trim()
    }
}
