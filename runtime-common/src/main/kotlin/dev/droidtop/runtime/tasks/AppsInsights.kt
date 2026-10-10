package dev.droidtop.runtime.tasks

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import dev.droidtop.runtime.systemstatus.PerformanceMonitor

/**
 * What the companion's Apps tab shows beside each running app (docs/SPEC.md "The companion's tabs", Apps;
 * Droidtop/tracker#414 slice C20): its processor share or memory with the helper app (`dumpsys cpuinfo`,
 * `dumpsys meminfo -c`), its data use over the last hours with Usage access (`NetworkStatsManager`, in buckets of
 * several hours, so "used data lately" is a marker, never a rate), and the sensitive permissions it holds. Reads are
 * binder and shell work: off the main thread.
 */
object AppsInsights {
    enum class Sort(val label: String) { RECENT("Recent"), CPU("Processor"), MEMORY("Memory"), DATA("Data") }

    /** The window "used data lately" looks at. */
    const val DATA_HOURS = 3L

    /** Running apps in [sort] order by [metric] (highest first; apps without a figure keep their order, last). Pure. */
    fun sort(apps: List<RunningApp>, sort: Sort, metric: Map<String, Double>): List<RunningApp> =
        if (sort == Sort.RECENT) apps else apps.sortedByDescending { metric[it.packageName] ?: -1.0 }

    /** `dumpsys meminfo -c` "proc,<kind>,<name>,<pid>,<pss kB>,..." lines as each package's memory in kB. Pure. */
    fun parseMeminfo(text: String): Map<String, Double> {
        val totals = LinkedHashMap<String, Double>()
        text.lineSequence().forEach { line ->
            val cells = line.split(',')
            if (cells.size < 5 || cells[0] != "proc") return@forEach
            val pss = cells[4].toDoubleOrNull() ?: return@forEach
            val name = cells[2].substringBefore(':')
            totals[name] = (totals[name] ?: 0.0) + pss
        }
        return totals
    }

    fun cpu(): Map<String, Double>? = shellText(listOf("dumpsys", "cpuinfo"))?.let { text ->
        PerformanceMonitor.parseTopApps(text, limit = Int.MAX_VALUE).groupBy { it.packageName.substringBefore(':') }.mapValues { (_, rows) -> rows.sumOf { it.percent } }
    }

    fun memory(): Map<String, Double>? = shellText(listOf("dumpsys", "meminfo", "-c"))?.let(::parseMeminfo)

    private fun shellText(argv: List<String>): String? {
        val shell = TaskManager.shell
        if (!runCatching { shell.capabilities().shellCommand }.getOrDefault(false)) return null
        return shell.exec(argv)?.takeIf { it.exit == 0 }?.stdout
    }

    /**
     * Bytes each package moved over Wi-Fi and mobile data in the last [DATA_HOURS] hours, or null without Usage access.
     * Android keeps network use in buckets of a few hours, so the figure is coarse by design.
     */
    @Suppress("DEPRECATION")
    fun data(context: Context): Map<String, Double>? {
        val stats = context.getSystemService(NetworkStatsManager::class.java) ?: return null
        val end = System.currentTimeMillis()
        val start = end - DATA_HOURS * 3_600_000L
        val byUid = HashMap<Int, Double>()
        return runCatching {
            listOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE).forEach { type ->
                val summary = runCatching { stats.querySummary(type, null, start, end) }.getOrNull() ?: return@forEach
                val bucket = NetworkStats.Bucket()
                while (summary.hasNextBucket()) {
                    summary.getNextBucket(bucket)
                    byUid[bucket.uid] = (byUid[bucket.uid] ?: 0.0) + bucket.rxBytes + bucket.txBytes
                }
                summary.close()
            }
            val pm = context.packageManager
            buildMap { byUid.forEach { (uid, bytes) -> pm.getPackagesForUid(uid)?.forEach { put(it, bytes) } } }
        }.getOrNull()
    }

    /** "Used data lately": above a megabyte in the window. Pure. */
    fun usedData(bytes: Double?): Boolean = (bytes ?: 0.0) >= 1_000_000

    /** Sensitive permissions and their words, the ones Android's privacy dashboard names. */
    val SENSITIVE: List<Pair<String, String>> = listOf(
        "android.permission.CAMERA" to "Camera",
        "android.permission.RECORD_AUDIO" to "Microphone",
        "android.permission.ACCESS_FINE_LOCATION" to "Location",
        "android.permission.ACCESS_COARSE_LOCATION" to "Location",
        "android.permission.READ_CONTACTS" to "Contacts",
        "android.permission.READ_SMS" to "Messages",
        "android.permission.READ_CALL_LOG" to "Call log",
        "android.permission.BODY_SENSORS" to "Body sensors",
        "android.permission.READ_CALENDAR" to "Calendar",
    )

    /** The sensitive permissions in [requested] whose [granted] flag is set, in words, once each. Pure. */
    fun grantedSensitive(requested: List<String>, granted: List<Boolean>): List<String> =
        requested.zip(granted).filter { it.second }.mapNotNull { (perm, _) -> SENSITIVE.firstOrNull { it.first == perm }?.second }.distinct()

    fun sensitive(context: Context, packageName: String): List<String> {
        val info: PackageInfo = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            }
        }.getOrNull() ?: return emptyList()
        val requested = info.requestedPermissions?.toList().orEmpty()
        val flags = info.requestedPermissionsFlags?.toList().orEmpty()
        return grantedSensitive(requested, flags.map { it and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0 })
    }

    /** The app in front on the main screen (not droidtop), for "Stop the app in front". Blocks. */
    suspend fun foreground(context: Context): String? {
        TaskManager.refresh(context)
        return TaskManager.snapshot.value?.apps?.firstOrNull { it.visible && it.displayId == android.view.Display.DEFAULT_DISPLAY && it.packageName != context.packageName }?.packageName
    }
}
