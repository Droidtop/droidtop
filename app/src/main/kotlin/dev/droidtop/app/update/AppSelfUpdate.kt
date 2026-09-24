package dev.droidtop.app.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * droidtop's own update check and self-update (docs/SPEC.md "Releases and
 * updates").
 *
 * What Android actually allows a normally-installed app: it may download a
 * new APK of itself and open a PackageInstaller session for it, and the
 * system asks the user to confirm ("install unknown apps" must be enabled
 * for droidtop the first time). Silent self-replacement exists only where
 * the system itself permits it -- droidtop being its own installer of
 * record, true after the first in-app update, on Android 12+ via
 * UPDATE_PACKAGES_WITHOUT_USER_ACTION -- and otherwise the confirmation
 * dialog appears. Signature continuity is enforced by Android: an APK not
 * signed with the persistent CI key refuses to install over this one.
 *
 * "Is this newer" is answered by versionCode, which CI sets to the number of
 * commits reachable from the built commit (a plain monotonic integer) and
 * publishes with the APK in release-info.json on the channel's release. The check downloads
 * that one small file, unauthenticated; nothing about the device or its
 * library is ever sent. Offline or failed checks are silent.
 */
object AppSelfUpdate {
    private const val DOWNLOADS = "https://github.com/Droidtop/droidtop/releases/download"
    private const val PREFS = "app_update_check"
    private const val KEY_CHECK_DAILY = "updates_check_daily"
    private const val KEY_FREQUENCY = "updates_frequency"
    private const val KEY_UNMETERED_ONLY = "updates_unmetered_only"
    private const val KEY_LAST_ATTEMPT = "last_attempt_ms"
    private const val KEY_SEEN_CODE = "newest_seen_version_code"
    private const val KEY_SEEN_NAME = "newest_seen_version_name"
    private const val KEY_CHANNEL = "updates_channel"
    private const val KEY_DEBUG_BUILDS = "updates_debug_builds"

    /** How often the background probe may run. [OFF] means only when asked. */
    enum class Frequency(val intervalMs: Long, val label: String) {
        OFF(Long.MAX_VALUE, "Never"),
        DAILY(24L * 60 * 60 * 1000, "Every day"),
        WEEKLY(7 * 24L * 60 * 60 * 1000, "Every week"),
        MONTHLY(30 * 24L * 60 * 60 * 1000, "Every month"),
    }

    /**
     * Which line of builds to follow. Each is its own GitHub release tag
     * carrying its own `release-info.json`, so switching channel changes
     * which build "newer" is measured against -- nothing else.
     *
     * [UNSTABLE] is every push to main and is the default, because it is
     * the only channel droidtop has ever published; the other two exist
     * once a build has been promoted to them, and a channel with no
     * release yet simply reports that there is nothing there.
     */
    enum class Channel(val tag: String, val label: String) {
        STABLE("stable", "Stable"),
        TESTING("testing", "Testing"),
        UNSTABLE("latest", "Unstable (every build)"),
    }

