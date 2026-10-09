package dev.droidtop.library.consoles

import android.content.Context
import android.content.Intent
import dev.droidtop.pluginhost.DownloadJobs
import dev.droidtop.pluginhost.PluginResult
import dev.droidtop.runtime.tasks.PrivilegedShell
import dev.droidtop.runtime.tasks.RiskyActions
import dev.droidtop.runtime.tasks.RiskyClass
import dev.droidtop.runtime.tasks.RiskyPrompts
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
 *
 * Placing a core is a risky action (docs/SPEC.md "Risky actions"): root-level commands, in another app's private
 * folder. It happens only with Settings > Risky actions > Root-level commands on, and only for a person who has just
 * confirmed it for this core ([ensure]'s `confirmed`); a launch never places one by itself.
 */
object RetroArchCores {
    const val DOWNLOAD_POST = "retroarch_core"
    private const val BUILDBOT = "https://buildbot.libretro.com/nightly/android/latest"
    private const val SUFFIX = "_libretro_android.so"
    private const val MAX_ZIP_BYTES = 200L * 1024 * 1024

    private const val ARG_PACKAGE = "retroarchPackage"
    private const val ARG_CORE = "core"
    private const val ARG_ABI = "abi"

    /**
     * What droidtop knows about one core. [PARTIAL]: the file is there but is not a whole ELF library (an interrupted
     * download), which RetroArch cannot load either. [UNKNOWN]: no root helper, so RetroArch's private folder cannot
     * be looked at.
     */
    enum class State { INSTALLED, MISSING, PARTIAL, UNKNOWN }

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
            return needForPath(packageName, tokens[i + 1])
        }
        return null
    }

    /** The same, from the LIBRETRO path itself (a built launch intent's extra). */
    fun needForPath(packageName: String, path: String): Need? {
        if (!isRetroArch(packageName)) return null
        val file = File(path)
        val parent = file.parent ?: return null
        if (parent != coresDir(packageName) && parent != "/data/data/$packageName/cores") return null
        val name = file.name
        if (!name.endsWith(SUFFIX)) return null
        val core = name.removeSuffix(SUFFIX)
        if (!core.matches(CORE_ID)) return null
        return Need(packageName, core, "${coresDir(packageName)}/$name")
    }

    /**
     * The sentence the launch watchdog adds when a RetroArch launch is stuck: RetroArch shows no
     * error for a missing core, it sits black and stops answering (console, build 1386: a Game Boy
     * Color game with gambatte, which RetroArch never loaded, while mGBA ran through the same
     * launch). [suspect] decides whether it applies. Pure.
     */
    fun troubleHint(need: Need): String =
        "RetroArch shows a black screen when the core it is given is not installed or is incomplete: " +
            "install ${need.core} in RetroArch (Online Updater > Core Downloader), or choose Get the core."

    /** The launch-failure sentence for a core droidtop saw is [State.MISSING] or [State.PARTIAL]. Pure. */
    fun missingMessage(need: Need, state: State): String =
        if (state == State.PARTIAL) {
            "The ${need.core} core in RetroArch is incomplete, so RetroArch would show a black screen. " +
                "Install it again in RetroArch (Online Updater > Core Downloader), or choose Get the core."
        } else {
            "RetroArch does not have the ${need.core} core this game needs, so it would show a black screen. " +
                "Choose Get the core, or install it in RetroArch (Online Updater > Core Downloader)."
        }

    /**
     * Whether [header] (at least the first 64 bytes) and [size] make a whole ELF file: the magic, and a section header
     * table that ends inside the file (ELF64 e_shoff at 0x28, e_shentsize 0x3A, e_shnum 0x3C; ELF32 at 0x20, 0x2E,
     * 0x30). An interrupted copy keeps the header and loses the table at the end. Pure.
     */
    fun elfWhole(header: ByteArray, size: Long): Boolean {
        if (header.size < 52 || header[0] != 0x7F.toByte() || header[1] != 'E'.code.toByte() ||
            header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte()
        ) return false
        fun le(offset: Int, bytes: Int): Long = (0 until bytes).fold(0L) { acc, i -> acc or ((header[offset + i].toLong() and 0xFF) shl (8 * i)) }
        val (shoff, entsize, count) = when (header[4].toInt()) {
            2 -> if (header.size < 64) return false else Triple(le(0x28, 8), le(0x3A, 2), le(0x3C, 2))
            1 -> Triple(le(0x20, 4), le(0x2E, 2), le(0x30, 2))
            else -> return false
        }
        return shoff == 0L || size >= shoff + entsize * count
    }

    /**
     * The core a stuck RetroArch launch may be missing: [corePath]'s core unless the root helper
     * confirmed it is installed. Background only (it may ask the root helper).
     */
    fun suspect(packageName: String, corePath: String): Need? {
        val need = needForPath(packageName, corePath) ?: return null
        return need.takeIf { state(it) != State.INSTALLED }
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

    /**
     * The helper when it runs as root (Sui) and Root-level commands is on in Settings > Risky actions; null otherwise,
     * and then nothing is asked of it, not even to look. Blocks on the provider: background only.
     */
    private fun rootShell(): PrivilegedShell? {
        if (!RiskyActions.allows(RiskyClass.ROOT_COMMANDS)) return null
        val shell = TaskManager.shell
        if (!shell.capabilities().shell) return null
        val id = shell.exec(listOf("id", "-u")) ?: return null
        return shell.takeIf { id.exit == 0 && id.stdout.trim() == "0" }
    }

    /** Whether the switch is on and the root helper is there: whether a confirmed press can place a core. Background only. */
    fun canPlace(): Boolean = rootShell() != null

    /** Whether RetroArch has [need]'s core, and whole ([elfWhole]). Background only. */
    fun state(need: Need): State {
        val root = rootShell() ?: return State.UNKNOWN
        val out = root.exec(listOf("test", "-e", need.corePath)) ?: return State.UNKNOWN
        if (out.exit != 0) return State.MISSING
        val size = root.exec(listOf("stat", "-c", "%s", need.corePath))?.takeIf { it.exit == 0 }?.stdout?.trim()?.toLongOrNull()
        val hex = root.exec(listOf("od", "-An", "-tx1", "-N64", need.corePath))?.takeIf { it.exit == 0 }?.stdout
        val header = hex?.split(' ', '\n')?.filter { it.isNotBlank() }?.mapNotNull { it.toIntOrNull(16)?.toByte() }?.toByteArray()
        // A read the helper could not answer says nothing against the file.
        if (size == null || header == null) return State.INSTALLED
        return if (elfWhole(header, size)) State.INSTALLED else State.PARTIAL
    }

    /** The core [system]'s chosen RetroArch launch needs, if RetroArch is the chosen emulator. Background only. */
    fun needForSystem(context: Context, system: ConsoleSystemDef): Need? {
        val player = resolvePlayer(context, system) ?: return null
        return needFor(player.packageName, player.argumentsTemplate)
    }

    /**
     * Makes sure RetroArch has [need]'s core: nothing when it has it, a download-and-place job in
     * Downloads and installs when root can place it and the person has [confirmed] it with Root-level
     * commands switched on, and otherwise opens RetroArch and says where its own Core Downloader is (or
     * which switch lets droidtop place it). RetroArch is opened only when the person asked ([asked], or [confirmed]
     * which implies it): a launch passes neither and must never put RetroArch in front. Returns the line the caller shows.
     */
    suspend fun ensure(
        context: Context,
        need: Need,
        confirmed: Boolean = false,
        asked: Boolean = confirmed,
        onStatus: (String) -> Unit = {},
    ): Outcome {
        when (withContext(Dispatchers.IO) { state(need) }) {
            State.INSTALLED -> return Outcome.Ready
            State.PARTIAL -> {
                // droidtop never replaces a core file RetroArch has; RetroArch's own downloader does.
                if (asked) openRetroArch(context, need.packageName)
                return Outcome.Manual("Install the ${need.core} core again in RetroArch: Online Updater > Core Downloader.")
            }
            State.MISSING -> {
                if (!confirmed) {
                    if (asked) openRetroArch(context, need.packageName)
                    return Outcome.Manual(
                        "Install the ${need.core} core in RetroArch: Online Updater > Core Downloader, or press Install core on the system's emulator screen in Settings.",
                    )
                }
            }
            State.UNKNOWN -> {
                openRetroArch(context, need.packageName)
                return Outcome.Manual(
                    "Install the ${need.core} core in RetroArch: Online Updater > Core Downloader" +
                        if (RiskyActions.allows(RiskyClass.ROOT_COMMANDS)) "" else ". " + RiskyPrompts.turnOnHint(RiskyClass.ROOT_COMMANDS),
                )
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

    /**
     * A launch refused before it started because droidtop saw RetroArch lacks [need]'s core, or has it incomplete
     * ([State.MISSING], [State.PARTIAL]): the shell offers Get the core at the point of failure.
     */
    class Missing(val need: Need, message: String) : IllegalStateException(message)

    sealed interface Outcome {
        data object Ready : Outcome
        data class Manual(val line: String) : Outcome
        data class Failed(val line: String) : Outcome
    }

    /**
     * The cores of every system in [systems] whose chosen emulator is RetroArch: each missing one
     * installed as its own job, the person having confirmed the batch. Without root, or with Root-level commands
     * off, opens RetroArch and names the cores. Returns the line shown.
     */
    suspend fun ensureForLibrary(context: Context, systems: List<ConsoleSystemDef>, onStatus: (String) -> Unit = {}): String =
        withContext(Dispatchers.IO) {
            val needs = systems.mapNotNull { needForSystem(context, it) }.distinctBy { it.packageName to it.core }
            if (needs.isEmpty()) return@withContext "No system uses RetroArch"
            if (!canPlace()) {
                openRetroArch(context, needs.first().packageName)
                return@withContext "Install in RetroArch's Core Downloader: " + needs.joinToString(", ") { it.core } +
                    if (RiskyActions.allows(RiskyClass.ROOT_COMMANDS)) "" else ". " + RiskyPrompts.turnOnHint(RiskyClass.ROOT_COMMANDS)
            }
            val missing = needs.filter { state(it) == State.MISSING }
            val failed = missing.filter { ensure(context, it, confirmed = true, onStatus = onStatus) !is Outcome.Ready }
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
                    check(RiskyActions.allows(RiskyClass.ROOT_COMMANDS)) { "Root-level commands are off in Settings > Risky actions" }
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
