package dev.droidtop.runtime.systemstatus

import dev.droidtop.runtime.tasks.RiskyActions
import dev.droidtop.runtime.tasks.RiskyClass
import dev.droidtop.runtime.tasks.TaskManager
import java.io.File

/**
 * What the companion's System > Power card can show and change on this device (docs/SPEC.md "The companion's tabs",
 * Power and performance; Droidtop/tracker#414 slice C16), found by looking, never assumed per model:
 * - each CPU cluster's clock and governor from cpufreq (`/sys/devices/system/cpu/cpufreq/policy*`), and the GPU's clock
 *   from the paths kernels use for it; a value Android hides from apps is left out, never drawn as zero;
 * - a fan mode and a charge limit, where the device keeps them as Android settings (`settings list`, through the helper
 *   app): any system, global or secure key naming a fan, or a charge limit, stop or protection;
 * - with Settings > Risky actions > Root-level commands and a root provider, a cluster's governor and highest clock.
 * Every read is file or shell work: off the main thread.
 */
object PowerProbe {
    data class Cluster(
        val policy: String,
        val cpus: String?,
        val curKHz: Int?,
        val maxKHz: Int?,
        val governor: String?,
        val governors: List<String>,
        val frequencies: List<Int>,
    )

    /** Device settings that look like a fan mode or a charge limit, with their values (namespace/key -> value). */
    data class Probe(val fan: Map<String, String>, val charge: Map<String, String>)

    private val FAN = Regex("(^|[_.])fan([_.]|$)|fan_?mode|fan_?speed|cooling_?fan", RegexOption.IGNORE_CASE)
    private val CHARGE = Regex("charg\\w*_?(limit|stop|protect|threshold|bypass|level_max)|(limit|stop|protect)\\w*_?charg", RegexOption.IGNORE_CASE)
    private val POLICY = Regex("policy\\d{1,2}")
    private val GOVERNOR = Regex("[a-z0-9_]{1,32}")

    /** `settings list <namespace>` output (`key=value` lines) narrowed to fan and charge-limit keys. Pure. */
    fun parseSettings(namespace: String, text: String): Probe {
        val pairs = text.lineSequence().mapNotNull { line ->
            val eq = line.indexOf('=')
            if (eq <= 0) null else line.substring(0, eq).trim() to line.substring(eq + 1).trim()
        }.toList()
        return Probe(
            fan = pairs.filter { FAN.containsMatchIn(it.first) }.associate { "$namespace/${it.first}" to it.second },
            charge = pairs.filter { CHARGE.containsMatchIn(it.first) }.associate { "$namespace/${it.first}" to it.second },
        )
    }

    fun merge(probes: List<Probe>): Probe = Probe(probes.flatMap { it.fan.entries }.associate { it.key to it.value }, probes.flatMap { it.charge.entries }.associate { it.key to it.value })

    /** Reads the device's settings through the helper app; empty without it. Blocks. */
    fun probe(): Probe {
        val shell = TaskManager.shell
        if (!runCatching { shell.capabilities().shellCommand }.getOrDefault(false)) return Probe(emptyMap(), emptyMap())
        return merge(
            listOf("system", "global", "secure").mapNotNull { ns ->
                shell.exec(listOf("settings", "list", ns))?.takeIf { it.exit == 0 }?.let { parseSettings(ns, it.stdout) }
            },
        )
    }

    /** What the card shows. Pure, so the probe result to visible items is tested. */
    data class Visible(val fan: Map<String, String>, val chargeLimit: Map<String, String>, val clusters: List<Cluster>, val gpuMHz: Int?, val tuning: Boolean)

    fun visible(probe: Probe, clusters: List<Cluster>, gpuMHz: Int?, rootTuning: Boolean): Visible = Visible(
        fan = probe.fan,
        chargeLimit = probe.charge,
        // A cluster droidtop cannot read the clock of is left out, not drawn as 0.
        clusters = clusters.filter { it.curKHz != null },
        gpuMHz = gpuMHz,
        tuning = rootTuning && clusters.any { it.governors.isNotEmpty() },
    )

