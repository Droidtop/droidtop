package dev.droidtop.runtime

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The kernel features droidtop's container engine uses when a device has them (docs/SPEC.md 3, "Kernel features,
 * detected at runtime"; Droidtop/tracker#555 D10). Detected on each device, never assumed at build time: the RP5 has
 * none of them, a rooted phone may have all.
 */
@Serializable
enum class EngineFeature(val title: String, internal val nsEntry: String?, internal val unshareFlags: List<String>) {
    USER_NAMESPACES("user namespaces", "user", listOf("-U")),
    MOUNT_NAMESPACES("mount namespaces", "mnt", listOf("-m")),
    PID_NAMESPACES("PID namespaces", "pid", listOf("-p", "-f")),
    NETWORK_NAMESPACES("network namespaces", "net", listOf("-n")),
    HOSTNAME_NAMESPACES("hostname namespaces", "uts", listOf("-u")),
    CGROUPS("cgroups", null, emptyList()),
    OVERLAYFS("overlayfs", null, emptyList()),
}

/**
 * One feature on this device: whether the kernel has it, and whether droidtop could use it as itself ([app], through
 * unprivileged user namespaces) and through the root helper ([root]). Null is not tried: no root helper at the time.
 */
@Serializable
data class FeatureState(val kernel: Boolean, val app: Boolean? = null, val root: Boolean? = null) {
    val usable: Boolean get() = kernel && (app == true || root == true)
}

/** What [EngineFeatureProbe] found, for one boot of the device ([bootId]). */
@Serializable
data class EngineFeatures(
    val bootId: String,
    val states: Map<EngineFeature, FeatureState>,
    /** The cgroup v2 controllers the root of the hierarchy offers, when it could be read. */
    val cgroupControllers: List<String> = emptyList(),
) {
    fun state(feature: EngineFeature): FeatureState = states[feature] ?: FeatureState(kernel = false)

    fun usable(feature: EngineFeature): Boolean = state(feature).usable

    fun encode(): String = JSON.encodeToString(EngineFeatures.serializer(), this)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        fun decode(text: String): EngineFeatures? = runCatching { JSON.decodeFromString(EngineFeatures.serializer(), text) }.getOrNull()
    }
}

/**
 * The container options that need a kernel feature, and the one sentence each gets when droidtop cannot give it. One
 * table for every front (`docker` and `podman`, later the API daemons), so no option is refused differently in two
 * places and none is accepted and silently ignored. Pure.
 */
object EngineOptions {
    /** `run` options that need a feature: the option, with or without its `=value`, to the feature. */
    val NEEDS: Map<String, EngineFeature> = mapOf(
        "-m" to EngineFeature.CGROUPS,
        "--memory" to EngineFeature.CGROUPS,
        "--memory-swap" to EngineFeature.CGROUPS,
        "--memory-reservation" to EngineFeature.CGROUPS,
        "--cpus" to EngineFeature.CGROUPS,
        "-c" to EngineFeature.CGROUPS,
        "--cpu-shares" to EngineFeature.CGROUPS,
        "--cpuset-cpus" to EngineFeature.CGROUPS,
        "--pids-limit" to EngineFeature.CGROUPS,
        "--network" to EngineFeature.NETWORK_NAMESPACES,
        "--net" to EngineFeature.NETWORK_NAMESPACES,
        "--pid" to EngineFeature.PID_NAMESPACES,
        "-h" to EngineFeature.HOSTNAME_NAMESPACES,
        "--hostname" to EngineFeature.HOSTNAME_NAMESPACES,
        "--tmpfs" to EngineFeature.MOUNT_NAMESPACES,
        "--read-only" to EngineFeature.MOUNT_NAMESPACES,
    )

    /** Options that take no value. */
    private val SWITCHES = setOf("--read-only")

    /**
     * Values that ask for what droidtop's containers already are: they share the device's network and processes, so
     * `--network host` and `--pid host` are true as given and are taken, not refused.
     */
    private val AS_IS = mapOf("--network" to "host", "--net" to "host", "--pid" to "host")

    /** The table's option for [arg] (`--memory` or `--memory=1g`), or null when it is not one. */
    fun option(arg: String): String? = NEEDS.keys.firstOrNull { arg == it || arg.startsWith("$it=") }

