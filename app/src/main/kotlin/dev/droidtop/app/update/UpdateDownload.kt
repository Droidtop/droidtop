package dev.droidtop.app.update

import android.content.Context
import android.util.Log
import dev.droidtop.app.apps.ApkInstaller
import dev.droidtop.pluginhost.DownloadJobs
import dev.droidtop.pluginhost.PluginJobsCenter
import dev.droidtop.pluginhost.PluginResult
import dev.droidtop.shared.trust.Sha256
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

/**
 * The self-update's APK transfer, done by droidtop's own download runner (docs/SPEC.md 10b, "Self-update
 * downloader"; Droidtop/tracker#226, the owner's rule: "We want to use our own internal downloader when we want to
 * put something somewhere specific. Using android's downloader spams the user with notifications from it").
 *
 * It is one [DownloadJobs] job like every other single-file download: `droidtop-<build>.apk` in droidtop's downloads
 * area, resumable from its `.part` file, one entry in "Downloads and installs", the digest published with the release
 * checked by the job, and a post step ([POST]) that asks Android's installer for the file ([ApkInstaller], the one
 * install path) and ends the job with the installer's answer. A job restored after a restart is finished by the
 * same post step. Android's DownloadManager is not used and its notification is gone.
 */
internal object UpdateDownload {
    /** The [DownloadJobs] post step that installs the finished APK. */
    const val POST = "droidtop.self_update"

    private const val ARG_BUILD = "updateBuild"
    private const val ARG_VERSION = "updateVersion"
    private const val ARG_SHA256 = "sha256"

    /** How long the post step waits for the person's answer to Android's confirmation. */
    private const val OUTCOME_WAIT_MS = 10 * 60 * 1000L

    /** What a pass found for the build it wants. */
    sealed class Found {
        /** A file of that build is on disk and matches its digest: install it. */
        data class Ready(val apk: File) : Found()

        /** A download job for that build is already going (an earlier pass, or a job restored after a restart). */
        data class Downloading(val jobId: String) : Found()

        /** Nothing yet: [download] it. */
        object Missing : Found()
    }

    fun fileName(build: Long) = "droidtop-$build.apk"

    /** Registers the post step. Called once at process start, so a job restored after a restart finds it. */
    fun register() {
        DownloadJobs.registerPost(POST, keepFile = true) { context, file, args -> finish(context, file, args) }
    }

    private suspend fun finish(context: Context, file: File, args: Map<String, String>): String = withContext(Dispatchers.IO) {
        val build = requireNotNull(args[ARG_BUILD]?.toLongOrNull()) { "the download does not say which build it is" }
        val name = args[ARG_VERSION] ?: "?"
        val digest = requireNotNull(args[ARG_SHA256]) { "the download has no digest" }
        val session = AppSelfUpdate.install(context, file, build, name, digest)
        Log.i(UpdateNow.TAG, "asked Android's installer to install $name (build $build)")
        val outcome = ApkInstaller.awaitOutcome(session, OUTCOME_WAIT_MS)
        // On a success this process is replaced as it lands; any other answer keeps the file for the next try.
        if (outcome != null && outcome.startsWith("Installed")) file.delete()
        outcome ?: "Waiting for you to confirm the install in Android's installer"
    }

    /** What the pass for [info] does: finds the job still going, or the verified file on disk, or neither. Blocking (it hashes a file). */
    fun find(context: Context, info: AppSelfUpdate.Info): Found {
        val name = fileName(info.versionCode)
        jobFor(name)?.let { return Found.Downloading(it) }
        val apk = DownloadJobs.fileFor(context, name)
        if (apk.isFile) {
            if (Sha256.hex(apk).equals(info.apkSha256, ignoreCase = true)) return Found.Ready(apk)
            // A file that does not match the published digest is not resumed into: the job starts it afresh.
            apk.delete()
        }
        return Found.Missing
    }

    /** What the update is doing right now, for the Updates screen: the running pass, or the APK's download job; null when idle. */
    fun liveLine(): String? {
        val pass = UpdateNow.pass.value
        if (pass.running) return pass.line.ifBlank { "Checking..." }
        val entry = liveJob() ?: return null
        // The job's own words, as the Downloads place says them (docs/SPEC.md 12a, "Jobs and their results").
        return dev.droidtop.library.integrations.PluginJobsScreen.oneLine(entry)
    }

    /** The running download job of a droidtop build, or null: what droidtop's Updates row shows (Droidtop/tracker#833). */
    fun liveJob(): PluginJobsCenter.Entry? = PluginJobsCenter.entries().value.firstOrNull { entry ->
        entry.nativeKind == DownloadJobs.KIND && !entry.done &&
            PluginJobsCenter.argsOf(entry.jobId)?.get("name")?.let { UpdateFiles.buildOf(it) } != null
    }

    /** The id of the running download job for the file [name], or null. */
    fun jobFor(name: String): String? = PluginJobsCenter.entries().value.firstOrNull { entry ->
        entry.nativeKind == DownloadJobs.KIND && !entry.done && PluginJobsCenter.argsOf(entry.jobId)?.get("name") == name
    }?.jobId

    /**
     * Runs the download job for [info] to its end (the post step has asked the installer and heard its answer) and
     * returns the line to report.
     */
    suspend fun download(context: Context, info: AppSelfUpdate.Info, onStatus: (String) -> Unit): String {
        val result = DownloadJobs.run(
            context,
            title = "droidtop ${info.versionName}",
            post = POST,
            category = dev.droidtop.net.DownloadCategory.UPDATES,
            url = info.apkUrl,
            name = fileName(info.versionCode),
            sha256 = info.apkSha256,
            extra = mapOf(ARG_BUILD to info.versionCode.toString(), ARG_VERSION to info.versionName),
            onStatus = onStatus,
        )
        return line(result)
    }

    /** Waits for the running job [jobId] to end, narrating its progress through [onStatus], and returns the line to report. */
    suspend fun await(jobId: String, onStatus: (String) -> Unit): String {
        val ended = PluginJobsCenter.entries()
            .onEach { entries ->
                entries.firstOrNull { it.jobId == jobId }?.takeIf { !it.done }?.let { entry ->
                    onStatus(if (entry.percent in 0..99) "${entry.statusLine} (${entry.percent}%)" else entry.statusLine)
                }
            }
            .first { entries -> entries.firstOrNull { it.jobId == jobId }?.done != false }
            .firstOrNull { it.jobId == jobId } ?: return "the update download is no longer listed"
        return ended.result?.let(::line) ?: ended.statusLine
    }

    private fun line(result: PluginResult): String =
        if (result.ok) result.values["summary"].orEmpty().ifBlank { "done" } else "Update download failed: " + (result.error ?: "unknown error")
}
