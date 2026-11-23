package dev.droidtop.runtime

import dev.droidtop.shared.containers.ContainerArch
import dev.droidtop.shared.containers.ContainerPlatform
import dev.droidtop.shared.containers.Translator
import dev.droidtop.shared.runners.TranslatedCommand
import java.io.File
import java.util.Properties
import org.json.JSONObject

/**
 * Where native Linux games run, and the FEX that runs their x86 programs on an arm64 device (docs/SPEC.md 3c,
 * Droidtop/tracker#962).
 *
 * Desktop mode's primary container is Alpine (musl), and FEX needs a glibc host, so a Linux game runs in a sibling
 * container made from [IMAGE]. That is either the one shared "Linux games" container or, when the game's settings
 * say so, a container of the game's own, made the first time the game runs (owner, 2026-11-22: "per-game containers,
 * just like wineprefixes"). Siblings share the desktop's display and storage, so a game's folder is where it was.
 *
 * FEX goes into a container through its own Ubuntu packages ([installScript]). Its x86 rootfs, FEX's published Ubuntu
 * image ([ROOTFS_LIST]), is downloaded once through droidtop's download system, unpacked inside a container (Android
 * has no unsquashfs) into app storage, and used by every Linux games container through `FEX_ROOTFS`, since app storage
 * is bound into each of them at [ContainerLayout.APP_STORAGE_DIR].
 */
object LinuxGames {
    /** The image a Linux games container is made from: glibc, the distro FEX's packages and rootfs are for. */
    val IMAGE = RootfsImage("docker.io/library/ubuntu:24.04")

    /** The shared container's name, as the person sees it under Containers. */
    const val SHARED_NAME = "Linux games"

    /** FEX's list of its published rootfs images (what its own FEXRootFSFetcher reads). */
    const val ROOTFS_LIST = "https://rootfs.fex-emu.gg/RootFS_links.json"

    /** The rootfs distro matching [IMAGE]. */
    private const val ROOTFS_DISTRO = "ubuntu"
    private const val ROOTFS_VERSION = "24.04"
    private const val ROOTFS_FOLDER = "Ubuntu_24_04"

    /** The signing key of FEX's Ubuntu packages (launchpad.net/~fex-emu/+archive/ubuntu/fex, its signing_key_fingerprint). */
    private const val FEX_KEY = "EDB98BFE8A2310DC9C4A376E76DBFEBEA206F5AC"

    private const val READY = ".droidtop-ready"
    private const val RECORDS = "linux-game-containers.properties"
    private const val SHARED_KEY = "shared"

    /** The unpacked rootfs in app storage. */
    fun rootfsDir(filesDir: File): File = File(filesDir, "fex-rootfs/$ROOTFS_FOLDER")

    /** The downloaded image before it is unpacked, beside [rootfsDir]; removed once unpacked. */
    fun rootfsImage(filesDir: File): File = File(filesDir, "fex-rootfs/$ROOTFS_FOLDER.sqsh")

    /** [rootfsDir] as every Linux games container sees it. */
    val rootfsInContainer: String = "${ContainerLayout.APP_STORAGE_DIR}/fex-rootfs/$ROOTFS_FOLDER"

    /** [rootfsImage] as a container sees it. */
    val rootfsImageInContainer: String = "${ContainerLayout.APP_STORAGE_DIR}/fex-rootfs/$ROOTFS_FOLDER.sqsh"

    /**
     * The x86-64 loader inside the rootfs, which FEX needs for every program: without it FEX says "Invalid or
     * Unsupported elf file … misconfigured x86-64 RootFS" (console, 2026-11-23, a rootfs two overlapping setups left
     * at 159 MB with the ready mark on it).
     */
    const val ROOTFS_LOADER = "usr/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2"

    /** Whether the rootfs is unpacked and checked: its ready mark, and the loader that proves it whole. */
    fun rootfsReady(filesDir: File): Boolean =
        File(rootfsDir(filesDir), READY).isFile && File(rootfsDir(filesDir), ROOTFS_LOADER).isFile

    /** The variables every FEX run needs: where its x86 rootfs is. */
    fun fexEnvironment(): Map<String, String> = mapOf("FEX_ROOTFS" to rootfsInContainer)

    /** The record key of [entryId]'s container: its own when [own], else the shared one. */
    fun key(entryId: String?, own: Boolean): String = if (own && entryId != null) "game.$entryId" else SHARED_KEY

    /** The name a game's own container gets: what the person reads under Containers. */
    fun ownName(gameTitle: String?, entryId: String): String = "Linux: ${gameTitle?.takeIf { it.isNotBlank() } ?: entryId}"

