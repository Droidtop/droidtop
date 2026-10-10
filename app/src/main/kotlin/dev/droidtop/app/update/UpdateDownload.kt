package dev.droidtop.app.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import dev.droidtop.pluginhost.DownloadJobs
import dev.droidtop.runtime.util.Sha256
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The self-update's APK transfer, done by Android's DownloadManager (docs/SPEC.md 10b,
 * Droidtop/tracker#445).
 *
 * Not droidtop's own download runner ([DownloadJobs]): that runs in droidtop's process, and a pass
 * started while droidtop is in the background (the UPDATE_NOW broadcast) has nothing that keeps that
 * process running. Android 14 froze the cached process ten seconds after the broadcast returned,
 * mid-download, and refused the foreground service meant to prevent it ("Background started FGS:
 * Disallowed ... uidState: RCVR ... code:DENIED", rig 2026-10-09): a plain broadcast is not among the
 * documented exemptions to the background start restriction, so any foreground service route can be
 * refused the same way. DownloadManager runs the transfer in the system's downloads provider, which
 * droidtop's process state does not touch; it retries, resumes after a lost network or a reboot, and
 * shows its own progress notification. When the download ends, the provider sends
 * ACTION_DOWNLOAD_COMPLETE to droidtop by package (AOSP DownloadInfo.sendIntentIfRequested,
 * intent.setPackage), which starts [UpdateDownloadReceiver] whether droidtop's process is frozen, dead
 * or in front; [complete] then verifies the file and asks Android's installer.
 *
 * One download is recorded at a time (its id, build and digest, in preferences, so a later process
 * knows it). The finished download is claimed once ([claim]): whoever handles it first (the receiver,
 * or a Check now pass watching it) asks the installer, and the other waits for that answer. The record
 * and the file go when [UpdateFiles] finds the build stale.
 */
internal object UpdateDownload {
    private const val PREFS = "app_update_download"
    private const val KEY_ID = "id"
    private const val KEY_BUILD = "build"
    private const val KEY_NAME = "version_name"
    private const val KEY_SHA = "sha256"
    private const val KEY_CLAIMED = "claimed"

    /** How often a watching pass reads the download's progress. */
    private const val WATCH_INTERVAL_MS = 1_000L

    /** The download droidtop asked for; [claimed] once someone has asked the installer for it. [id] is -1 for a file found on disk. */
    data class Pending(val id: Long, val build: Long, val versionName: String, val sha256: String, val claimed: Boolean = false) {
        val label: String get() = "$versionName (build $build)"
    }

    /** Where DownloadManager has a download: still going (queued, running or paused), done, failed, or unknown to it. */
    enum class State { ACTIVE, SUCCESSFUL, FAILED, GONE }

    data class Progress(val state: State, val paused: Boolean = false, val done: Long = 0, val total: Long = -1, val reason: Int = 0)

    /** What a pass found for the build it wants. */
    sealed class Found {
        /** A file of that build is on disk and matches its digest: install it. */
        data class Ready(val pending: Pending) : Found()

        /** DownloadManager is fetching it; [joined] when an earlier pass started that download. */
        data class Downloading(val id: Long, val joined: Boolean) : Found()

        /** Its finished download is being handed to the installer right now. */
        data class BeingInstalled(val id: Long) : Found()
    }

    private val lock = Any()

    /** What became of each finished download this process claimed: the installer session (or null), and the line to report. */
    private val installs = ConcurrentHashMap<Long, CompletableFuture<Pair<Int?, String>>>()

    fun fileName(build: Long) = "droidtop-$build.apk"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun manager(context: Context): DownloadManager =
        context.getSystemService(DownloadManager::class.java) ?: throw IllegalStateException("Android's download manager is not available")

    fun pending(context: Context): Pending? {
        val prefs = prefs(context)
        if (!prefs.contains(KEY_ID)) return null
        return Pending(
            prefs.getLong(KEY_ID, -1),
            prefs.getLong(KEY_BUILD, -1),
            prefs.getString(KEY_NAME, null) ?: "?",
            prefs.getString(KEY_SHA, null) ?: "",
            prefs.getBoolean(KEY_CLAIMED, false),
        )
    }

    private fun record(context: Context, pending: Pending?) {
        val editor = prefs(context).edit().clear()
        if (pending != null) {
            editor.putLong(KEY_ID, pending.id).putLong(KEY_BUILD, pending.build).putString(KEY_NAME, pending.versionName)
                .putString(KEY_SHA, pending.sha256).putBoolean(KEY_CLAIMED, pending.claimed)
        }
        editor.commit()
    }

    /** Where DownloadManager has download [id]. */
    fun query(context: Context, id: Long): Progress =
        manager(context).query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            fun long(column: String) = cursor.getLong(cursor.getColumnIndexOrThrow(column))
            val status = long(DownloadManager.COLUMN_STATUS).toInt()
            Progress(
                state = stateOf(status),
                paused = status == DownloadManager.STATUS_PAUSED,
                done = long(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                total = long(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
                reason = long(DownloadManager.COLUMN_REASON).toInt(),
            )
        } ?: Progress(State.GONE)

    /** DownloadManager's COLUMN_STATUS, as far as the update cares. */
    fun stateOf(status: Int): State = when (status) {
        DownloadManager.STATUS_SUCCESSFUL -> State.SUCCESSFUL
        DownloadManager.STATUS_FAILED -> State.FAILED
        DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED -> State.ACTIVE
        else -> State.GONE
    }

    /** The line a watching pass shows for a download of [versionName]. */
    fun progressLine(versionName: String, progress: Progress): String = when {
        progress.paused -> "Downloading $versionName: paused, waiting for a network or to retry (Android's download manager goes on by itself)"
        progress.total > 0 -> "Downloading $versionName: ${mb(progress.done)} of ${mb(progress.total)} MB"
        else -> "Downloading $versionName..."
    }

    private fun mb(bytes: Long) = (bytes / (1024 * 1024)).toString()

    /**
     * What the pass for [info] does: joins a download of that build still going, or installs a file of it
     * already on disk that matches the digest, or else hands a fresh download to DownloadManager. A record of a
     * failed or vanished download of that build is dropped first. Blocking (it hashes a file on disk).
     */
    fun obtain(context: Context, info: AppSelfUpdate.Info): Found {
        val apk = DownloadJobs.fileFor(context, fileName(info.versionCode))
        synchronized(lock) {
            val pending = pending(context)?.takeIf { it.build == info.versionCode }
            if (pending != null) {
                val state = query(context, pending.id).state
                if (state == State.ACTIVE) return Found.Downloading(pending.id, joined = true)
                if (installs[pending.id]?.isDone == false) return Found.BeingInstalled(pending.id)
                if (state == State.SUCCESSFUL && apk.isFile && sha256(apk) == info.apkSha256) {
                    // Claimed here, so the receiver, if it has not run yet, leaves it alone.
                    val claimed = pending.copy(sha256 = info.apkSha256, claimed = true)
                    record(context, claimed)
                    installs[pending.id] = CompletableFuture()
                    return Found.Ready(claimed)
                }
                // Failed, removed by the person, or a file that no longer matches: start again.
                runCatching { manager(context).remove(pending.id) }
                record(context, null)
            }
        }
        if (apk.isFile && sha256(apk) == info.apkSha256) {
            return Found.Ready(Pending(-1, info.versionCode, info.versionName, info.apkSha256, claimed = true))
        }
        // DownloadManager does not overwrite: with a file already there it would pick another name.
        apk.delete()
        return Found.Downloading(enqueue(context, info), joined = false)
    }

    private fun enqueue(context: Context, info: AppSelfUpdate.Info): Long {
        val request = DownloadManager.Request(Uri.parse(info.apkUrl))
            .setTitle("droidtop ${info.versionName}")
            .setDescription("droidtop update")
            .addRequestHeader("User-Agent", "droidtop")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            // The same file DownloadJobs.fileFor names, so UpdateFiles finds it.
            .setDestinationInExternalFilesDir(context, null, "downloads/" + fileName(info.versionCode))
        val id = manager(context).enqueue(request)
        synchronized(lock) { record(context, Pending(id, info.versionCode, info.versionName, info.apkSha256)) }
        Log.i(UpdateNow.TAG, "handed the download of ${info.versionName} (build ${info.versionCode}) to Android's download manager (id $id)")
        return id
    }

    /** Marks download [id] as handled by the caller; null when it is not droidtop's update or someone already claimed it. */
    fun claim(context: Context, id: Long): Pending? {
        synchronized(lock) {
            val pending = pending(context)?.takeIf { it.id == id && !it.claimed } ?: return null
            val claimed = pending.copy(claimed = true)
            record(context, claimed)
            installs[id] = CompletableFuture()
            return claimed
        }
    }

    /**
     * Download [id] has ended: claims it and, when it succeeded, verifies the file and asks Android's installer
     * (the receiver's job, and a watching pass's when it sees the end first). When someone else in this process
     * claimed it, waits up to [waitMs] for their result. Returns the installer session (or null) and the line to
     * report; null when there is nothing to report. Blocking.
     */
    fun complete(context: Context, id: Long, waitMs: Long = 0): Pair<Int?, String>? {
        val pending = claim(context, id) ?: return if (waitMs > 0) awaitInstall(id, waitMs) else null
        val progress = runCatching { query(context, id) }.getOrDefault(Progress(State.GONE))
        if (progress.state == State.FAILED) {
            runCatching { manager(context).remove(id) }
            synchronized(lock) { record(context, null) }
            val result = Pair<Int?, String>(
                null,
                log("Update download of ${pending.label} failed (download manager reason ${progress.reason}); press Check now to try again"),
            )
            installs[id]?.complete(result)
            return result
        }
        return install(context, pending)
    }

    /** Verifies [pending]'s file against its digest and asks Android's installer for it. Never throws. */
    fun install(context: Context, pending: Pending): Pair<Int?, String> {
        val result = try {
            val apk = DownloadJobs.fileFor(context, fileName(pending.build))
            val session = AppSelfUpdate.install(context, apk, pending.build, pending.versionName, pending.sha256)
            Pair<Int?, String>(session, log("asked Android's installer to install ${pending.label}"))
        } catch (error: Exception) {
            Pair<Int?, String>(null, log("Update install failed: " + (error.message ?: error.javaClass.simpleName)))
        }
        installs[pending.id]?.complete(result)
        return result
    }

    /** Narrates download [id] through [onStatus] until it is no longer going; returns where it ended. Blocking. */
    fun watch(context: Context, id: Long, versionName: String, onStatus: (String) -> Unit): Progress {
        while (true) {
            val progress = query(context, id)
            if (progress.state != State.ACTIVE) return progress
            onStatus(progressLine(versionName, progress))
            Thread.sleep(WATCH_INTERVAL_MS)
        }
    }

    /** The result of the claim of download [id] made in this process, waiting up to [waitMs]; null when there is none. */
    fun awaitInstall(id: Long, waitMs: Long): Pair<Int?, String>? =
        runCatching { installs[id]?.get(waitMs, TimeUnit.MILLISECONDS) }.getOrNull()

    /** Drops the recorded download (and DownloadManager's file) once its build is stale ([UpdateFiles.isStale]). */
    fun dropIfStale(context: Context, installed: Long, target: Long?) {
        synchronized(lock) {
            val pending = pending(context) ?: return
            if (!UpdateFiles.isStale(pending.build, installed, target)) return
            runCatching { manager(context).remove(pending.id) }
            record(context, null)
            installs.remove(pending.id)
        }
    }

    private fun sha256(file: File): String = Sha256.hex(file).uppercase()

    private fun log(message: String): String {
        Log.i(UpdateNow.TAG, message)
        return message
    }
}

/**
 * DownloadManager's word that a download ended (ACTION_DOWNLOAD_COMPLETE, sent to droidtop by package).
 * For the self-update's download it verifies the file and asks Android's installer ([UpdateDownload.complete]);
 * any other download is ignored. The work runs on a thread under goAsync(): this is a background broadcast
 * (60 s timeout), and hashing the APK and copying it into the installer session takes seconds.
 */
class UpdateDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (id < 0) return
        val application = context.applicationContext
        val pending = goAsync()
        Thread({
            try {
                UpdateDownload.complete(application, id)
            } catch (error: Exception) {
                Log.w(UpdateNow.TAG, "finishing download $id failed", error)
            } finally {
                pending.finish()
            }
        }, "UpdateDownload").start()
    }
}
