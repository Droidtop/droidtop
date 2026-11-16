package dev.droidtop.runtime.linux.noroot

import java.io.File

/**
 * Stand-ins for the /proc files Android refuses an app (SELinux: /proc/stat, /proc/loadavg, /proc/uptime and
 * /proc/vmstat are not readable from an app's domain on Android 8 and later), bound over them in a proot container so
 * top, ps, uptime and the like run (Droidtop/tracker#909). Only a file this process cannot read gets one; a readable
 * one is the kernel's own. The contents are fixed and plausible, as Termux's proot-distro writes them: the container
 * sees an idle machine with this device's CPU count, not real load.
 */
internal object ProcStandIns {
    /** Each stand-in's (host file, guest path), written into [dir] (the container's etc folder). Disk work: IO only. */
    fun binds(dir: File, readable: (String) -> Boolean = ::canRead, cpus: Int = Runtime.getRuntime().availableProcessors()): List<Pair<String, String>> =
        contents(cpus).mapNotNull { (name, text) ->
            val guest = "/proc/$name"
            if (readable(guest)) return@mapNotNull null
            val file = File(dir, "proc-$name")
            runCatching {
                if (!file.isFile || file.readText() != text) file.writeText(text)
                file.absolutePath to guest
            }.getOrNull()
        }

    private fun canRead(path: String): Boolean = runCatching { File(path).inputStream().use { it.read() }; true }.getOrDefault(false)

    /** The stand-ins' text, with one cpu line per core. Pure, for the tests. */
    internal fun contents(cpus: Int): List<Pair<String, String>> {
        val cores = cpus.coerceAtLeast(1)
        val stat = buildString {
            append("cpu  ${1957 * cores} 0 ${2877 * cores} ${93280 * cores} 262 342 254 87 0 0\n")
            for (i in 0 until cores) append("cpu$i 1957 0 2877 93280 32 42 31 10 0 0\n")
            append("intr 63361 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0\n")
            append("ctxt 38014093\n")
            append("btime 1694292441\n")
            append("processes 26442\n")
            append("procs_running 1\n")
            append("procs_blocked 0\n")
            append("softirq 75663 0 5903 6 25375 10774 0 243 11685 0 21677\n")
        }
        return listOf(
            "stat" to stat,
            "loadavg" to "0.12 0.07 0.02 2/165 765\n",
            "uptime" to "124.08 932.80\n",
            "vmstat" to "nr_free_pages 1743136\nnr_zone_inactive_anon 179281\nnr_zone_active_anon 7183\n" +
                "nr_zone_inactive_file 22858\nnr_zone_active_file 51328\nnr_mlock 0\npgpgin 198292\npgpgout 0\n",
        )
    }
}