    /** The container recorded for [key], when there is one. */
    fun recordedId(filesDir: File, key: String): String? = records(filesDir).getProperty("container.$key")

    /** Every container recorded as a Linux games container. */
    fun recordedIds(filesDir: File): Set<String> = records(filesDir).let { props ->
        props.stringPropertyNames().filter { it.startsWith("container.") }.mapNotNull { props.getProperty(it) }.toSet()
    }

    /** The FEX launcher found in container [containerId] after FEX was installed there, when it was. */
    fun recordedLauncher(filesDir: File, containerId: String): String? = records(filesDir).getProperty("fex.$containerId")

    /**
     * The architecture and translator chosen for the container recorded under [key] (a game's own or the shared one),
     * read with [ContainerPlatform.decode]: [host]'s own and the default translator until the person chooses. Kept by
     * key, not by container id, so the choice can be made before the container exists (it is made on first run).
     */
    fun platform(filesDir: File, key: String, host: ContainerArch): ContainerPlatform =
        records(filesDir).getProperty("platform.$key")?.let { ContainerPlatform.decode(it, host) } ?: ContainerPlatform.native(host)

    /** Keeps [platform] for [key] through [ContainerPlatform.encode], with a translator that covers its architecture. */
    fun recordPlatform(filesDir: File, key: String, platform: ContainerPlatform, host: ContainerArch) {
        val translator = platform.translator.takeIf { it in translatorChoices(platform.arch, host) } ?: Translator.DEFAULT
        update(filesDir) { it.setProperty("platform.$key", platform.copy(translator = translator).encode()) }
    }

    /**
     * What a person may pick as the translator for a container of [arch] on [host]: none where nothing is translated
     * (a host that is not arm64). An arm64 container's translated programs are x86_64 builds, so it offers what covers
     * those; an x86_64 container offers the same; an x86 (32-bit) one only FEX, since Box64 is 64-bit only.
     */
    fun translatorChoices(arch: ContainerArch, host: ContainerArch): List<Translator> {
        if (host != ContainerArch.ARM64) return emptyList()
        val program = if (arch == ContainerArch.ARM64) ContainerArch.X86_64 else arch
        return Translator.entries.filter { it.covers(program) }
    }

    /** The architectures offered for a container on [host]: every one only where something is translated. */
    fun architectureChoices(host: ContainerArch): List<ContainerArch> =
        if (host == ContainerArch.ARM64) ContainerArch.entries.toList() else emptyList()

    /** The launcher that runs a program through [translator]: [fexLauncher] for FEX, Box64's own name for Box64. */
    fun launcherFor(translator: Translator, fexLauncher: String?): String? = when (translator) {
        Translator.FEX -> fexLauncher
        Translator.BOX64 -> TranslatedCommand.launchers(translator).first()
    }

    fun recordContainer(filesDir: File, key: String, containerId: String) = update(filesDir) { it.setProperty("container.$key", containerId) }

    fun recordLauncher(filesDir: File, containerId: String, launcher: String) = update(filesDir) { it.setProperty("fex.$containerId", launcher) }

    /**
     * Whether a Linux game with [key] is ready to run x86 programs: its container exists, FEX is in it, and the rootfs
     * is unpacked. Reads two small files; off the main thread.
     */
    fun translationReady(filesDir: File, key: String): Boolean {
        val id = recordedId(filesDir, key) ?: return false
        return recordedLauncher(filesDir, id) != null && rootfsReady(filesDir)
    }

    /**
     * The container for [key], made from [IMAGE] when there is none yet (or the recorded one is gone), named [name].
     * Pulling the image is the slow part; call it from a setup step that says so.
     */
    suspend fun container(runtime: ContainerRuntime, filesDir: File, key: String, name: String): Container {
        val known = runtime.listContainers()
        recordedId(filesDir, key)?.let { id -> known.firstOrNull { it.container.id == id }?.let { return it.container } }
        val made = runtime.createSibling(IMAGE, name)
        recordContainer(filesDir, key, made.id)
        return made
    }

