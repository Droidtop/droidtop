package dev.droidtop.app.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import dev.droidtop.pluginhost.DownloadJobs
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import dev.droidtop.runtime.util.Sha256

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
 * publishes with the APK in release-info.json. Testing and Stable are each
 * one moving-pointer release (a fixed tag), read the same way as always: one
 * unauthenticated fetch of that release's release-info.json. Unstable is
 * every push to main, each its own permanent release tagged by version
 * (docs/SPEC.md 10b) -- there is no fixed tag to read any more, so that
 * channel instead asks the GitHub API which per-build release is newest and
 * reads release-info.json out of its assets (fetchNewestBuild). Either way
 * nothing about the device or its library is ever sent. Offline or failed
 * checks are silent.
 */
object AppSelfUpdate {
    private const val DOWNLOADS = "https://github.com/Droidtop/droidtop/releases/download"
    private const val API_RELEASES = "https://api.github.com/repos/Droidtop/droidtop/releases"

    // Every per-build history release's tag (build-scripts/release_channel.py
    // BASE_VERSION/BUILD_TAG_RE): "v" + versionName. testing/stable never
    // match this, so they are never picked up as an Unstable build by
    // accident.
    private val BUILD_TAG = Regex("""^v0\.2\.0-dev\.\d+$""")

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
     * Which line of builds to follow. [STABLE] and [TESTING] are each one
     * GitHub release at a fixed tag, carrying its own `release-info.json`;
     * [tag] is that release's tag for those two. [UNSTABLE] has no fixed
     * tag -- every push to main gets its own permanent, versioned release
     * (docs/SPEC.md 10b) -- so its [tag] is unused and `fetch` finds the
     * newest one through the GitHub API instead (fetchNewestBuild).
     *
     * [UNSTABLE] is every push to main and is the default, because it is
     * the only channel droidtop has ever published; the other two exist
     * once a build has been promoted to them, and a channel with no
     * release yet simply reports that there is nothing there.
     */
    enum class Channel(val tag: String, val label: String) {
        STABLE("stable", "Stable"),
        TESTING("testing", "Testing"),
        UNSTABLE("", "Unstable (every build)"),
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
        val apkUrl: String,
    )

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
            // Plugins from repositories the person trusts ride along too (docs/SPEC.md 12a "Plugin
            // repositories"): one cheap release listing per repository, bundles only on an
            // unmetered connection, and a notification when something was updated.
            runCatching {
                dev.droidtop.library.integrations.PluginRepoUpdates.runDue(application, isMetered(application)) { repo, result ->
                    PluginRepoUpdateNotification.show(application, repo, result)
                }
            }
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
        return if (channel == Channel.UNSTABLE) {
            fetchNewestBuild(wantDebug)
        } else {
            parseReleaseInfo(
                channel,
                wantDebug,
                openJson("$DOWNLOADS/${channel.tag}/release-info.json"),
            ) { name -> "$DOWNLOADS/${channel.tag}/$name" }
        }
    }

    /**
     * Unstable has no fixed tag any more: every push to main publishes its
     * own permanent release (docs/SPEC.md 10b), so "the newest build" is
     * answered by asking the GitHub API which per-build release is newest,
     * the same list release-promote.yml reads to find a commit to promote.
     * The API's default order for this endpoint is newest-created-first, so
     * the first tag matching the per-build pattern is Unstable's current
     * build; testing/stable never match it. release-info.json and the APK
     * both come from that one release's own assets, never a fixed URL.
     */
    private fun fetchNewestBuild(wantDebug: Boolean): Info {
        val releases = JSONArrayCompat(openJsonArray("$API_RELEASES?per_page=100"))
        val release = releases.firstOrNull { BUILD_TAG.matches(it.getString("tag_name")) }
            ?: throw IllegalStateException("No per-build release found on the Unstable channel")
        val assets = release.getJSONArray("assets")
        val urls = HashMap<String, String>()
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            urls[asset.getString("name")] = asset.getString("browser_download_url")
        }
        val infoUrl = urls["release-info.json"]
            ?: throw IllegalStateException("Release ${release.getString("tag_name")} carries no release-info.json")
        return parseReleaseInfo(Channel.UNSTABLE, wantDebug, openJson(infoUrl)) { name ->
            urls[name] ?: throw IllegalStateException("Release ${release.getString("tag_name")} carries no asset named $name")
        }
    }

    private fun parseReleaseInfo(channel: Channel, wantDebug: Boolean, json: JSONObject, urlFor: (String) -> String): Info {
        require(json.getInt("formatVersion") == 1) { "Unsupported release info" }
        // The debug APK is an added key, not a new format: a build from
        // before it existed reads the same document and sees the release
        // APK it always did.
        val debug = wantDebug && json.optString("debugApkName").isNotBlank()
        val name = if (debug) json.getString("debugApkName") else json.getString("apkName")
        val digest = (if (debug) json.getString("debugApkSha256") else json.getString("apkSha256")).uppercase()
        require(digest.matches(Regex("[A-F0-9]{64}"))) { "Release info carries no valid APK digest" }
        // A bare file name, never a path: it only ever selects an asset.
        require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*\\.apk"))) { "Release info names no valid APK file" }
        return Info(
            json.getLong("versionCode"),
            json.getString("versionName"),
            name,
            digest,
            channel,
            debug,
            urlFor(name),
        )
    }

    private fun openJson(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "droidtop")
        try {
            require(connection.responseCode in 200..299) { "$url returned HTTP ${connection.responseCode}" }
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun openJsonArray(url: String): org.json.JSONArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "droidtop")
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            require(connection.responseCode in 200..299) { "$url returned HTTP ${connection.responseCode}" }
            return org.json.JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    /** A thin, allocation-free view over a JSONArray of JSONObjects so callers can use firstOrNull. */
    private class JSONArrayCompat(private val array: org.json.JSONArray) : Iterable<JSONObject> {
        override fun iterator(): Iterator<JSONObject> = object : Iterator<JSONObject> {
            private var index = 0
            override fun hasNext() = index < array.length()
            override fun next(): JSONObject = array.getJSONObject(index++)
        }
    }

    /**
     * Downloads the release APK, verifies it against the digest published in
     * release-info.json, and hands it to the system installer, narrating
     * through [onStatus]. Blocking; call from a worker context. Throws on
     * failure. The system takes over from the commit: either a silent
     * update where it allows one, or its confirmation UI.
     */
    fun downloadAndInstall(context: Context, info: Info, onStatus: (String) -> Unit): Int {
        // Android 8 and later install an app's own download only once the
        // person has allowed that app under "Install unknown apps"; without
        // it the installer stops at "not allowed to install unknown apps
        // from this source" and droidtop never learned why (rig,
        // dq-shell2-01). Ask first, and open the one screen that fixes it.
        if (!context.packageManager.canRequestPackageInstalls()) {
            runCatching {
                dev.droidtop.runtime.systemstatus.SettingsLaunch.start(
                    context,
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        android.net.Uri.parse("package:" + context.packageName),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            throw IllegalStateException(UNKNOWN_APPS_BLOCKED)
        }
        onStatus("Downloading ${info.versionName}...")
        val apk = download(context, info, onStatus)
        onStatus("Handing the update to the Android installer...")
        return commitSession(context, apk, info)
    }

    /** What the person reads when Android has not allowed droidtop to install its own updates. */
    const val UNKNOWN_APPS_BLOCKED =
        "Android has not allowed droidtop to install updates. In the screen that opened (Settings > Apps > " +
            "Special app access > Install unknown apps > droidtop), turn on \"Allow from this source\", " +
            "then press Check now again."

    /**
     * The installer's answer for each session this process committed, so
     * the Check now row can report what really happened -- installed,
     * cancelled, refused -- instead of "handed to the installer" after the
     * person had already cancelled (rig, dq-shell2-01).
     */
    private val outcomes = java.util.concurrent.ConcurrentHashMap<Int, java.util.concurrent.CompletableFuture<String>>()

    internal fun reportOutcome(sessionId: Int, message: String) {
        android.util.Log.i(UpdateNow.TAG, "installer: " + message)
        outcomes.remove(sessionId)?.complete(message)
    }

    /** Waits up to [timeoutMs] for the installer's answer on [sessionId]; null when none came. */
    fun awaitOutcome(sessionId: Int, timeoutMs: Long): String? =
        runCatching { outcomes[sessionId]?.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrNull()

    /**
     * The APK as one resumable download job in "Downloads and installs" ([DownloadJobs], digest checked
     * there against the one published next to it: bytes that do not match are discarded, whatever
     * served them). The file stays where it landed for [commitSession]; a copy already there that
     * matches is used as it is. Blocking, like the rest of the update path.
     */
    private fun download(context: Context, info: Info, onStatus: (String) -> Unit): File {
        val name = "droidtop-${info.versionCode}.apk"
        val apk = DownloadJobs.fileFor(context, name)
        if (apk.isFile && sha256(apk) == info.apkSha256) return apk
        val result = kotlinx.coroutines.runBlocking {
            DownloadJobs.run(
                context, "droidtop ${info.versionName}", DownloadJobs.POST_KEEP, info.apkUrl, name,
                sha256 = info.apkSha256, onStatus = onStatus,
            )
        }
        require(result.ok) { "APK download failed: ${result.error}" }
        return apk
    }

    private fun commitSession(context: Context, apk: File, info: Info): Int {
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
        outcomes[sessionId] = java.util.concurrent.CompletableFuture()
        pendingNames[sessionId] = info.versionName + " (build " + info.versionCode + ")"
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
        return sessionId
    }

    /** What each committed session installs, for the outcome sentence. */
    internal val pendingNames = java.util.concurrent.ConcurrentHashMap<Int, String>()

    private fun sha256(file: File): String = Sha256.hex(file).uppercase()
}

/**
 * Receives PackageInstaller's verdict on a self-update. The one status that
 * needs code is PENDING_USER_ACTION: the system hands over its confirmation
 * UI to launch. Success needs none -- the process is replaced mid-update.
 */
class AppUpdateStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val name = AppSelfUpdate.pendingNames[sessionId] ?: "the update"
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }.onFailure {
                    AppSelfUpdate.reportOutcome(sessionId, "Android's installer could not show its confirmation; $name was not installed.")
                }
            }
            // The process is replaced as this lands; the sentence is for the log.
            PackageInstaller.STATUS_SUCCESS -> AppSelfUpdate.reportOutcome(sessionId, "Installed $name.")
            else -> {
                val message = if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
                    "The install was cancelled: $name was not installed. Press Check now to try again."
                } else {
                    "Android's installer refused $name: " +
                        (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "it gave no reason") + "."
                }
                AppSelfUpdate.reportOutcome(sessionId, message)
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
}