    fun takesValue(option: String): Boolean = option !in SWITCHES

    /** Whether [option] with [value] asks for what the containers already are. */
    fun asIs(option: String, value: String?): Boolean = value != null && AS_IS[option] == value

    /**
     * The sentence for [option] that droidtop does not apply, with [tool] (`docker run`) leading it: the feature it
     * needs and why this device cannot give it, or, where the feature is here, that droidtop has not built it yet.
     */
    fun refusal(tool: String, option: String, features: EngineFeatures?): String {
        val feature = NEEDS[option] ?: return "$tool: $option is not built in droidtop yet"
        val state = features?.state(feature) ?: return "$tool: $option is not built in droidtop yet"
        return when {
            !state.kernel -> "$tool: $option needs ${feature.title}, which this device's kernel does not have"
            state.usable -> "$tool: $option is not built in droidtop yet (this device has ${feature.title})"
            state.root == null -> "$tool: $option needs ${feature.title}, which droidtop can use here only with root " +
                "(Shizuku or Sui started as root)"
            else -> "$tool: $option needs ${feature.title}, which this device does not let droidtop use"
        }
    }

    /** `docker info`'s lines about the features, one per feature, in plain words. Pure. */
    fun describe(features: EngineFeatures): List<String> = EngineFeature.entries.map { feature ->
        val state = features.state(feature)
        val how = when {
            !state.kernel -> "not in this device's kernel"
            state.app == true -> "available"
            state.root == true -> "available with root"
            state.root == null -> "in the kernel; needs root to use, and no root helper is running"
            else -> "in the kernel, but refused to droidtop"
        }
        val extra = if (feature == EngineFeature.CGROUPS && features.cgroupControllers.isNotEmpty()) {
            " (controllers: ${features.cgroupControllers.joinToString(" ")})"
        } else {
            ""
        }
        " ${feature.title}: $how$extra"
    }

    /** Whether any of [args] (a `run`'s) needs the feature table read, so a command without one never probes. Pure. */
    fun mentioned(args: List<String>): Boolean = args.any { option(it) != null }
}

/**
 * Finds [EngineFeatures] on this device and keeps them for the boot (docs/SPEC.md 3, "Kernel features, detected at
 * runtime"). What the kernel has comes from `/proc/self/ns` and `/proc/filesystems`. Whether droidtop can use a
 * namespace is a trial `unshare` of it, as droidtop (inside a new user namespace) and, when the root helper runs,
 * as root. overlayfs is a trial mount inside a new mount namespace, so it is gone when the trial ends and nothing is
 * left mounted. Every command is an argv; nothing is a shell line. Blocking: off the main thread.
 */
object EngineFeatureProbe {
    private const val TAG = "droidtop.EngineFeatures"
    private const val PREFS = "engine_features"
    private const val KEY = "features"

    @Volatile
    private var memo: EngineFeatures? = null

    /** This boot's features: kept from an earlier probe this boot, else probed now and kept. */
    suspend fun current(context: Context): EngineFeatures {
        val boot = bootId()
        memo?.takeIf { it.bootId == boot }?.let { return it }
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.let(EngineFeatures::decode)?.takeIf { it.bootId == boot }?.let {
            memo = it
            return it
        }
        val found = probe(context, boot)
        prefs.edit().putString(KEY, found.encode()).apply()
        memo = found
        Log.i(TAG, EngineOptions.describe(found).joinToString(";"))
        return found
    }

    private fun bootId(): String = runCatching { File("/proc/sys/kernel/random/boot_id").readText().trim() }.getOrDefault("")