    /**
     * Installs FEX's packages, run as the container's root: the tools the rootfs step needs (unsquashfs, xxhsum), FEX's
     * repository with its signing key, and the FEX build for the CPU (armv8.0, 8.2 or 8.4, from the CPU's features;
     * armv8.0 runs on every arm64 CPU when the features cannot be read).
     */
    val installScript: String = """
        set -eu
        export DEBIAN_FRONTEND=noninteractive
        apt-get update -q
        apt-get install -y -q --no-install-recommends ca-certificates curl gnupg squashfs-tools xxhash
        curl -fsSL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x$FEX_KEY" | gpg --batch --yes --dearmor -o /usr/share/keyrings/fex-emu.gpg
        echo "deb [signed-by=/usr/share/keyrings/fex-emu.gpg] https://ppa.launchpadcontent.net/fex-emu/fex/ubuntu noble main" > /etc/apt/sources.list.d/fex-emu.list
        apt-get update -q
        level=armv8.0
        flags=${'$'}(grep -m1 -i '^Features' /proc/cpuinfo || true)
        case "${'$'}flags" in *atomics*) case "${'$'}flags" in *asimdrdm*) level=armv8.2 ;; esac ;; esac
        if [ "${'$'}level" = armv8.2 ]; then case "${'$'}flags" in *ilrcpc*) case "${'$'}flags" in *flagm*) level=armv8.4 ;; esac ;; esac; fi
        apt-get install -y -q fex-emu-${'$'}level
    """.trimIndent()

    /** Prints the FEX launcher's name: `FEX` from FEX 2509 on, `FEXInterpreter` before. Empty when FEX is not there. */
    const val FIND_LAUNCHER = "for c in FEX FEXInterpreter; do if command -v \"\$c\" >/dev/null 2>&1; then echo \"\$c\"; exit 0; fi; done; exit 1"

    /**
     * A sed program that turns one line of `xxhsum -H3` output into the bare hash without leading zeros. xxhsum 0.8.2
     * (Ubuntu 24.04) prints XXH3 in BSD style, `XXH3 (file) = 3517e0e5ea25a473`, where older ones print
     * `XXH3_3517e0e5ea25a473  file`; taking the first field read "XXH3" and refused every image (console, 2026-11-22).
     */
    const val XXH3_PARSE = "s/^.*= //; s/^XXH3_//; s/[[:space:]].*\$//; s/^0*//"

    /**
     * Checks the downloaded image against FEX's published [hash] (its XXH3, which droidtop's download system does
     * not check), unpacks it into a folder of this run's own beside the rootfs, checks the loader is there, puts it in
     * place and marks it ready, then removes the image. A run's own folder (`.unpacking.<pid>`), not a shared one: a
     * second run used to clear the folder the first was still unpacking into, and the first then put the remains in
     * place as ready.
     */
    fun unpackScript(hash: String): String {
        val image = rootfsImageInContainer
        val dest = rootfsInContainer
        return """
            set -eu
            # FEX's list gives the image's XXH3 (64-bit) as a number, leading zeros dropped.
            want="${hash.lowercase().trimStart('0')}"
            got=${'$'}(xxhsum -H3 "$image" | sed -E '$XXH3_PARSE')
            [ "${'$'}got" = "${'$'}want" ] || { echo "FEX's rootfs does not match its published checksum (${'$'}got)" >&2; exit 1; }
            tmp="$dest.unpacking.${'$'}${'$'}"
            trap 'rm -rf "${'$'}tmp"' EXIT
            unsquashfs -q -n -d "${'$'}tmp" "$image"
            [ -f "${'$'}tmp/$ROOTFS_LOADER" ] || { echo "FEX's rootfs unpacked without its x86-64 loader" >&2; exit 1; }
            rm -rf "$dest"
            mv "${'$'}tmp" "$dest"
            touch "$dest/$READY"
            rm -f "$image"
        """.trimIndent()
    }

    /** The rootfs image's address and xxHash64 from FEX's list ([ROOTFS_LIST]), or null when the list has none. */
    fun rootfsFromList(json: String): Pair<String, String>? {
        val entries = JSONObject(json).optJSONObject("v1") ?: return null
        for (name in entries.keys()) {
            val entry = entries.optJSONObject(name) ?: continue
            if (entry.optString("DistroMatch") == ROOTFS_DISTRO && entry.optString("DistroVersion") == ROOTFS_VERSION &&
                entry.optString("Type") == "squashfs"
            ) {
                val url = entry.optString("URL").takeIf { it.startsWith("https://") } ?: continue
                val hash = entry.optString("Hash").takeIf { it.matches(Regex("[0-9a-fA-F]{1,16}")) } ?: continue
                return url to hash
            }
        }
        return null
    }

    private fun records(filesDir: File): Properties = Properties().also { props ->
        File(filesDir, RECORDS).takeIf { it.isFile }?.inputStream()?.use { props.load(it) }
    }

    @Synchronized
    private fun update(filesDir: File, change: (Properties) -> Unit) {
        val props = records(filesDir)
        change(props)
        val file = File(filesDir, RECORDS)
        val temp = File(filesDir, "$RECORDS.tmp")
        temp.outputStream().use { props.store(it, "Linux games containers (docs/SPEC.md 3c)") }
        check(temp.renameTo(file)) { "couldn't save ${file.name}" }
    }
}
