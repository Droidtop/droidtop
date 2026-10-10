package dev.droidtop.app.apps

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import dev.droidtop.library.integrations.AppCatalogs
import dev.droidtop.library.integrations.AppPackages
import dev.droidtop.pluginhost.DownloadJobs
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The one way droidtop installs an APK (docs/SPEC.md 10b "Installing apps", Droidtop/tracker#261): droidtop's own
 * update ([dev.droidtop.app.update.AppSelfUpdate]) and every app an app catalog offers ([AppCatalogs]) go through
 * one PackageInstaller session here, and Android's verdict comes back to [ApkInstallStatusReceiver].
 *
 * Non-root first: a session is what any app may open, and Android asks the person unless it decides itself that it
 * need not. On Android 12+ droidtop asks for no confirmation (`USER_ACTION_NOT_REQUIRED`) where Android allows that,
 * which is an update of an app droidtop installed (droidtop is its installer of record) that targets a recent enough
 * SDK, and only while "Update apps without asking" is on ([silentUpdates]); on Android 14+ a catalog install asks for
 * update ownership, so another store does not update it behind droidtop's back without Android asking. Root and
 * Shizuku are not used here (an enhancement for later; SPEC 10b).
 */
object ApkInstaller {
    private const val TAG = "ApkInstaller"
    private const val PREFS = "apps_install"
    private const val KEY_SILENT = "silent_updates"

    /** "Update apps without asking, where Android allows it": on unless the person turned it off. */
    fun silentUpdates(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SILENT, true)

    fun setSilentUpdates(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SILENT, value).apply()
    }

    /** The installer's answer for each session this process committed, so a caller can report what really happened. */
    private val outcomes = ConcurrentHashMap<Int, CompletableFuture<String>>()

    /** What each committed session installs, for the outcome sentence. */
    internal val pendingNames = ConcurrentHashMap<Int, String>()

    internal fun reportOutcome(sessionId: Int, message: String) {
        Log.i(TAG, "installer: $message")
        pendingNames.remove(sessionId)
        outcomes.remove(sessionId)?.complete(message)
    }

    /** Waits up to [timeoutMs] for the installer's answer on [sessionId]; null when none came. */
    fun awaitOutcome(sessionId: Int, timeoutMs: Long): String? =
        runCatching { outcomes[sessionId]?.get(timeoutMs, TimeUnit.MILLISECONDS) }.getOrNull()

    /**
     * Writes [apk] into a new session for [packageName] and commits it; returns the session. Blocking: a worker
     * thread only. [silent] asks Android to skip its confirmation where it allows that; [requestOwnership] asks to be
     * the app's update owner (Android 14+). Android checks the signing key itself: an APK signed by another key than
     * the installed app's is refused by the system whatever droidtop checked before.
     */
    fun commit(context: Context, apk: File, packageName: String, label: String, silent: Boolean, requestOwnership: Boolean): Int {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(
                    if (silent) PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED else PackageInstaller.SessionParams.USER_ACTION_REQUIRED,
                )
            }
            if (Build.VERSION.SDK_INT >= 33 && packageName != context.packageName) {
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
            }
            if (Build.VERSION.SDK_INT >= 34 && requestOwnership) setRequestUpdateOwnership(true)
        }
        val sessionId = installer.createSession(params)
        outcomes[sessionId] = CompletableFuture()
        pendingNames[sessionId] = label
        try {
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val intent = Intent(context, ApkInstallStatusReceiver::class.java).setPackage(context.packageName)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                session.commit(PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender)
            }
        } catch (error: Exception) {
            outcomes.remove(sessionId)
            pendingNames.remove(sessionId)
            runCatching { installer.abandonSession(sessionId) }
            throw error
        }
        return sessionId
    }

    /**
     * Registers the Downloads post step that installs a catalog's APK ([AppCatalogs.POST_INSTALL]): checks the file
     * ([AppPackages.refusal]), then commits it and waits for Android's answer, so the job in Downloads ends with what happened.
     * Called once at process start, so a job restored after a restart finds it.
     */
    fun registerDownloadPost() {
        DownloadJobs.registerPost(AppCatalogs.POST_INSTALL) { context, file, args ->
            withContext(Dispatchers.IO) {
                val expected = AppCatalogs.Expected.fromArgs(args) ?: error("the download does not say which app it is")
                val label = args[AppCatalogs.ARG_LABEL] ?: expected.packageName
                val installed = AppPackages.installedFacts(context, expected.packageName)
                AppPackages.refusal(AppPackages.archiveFacts(context, file), expected, installed)?.let { reason ->
                    file.delete()
                    error("Not installed: $reason")
                }
                if (!context.packageManager.canRequestPackageInstalls()) {
                    error(
                        "Android has not allowed droidtop to install apps. In Settings > Apps > Special app access > " +
                            "Install unknown apps > droidtop, turn on \"Allow from this source\", then try again",
                    )
                }
                val session = commit(
                    context, file, expected.packageName, label,
                    silent = installed != null && silentUpdates(context),
                    requestOwnership = installed == null,
                )
                val outcome = awaitOutcome(session, OUTCOME_WAIT_MS)
                if (outcome == null || outcome.startsWith("Installed")) file.delete()
                outcome ?: "Android is asking you to confirm installing $label"
            }
        }
    }

    /** How long the post step waits for the person's answer to Android's confirmation before the job ends without it. */
    private const val OUTCOME_WAIT_MS = 10 * 60_000L
}

/**
 * Receives PackageInstaller's verdict on every session [ApkInstaller] committed. The one status that needs code is
 * PENDING_USER_ACTION: the system hands over its confirmation UI to launch. For droidtop's own update a success
 * replaces the process as it lands; the sentence is for the log.
 */
class ApkInstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val name = ApkInstaller.pendingNames[sessionId] ?: "the app"
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }.onFailure {
                    ApkInstaller.reportOutcome(sessionId, "Android's installer could not show its confirmation; $name was not installed.")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> ApkInstaller.reportOutcome(sessionId, "Installed $name.")
            else -> {
                val message = if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
                    "The install was cancelled: $name was not installed."
                } else {
                    "Android's installer refused $name: " +
                        (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "it gave no reason") + "."
                }
                ApkInstaller.reportOutcome(sessionId, message)
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
}