    private suspend fun probe(context: Context, boot: String): EngineFeatures {
        val namespaces = File("/proc/self/ns").list()?.toSet().orEmpty()
        val filesystems = runCatching { File("/proc/filesystems").readText() }.getOrDefault("")
        val rootHelper = RootProcess.access().available
        val scratch = File(context.cacheDir, "engine-probe")
        val states = EngineFeature.entries.associateWith { feature ->
            val kernel = kernelHas(feature, namespaces, filesystems)
            if (!kernel) return@associateWith FeatureState(kernel = false)
            when (feature) {
                EngineFeature.CGROUPS -> FeatureState(kernel = true, app = false, root = if (rootHelper) true else null)
                EngineFeature.OVERLAYFS -> FeatureState(
                    kernel = true,
                    app = overlayTrial(scratch, asRoot = false),
                    root = if (rootHelper) overlayTrial(scratch, asRoot = true) else null,
                )
                else -> FeatureState(
                    kernel = true,
                    app = succeeds(appUnshare(feature.unshareFlags) + "true", asRoot = false),
                    root = if (rootHelper) succeeds(listOf("unshare") + feature.unshareFlags + "true", asRoot = true) else null,
                )
            }
        }
        val controllers = if (states.getValue(EngineFeature.CGROUPS).kernel) cgroupControllers(rootHelper) else emptyList()
        return EngineFeatures(boot, states, controllers)
    }

    /** `unshare` as droidtop: everything but a user namespace needs one around it, mapped to root inside. */
    private fun appUnshare(flags: List<String>): List<String> =
        if (flags == listOf("-U")) listOf("unshare", "-U") else listOf("unshare", "-U", "-r") + flags

    private suspend fun succeeds(argv: List<String>, asRoot: Boolean): Boolean {
        val result = if (asRoot) RootProcess.run(*argv.toTypedArray()) else ProcessRunner.run(argv)
        return result.succeeded
    }

    /** A trial overlay mount inside a mount namespace of its own, so it ends with the trial. */
    private suspend fun overlayTrial(scratch: File, asRoot: Boolean): Boolean {
        val dirs = OverlayDirs(File(scratch, if (asRoot) "root" else "app"))
        if (!dirs.make()) return false
        try {
            val mount = listOf("mount", "-t", "overlay", "overlay", "-o", dirs.options(), dirs.merged.path)
            val argv = if (asRoot) listOf("unshare", "-m") + mount else listOf("unshare", "-U", "-r", "-m") + mount
            return succeeds(argv, asRoot)
        } finally {
            dirs.remove()
        }
    }

    private suspend fun cgroupControllers(rootHelper: Boolean): List<String> {
        val path = "/sys/fs/cgroup/cgroup.controllers"
        val text = runCatching { File(path).readText() }.getOrNull()
            ?: if (rootHelper) RootProcess.run("cat", path).takeIf { it.succeeded }?.stdout else null
        return parseControllers(text.orEmpty())
    }

    /** Whether the kernel has [feature], from the namespace entries a process has and `/proc/filesystems`. Pure. */
    fun kernelHas(feature: EngineFeature, namespaces: Set<String>, filesystems: String): Boolean = when (feature) {
        EngineFeature.CGROUPS -> "cgroup2" in filesystemNames(filesystems)
        EngineFeature.OVERLAYFS -> "overlay" in filesystemNames(filesystems)
        else -> feature.nsEntry in namespaces
    }

    /** The filesystem names in `/proc/filesystems` (`nodev\toverlay`, `\text4`). Pure. */
    fun filesystemNames(text: String): Set<String> =
        text.lineSequence().mapNotNull { line -> line.trim().split(Regex("\\s+")).lastOrNull()?.takeIf { it.isNotEmpty() } }.toSet()

    /** The controllers in a `cgroup.controllers` file: one line, separated by spaces. Pure. */
    fun parseControllers(text: String): List<String> = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    /**
     * The four folders a trial overlay mount needs, in droidtop's cache. They are removed one by one, each empty, with
     * the one folder overlayfs makes in its work folder: never a recursive delete.
     */
    private class OverlayDirs(private val base: File) {
        private val lower = File(base, "lower")
        private val upper = File(base, "upper")
        private val work = File(base, "work")
        val merged = File(base, "merged")

        fun make(): Boolean = listOf(lower, upper, work, merged).all { it.isDirectory || it.mkdirs() }

        fun options(): String = "lowerdir=${lower.path},upperdir=${upper.path},workdir=${work.path}"

        fun remove() {
            listOf(File(work, "work"), File(work, "index"), lower, upper, work, merged, base, base.parentFile).forEach {
                if (it != null && it.isDirectory) it.delete()
            }
        }
    }
}
