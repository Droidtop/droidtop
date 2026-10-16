package dev.droidtop.pluginhost

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** What droidtop read from an APK before installing it: never what the caller said it was. */
data class ApkInfo(val packageName: String, val label: String, val versionName: String?, val versionCode: Long)

/**
 * `priv.packages install` (docs/plugin-api.md 2.7, Droidtop/tracker#130): droidtop's half, run by the broker after the
 * caller's own grant and before the call reaches the provider. The caller names an APK in its own `data` folder;
 * droidtop reads it, refuses an app it never replaces, asks the person by the app's own name, and copies the file to
 * its private staging folder. The provider is handed only `{staged: {path, size, sha256, package}}`, which droidtop
 * writes: a caller's own `staged` never reaches it. A provider streams the staged file into `pm install` on stdin,
 * so it must run in droidtop's process (full access); no copy is ever readable by the shell user or another app.
 */
object PrivInstall {
    const val API = "priv.packages"
    const val OP = "install"

    /** The largest APK droidtop stages. */
    const val MAX_BYTES = 2L * 1024 * 1024 * 1024

    /** A staged copy older than this is left over from a job that never ended, and is removed on the next install. */
    const val STALE_MS = 60L * 60 * 1000

    fun applies(api: String, op: String): Boolean = api == API && op == OP

    /** The args the provider is called with, or a [BrokerException] that says why not. */
    fun prepare(env: BrokerEnvironment, caller: PluginRecord, provider: PluginRecord, args: JSONObject): JSONObject {
        if (PluginTiers.of(provider, env.grants(provider.manifest.id)) != PluginTier.FULL_TRUST) {
            fail(PluginErrorCode.UNSUPPORTED, "${provider.manifest.label} cannot install apps: only a provider running with full access can.")
        }
        if (!env.userInitiated(caller.manifest.id)) {
            fail(PluginErrorCode.PERMISSION_DENIED, "An app is installed only when you start it.")
        }
        val name = args.optString("name").trim().ifEmpty { fail(PluginErrorCode.INVALID_ARGS, "name must be an APK in the plugin's data") }
        val store = env.dataStore(caller.manifest.id) ?: fail(PluginErrorCode.UNSUPPORTED, "This droidtop keeps no plugin data.")
        val file = store.resolve(name)
        if (!file.isFile) fail(PluginErrorCode.NOT_FOUND, "There is no file \"$name\" in ${caller.manifest.label}'s data.")
        if (file.length() > MAX_BYTES) fail(PluginErrorCode.INVALID_ARGS, "The app package is larger than droidtop installs.")
        val apk = env.apkInfo(file) ?: fail(PluginErrorCode.INVALID_ARGS, "\"$name\" is not an app package.")
        env.installRefusal(apk.packageName)?.let { fail(PluginErrorCode.PERMISSION_DENIED, it) }
        val version = apk.versionName?.let { " $it" }.orEmpty()
        val notice = dev.droidtop.library.settings.Notice(
            tone = dev.droidtop.library.settings.NoticeTone.Caution,
            title = "Install ${apk.label}$version?",
            body = "${caller.manifest.label} wants to install ${apk.label} (${apk.packageName}) through ${provider.manifest.label}.",
            source = caller.manifest.label,
        )
        if (!env.confirm(notice, "Install", "Cancel")) fail(PluginErrorCode.CANCELLED, "You chose not to install ${apk.label}.")
        val staged = env.stageApk(file) ?: fail(PluginErrorCode.FAILED, "droidtop could not prepare the app package.")
        return JSONObject().put(
            "staged",
            JSONObject()
                .put("path", staged.absolutePath)
                .put("size", staged.length())
                .put("sha256", sha256(staged))
                .put("package", apk.packageName),
        )
    }

    /** Copies [apk] into [dir] under a name nobody can guess, after removing copies older than [STALE_MS]. */
    fun stage(apk: File, dir: File, nowMs: Long): File {
        dir.mkdirs()
        dir.listFiles()?.filter { nowMs - it.lastModified() > STALE_MS }?.forEach { it.delete() }
        val staged = File(dir, java.util.UUID.randomUUID().toString() + ".apk")
        apk.copyTo(staged)
        return staged
    }

    fun sha256(file: File): String {
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

    private fun fail(code: PluginErrorCode, message: String): Nothing = throw BrokerException(code, message)
}
