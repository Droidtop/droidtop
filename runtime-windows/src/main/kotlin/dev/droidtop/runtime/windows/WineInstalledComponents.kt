package dev.droidtop.runtime.windows

import android.content.Context
import com.winlator.contents.ContentProfile
import com.winlator.contents.ContentsManager
import dev.droidtop.runtime.SafeDelete
import dev.droidtop.runtime.windows.utils.ManifestComponentHelper
import dev.droidtop.runtime.windows.utils.ManifestContentTypes
import dev.droidtop.runtime.windows.utils.X86_64GuestLibs
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Windows components on this device (docs/SPEC.md 5a "Components on this device", Droidtop/tracker#233): every Wine,
 * Proton, DXVK, VKD3D, driver, Box64 and FEXCore build the runtime's contents store holds, with its size and whether
 * anything uses it, and Remove for one nothing uses.
 *
 * "Uses" is checked by name against every prefix's settings and every game's own choices
 * ([WineOptions.componentValuesInUse]). The check errs one way only: a build whose name appears anywhere there counts as
 * in use and cannot be removed, so a removal never takes a build a game needs (a Wine build added from a file cannot be
 * downloaded again). The runtime's bundled Wine builds are never offered for removal.
 */
object WineInstalledComponents {
    data class Component(
        /** The kind as the runtime names it: Wine, Proton, DXVK, VKD3D, Turnip, Box64, FEXCore... */
        val kind: String,
        val name: String,
        /** The contents store's own key for it ([ContentsManager.getEntryName]). */
        val entry: String,
        val bytes: Long,
        val inUse: Boolean,
        val bundled: Boolean,
    ) {
        val removable: Boolean get() = !inUse && !bundled
    }

    /**
     * Whether a build named [name] (store key [entry]) is named by any of [used]. Pure. Matching is loose on purpose: a
     * value that contains the build's name, or the store key, ignoring case, counts, and a build with no name counts as
     * used, so an unclear case keeps the build.
     */
    fun inUse(name: String, entry: String, used: Set<String>): Boolean {
        if (name.isBlank()) return true
        return used.any { value -> value.equals(entry, ignoreCase = true) || value.contains(name, ignoreCase = true) }
    }

    /** Every installed component, kinds in the runtime's order and names sorted. Disk: reads the store and sizes each build. */
    suspend fun list(context: Context): List<Component> = withContext(Dispatchers.IO) {
        val manager = ContentsManager(context).apply { syncContents() }
        val used = WineOptions.componentValuesInUse(context)
        val bundled = bundledNames(context)
        ContentProfile.ContentType.values().flatMap { type ->
            manager.getProfiles(type).orEmpty().sortedBy { it.verName.lowercase() }.map { profile ->
                val entry = ContentsManager.getEntryName(profile)
                Component(
                    kind = type.toString(),
                    name = profile.verName,
                    entry = entry,
                    bytes = sizeOf(ContentsManager.getInstallDir(context, profile).toPath()),
                    inUse = inUse(profile.verName, entry, used),
                    bundled = bundled.any { it.equals(profile.verName, ignoreCase = true) || it.equals(entry, ignoreCase = true) },
                )
            }
        }
    }

    /**
     * Removes the build stored as [entry], after checking again that nothing uses it. Deletes only inside the contents
     * store's folder for its kind ([SafeDelete.clearWithin]), never a prefix. Returns the sentence to show.
     */
    suspend fun remove(context: Context, entry: String): String = withContext(Dispatchers.IO) {
        val component = list(context).firstOrNull { it.entry == entry } ?: return@withContext "It is no longer on this device"
        if (component.bundled) return@withContext "${component.name} comes with droidtop and stays"
        if (component.inUse) return@withContext "${component.name} is in use, so it stays"
        val manager = ContentsManager(context).apply { syncContents() }
        val profile = ContentProfile.ContentType.values().asSequence()
            .flatMap { manager.getProfiles(it).orEmpty().asSequence() }
            .firstOrNull { ContentsManager.getEntryName(it) == entry }
            ?: return@withContext "It is no longer on this device"
        val type = profile.type
        val reason = SafeDelete.clearWithin(ContentsManager.getContentTypeDir(context, type), ContentsManager.getInstallDir(context, profile))
        manager.syncContents()
        if (type == ContentProfile.ContentType.CONTENT_TYPE_WINE || type == ContentProfile.ContentType.CONTENT_TYPE_PROTON) {
            WineBuilds.forget(context, WineBuilds.id(profile))
        }
        if (reason == null) "Removed ${component.name}" else "Couldn't remove ${component.name}: $reason"
    }

    /** A catalog build this device cannot run, with why ("Not on this device", SPEC 7i). [bytes] is the catalog's, 0 when unknown. */
    data class Unavailable(val kind: String, val name: String, val bytes: Long, val reason: String)

    /**
     * The enabled sources' builds this CPU cannot run, which the choice rows leave out: on an x86_64 device the ARM
     * (arm64ec) Wine builds and the x86_64 ones before Wine 10 ([WineOptionPlan.notHereReason]), and the ARM GPU drivers.
     * Empty on an ARM device, which runs every bionic build (x86_64 ones under Box64). Disk (the catalog file).
     */
    suspend fun notOnThisDevice(context: Context): List<Unavailable> = withContext(Dispatchers.IO) {
        val x86 = X86_64GuestLibs.isX86_64Host()
        if (!x86) return@withContext emptyList()
        val manifest = ManifestComponentHelper.loadComponentAvailability(context).manifest
        fun bionic(type: String) = ManifestComponentHelper.filterManifestByVariant(manifest.items[type].orEmpty(), "bionic")
        val wines = (bionic(ManifestContentTypes.PROTON) + bionic(ManifestContentTypes.WINE)).distinctBy { it.id }.mapNotNull { entry ->
            WineOptionPlan.notHereReason(x86, entry.id)?.let { Unavailable("Wine", entry.name.ifBlank { entry.id }, entry.size, it) }
        }
        val drivers = bionic(ManifestContentTypes.DRIVER).map { entry ->
            Unavailable("Driver", entry.name.ifBlank { entry.id }, entry.size, "An ARM GPU driver; an x86_64 device never loads one")
        }
        (wines + drivers).sortedWith(compareBy({ it.kind }, { it.name.lowercase() }))
    }

    private fun bundledNames(context: Context): List<String> =
        runCatching { context.resources.getStringArray(R.array.bionic_wine_entries).toList() }.getOrDefault(emptyList())

    /** The bytes under [root], links not followed (a Wine build links into the shared store). */
    private fun sizeOf(root: Path): Long {
        if (!Files.exists(root)) return 0L
        var total = 0L
        runCatching {
            Files.walkFileTree(
                root,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (attrs.isRegularFile) total += attrs.size()
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
                },
            )
        }
        return total
    }
}
