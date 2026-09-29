package dev.droidtop.app.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dev.droidtop.library.EngineHost
import dev.droidtop.library.EnginehostCapabilities
import dev.droidtop.library.ScanLog
import dev.droidtop.library.consoles.PlatformDatabaseSnapshot
import dev.droidtop.library.theme.ThemeAssets
import dev.droidtop.library.theme.ThemePrefs
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The one diagnostics archive docs/SPEC.md 10c specifies: the logs folder,
 * the settings export with every credential key left out, the
 * platform-database snapshot id, the installed theme names and the
 * enginehost version, zipped and handed to the system share sheet.
 * Nothing here sends anything anywhere by itself -- the person picks the
 * share target, and until then the archive stays in droidtop's cache.
 *
 * The same action's §10c reachability from a crash note's restart screen
 * waits on the crash-notes mechanism; this object is the archive and the
 * share, which is all of §10c's share paragraph that exists so far.
 */
object ShareDiagnostics {

    private const val ARCHIVE_PREFIX = "droidtop-diagnostics-"

    /** One day: after that, a previously shared archive is long since read and pruning it keeps the cache to one zip. */
    private const val OLD_ARCHIVE_MS = 24L * 60 * 60 * 1000

    private val CREDENTIAL_WORDS = listOf(
        "secret", "password", "devid", "ssid", "client", "apikey", "api_key", "token",
    )

    /**
     * The 10c privacy rule for the shared settings export. The settings
     * file's credentials are the scraper ones (7h), and each of their
     * names says what it is (droidtop_screenscraper_devpassword,
     * droidtop_igdb_client_secret, droidtop_thegamesdb_apikey), so a
     * droidtop_ key whose name carries a credential word is left out.
     * Launcher preferences hold no credentials and are not droidtop_
     * keys, so the filter cannot touch them.
     */
    internal fun isCredentialKey(key: String): Boolean =
        key.startsWith("droidtop_") && CREDENTIAL_WORDS.any { key.lowercase().contains(it) }

    fun buildArchive(context: Context, onStatus: (String) -> Unit): File {
        context.cacheDir.listFiles { file ->
            file.isFile && file.name.startsWith(ARCHIVE_PREFIX) &&
                file.lastModified() < System.currentTimeMillis() - OLD_ARCHIVE_MS
        }?.forEach { runCatching { it.delete() } }
        val zipFile = File(context.cacheDir, "$ARCHIVE_PREFIX${System.currentTimeMillis()}.zip")
        ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
            onStatus("Collecting logs...")
            for (file in (ScanLog.logsDir(context).listFiles() ?: emptyArray()).sortedBy { it.name }) {
                if (!file.isFile) continue
                zip.putNextEntry(ZipEntry("logs/${file.name}"))
                file.inputStream().use { input -> zip.write(input.readBytes()) }
                zip.closeEntry()
            }
            onStatus("Collecting settings...")
            zip.putNextEntry(ZipEntry("settings.json"))
            zip.write(
                DroidtopWideSettings.settingsJson(context, omitCredentials = true)
                    .toString(2).toByteArray(Charsets.UTF_8),
            )
            zip.closeEntry()
            onStatus("Collecting version and theme facts...")
            zip.putNextEntry(ZipEntry("info.txt"))
            zip.write(infoText(context).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return zipFile
    }

    /**
     * The facts a support person cannot see inside the zipped files:
     * which platform databases this build was seeded from (the 7e2
     * snapshot id, the same one the Platform database row names), which
     * enginehost and which of its bundles are installed, and which
     * themes are on the device.
     */
    private fun infoText(context: Context): String = buildString {
        appendLine("platform-database-snapshot: ${PlatformDatabaseSnapshot.shortCommit(context) ?: "unrecorded"}")
        val enginehostVersion = runCatching {
            context.packageManager.getPackageInfo(EngineHost.PACKAGE_NAME, 0).versionName
        }.getOrNull()
        appendLine("enginehost-version: ${enginehostVersion ?: "not installed"}")
        val bundles = EnginehostCapabilities.installedBundles(context)
        appendLine(if (bundles.isEmpty()) "enginehost-bundles: none" else "enginehost-bundles:")
        for (bundle in bundles.sortedBy { it.bundleId }) {
            appendLine(
                "  ${bundle.bundleId}: engine=${bundle.engine}, " +
                    "runtime=${bundle.runtimeVersion ?: "?"}, plugin=${bundle.pluginVersion ?: "?"}",
            )
        }
        for (theme in ThemeAssets.discoverThemes(context).sortedBy { it.name }) {
            appendLine("theme: ${theme.name}")
        }
        appendLine("active-theme: ${ThemePrefs.get(context) ?: "(not set)"}")
    }

    /**
     * Opens Android's share sheet with the finished archive. This is the
     * only step that can move the archive off the device, and it moves
     * nowhere until the person chooses a target.
     */
    fun share(context: Context, archiveFile: File) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(
                Intent.EXTRA_STREAM,
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", archiveFile),
            )
            putExtra(Intent.EXTRA_SUBJECT, "droidtop diagnostics")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(shareIntent, "Share diagnostics")
        if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}
