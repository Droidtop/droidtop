package dev.droidtop.pluginhost

import dev.droidtop.shared.trust.Sha256
import java.io.File

/** The download identity shared by the plugin-host runtime installers. */
data class ArtifactSpec(val url: String, val sha256: String, val version: String)

/** Common phases shown while a runtime artifact is installed. */
sealed interface RuntimeArtifactProgress {
    data class Downloading(val line: String) : RuntimeArtifactProgress
    data object Verifying : RuntimeArtifactProgress
    data object Extracting : RuntimeArtifactProgress
    data object Done : RuntimeArtifactProgress
}

/** Shared verified extraction and marker lifecycle for downloaded runtimes. */
object RuntimeArtifactInstaller {
    const val MARKER_FILE = ".verified"

    /**
     * Where an archive entry named [relative] goes under [installDir], or null when it must not be written: a name with
     * a `..` or `.` part, an absolute name, or one whose real path is not strictly inside [installDir] (a symlink in the
     * way). A plain prefix check let `lib/../../python-evil/x` reach a sibling folder sharing the install folder's name
     * as its prefix (Droidtop/tracker#874); this refuses `..` outright as lib-net's `BundleTar.validatePath`
     * does, then compares real paths with the separator after the root. Pure but for the paths it resolves.
     */
    fun entryTarget(installDir: File, relative: String): File? {
        if (relative.isEmpty() || relative.startsWith("/") || relative.contains('\\')) return null
        val parts = relative.split('/')
        if (parts.any { it == ".." || it == "." }) return null
        val root = installDir.canonicalFile
        val target = File(root, relative).canonicalFile
        return target.takeIf { it.path.startsWith(root.path.trimEnd(File.separatorChar) + File.separator) }
    }

    /** Installs [archive] into [installDir]. The caller has already downloaded it; its digest is checked here too. */
    fun install(
        archive: File,
        installDir: File,
        spec: ArtifactSpec,
        extract: (File, File) -> Unit,
        onProgress: (RuntimeArtifactProgress) -> Unit = {},
    ) {
        try {
            onProgress(RuntimeArtifactProgress.Verifying)
            require(Sha256.hex(archive).equals(spec.sha256, ignoreCase = true)) { DownloadJobs.DIGEST_MISMATCH }
            onProgress(RuntimeArtifactProgress.Extracting)
            if (installDir.isDirectory) installDir.deleteRecursively()
            check(installDir.mkdirs() || installDir.isDirectory) { "couldn't create the runtime directory" }
            extract(archive, installDir)
            File(installDir, MARKER_FILE).writeText("${spec.version} ${spec.sha256}")
            onProgress(RuntimeArtifactProgress.Done)
        } catch (e: Exception) {
            installDir.deleteRecursively()
            throw e
        }
    }

    fun isInstalled(installDir: File): Boolean = File(installDir, MARKER_FILE).isFile
}
