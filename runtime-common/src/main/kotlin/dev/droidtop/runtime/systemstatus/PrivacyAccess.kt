package dev.droidtop.runtime.systemstatus

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import dev.droidtop.runtime.tasks.TaskManager

/**
 * The companion's Privacy card (docs/SPEC.md "The companion's tabs", Privacy; Droidtop/tracker#414 slice C17):
 * - with the helper app, the apps that used the camera, the microphone or location in the last 24 hours, read from
 *   Android's own record (`dumpsys appops --op <op>`: each package's last `Access:` with how long ago);
 * - "What droidtop can access": each of droidtop's own grants, whether it holds it, and why it asks.
 * Reads are binder calls and shell work: off the main thread.
 */
object PrivacyAccess {
    enum class Kind(val op: String, val label: String) { CAMERA("CAMERA", "Camera"), MIC("RECORD_AUDIO", "Microphone"), LOCATION("FINE_LOCATION", "Location") }

    /** One app's last use of one kind, [agoMs] before the dump. */
    data class Use(val packageName: String, val kind: Kind, val agoMs: Long)

    const val DAY_MS = 24L * 60 * 60 * 1000

    private val PACKAGE = Regex("^\\s*Package ([\\w.]+):\\s*$")
    private val ACCESS = Regex("Access:.*\\((-[0-9dhms]+)\\)")
    private val PART = Regex("(\\d+)(ms|d|h|m|s)")

    /** "-4h42m14s943ms" in milliseconds (positive), or null. Pure. */
    fun parseAgo(text: String): Long? {
        if (!text.startsWith("-")) return null
        val parts = PART.findAll(text.removePrefix("-")).toList()
        if (parts.isEmpty()) return null
        return parts.sumOf { m ->
            val n = m.groupValues[1].toLong()
            when (m.groupValues[2]) {
                "d" -> n * 86_400_000
                "h" -> n * 3_600_000
                "m" -> n * 60_000
                "s" -> n * 1_000
                else -> n
            }
        }
    }

    /** Each package's most recent access in one op's dump, within [withinMs]. Pure. */
    fun parse(kind: Kind, dump: String, withinMs: Long = DAY_MS): List<Use> {
        val latest = LinkedHashMap<String, Long>()
        var pkg: String? = null
        dump.lineSequence().forEach { line ->
            PACKAGE.find(line)?.let { pkg = it.groupValues[1]; return@forEach }
            val ago = ACCESS.find(line)?.groupValues?.get(1)?.let(::parseAgo) ?: return@forEach
            val name = pkg ?: return@forEach
            latest[name] = minOf(latest[name] ?: Long.MAX_VALUE, ago)
        }
        return latest.filter { it.value <= withinMs }.map { Use(it.key, kind, it.value) }.sortedBy { it.agoMs }
    }

    /** The last day's camera, microphone and location use, through the helper app; null without it. Blocks. */
    fun recentUse(): List<Use>? {
        val shell = TaskManager.shell
        if (!runCatching { shell.capabilities().shellCommand }.getOrDefault(false)) return null
        return Kind.entries.flatMap { kind ->
            shell.exec(listOf("dumpsys", "appops", "--op", kind.op))?.takeIf { it.exit == 0 }?.let { parse(kind, it.stdout) }.orEmpty()
        }
    }

    /** "2 h ago", "12 min ago", "just now". Pure. */
    fun agoText(ms: Long): String = when {
        ms < 60_000 -> "just now"
        ms < 3_600_000 -> "${ms / 60_000} min ago"
        else -> "${ms / 3_600_000} h ago"
    }

    /** One of droidtop's own grants: what it is, whether droidtop has it, and why droidtop asks. */
    data class Grant(val label: String, val held: Boolean, val reason: String)

    fun droidtopGrants(context: Context): List<Grant> {
        val pkg = context.packageName
        val appOps = context.getSystemService(AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        val usage = runCatching {
            appOps?.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), pkg) == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
        val listener = NotificationsStore.isGranted(context)
        val accessibility = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.any { it.startsWith("$pkg/") } == true
        val bluetooth = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission("android.permission.BLUETOOTH_CONNECT") == PackageManager.PERMISSION_GRANTED
        val provider = runCatching { TaskManager.shell.capabilities().shellCommand }.getOrDefault(false)
        return listOf(
            Grant("Notification access", listener, "Shows your notifications and lets you answer them on the companion"),
            Grant("Usage access", usage, "Knows which app and game is in front, and sorts apps by data use"),
            Grant("Accessibility service", accessibility, "Types into other apps on a second screen, and takes screenshots"),
            Grant("Bluetooth", bluetooth, "Lists your Bluetooth devices on the companion"),
            Grant("Helper app (Shizuku)", provider, "Closes apps for good, switches radios, and reads what other apps used"),
        )
    }
}
