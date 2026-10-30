package dev.droidtop.runtime

/**
 * What a running container uses now (docs/SPEC.md 3d, "Resource usage"; Droidtop/tracker#757 slice 3): how many
 * processes it has, the memory they hold (resident set, summed) and the share of the device's processor they used
 * over the sample, 0 to 100 for the whole device.
 */
data class ContainerUsage(val processes: Int, val memoryBytes: Long, val cpuPercent: Double)

/** How far apart the two readings of [ContainerRuntime.usage] are by default: long enough for a steady share. */
const val USAGE_SAMPLE_MS = 1_000L

/**
 * Reading processes from `/proc` (pure: it takes the files' text). One [Sample] is every process's CPU time in clock
 * ticks and its resident memory; two samples a known time apart give the processor share.
 */
object ProcUsage {
    /** The kernel's clock ticks a second (`_SC_CLK_TCK`), 100 when it cannot be read. */
    fun ticksPerSecond(): Long = runCatching { android.system.Os.sysconf(android.system.OsConstants._SC_CLK_TCK) }
        .getOrDefault(100L).takeIf { it > 0 } ?: 100L

    /** One process: CPU time used so far (user and system, in clock ticks) and resident memory in bytes. */
    data class Process(val pid: Int, val cpuTicks: Long, val memoryBytes: Long)

    /**
     * The CPU ticks in a `/proc/<pid>/stat` line: utime and stime, fields 14 and 15. The command name (field 2) can
     * hold spaces and parentheses, so the fields are counted from the last `)`. Null for a line that is not one.
     */
    fun cpuTicks(stat: String): Long? {
        val rest = stat.substringAfterLast(')', "").trim().takeIf { it.isNotEmpty() } ?: return null
        val fields = rest.split(' ').filter { it.isNotEmpty() }
        // After ')': field 3 (state) is index 0, so utime (14) is index 11 and stime (15) index 12.
        val utime = fields.getOrNull(11)?.toLongOrNull() ?: return null
        val stime = fields.getOrNull(12)?.toLongOrNull() ?: return null
        return utime + stime
    }

    /** The resident memory in a `/proc/<pid>/status` text (`VmRSS:  1234 kB`), in bytes; 0 for a kernel thread. */
    fun memoryBytes(status: String): Long {
        val line = status.lineSequence().firstOrNull { it.startsWith("VmRSS:") } ?: return 0
        val kb = line.removePrefix("VmRSS:").trim().substringBefore(' ').toLongOrNull() ?: return 0
        return kb * 1024
    }

    /**
     * The usage between [first] and [second], taken [elapsedMs] apart, on a device with [cores] processors whose
     * clock runs [ticksPerSecond]. Only processes in both samples count toward the processor share (one that started
     * or ended in between has no difference to give); the memory and the count are [second]'s. Pure.
     */
    fun usage(first: List<Process>, second: List<Process>, elapsedMs: Long, ticksPerSecond: Long, cores: Int): ContainerUsage {
        val before = first.associate { it.pid to it.cpuTicks }
        val ticks = second.sumOf { p -> before[p.pid]?.let { (p.cpuTicks - it).coerceAtLeast(0) } ?: 0L }
        val available = ticksPerSecond.toDouble() * (elapsedMs / 1000.0) * cores.coerceAtLeast(1)
        val percent = if (available <= 0) 0.0 else (ticks / available * 100).coerceIn(0.0, 100.0)
        return ContainerUsage(second.size, second.sumOf { it.memoryBytes }, percent)
    }

    /**
     * The shell text for a root shell that prints, for every process in the same PID namespace as [initPid], a line
     * `P <pid>`, its `stat` line and its `VmRSS` line, then `END`; [parse] reads it back. Used where the container's
     * processes are root's (droidspaces), read through Shizuku or Sui.
     */
    fun namespaceScript(initPid: Int): String =
        "ns=\$(readlink /proc/$initPid/ns/pid) || exit 3; " +
            "for p in /proc/[0-9]*; do " +
            "[ \"\$(readlink \$p/ns/pid 2>/dev/null)\" = \"\$ns\" ] || continue; " +
            "echo \"P \${p#/proc/}\"; cat \$p/stat 2>/dev/null; grep '^VmRSS:' \$p/status 2>/dev/null; " +
            "done; echo END"

    /** [namespaceScript]'s output read back; a process whose stat line is missing (it ended meanwhile) is left out. Pure. */
    fun parse(output: String): List<Process> {
        val found = ArrayList<Process>()
        var pid: Int? = null
        var ticks: Long? = null
        var memory = 0L
        fun close() {
            val p = pid
            val t = ticks
            if (p != null && t != null) found += Process(p, t, memory)
            pid = null; ticks = null; memory = 0
        }
        for (line in output.lineSequence()) {
            when {
                line.startsWith("P ") -> { close(); pid = line.removePrefix("P ").trim().toIntOrNull() }
                line == "END" -> close()
                line.startsWith("VmRSS:") -> memory = memoryBytes(line)
                pid != null && ticks == null -> ticks = cpuTicks(line)
            }
        }
        close()
        return found
    }
}
