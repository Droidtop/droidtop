package dev.droidtop.library.diagnostics

import android.content.Context
import android.os.Build
import dev.droidtop.library.EngineHost
import dev.droidtop.library.ScanLog
import dev.droidtop.library.consoles.PlatformDatabaseSnapshot
import dev.droidtop.library.settings.CatalogPrefs
import dev.droidtop.library.theme.ThemeAssets
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * The archive behind Global settings > Data > Share diagnostics (docs/SPEC.md
 * 10c). One builder, so what leaves the device is decided in one place: the
 * logs folder (every file already rolls at a fixed size and is written
 * redacted), the settings export with every credential key left out,
 * and one text file naming the build, the platform-database snapshot, the
 * installed themes and the Enginehost version. It never sends anything
 * itself; the caller hands the finished file to the system share sheet.
 */
object DiagnosticsArchive {
    /**
     * Where the finished archive is written: under the same files root as
     * the logs (docs/SPEC.md 10c), so a person -- or adb on an unrooted
     * device, which cannot reach the cache at all -- can check what the
     * action produced before or instead of sharing it. [build] leaves one
     * archive: the newest.
     */
    private const val OUT_DIR = "diagnostics"

    /** Per-file cap on what goes in. The log files roll well below this; it bounds a stray file someone put in the folder. */
    private const val MAX_FILE_BYTES = 1024L * 1024L

    /**
     * Whether a settings key holds a credential (or the identity that goes
     * with one) and so stays out of the archive. Matched on the key name:
     * a new credential key that follows droidtop's naming (a password, a
     * secret, an API key, a token, a login or client id) is left out with no
     * list to remember to extend.
     */
    fun isCredentialKey(key: String): Boolean {
        val lower = key.lowercase()
        return CREDENTIAL_MARKERS.any { lower.contains(it) }
    }

    private val CREDENTIAL_MARKERS = listOf(
        "password", "passwd", "secret", "apikey", "api_key", "token", "credential",
        "client_id", "clientid", "_ssid", "devid", "login", "auth",
    )

    /** The settings export the backup writes, minus every credential key. */
    fun redactedSettings(all: Map<String, *>): JSONObject {
        val json = JSONObject()
        for ((key, value) in all) {
            if (isCredentialKey(key)) continue
            when (value) {
                is Boolean, is Int, is Long, is Float, is String -> json.put(key, value)
                is Set<*> -> json.put(key, JSONArray(value.toList()))
                else -> {}
            }
        }
        return json
    }

    /** Builds the archive and answers the file. Blocking file work: call off the main thread. */
    fun build(context: Context): File {
        val dir = File(ScanLog.filesRoot(context), OUT_DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "droidtop-diagnostics-${System.currentTimeMillis() / 1000}.zip")
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            ScanLog.logsDir(context).listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.forEach { file ->
                zip.putNextEntry(ZipEntry("logs/${file.name}"))
                zip.write(tail(file))
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("settings.json"))
            zip.write(redactedSettings(CatalogPrefs.prefs(context).all).toString(2).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("info.txt"))
            zip.write(info(context).toByteArray())
            zip.closeEntry()
        }
        return out
    }

    /** The last [MAX_FILE_BYTES] of [file]: the newest lines are the ones that matter. */
    private fun tail(file: File): ByteArray = java.io.RandomAccessFile(file, "r").use { raf ->
        val length = raf.length()
        val size = minOf(length, MAX_FILE_BYTES).toInt()
        raf.seek(length - size)
        ByteArray(size).also { raf.readFully(it) }
    }

    private fun info(context: Context): String = buildString {
        val pm = context.packageManager
        val self = runCatching { pm.getPackageInfo(context.packageName, 0) }.getOrNull()
        appendLine("droidtop ${self?.versionName ?: "unknown build"}")
        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}, ABIs ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("Platform database snapshot: ${PlatformDatabaseSnapshot.commit(context) ?: "none recorded"}")
        val enginehost = runCatching { pm.getPackageInfo(EngineHost.PACKAGE_NAME, 0).versionName }.getOrNull()
        appendLine("Enginehost: ${enginehost ?: "not installed"}")
        val themes = runCatching { ThemeAssets.discoverThemes(context).map { it.name } }.getOrDefault(emptyList())
        appendLine("Themes: ${if (themes.isEmpty()) "none" else themes.joinToString()}")
    }
}