    /**
     * What a channel currently offers, and which of its two APKs this
     * device asked for.
     *
     * [debug] is true only when the debug build was both wanted and
     * published; a channel that carries no debug APK falls back to the
     * release one rather than failing, and says so through this flag so
     * the caller can tell the person what they are actually installing.
     */
    data class Info(
        val versionCode: Long,
        val versionName: String,
        val apkName: String,
        val apkSha256: String,
        val channel: Channel,
        val debug: Boolean,
    ) {
        val apkUrl: String get() = "$DOWNLOADS/${channel.tag}/$apkName"
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // PackageInfo.longVersionCode is API 28 and droidtop's minSdk is 26;
    // PackageInfoCompat reads the same number on every release we ship to.
    fun installedVersionCode(context: Context): Long =
        PackageInfoCompat.getLongVersionCode(
            context.packageManager.getPackageInfo(context.packageName, 0),
        )

    fun installedVersionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"

    fun frequency(context: Context): Frequency =
        prefs(context).getString(KEY_FREQUENCY, null)?.let { name -> Frequency.entries.firstOrNull { it.name == name } }
            // Before there was a frequency there was one daily switch; honour what it said.
            ?: if (prefs(context).getBoolean(KEY_CHECK_DAILY, true)) Frequency.DAILY else Frequency.OFF

    fun setFrequency(context: Context, value: Frequency) =
        prefs(context).edit().putString(KEY_FREQUENCY, value.name).remove(KEY_CHECK_DAILY).apply()

    fun channel(context: Context): Channel =
        prefs(context).getString(KEY_CHANNEL, null)?.let { name -> Channel.entries.firstOrNull { it.name == name } }
            ?: Channel.UNSTABLE

    fun setChannel(context: Context, value: Channel) =
        prefs(context).edit().putString(KEY_CHANNEL, value.name).apply()

    /**
     * Follow the debug build of the chosen channel instead of the release
     * one. A debug APK is not compiled ahead of time and Android runs it
     * without inlining, which is what made droidtop slow on the console
     * before build 558 (SPEC 10b): it is for making a build inspectable
     * (`adb shell run-as`, a debugger), never for playing on.
     */
    fun debugBuilds(context: Context): Boolean = prefs(context).getBoolean(KEY_DEBUG_BUILDS, false)

    fun setDebugBuilds(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_DEBUG_BUILDS, value).apply()

    /** Skip the probe on metered connections (mobile data, tethering). */
    fun unmeteredOnly(context: Context): Boolean = prefs(context).getBoolean(KEY_UNMETERED_ONLY, false)

    fun setUnmeteredOnly(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_UNMETERED_ONLY, value).apply()

    /** When the probe last ran, as epoch milliseconds, or null if it never has. */
    fun lastAttempt(context: Context): Long? = prefs(context).getLong(KEY_LAST_ATTEMPT, 0L).takeIf { it > 0 }

    /** The newest build a check has seen, when newer than what is running. */
    fun newerSeenVersionName(context: Context): String? {
        if (prefs(context).getLong(KEY_SEEN_CODE, 0L) <= installedVersionCode(context)) return null
        return prefs(context).getString(KEY_SEEN_NAME, null)
    }

    /**
     * The scheduled background pass, called at process start (from
     * SettingsCatalogInitProvider). When it is due, enabled and allowed on
     * the current network it asks what the newest build is and brings the
     * platform databases up to date; otherwise nothing. Never throws, never
     * blocks the caller.
     */
    fun maybeCheck(context: Context) {
        val application = context.applicationContext
        val now = System.currentTimeMillis()
        val allowed = mayCheck(
            forced = false,
            frequency = frequency(application),
            lastAttemptMs = prefs(application).getLong(KEY_LAST_ATTEMPT, 0L),
            nowMs = now,
            unmeteredOnly = unmeteredOnly(application),
            metered = isMetered(application),
        )
        if (!allowed) return
        prefs(application).edit().putLong(KEY_LAST_ATTEMPT, now).apply()
        Thread {
            // The platform databases ride along on the same pass, the way
            // enginehost's engine detection rules ride along on its plugin
            // check: they are the part of droidtop that changes faster than
            // the app, and a person whose new emulator or engine is missing
            // has no way to know a fix was published. The index-driven
            // refresh downloads one small document when nothing changed,
            // and validate-before-replace means a bad download changes
            // nothing. The manual button in Settings runs the same call.
            runCatching { dev.droidtop.library.consoles.PlatformDatabases.refresh(application) }
            runCatching { fetch(application) }.onSuccess { info ->
                prefs(application).edit()
                    .putLong(KEY_SEEN_CODE, info.versionCode)
                    .putString(KEY_SEEN_NAME, info.versionName)
                    .apply()
            }
        }.start()
    }

    /**
     * The one gate both update paths ask. The scheduled pass calls it with
     * forced = false and obeys all three conditions the user set: the
     * frequency (OFF means no automatic traffic at all), the interval since
     * the last attempt, and the unmetered-only option. UpdateNow calls it
     * with forced = true, which is what "bypasses the schedule and any
     * consent gate" means -- one function, so the bypass cannot drift away
     * from what it is bypassing.
     */
    fun mayCheck(
        forced: Boolean,
        frequency: Frequency,
        lastAttemptMs: Long,
        nowMs: Long,
        unmeteredOnly: Boolean,
        metered: Boolean,
    ): Boolean {
        if (forced) return true
        if (frequency == Frequency.OFF) return false
        if (nowMs - lastAttemptMs < frequency.intervalMs) return false
        return !(unmeteredOnly && metered)
    }

    private fun isMetered(context: Context): Boolean =
        context.getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered ?: false

    /** Records that a check ran now; the manual check calls this so "last checked" stays truthful. */
    fun noteAttempt(context: Context) {
        prefs(context.applicationContext).edit().putLong(KEY_LAST_ATTEMPT, System.currentTimeMillis()).apply()
    }

    /**
     * Fetches what the chosen channel currently offers. Throws on any
     * failure, including a channel that has never been published (its
     * release-info.json is simply not there).
     */
    fun fetch(context: Context): Info {
        val channel = channel(context)
        val wantDebug = debugBuilds(context)
        val connection = URL("$DOWNLOADS/${channel.tag}/release-info.json").openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "droidtop")
        try {
            require(connection.responseCode in 200..299) { "Release info returned HTTP ${connection.responseCode}" }
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            require(json.getInt("formatVersion") == 1) { "Unsupported release info" }
            // The debug APK is an added key, not a new format: a build from
            // before it existed reads the same document and sees the release
            // APK it always did.
            val debug = wantDebug && json.optString("debugApkName").isNotBlank()
            val name = if (debug) json.getString("debugApkName") else json.getString("apkName")
            val digest = (if (debug) json.getString("debugApkSha256") else json.getString("apkSha256")).uppercase()
            require(digest.matches(Regex("[A-F0-9]{64}"))) { "Release info carries no valid APK digest" }
            return Info(
                json.getLong("versionCode"),
                json.getString("versionName"),
                name,
                digest,
                channel,
                debug,
            )
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Downloads the release APK, verifies it against the digest published in
     * release-info.json, and hands it to the system installer, narrating
     * through [onStatus]. Blocking; call from a worker context. Throws on
     * failure. The system takes over from the commit: either a silent
     * update where it allows one, or its confirmation UI.
     */
    fun downloadAndInstall(context: Context, info: Info, onStatus: (String) -> Unit) {
        onStatus("Downloading ${info.versionName}...")
        val apk = download(context, info)
        onStatus("Handing the update to the Android installer...")
        commitSession(context, apk)
    }

    private fun download(context: Context, info: Info): File {
        val directory = File(context.cacheDir, "app-updates").apply { mkdirs() }
        val apk = File(directory, "droidtop-${info.versionCode}.apk")
        if (apk.isFile && sha256(apk) == info.apkSha256) return apk
        val connection = URL(info.apkUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 300_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "droidtop")
        try {
            require(connection.responseCode in 200..299) { "APK download returned HTTP ${connection.responseCode}" }
            val temporary = File(directory, apk.name + ".partial")
            connection.inputStream.buffered().use { input ->
                temporary.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            // The digest published next to the APK is the gate: bytes that
            // do not match it are discarded, whatever served them.
            require(sha256(temporary) == info.apkSha256) { "Downloaded APK does not match the published digest" }
            if (apk.exists()) apk.delete()
            require(temporary.renameTo(apk)) { "Could not retain downloaded APK" }
            return apk
        } finally {
            connection.disconnect()
        }
    }

    private fun commitSession(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) {
                // Silent only when the system itself decides droidtop is
                // eligible (its own installer of record, and so on);
                // otherwise the normal confirmation dialog appears.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("droidtop.apk", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val intent = Intent(context, AppUpdateStatusReceiver::class.java).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            session.commit(PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender)
        }
    }

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
        return digest.digest().joinToString("") { "%02X".format(it) }
    }
}

/**
 * Receives PackageInstaller's verdict on a self-update. The one status that
 * needs code is PENDING_USER_ACTION: the system hands over its confirmation
 * UI to launch. Success needs none -- the process is replaced mid-update.
 */
class AppUpdateStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> android.widget.Toast.makeText(
                context,
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Update failed",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }
}
