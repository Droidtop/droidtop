package dev.droidtop.library.consoles

import android.content.Context
import android.content.Intent
import dev.droidtop.pluginhost.DownloadJobs
import dev.droidtop.pluginhost.PluginResult
import dev.droidtop.runtime.tasks.PrivilegedShell
import dev.droidtop.runtime.tasks.TaskManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Getting the libretro core a RetroArch launch needs into RetroArch, without the person opening
 * RetroArch (docs/SPEC.md 7e2c, Droidtop/tracker#271).
 *
 * What RetroArch offers another app, checked against its 1.22.2 source: no intent, content provider
 * or network command downloads or installs a core (command.h lists every network command; none
 * does), and its core loader `dlopen`s the LIBRETRO path verbatim (runloop.c load_dynamic_core), so
 * a core must sit somewhere RetroArch can map executable. Shared storage is mounted noexec
 * (/proc/mounts on the console: `/storage/emulated fuse ... noexec`), and the cores folder,
 * `/data/user/0/<package>/cores`, is RetroArch's private data, which the shell user (Shizuku) cannot
 * reach either. So the one touchless path is: droidtop downloads the official core from the libretro
 * buildbot over https, checks it, and a privileged helper running as root (Sui, through the
 * `priv.shell` provider; never `su` in droidtop's own process) copies it into the cores folder, owned
 * by RetroArch with the folder's own SELinux label. Without root, droidtop cannot see or place cores,
 * so it opens RetroArch for the person (its Core Downloader is one menu away). An existing core
 * is never overwritten.
 */
object RetroArchCores {
    const val DOWNLOAD_POST = "retroarch_core"
    private const val BUILDBOT = "https://buildbot.libretro.com/nightly/android/latest"
    private const val SUFFIX = "_libretro_android.so"
    private const val MAX_ZIP_BYTES = 200L * 1024 * 1024

    private const val ARG_PACKAGE = "retroarchPackage"
    private const val ARG_CORE = "core"
    private const val ARG_ABI = "abi"

    /** What droidtop knows about one core. [UNKNOWN]: no root helper, so RetroArch's private folder cannot be looked at. */
    enum class State { INSTALLED, MISSING, UNKNOWN }

    /** A RetroArch launch's package and the core file it loads. */
    data class Need(val packageName: String, val core: String, val corePath: String)

    // --- pure parts -------------------------------------------------------------------------------

    fun isRetroArch(packageName: String): Boolean = packageName in DefaultPlayers.RETROARCH_PACKAGE_VARIANTS

    /** The cores folder RetroArch derives from its data dir (platform_unix.c, DEFAULT_DIR_CORE). */
    fun coresDir(packageName: String): String = "/data/user/0/$packageName/cores"

    /**
     * The core a RetroArch launch [template] loads, when its LIBRETRO extra names a buildbot-named
     * file in that package's own cores folder (`/data/user/0/<pkg>/cores` or `/data/data/<pkg>/cores`).
     * Anything else (a custom path, no LIBRETRO) is not droidtop's to install.
     */
    fun needFor(packageName: String, template: String): Need? {
        if (!isRetroArch(packageName)) return null
        val tokens = runCatching { AmStartCommandToIntentConverter.tokenize(template, null, null) }.getOrNull() ?: return null
        for (i in 1 until tokens.size - 1) {
            if (tokens[i] != "LIBRETRO" || tokens[i - 1] !in setOf("-e", "--es")) continue
            val path = tokens[i + 1]
            val file = File(path)
            val parent = file.parent ?: return null
            if (parent != coresDir(packageName) && parent != "/data/data/$packageName/cores") return null
            val name = file.name
            if (!name.endsWith(SUFFIX)) return null
            val core = name.removeSuffix(SUFFIX)
            if (!core.matches(CORE_ID)) return null
            return Need(packageName, core, "${coresDir(packageName)}/$name")
        }
        return null
    }

    private val CORE_ID = Regex("[a-z0-9_]+")

    /** The official buildbot zip for [core] on [abi], https only. */
    fun buildbotUrl(abi: String, core: String): String {
        require(abi in ABIS) { "unknown ABI $abi" }
        require(core.matches(CORE_ID)) { "not a core id: $core" }
        return "$BUILDBOT/$abi/$core$SUFFIX.zip"
    }

    private val ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

    /**
     * The ABI RetroArch runs as, from its native library folder's last segment (`.../lib/arm64`),
     * which is what Android picked for it; [deviceAbis] in order when that says nothing.
     */
    fun abiFor(nativeLibraryDir: String?, deviceAbis: List<String>): String? {
        val fromDir = when (nativeLibraryDir?.substringAfterLast('/')) {
            "arm64" -> "arm64-v8a"
            "arm" -> "armeabi-v7a"
            "x86_64" -> "x86_64"
            "x86" -> "x86"
            else -> null
        }
        return fromDir ?: deviceAbis.firstOrNull { it in ABIS }
    }

    /** Whether [header] (the first 20 bytes of a file) is an ELF shared object built for [abi]. */
    fun elfMatches(header: ByteArray, abi: String): Boolean {
        if (header.size < 20) return false
        if (header[0] != 0x7F.toByte() || header[1] != 'E'.code.toByte() || header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte()) return false
        val is64 = header[4].toInt() == 2
        val machine = (header[18].toInt() and 0xFF) or ((header[19].toInt() and 0xFF) shl 8)
        return when (abi) {
            "arm64-v8a" -> is64 && machine == 183
            "x86_64" -> is64 && machine == 62
            "armeabi-v7a" -> !is64 && machine == 40
            "x86" -> !is64 && machine == 3
            else -> false
        }
    }

    /**
     * Unpacks the one `<core>_libretro_android.so` from a buildbot zip into [dest] and checks it is
     * an ELF for [abi]. Refuses a zip holding anything else, so a wrong or tampered archive never
     * reaches RetroArch.
     */
    fun extractCore(zip: File, core: String, abi: String, dest: File) {
        val wanted = core + SUFFIX
        var found = false
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                if (entry.isDirectory) continue
                require(entry.name == wanted && !found) { "the core archive holds an unexpected file" }
                dest.parentFile?.mkdirs()
                dest.outputStream().use { input.copyTo(it) }
                found = true
            }
        }
        require(found) { "the core archive is empty" }
        val header = ByteArray(20)
        val read = dest.inputStream().use { it.read(header) }
        if (read < 20 || !elfMatches(header, abi)) {
            dest.delete()
            throw IllegalStateException("the core is not built for $abi")
        }
    }

    /**
     * The root commands that place [source] as [target] in RetroArch's cores folder: a copy beside
     * it, RetroArch's owner and the folder's own SELinux label on it, then a no-clobber rename, so
     * a core RetroArch already has is never replaced and a half-copied file is never loaded.
     */
    fun placeCommands(source: String, target: String, uid: Int, label: String): List<List<String>> {
        val staged = "$target.droidtop-new"
        return listOf(
            listOf("cp", source, staged),
            listOf("chown", "$uid:$uid", staged),
            listOf("chmod", "600", staged),
            listOf("chcon", label, staged),
            listOf("mv", "-n", staged, target),
            listOf("rm", "-f", staged),
        )
    }

    // --- device parts ----------------------------------------------------------------------------

    /** The helper when it runs as root (Sui); null otherwise. Blocks on the provider: background only. */
    private fun rootShell(): PrivilegedShell? {
        val shell = TaskManager.shell
        if (!shell.capabilities().shell) return null
        val id = shell.exec(listOf("id", "-u")) ?: return null
        return shell.takeIf { id.exit == 0 && id.stdout.trim() == "0" }
    }

    /** Whether RetroArch has [need]'s core. Background only. */
    fun state(need: Need): State {
        val root = rootShell() ?: return State.UNKNOWN
        val out = root.exec(listOf("test", "-e", need.corePath)) ?: return State.UNKNOWN
        return if (out.exit == 0) State.INSTALLED else State.MISSING
    }

    /** The core [system]'s chosen RetroArch launch needs, if RetroArch is the chosen emulator. Background only. */
    fun needForSystem(context: Context, system: ConsoleSystemDef): Need? {
        val player = resolvePlayer(context, system) ?: return null
        return needFor(player.packageName, player.argumentsTemplate)
    }

    /**
     * Makes sure RetroArch has [need]'s core: nothing when it has it, a download-and-place job in
     * Downloads and installs when root can place it, and when it cannot, opens RetroArch and says
     * where its own Core Downloader is. Returns the line the caller shows.
     */
    suspend fun ensure(context: Context, need: Need, onStatus: (String) -> Unit = {}): Outcome {
        when (withContext(Dispatchers.IO) { state(need) }) {
            State.INSTALLED -> return Outcome.Ready
            State.MISSING -> Unit
            State.UNKNOWN -> {
                openRetroArch(context, need.packageName)
                return Outcome.Manual("Install the ${need.core} core in RetroArch: Online Updater > Core Downloader")
            }
        }
        val abi = withContext(Dispatchers.IO) {
            val info = runCatching { context.packageManager.getApplicationInfo(need.packageName, 0) }.getOrNull()
            abiFor(info?.nativeLibraryDir, android.os.Build.SUPPORTED_ABIS.toList())
        } ?: return Outcome.Failed("No core build for this device")
        val result: PluginResult = DownloadJobs.run(
            context,
            title = "RetroArch core ${need.core}",
            post = DOWNLOAD_POST,
            url = buildbotUrl(abi, need.core),
            name = "retroarch-${need.core}-$abi.zip",
            maxBytes = MAX_ZIP_BYTES,
            extra = mapOf(ARG_PACKAGE to need.packageName, ARG_CORE to need.core, ARG_ABI to abi),
            onStatus = onStatus,
        )
        return if (result.ok) Outcome.Ready else Outcome.Failed(result.error ?: "The core was not installed")
    }

    sealed interface Outcome {
        data object Ready : Outcome
        data class Manual(val line: String) : Outcome
        data class Failed(val line: String) : Outcome
    }

    /**
     * The cores of every system in [systems] whose chosen emulator is RetroArch: each missing one
     * installed as its own job. Without root, opens RetroArch and names the cores. Returns the line shown.
     */
    suspend fun ensureForLibrary(context: Context, systems: List<ConsoleSystemDef>, onStatus: (String) -> Unit = {}): String =
        withContext(Dispatchers.IO) {
            val needs = systems.mapNotNull { needForSystem(context, it) }.distinctBy { it.packageName to it.core }
            if (needs.isEmpty()) return@withContext "No system uses RetroArch"
            if (rootShell() == null) {
                openRetroArch(context, needs.first().packageName)
                return@withContext "Install in RetroArch's Core Downloader: " + needs.joinToString(", ") { it.core }
            }
            val missing = needs.filter { state(it) == State.MISSING }
            val failed = missing.filter { ensure(context, it, onStatus) !is Outcome.Ready }
            when {
                missing.isEmpty() -> "All ${needs.size} cores installed"
                failed.isEmpty() -> "Installed ${missing.size} of ${missing.size}"
                else -> "Not installed: " + failed.joinToString(", ") { it.core }
            }
        }

    private fun openRetroArch(context: Context, packageName: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** The post step: unpack, check, place as root. Registered at process start so a finished download is placed after a restart. */
    fun registerDownloadPost() {
        DownloadJobs.registerPost(DOWNLOAD_POST) { context, file, args ->
            val packageName = requireNotNull(args[ARG_PACKAGE]?.takeIf { isRetroArch(it) }) { "not a RetroArch package" }
            val core = requireNotNull(args[ARG_CORE]?.takeIf { it.matches(CORE_ID) }) { "no core named" }
            val abi = requireNotNull(args[ARG_ABI]) { "no ABI named" }
            withContext(Dispatchers.IO) {
                val so = File(context.filesDir, "retroarch-cores/$core$SUFFIX")
                try {
                    extractCore(file, core, abi, so)
                    val root = checkNotNull(rootShell()) { "root access is needed to place the core" }
                    val dir = coresDir(packageName)
                    val target = "$dir/$core$SUFFIX"
                    val label = root.exec(listOf("stat", "-c", "%C", dir))?.takeIf { it.exit == 0 }?.stdout?.trim()
                        ?: throw IllegalStateException("RetroArch's cores folder is not there; open RetroArch once")
                    if (root.exec(listOf("test", "-e", target))?.exit == 0) {
                        "$core is already installed"
                    } else {
                        val uid = context.packageManager.getApplicationInfo(packageName, 0).uid
                        for (argv in placeCommands(so.absolutePath, target, uid, label)) {
                            val out = root.exec(argv) ?: throw IllegalStateException("the root helper stopped")
                            if (out.exit != 0 && argv.first() != "rm") {
                                throw IllegalStateException("placing the core failed: ${out.stderr.trim().take(120)}")
                            }
                        }
                        check(root.exec(listOf("test", "-e", target))?.exit == 0) { "the core is not in place" }
                        "Installed $core"
                    }
                } finally {
                    so.delete()
                }
            }
        }
    }
}