    /**
     * The page a row with no control of droidtop's own opens: the device's own page when one resolves (its fan or
     * charging screen), else Android's. [candidates] in order, [resolves] answers for each. Pure.
     */
    fun <T> pageFor(devicePages: List<T>, androidPage: T, resolves: (T) -> Boolean): T? =
        devicePages.firstOrNull(resolves) ?: androidPage.takeIf(resolves)

    // ---- reads ----

    private const val CPUFREQ = "/sys/devices/system/cpu/cpufreq"

    private fun read(path: String): String? = runCatching { File(path).readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

    fun clusters(): List<Cluster> {
        val dirs = File(CPUFREQ).listFiles { f -> f.isDirectory && POLICY.matches(f.name) }?.sortedBy { it.name.removePrefix("policy").toInt() } ?: return emptyList()
        return dirs.map { dir ->
            val p = dir.absolutePath
            Cluster(
                policy = dir.name,
                cpus = read("$p/related_cpus"),
                curKHz = read("$p/scaling_cur_freq")?.toIntOrNull(),
                maxKHz = read("$p/scaling_max_freq")?.toIntOrNull(),
                governor = read("$p/scaling_governor"),
                governors = read("$p/scaling_available_governors")?.split(' ')?.filter { it.isNotBlank() }.orEmpty(),
                frequencies = read("$p/scaling_available_frequencies")?.split(' ')?.mapNotNull { it.toIntOrNull() }.orEmpty(),
            )
        }
    }

    /** The GPU's clock in MHz where the kernel lets an app read it (Adreno's kgsl in Hz, else a devfreq node). */
    fun gpuMHz(): Int? {
        read("/sys/class/kgsl/kgsl-3d0/gpuclk")?.toLongOrNull()?.let { return (it / 1_000_000).toInt() }
        val devfreq = File("/sys/class/devfreq").listFiles()?.firstOrNull { it.name.contains("gpu", ignoreCase = true) || it.name.contains("kgsl") }
        return devfreq?.let { read("${it.absolutePath}/cur_freq")?.toLongOrNull()?.let { hz -> (hz / 1_000_000).toInt() } }
    }

    // ---- root tuning (Risky actions > Root-level commands) ----

    /** Whether the switch is on and the helper runs as root. Blocks. */
    fun rootTuning(): Boolean {
        if (!RiskyActions.allows(RiskyClass.ROOT_COMMANDS)) return false
        val shell = TaskManager.shell
        if (!runCatching { shell.capabilities().shell }.getOrDefault(false)) return false
        return shell.exec(listOf("id", "-u"))?.let { it.exit == 0 && it.stdout.trim() == "0" } == true
    }

    /** The root command that sets [policy]'s [file] to [value], or null for anything outside cpufreq's own names. Pure. */
    fun writeCommand(policy: String, file: String, value: String): List<String>? {
        if (!POLICY.matches(policy) || file !in WRITABLE) return null
        if (file == "scaling_governor" && !GOVERNOR.matches(value)) return null
        if (file == "scaling_max_freq" && value.toIntOrNull() == null) return null
        return listOf("sh", "-c", "echo $value > $CPUFREQ/$policy/$file")
    }

    private val WRITABLE = setOf("scaling_governor", "scaling_max_freq")

    /** Writes one cpufreq value as root; the plain reason when it cannot. Blocks. */
    fun write(policy: String, file: String, value: String): String? {
        val command = writeCommand(policy, file, value) ?: return "Not a setting droidtop changes"
        if (!rootTuning()) return "Turn on Risky actions, then \"${RiskyClass.ROOT_COMMANDS.title}\", with a root provider"
        val out = TaskManager.shell.exec(command) ?: return "The root provider did not answer"
        return if (out.exit == 0) null else out.stderr.trim().ifEmpty { "The kernel refused" }
    }
}
