package dev.droidtop.app.update

import android.content.Context
import android.util.Log
import dev.droidtop.pluginhost.DownloadJobs
import dev.droidtop.pluginhost.PluginJobsCenter
import java.io.File

/**
 * The self-update's files in the downloads folder (docs/SPEC.md 10b): `droidtop-<versionCode>.apk`
 * and the `.apk.part` (and its `.part.meta`) a download in progress leaves beside it. Each build's
 * APK is about 130 MB and the folder otherwise keeps every one ever fetched, so this decides which
 * to delete: everything for a build that is installed or older, and, once a newer build is being
 * fetched, everything older than that one. A partial file of an older build is never resumed
 * (Droidtop/tracker#445: droidtop-1736.apk.part and 1744 resumed beside the build in hand).
 */
internal object UpdateFiles {
    private val NAME = Regex("^droidtop-(\\d+)\\.apk(\\.part(\\.meta)?)?$")

    /** The build number a file in the downloads folder belongs to, or null for a file that is not the updater's. */
    fun buildOf(fileName: String): Long? = NAME.matchEntire(fileName)?.groupValues?.get(1)?.toLongOrNull()

    /**
     * Whether the files of [build] are stale: that build is already installed (or older than what is),
     * or a [target] build newer than it is being fetched. The target's own files are never stale.
     */
    fun isStale(build: Long, installed: Long, target: Long?): Boolean =
        build <= installed || (target != null && build < target)

    /** The names among [fileNames] to delete. */
    fun stale(fileNames: Collection<String>, installed: Long, target: Long?): List<String> =
        fileNames.filter { name -> buildOf(name)?.let { isStale(it, installed, target) } == true }

    /**
     * Deletes the stale files and cancels any download job still holding one (a job restored from a
     * previous run would otherwise fetch the old build again). Blocking file work: call from a worker
     * thread. Never throws; returns how many files went.
     */
    fun clean(context: Context, installed: Long, target: Long?): Int = runCatching {
        val folder = DownloadJobs.fileFor(context, "droidtop-0.apk").parentFile ?: return@runCatching 0
        PluginJobsCenter.entries().value
            .filter { it.nativeKind == DownloadJobs.KIND && !it.done }
            .filter { entry ->
                PluginJobsCenter.argsOf(entry.jobId)?.get("name")
                    ?.let { name -> buildOf(name)?.let { isStale(it, installed, target) } } == true
            }
            .forEach { PluginJobsCenter.cancel(it.jobId) }
        var removed = 0
        for (name in stale(folder.list()?.toList().orEmpty(), installed, target)) {
            if (File(folder, name).delete()) removed++
        }
        if (removed > 0) Log.i(UpdateNow.TAG, "removed $removed stale update file(s) (installed build $installed, fetching ${target ?: "nothing"})")
        removed
    }.getOrDefault(0)
}
