package app.gamenative.utils.downloader

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Real downloader for the container archives the forked `com.winlator`
 * tree asks for -- `ContainerManager.extractContainerPatternFile` calls
 * [ensureContainerFileAvailableBlocking] for `container_pattern_*`, and
 * `ImageFsInstaller.installGuestLibs` for `extras`.
 *
 * This replaces a shim that always returned null. That shim's own doc
 * justified itself two ways, and checking upstream shows both claims
 * were wrong: the downloads are NOT auth-gated (upstream's
 * `fetchFileWithFallback` is a plain, unauthenticated GET against
 * `downloads.gamenative.app` with a public R2 mirror as fallback), and
 * the callers do NOT all "fall back to the bundled asset" -- the
 * container pattern and imagefs archives are not in the bundled assets
 * at all, so returning null made container creation fail on its very
 * first extraction. That dead end is the real reason droidtop could
 * never create a Wine environment.
 *
 * URLs come from the bundled `container_files_download.json` manifest --
 * the same file upstream ships and reads -- rather than being hardcoded
 * here, so upstream moving an archive updates droidtop through the
 * ordinary vendor sync.
 */
fun interface ProgressCallback {
    fun onProgress(progress: Float)
}

private const val TAG = "droidtop.ContainerDl"
private const val MANIFEST_ASSET = "container_files_download.json"

/** Upstream's own public mirror, tried when the primary host fails. */
private const val FALLBACK_HOST = "https://pub-9fcd5294bd0d4b85a9d73615bf98f3b5.r2.dev"

fun ensureContainerFileAvailableBlocking(
    context: Context,
    componentId: String,
    callback: ProgressCallback,
): File? {
    val entry = manifestEntry(context, componentId)
    if (entry == null) {
        Log.w(TAG, "No manifest entry for component \"$componentId\"")
        return null
    }
    val (name, url) = entry
    val dest = File(File(context.filesDir, "container_files"), name)
    if (dest.isFile && dest.length() > 0) return dest

    // Primary is the manifest's own URL; the R2 mirror is tried with the
    // same path and then the bare filename, since upstream's top-level
    // fallback uses bare names and the mirror's layout for subpaths is
    // not documented anywhere -- trying both costs one failed request.
    val path = runCatching { URL(url).path }.getOrNull() ?: "/$name"
    val candidates = listOf(url, "$FALLBACK_HOST$path", "$FALLBACK_HOST/$name").distinct()
    return if (downloadToFile(candidates, dest, callback)) dest else null
}

/**
 * The `imagefs_*.txz` base-system archive, downloaded to where
 * `ImageFsInstaller.installFromAssetsFuture` actually looks for it (the
 * app files dir -- `ImageFs.getFilesDir()` is the imagefs root's
 * parent). Upstream pre-downloads this in its own pre-launch phase
 * through `SteamService.downloadFile`, a service droidtop deliberately
 * does not fork in -- so droidtop's provisioning calls this instead,
 * before running the installer.
 */
fun ensureImageFsArchiveBlocking(
    context: Context,
    fileName: String,
    callback: ProgressCallback,
): File? {
    val dest = File(context.filesDir, fileName)
    if (dest.isFile && dest.length() > 0) return dest
    // Same primary/fallback pair upstream's fetchFileWithFallback uses.
    val candidates = listOf(
        "https://downloads.gamenative.app/$fileName",
        "$FALLBACK_HOST/$fileName",
    )
    return if (downloadToFile(candidates, dest, callback)) dest else null
}

/** id -> (name, url) from the bundled manifest. */
private fun manifestEntry(context: Context, componentId: String): Pair<String, String>? =
    runCatching {
        val doc = JSONObject(
            context.assets.open(MANIFEST_ASSET).bufferedReader().use { it.readText() },
        )
        val components = doc.getJSONArray("components")
        for (i in 0 until components.length()) {
            val component = components.getJSONObject(i)
            if (component.getString("id") == componentId) {
                return@runCatching component.getString("name") to component.getString("url")
            }
        }
        null
    }.getOrNull()

/**
 * Tries [candidates] in order until one succeeds. Downloads to a `.part`
 * file and renames only on completion, so a partial download from a
 * killed process is never mistaken for the finished archive on the next
 * attempt -- these are multi-hundred-megabyte files and a truncated one
 * extracts "successfully" into a broken environment.
 */
private fun downloadToFile(candidates: List<String>, dest: File, callback: ProgressCallback): Boolean {
    dest.parentFile?.mkdirs()
    val part = File(dest.absolutePath + ".part")
    for (candidate in candidates) {
        try {
            val connection = URL(candidate).openConnection() as HttpURLConnection
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true
            try {
                if (connection.responseCode !in 200..299) {
                    Log.w(TAG, "$candidate -> HTTP ${connection.responseCode}")
                    continue
                }
                val total = connection.contentLengthLong
                connection.inputStream.use { input ->
                    FileOutputStream(part).use { output ->
                        val buffer = ByteArray(256 * 1024)
                        var copied = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            if (total > 0) callback.onProgress(copied.toFloat() / total)
                        }
                    }
                }
                if (part.length() == 0L || (total > 0 && part.length() != total)) {
                    throw IOException("short download: ${part.length()} of $total bytes")
                }
                dest.delete()
                if (!part.renameTo(dest)) throw IOException("couldn't move ${part.name} into place")
                return true
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Download from $candidate failed", e)
            part.delete()
        }
    }
    return false
}
