package dev.droidtop.runtime.linux.root

import android.content.Context

/**
 * Extracts the bundled `crane` binary (vendor/go-containerregistry's CLI,
 * cross-compiled per-ABI by build-scripts/build-vendor-deps.sh into
 * `runtime-linux-root/src/main/assets/bin/crane-<abi>`) from APK assets to a
 * real executable file the first time it's needed — same pattern as
 * [DroidSpacesBinary], see that class for the fat-APK/ABI-resolution
 * rationale.
 *
 * Unlike droidspaces (a static musl binary needing nothing else), crane is
 * a normal Go binary built with cgo enabled on EVERY ABI — required on
 * x86_64 for linking at all, and on arm64 for a working DNS resolver
 * (Android has no /etc/resolv.conf; only the cgo resolver reaches
 * bionic/netd — see build-scripts/build-vendor-deps.sh's crane section
 * for the full confirmed-on-device story). Both are still self-contained
 * executables once built; no runtime shared-library dependency this
 * class needs to worry about.
 *
 * Extraction is keyed on the APK's own `lastUpdateTime` (same real
 * pattern, for the same confirmed-live reason, as ThemeAssets'
 * bundled-theme extraction): a bare `dest.exists()` check kept serving a
 * binary extracted by an OLD install forever — the real case: the cgo
 * DNS fix shipped in a new APK while the device kept executing the
 * three-day-old no-cgo binary, reproducing the exact failure the new
 * build had fixed. versionCode is pinned at 1 in this project, so it
 * can't be the key.
 */
object CraneBinary {
    fun ensureExtracted(context: Context): String =
        BundledBinary.ensureExtracted(context, dirName = "crane", binaryName = "crane", assetBaseName = "crane")
}
