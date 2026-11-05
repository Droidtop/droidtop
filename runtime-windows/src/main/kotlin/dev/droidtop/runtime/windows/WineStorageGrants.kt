package dev.droidtop.runtime.windows

import android.content.Context
import com.winlator.container.Container
import com.winlator.xenvironment.ImageFs
import dev.droidtop.runtime.SharedVolume
import dev.droidtop.runtime.WideAccess
import java.io.File

/**
 * The two wide grants of a Windows game (docs/SPEC.md 5a, "What a game can reach", 4; Droidtop/tracker#584), off
 * for every game and prefix until the person turns one on (owner, 2026-10-17: "Let's gate the root and internal
 * storage maps behind a permission toggle. That way users can enable it if something fucky depends on it, but it's
 * not normally there"):
 *
 * - [DEVICE_SYSTEM], "Device system folders": Wine's Z: points at the device's own root `/` instead of the image's.
 * - [ALL_STORAGE], "All internal storage": every mounted volume gets a drive letter, and a drive whose folder is a
 *   volume root (or above one) is linked at all.
 *
 * A game's own grant travels as a launch override ([Container.LAUNCH_OVERRIDE_KEYS]); a prefix's is the prefix's
 * extra of the same name and covers every game in it. Either one turns the grant on. What is "wide" is
 * [WideAccess.classify]. Enforcement by the sandbox broker (the decider consulting these) is the broker's own piece.
 */
object WineStorageGrants {
    const val DEVICE_SYSTEM = "droidtopDeviceSystem"
    const val ALL_STORAGE = "droidtopAllStorage"

    /**
     * The launching game's own folder, as a launch override: always reachable (SPEC 5a's list), so it gets a drive of
     * its own when the drive that would have held it (a whole-volume games root) is not linked.
     */
    const val GAME_FOLDER = "droidtopGameFolder"

    /** The line under both toggles: the owner's own words. */
    const val LINE = "Lets this game see and change everything on the device's storage"

    /** Whether [key] is granted for this launch: the game's own override, or its prefix's extra. */
    @JvmStatic
    fun granted(container: Container, key: String): Boolean =
        container.launchOverrides[key] == "1" || container.getExtra(key) == "1"

    /** What Wine's Z: points at: `/` with [DEVICE_SYSTEM], else the image's root (the runtime's own files). */
    @JvmStatic
    fun zTarget(context: Context, container: Container): String =
        if (granted(container, DEVICE_SYSTEM)) "/" else ImageFs.find(context).rootDir.absoluteFile.canonicalPath

    /**
     * The drives to link for [container] (letter and folder, Z: and C: aside): its own drive list with each wide
     * folder kept only under its grant, and with [ALL_STORAGE] every mounted volume added under a free letter.
     */
    @JvmStatic
    fun drives(context: Context, container: Container): List<Pair<String, String>> {
        val volumes = runCatching { SharedVolume.mounted(context).map { it.root.absolutePath } }.getOrDefault(emptyList())
        return drives(
            container.drivesIterator().map { it[0] to it[1] },
            volumes,
            context.dataDir.absolutePath,
            granted(container, DEVICE_SYSTEM),
            granted(container, ALL_STORAGE),
            listOfNotNull(container.launchOverrides[GAME_FOLDER]),
        )
    }

    /** [drives] without Android: the rule itself. Pure. */
    internal fun drives(
        own: List<Pair<String, String>>,
        volumes: List<String>,
        privateDir: String,
        deviceSystem: Boolean,
        allStorage: Boolean,
        required: List<String> = emptyList(),
    ): List<Pair<String, String>> {
        val kept = own.filter { (_, folder) ->
            when (WideAccess.classify(canonical(folder), volumes, privateDir)) {
                WideAccess.Kind.NARROW -> true
                WideAccess.Kind.ALL_STORAGE -> allStorage
                WideAccess.Kind.DEVICE_SYSTEM -> deviceSystem
            }
        }
        val used = kept.map { it.first.uppercase() }.toMutableSet().apply { addAll(listOf("A", "B", "C", "Z")) }
        fun nextLetter(): String? = ('D'..'Y').map(Char::toString).firstOrNull { it !in used }?.also { used += it }
        val have = kept.map { canonical(it.second) }.toSet()
        val volumeDrives = if (!allStorage) emptyList() else volumes.filter { canonical(it) !in have }.mapNotNull { volume ->
            nextLetter()?.let { it to volume }
        }
        val linked = kept + volumeDrives
        val folders = required.filter { folder ->
            val path = canonical(folder)
            linked.none { (_, drive) -> canonical(drive).let { path == it || path.startsWith(if (it == "/") "/" else "$it/") } }
        }.mapNotNull { folder -> nextLetter()?.let { it to folder } }
        return linked + folders
    }

    private fun canonical(path: String): String = runCatching { File(path).canonicalPath }.getOrDefault(path)
}
