package dev.droidtop.runtime.tasks

import java.io.File

/**
 * Android 12's phantom process limit (docs/SPEC.md "Child processes and Android's limit", Droidtop/tracker#816).
 * Android counts the native processes every app forks (proot and each program in a container, sway, Xwayland, Wine
 * and its tree), allows [DEFAULT_LIMIT] across all apps, and kills the excess, signal 9, with nothing said. There is
 * no switch for it on Android 12 and 13. With Shizuku droidtop can lift it, after the person agrees, and put it back.
 * Android 14 has a developer option, "Disable child process restrictions", which the same path sets.
 */
object PhantomProcesses {
    /** Android's limit across all apps when nothing changed it (ActivityManagerConstants.DEFAULT_MAX_PHANTOM_PROCESSES). */
    const val DEFAULT_LIMIT = 32

    /** The value droidtop sets on Android 12 and 13: no practical limit. */
    const val LIFTED = Int.MAX_VALUE

    /** Android 12 is the first with the limit. */
    const val FIRST_SDK = 31

    /** Android 14 is the first with "Disable child process restrictions", the monitor's feature flag. */
    const val FLAG_SDK = 34

    /** The feature flag Android 12L+ reads to watch phantom processes at all, kept in global settings. */
    const val MONITOR_FLAG = "settings_enable_monitor_phantom_procs"

    /**
     * What the device says. [limit] and [monitored] are null when droidtop could not ask (no Shizuku); [ours] is
     * droidtop's own child processes now.
     */
    data class Status(val sdk: Int, val limit: Int?, val monitored: Boolean?, val ours: Int) {
        /** Whether Android may kill droidtop's children at all. */
        val applies: Boolean get() = sdk >= FIRST_SDK && monitored != false && (limit ?: DEFAULT_LIMIT) < LIFTED

        /** Whether droidtop's own children alone are over the limit Android allows every app together. */
        val over: Boolean get() = applies && ours >= (limit ?: DEFAULT_LIMIT)
    }

    /** `device_config get`'s answer: a number, or "null" when nothing set it (Android's default). Pure. */
    internal fun parseLimit(out: String?): Int? {
        val text = out?.trim() ?: return null
        if (text.isEmpty() || text == "null") return DEFAULT_LIMIT
        return text.toIntOrNull()
    }

    /** `settings get global` for [MONITOR_FLAG]: false only when it says so; "null" is Android's default, on. Pure. */
    internal fun parseMonitored(out: String?): Boolean? {
        val text = out?.trim() ?: return null
        return !text.equals("false", ignoreCase = true)
    }

    /** Whether a process's command line is one of droidtop's own app processes, which Android does not count. Pure. */
    internal fun isAppProcess(cmdline: String, packageName: String): Boolean =
        cmdline == packageName || cmdline.startsWith("$packageName:")

    /**
     * droidtop's child processes now: every process of droidtop's own user that is not one of its app processes. The
     * kernel shows an app only its own processes, which is exactly what Android counts for it. Blocking: IO only.
     */
    fun countOurs(uid: Int, packageName: String, procRoot: File = File("/proc")): Int =
        procRoot.list().orEmpty().count { name ->
            val pid = name.toIntOrNull() ?: return@count false
            val dir = File(procRoot, name)
            val status = runCatching { File(dir, "status").readText() }.getOrNull() ?: return@count false
            val owner = status.lineSequence().firstOrNull { it.startsWith("Uid:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toIntOrNull()
            if (owner != uid || pid <= 0) return@count false
            val cmdline = runCatching { File(dir, "cmdline").readText().substringBefore('\u0000') }.getOrDefault("")
            !isAppProcess(cmdline, packageName)
        }

    /** The commands that lift the limit on [sdk]. Pure. */
    fun liftCommands(sdk: Int): List<List<String>> =
        if (sdk >= FLAG_SDK) {
            listOf(listOf("settings", "put", "global", MONITOR_FLAG, "false"))
        } else {
            // Without the first, Android's server-side config sync puts the default back.
            listOf(
                listOf("device_config", "set_sync_disabled_for_tests", "persistent"),
                listOf("device_config", "put", "activity_manager", "max_phantom_processes", LIFTED.toString()),
            )
        }

    /** The commands that put Android's own behaviour back on [sdk]. Pure. */
    fun restoreCommands(sdk: Int): List<List<String>> =
        if (sdk >= FLAG_SDK) {
            listOf(listOf("settings", "delete", "global", MONITOR_FLAG))
        } else {
            listOf(
                listOf("device_config", "delete", "activity_manager", "max_phantom_processes"),
                listOf("device_config", "set_sync_disabled_for_tests", "none"),
            )
        }

    /** Reads the device's state through [shell] when it can run commands. Blocking: IO only. */
    fun read(shell: PrivilegedShell, sdk: Int, uid: Int, packageName: String): Status {
        val canAsk = runCatching { shell.capabilities().shellCommand }.getOrDefault(false)
        fun ask(argv: List<String>): String? =
            if (!canAsk) null else runCatching { shell.exec(argv)?.takeIf { it.exit == 0 }?.stdout }.getOrNull()
        val limit = if (sdk >= FIRST_SDK) parseLimit(ask(listOf("device_config", "get", "activity_manager", "max_phantom_processes"))) else null
        // The flag exists from Android 12L; on 12 the answer is "null", which reads as watched.
        val monitored = if (sdk >= FIRST_SDK) parseMonitored(ask(listOf("settings", "get", "global", MONITOR_FLAG))) else false
        return Status(sdk, limit, monitored, countOurs(uid, packageName))
    }

    /** Runs [commands] through [shell], all of them; true when every one succeeded. Blocking: IO only. */
    fun run(shell: PrivilegedShell, commands: List<List<String>>): Boolean =
        commands.all { argv -> runCatching { shell.exec(argv)?.exit == 0 }.getOrDefault(false) }
}
