package dev.droidtop.runtime.windows.utils

import android.content.Context
import dev.droidtop.runtime.windows.RuntimeDownloads
import com.winlator.core.FileUtils
import com.winlator.core.TarCompressorUtils
import java.io.File
import java.security.MessageDigest
import timber.log.Timber

/**
 * One archive published as a release asset of this repository, pinned by tag
 * and SHA-256, downloaded once and unpacked into its own directory under the
 * app's files dir. The x86_64 pieces that are not part of the APK
 * ([X86_64GuestLibs], [X86_64Graphics]) are each one of these.
 *
 * Installed means the directory carries a stamp naming [tag], so moving the pin
 * to a new release makes the next setup fetch it again.
 */
class PinnedReleaseAsset(
    val tag: String,
    val asset: String,
    val sha256: String,
    private val dirName: String,
    /** What a person reads when the download or the check fails ("the x86_64 Windows libraries"). */
    private val label: String,
) {
    private val url = "https://github.com/Droidtop/gamenative-tux/releases/download/$tag/$asset"

    fun root(context: Context): File = File(context.filesDir, dirName)

    fun isInstalled(context: Context): Boolean =
        File(root(context), STAMP).let { it.isFile && it.readText().trim() == tag }

    /**
     * Downloads and unpacks the archive unless this [tag] is already in place.
     * Throws with a message a person can act on when the download or the
     * checksum fails; a half-unpacked tree never replaces a working one.
     */
    suspend fun ensureInstalled(context: Context, onProgress: (Float) -> Unit) {
        if (isInstalled(context)) return
        val archive = File(context.filesDir, asset)
        if (!archive.isFile || sha256(archive) != sha256) {
            archive.delete()
            RuntimeDownloads.fetchUrl(url, archive, onProgress)
        }
        val actual = sha256(archive)
        if (actual != sha256) {
            archive.delete()
            Timber.w("PinnedReleaseAsset: %s SHA-256 %s does not match the pinned %s", tag, actual, sha256)
            error("the download of $label failed its checksum; retry the setup")
        }
        Timber.i("PinnedReleaseAsset: %s SHA-256 %s matches the pin", tag, actual)
        val dest = root(context)
        val staging = File(context.filesDir, "$dirName.tmp")
        FileUtils.delete(staging)
        staging.mkdirs()
        if (!TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, archive, staging)) {
            FileUtils.delete(staging)
            error("couldn't unpack $label")
        }
        FileUtils.delete(dest)
        if (!staging.renameTo(dest)) {
            FileUtils.delete(staging)
            error("couldn't install $label")
        }
        File(dest, STAMP).writeText(tag)
        archive.delete()
        Timber.i("PinnedReleaseAsset: installed %s", tag)
    }

    private fun sha256(file: File): String {
        if (!file.isFile) return ""
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val STAMP = ".release"
    }
}
