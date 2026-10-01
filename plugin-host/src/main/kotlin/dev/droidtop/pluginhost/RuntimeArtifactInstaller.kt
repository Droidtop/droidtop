package dev.droidtop.pluginhost

import java.io.File
import java.security.MessageDigest

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
            require(sha256(archive).equals(spec.sha256, ignoreCase = true)) { DownloadJobs.DIGEST_MISMATCH }
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

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
