package dev.droidtop.runtime

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

    /** Whether the rootfs is unpacked and checked. */
    fun rootfsReady(filesDir: File): Boolean = File(rootfsDir(filesDir), READY).isFile

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
     * Checks the downloaded image against FEX's published [hash] (a 64-bit xxHash, which droidtop's download system does
     * not check), unpacks it beside the rootfs, puts it in place and marks it ready, then removes the image.
     */
    fun unpackScript(hash: String): String {
        val image = rootfsImageInContainer
        val dest = rootfsInContainer
        return """
            set -eu
            # FEX's list gives a 64-bit xxHash as a number (leading zeros dropped); XXH3 and XXH64 are both accepted.
            want="${hash.lowercase().trimStart('0')}"
            h3=${'$'}(xxhsum -H3 "$image" | cut -d' ' -f1 | sed 's/^XXH3_//; s/^0*//')
            h64=${'$'}(xxhsum -H1 "$image" | cut -d' ' -f1 | sed 's/^0*//')
            [ "${'$'}h3" = "${'$'}want" ] || [ "${'$'}h64" = "${'$'}want" ] || { echo "FEX's rootfs does not match its published checksum" >&2; exit 1; }
            rm -rf "$dest.unpacking"
            unsquashfs -q -n -d "$dest.unpacking" "$image"
            rm -rf "$dest"
            mv "$dest.unpacking" "$dest"
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
