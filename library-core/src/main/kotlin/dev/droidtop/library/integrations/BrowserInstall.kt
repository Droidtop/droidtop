package dev.droidtop.library.integrations

import android.content.Context
import android.os.Build
import dev.droidtop.net.Http
import dev.droidtop.pluginhost.AppInterfaceTrust
import dev.droidtop.pluginhost.DownloadJobs
import dev.droidtop.pluginhost.PluginResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * droidtop Browser, installed and updated by droidtop itself (owner, 2026-11-22: "we probably also want to add a browser
 * installer into droidtop"; docs/SPEC.md 12a). The browser is droidtop's own GeckoView app (mytop-project/droidtop-browser)
 * that shows droidtop's sign-in, check and download pages where the device's WebView is too old (docs/plugin-api.md G3a).
 *
 * The newest release of its repository is fetched, the APK for this device's processor is downloaded as a Downloads job,
 * and the same install step as an app catalog's checks it before Android installs it: the package must be
 * [PACKAGE] and the signer the certificate droidtop pins for it ([AppInterfaceTrust.OFFICIAL]), or nothing is installed.
 */
object BrowserInstall {
    const val PACKAGE = "dev.droidtop.browser"
    const val LABEL = "droidtop Browser"
    private const val RELEASES = "https://api.github.com/repos/mytop-project/droidtop-browser/releases/latest"

    /** The processors the browser is built for, in droidtop's order of preference. */
    private val ABIS = listOf("arm64-v8a", "x86_64")

    /** The release to install: its tag, the APK's address and size. */
    data class Release(val tag: String, val url: String, val size: Long)

    /** The installed browser's version, or null when it is not installed. */
    fun installedVersion(context: Context): String? = runCatching {
        context.packageManager.getPackageInfo(PACKAGE, 0).versionName
    }.getOrNull()

    /** The APK in a release's JSON for the first of [abis] it carries; null when it carries none of them. */
    internal fun pick(release: JSONObject, abis: List<String>): Release? {
        val tag = release.optString("tag_name").takeIf { it.isNotBlank() } ?: return null
        val assets = release.optJSONArray("assets") ?: return null
        val byName = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }.associateBy { it.optString("name") }
        val abi = abis.firstOrNull { "droidtop-browser-$it.apk" in byName } ?: return null
        val asset = byName.getValue("droidtop-browser-$abi.apk")
        val url = asset.optString("browser_download_url").takeIf { it.startsWith("https://") } ?: return null
        return Release(tag, url, asset.optLong("size", 0L))
    }

    /** Whether [installed] (a version name, "160.0.1-dt.3") is the release [tag] ("v160.0.1-dt.3"). */
    internal fun isCurrent(installed: String?, tag: String): Boolean = installed != null && installed == tag.removePrefix("v")

    /**
     * Installs or updates the browser: the newest release's APK for this device, through Downloads and the checked install
     * step. Returns the line to show.
     */
    suspend fun install(context: Context, onStatus: (String) -> Unit): String {
        val signers = AppInterfaceTrust.OFFICIAL[PACKAGE].orEmpty()
        if (signers.isEmpty()) return "No signing key known"
        onStatus("Checking")
        val release = withContext(Dispatchers.IO) {
            runCatching { pick(Http.getJson(RELEASES), Build.SUPPORTED_ABIS.toList().filter { it in ABIS }.ifEmpty { ABIS }) }.getOrNull()
        } ?: return "None for this device"
        val installed = installedVersion(context)
        if (isCurrent(installed, release.tag)) return "Up to date"
        val result: PluginResult = DownloadJobs.run(
            context = context,
            title = if (installed == null) "Install $LABEL" else "Update $LABEL",
            post = AppCatalogs.POST_INSTALL,
            url = release.url,
            name = "app_${PACKAGE.replace('.', '_')}_${release.tag}.apk",
            maxBytes = release.size,
            sizeBytes = release.size,
            extra = AppCatalogs.Expected(PACKAGE, 0L, signers).toArgs() + (AppCatalogs.ARG_LABEL to LABEL),
            onStatus = onStatus,
        )
        if (!result.ok) return result.error ?: "Download failed"
        // The job ends when Android installed it, or early while Android waits for the person to confirm. The row shows
        // this line until its screen is left, so it says which: "In Downloads" stayed after the install on the console
        // (dt.16 over an adb-installed dt.13, 2026-11-22).
        return withContext(Dispatchers.IO) { installedVersion(context) }?.takeIf { isCurrent(it, release.tag) } ?: "Confirm in Android"
    }
}
