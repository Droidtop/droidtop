package dev.droidtop.library.consoles

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Environment
import android.provider.MediaStore
import dev.droidtop.pluginhost.PluginCapability
import dev.droidtop.pluginhost.PluginJobsCenter
import dev.droidtop.pluginhost.PluginStore
import dev.droidtop.runtime.tasks.PrivilegedShell
import dev.droidtop.runtime.tasks.RiskyActions
import dev.droidtop.runtime.tasks.RiskyClass
import dev.droidtop.runtime.tasks.TaskManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Getting the libretro core a RetroArch launch needs into RetroArch (docs/SPEC.md 7e2c, Droidtop/tracker#271).
 *
 * One source of truth (coordinator, 2026-10-15): the RetroArch package is the one droidtop's preset launches, and
 * the core is fetched by the RetroArch manager plugin (`droidtop.retroarch`), whose `download_core` job droidtop
 * runs with that package. droidtop's own buildbot downloader, which placed cores as root, is gone. The manager
 * downloads the official core into its own data and, where a root-level helper is allowed, places it in RetroArch's
 * cores folder itself.
 *
 * Without root (the console: Shizuku runs as the shell user, which cannot write `/data/user/0/<package>/cores`,
 * RetroArch's private data), RetroArch installs the core itself: its exported `CoreSideloadActivity` (RetroArch
 * 1.19.1 to 1.22.0, pkg/android/phoenix) copies the file its `LIBRETRO` extra names into its own cores folder, as
 * RetroArch, then starts RetroArch with it. RetroArch has to be able to read that file: droidtop puts a copy in the
 * shared Download folder, which the buildbot builds read (target SDK 28, legacy storage, once RetroArch has its
 * storage permission) and the Play Store builds do not (target SDK 35, scoped storage). For those the person installs
 * the core in RetroArch's own Core Downloader. The installed-cores broadcast on RetroArch's master branch is not in
 * any release yet, so droidtop does not rely on it.
 *
 * Looking into RetroArch's cores folder ([state]) needs root and Settings > Risky actions > Root-level commands; the
 * state is [State.UNKNOWN] otherwise. An existing core is never overwritten by droidtop.
 */
object RetroArchCores {
    private const val SUFFIX = "_libretro_android.so"

    /** The RetroArch manager plugin, which fetches cores (and places them where it may run root-level commands). */
    const val MANAGER_ID = "droidtop.retroarch"

    /** Its job that fetches one core: args `core` and `playerPackage`, result `installedIntoRetroArch` and `file`. */
    private const val JOB_DOWNLOAD_CORE = "download_core"

    /** RetroArch's exported sideload screen (pkg/android/phoenix/AndroidManifest.xml, 1.19.1 to 1.22.0). */
    const val SIDELOAD_ACTIVITY = "com.retroarch.browser.debug.CoreSideloadActivity"

    /** The folder under the shared Download folder where droidtop leaves a core for RetroArch to sideload. */
    const val SHARED_DIR = "droidtop-cores"

    /** The highest target SDK at which an app still reads any file in shared storage with its storage permission. */
    private const val LEGACY_STORAGE_MAX_TARGET_SDK = 29

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
        "RetroArch shows a black screen when the core it is given is not installed or is incomplete. " +
            "This game needs ${checked(need)}: choose Get the core, or install it in RetroArch (Online Updater > Core Downloader)."

    /** The launch-failure sentence for a core droidtop saw is [State.MISSING] or [State.PARTIAL]. Pure. */
    fun missingMessage(need: Need, state: State): String =
        if (state == State.PARTIAL) {
            "The ${need.core} core in RetroArch is incomplete, so RetroArch would show a black screen (${checked(need)}). " +
                "Install it again in RetroArch (Online Updater > Core Downloader)."
        } else {
            "RetroArch does not have the core this game needs, so it would show a black screen (${checked(need)}). " +
                "Choose Get the core, or install it in RetroArch (Online Updater > Core Downloader)."
        }

    /** What droidtop looks for, named whole so a mismatch shows: the core, the RetroArch package and the file. Pure. */
    fun checked(need: Need): String = "${need.core} in ${need.packageName}, ${need.corePath}"

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

    /** Whether RetroArch [packageName] can read a file droidtop leaves in shared storage: a legacy-storage build with its storage permission. Pure. */
    fun canSideload(targetSdk: Int, storageGranted: Boolean): Boolean =
        targetSdk <= LEGACY_STORAGE_MAX_TARGET_SDK && storageGranted

    /** The path RetroArch is handed for [need]'s core under [downloadsDir] (the shared Download folder). Pure. */
    fun sharedCorePath(downloadsDir: String, need: Need): String = "$downloadsDir/$SHARED_DIR/${need.core}$SUFFIX"

    /** The intent that has RetroArch [need] copy [path] into its own cores folder and start with it. Pure apart from Intent. */
    fun sideloadIntent(need: Need, path: String): Intent =
        Intent().setClassName(need.packageName, SIDELOAD_ACTIVITY)
            .putExtra("LIBRETRO", path)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Makes sure RetroArch has [need]'s core: nothing when droidtop sees it is there; otherwise the RetroArch manager's
     * download_core job for it, in [need]'s package, which places it where it may; otherwise RetroArch's own sideload of
     * the downloaded file, when RetroArch can read it; otherwise the line saying where RetroArch's Core Downloader is.
     * RetroArch is opened only when the person asked ([asked]); a launch never puts it in front. Returns what to show.
     */
    suspend fun ensure(context: Context, need: Need, asked: Boolean = false, onStatus: (String) -> Unit = {}): Outcome {
        val state = withContext(Dispatchers.IO) { state(need) }
        if (state == State.INSTALLED) return Outcome.Ready
        if (state == State.PARTIAL) {
            // droidtop never replaces a core file RetroArch has; RetroArch's own downloader does.
            if (asked) openRetroArch(context, need.packageName)
            return Outcome.Manual("Install the ${need.core} core again in RetroArch: Online Updater > Core Downloader.")
        }
        val record = withContext(Dispatchers.IO) { PluginStore.installed(context).firstOrNull { it.manifest.id == MANAGER_ID } }
            ?: return manual(context, need, asked, "Add the RetroArch manager in Plugins to get cores, or install ${need.core} in RetroArch")
        val jobId = PluginJobsCenter.start(
            context = context,
            record = record,
            capability = PluginCapability.APP_STATUS,
            args = mapOf("job" to JOB_DOWNLOAD_CORE, "core" to need.core, "playerPackage" to need.packageName),
            title = "$JOB_DOWNLOAD_CORE (${need.core})",
            onProgress = { _, line -> onStatus(line) },
            userInitiated = true,
        ) ?: return manual(context, need, asked, "The RetroArch manager is not approved, so it cannot get cores: approve it in Plugins, or install ${need.core} in RetroArch")
        val result = PluginJobsCenter.await(jobId) ?: return Outcome.Failed("The core download did not finish")
        if (!result.ok) return Outcome.Failed(result.error ?: "The RetroArch manager could not get ${need.core}")
        if (result.values["installedIntoRetroArch"] == "true") return Outcome.Ready
        val downloaded = withContext(Dispatchers.IO) { result.values["file"]?.let { managerFile(PluginStore.dataDirFor(context, MANAGER_ID), it) } }
            ?: return manual(context, need, asked, "The RetroArch manager downloaded ${need.core} but did not say where; install it in RetroArch")
        return sideload(context, need, downloaded, asked)
    }

    /**
     * The file the manager names by [name] in its own data folder [dataDir], when it is one there: a name that leads out
     * of the folder (`..`, an absolute path, a link) is not followed. Background only.
     */
    internal fun managerFile(dataDir: File, name: String): File? {
        val root = dataDir.canonicalFile
        val file = File(root, name).canonicalFile
        return file.takeIf { it.path.startsWith(root.path + File.separator) && it.isFile }
    }

    /**
     * Hands [file] to RetroArch's sideload screen through a copy in the shared Download folder, when RetroArch can read
     * it there ([canSideload]); the Core Downloader line otherwise.
     */
    private suspend fun sideload(context: Context, need: Need, file: File, asked: Boolean): Outcome {
        val readable = withContext(Dispatchers.IO) {
            val info = runCatching { context.packageManager.getApplicationInfo(need.packageName, 0) }.getOrNull()
            val granted = context.packageManager.checkPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE, need.packageName) ==
                PackageManager.PERMISSION_GRANTED
            info != null && canSideload(info.targetSdkVersion, granted)
        }
        // The copy goes in through MediaStore's Downloads collection, which is Android 10 and up.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            return manual(context, need, asked, "droidtop can hand RetroArch a core from Android 10 on, so install ${need.core} in RetroArch")
        }
        if (!readable) {
            return manual(
                context,
                need,
                asked,
                "This RetroArch cannot read a core droidtop downloads (a Play Store build, or it has no storage access), so install ${need.core} in RetroArch",
            )
        }
        val path = withContext(Dispatchers.IO) { runCatching { shareCopy(context, need, file) }.getOrNull() }
            ?: return Outcome.Failed("Could not put ${need.core} in the Download folder for RetroArch")
        return runCatching { context.startActivity(sideloadIntent(need, path)) }.fold(
            onSuccess = { Outcome.Manual("RetroArch is installing ${need.core} and opens with it. Start the game again from droidtop.") },
            onFailure = { manual(context, need, asked, "This RetroArch has no sideload screen, so install ${need.core} in RetroArch") },
        )
    }

    /**
     * [file] copied to `Download/[SHARED_DIR]/<core>_libretro_android.so` through MediaStore (no storage permission for
     * droidtop), an earlier copy of the same name replaced; the absolute path RetroArch reads. Background only.
     */
    private fun shareCopy(context: Context, need: Need, file: File): String {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) error("MediaStore's Downloads collection needs Android 10")
        val name = "${need.core}$SUFFIX"
        val relative = "${Environment.DIRECTORY_DOWNLOADS}/$SHARED_DIR/"
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        resolver.delete(
            collection,
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(name, relative),
        )
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(collection, values)) { "the Download folder refused the file" }
        resolver.openOutputStream(uri).use { out -> file.inputStream().use { it.copyTo(checkNotNull(out)) } }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        @Suppress("DEPRECATION")
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
        return sharedCorePath(downloads, need)
    }

    /** RetroArch's Core Downloader as the way on, after [why]; RetroArch opened when the person asked. */
    private fun manual(context: Context, need: Need, asked: Boolean, why: String): Outcome {
        if (asked) openRetroArch(context, need.packageName)
        return Outcome.Manual("$why: Online Updater > Core Downloader.")
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
     * The cores of every system in [systems] whose chosen emulator is RetroArch. Where droidtop can see RetroArch's
     * cores (root), each missing one is fetched through the manager in turn; otherwise RetroArch is opened and the cores
     * are named, since one sideload screen at a time is a game's own Get the core. Returns the line shown.
     */
    suspend fun ensureForLibrary(context: Context, systems: List<ConsoleSystemDef>, onStatus: (String) -> Unit = {}): String {
        val needs = withContext(Dispatchers.IO) { systems.mapNotNull { needForSystem(context, it) }.distinctBy { it.packageName to it.core } }
        if (needs.isEmpty()) return "No system uses RetroArch"
        val states = withContext(Dispatchers.IO) { needs.associateWith { state(it) } }
        if (states.values.any { it == State.UNKNOWN }) {
            openRetroArch(context, needs.first().packageName)
            return "Install in RetroArch's Core Downloader: " + needs.joinToString(", ") { it.core }
        }
        val missing = needs.filter { states[it] == State.MISSING }
        val failed = missing.filter { ensure(context, it, onStatus = onStatus) !is Outcome.Ready }
        return when {
            missing.isEmpty() -> "All ${needs.size} cores installed"
            failed.isEmpty() -> "Installed ${missing.size} of ${missing.size}"
            else -> "Not installed: " + failed.joinToString(", ") { it.core }
        }
    }

    private fun openRetroArch(context: Context, packageName: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
