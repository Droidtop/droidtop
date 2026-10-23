package dev.droidtop.library.integrations

import android.content.Context
import dev.droidtop.library.GameEngine
import dev.droidtop.library.GameNaming
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.saves.SaveTemplates
import dev.droidtop.library.toLibraryEntryKind
import dev.droidtop.library.settings.LibraryPaths
import dev.droidtop.library.settings.PathChange
import dev.droidtop.runtime.SafeDelete
import java.io.File

/**
 * A game's older version folders, and removing one (docs/SPEC.md 7i, "Versions and updates"; Droidtop/tracker#624).
 * 7m keeps every version an update placed beside the newest; this is how the person clears the ones they are done with.
 * A version's saves kept in its own folder (the engine's `beside` places, the same ones an update copies) are never
 * removed with it: each one the kept version lacks is copied there first, and a save in the old folder that is newer
 * than the kept version's own copy stops the removal, since droidtop does not choose between two saves.
 */
object OldVersions {
    /** One version folder of the game other than the one kept. */
    data class Old(val entry: LibraryEntry, val folder: File, val version: String)

    /**
     * What removing [old] does to the saves: the files copied into the kept version first ([copy], old to new), and
     * the ones that stop it ([newer], relative paths, the old folder's copy newer than the kept one's).
     */
    data class SavesPlan(val copy: List<Pair<File, File>>, val newer: List<String>)

    /**
     * The folders of [siblings] that are an older or other version of [kept]: plain folder games (a store's install is
     * removed by its store's Uninstall), the same part of the game (`Week 1` is not an old `Week 2`) with the same mods,
     * naming a version, and neither inside the other. Pure, for the tests.
     */
    fun of(kept: LibraryEntry, siblings: List<LibraryEntry>): List<Old> {
        val keptPath = plainFolder(kept) ?: return emptyList()
        val keptName = GameNaming.derive(keptPath)
        return siblings.mapNotNull { other ->
            val path = plainFolder(other)?.takeIf { it != keptPath } ?: return@mapNotNull null
            if (path.startsWith("$keptPath/") || keptPath.startsWith("$path/")) return@mapNotNull null
            val name = GameNaming.derive(path)
            if (name.segment != keptName.segment || name.mods != keptName.mods) return@mapNotNull null
            val version = name.version.takeIf { it.isNotBlank() && it != keptName.version } ?: return@mapNotNull null
            Old(other, File(path), version)
        }
    }

    private fun plainFolder(entry: LibraryEntry): String? =
        entry.id.takeIf { it.startsWith("/") && !entry.missing }?.trimEnd('/')?.takeIf { it.isNotEmpty() }

    /**
     * Which of [old]'s saves under [beside] go to [kept] before [old] is removed: each one [kept] does not have, and,
     * stopping it, each one whose copy in [old] is newer than [kept]'s. Reads only. Pure file work, for the tests.
     */
    fun savesPlan(old: File, kept: File, beside: List<String>): SavesPlan {
        if (beside.isEmpty() || !old.isDirectory) return SavesPlan(emptyList(), emptyList())
        val scan = SaveTemplates.collect(beside.map { SaveTemplates.BASE + "/" + it }, mapOf(SaveTemplates.BASE to listOf(old)))
        val copy = mutableListOf<Pair<File, File>>()
        val newer = mutableListOf<String>()
        for (save in scan.files) {
            val relative = save.file.relativeToOrNull(old)?.path ?: continue
            if (relative.startsWith("..")) continue
            val target = File(kept, relative)
            when {
                !target.exists() -> copy += save.file to target
                save.modifiedMs > target.lastModified() -> newer += relative
            }
        }
        return SavesPlan(copy, newer.sorted())
    }

    /** "v0.37", or the version as the folder names it when it is not a number ("Final"). */
    fun label(version: String): String = if (version.firstOrNull()?.isDigit() == true) "v$version" else version

    /** The engine's `beside` save patterns for [entry]'s kind; empty when the engine keeps none in the game's folder. */
    fun besideFor(context: Context, entry: LibraryEntry): List<String> {
        val engine = GameEngine.entries.firstOrNull { it.toLibraryEntryKind() == entry.kind } ?: return emptyList()
        return dev.droidtop.library.EnginesDatabase.defFor(context, engine)?.saves?.beside.orEmpty()
    }

    /**
     * Removes [old]'s folder after moving its saves into [kept]'s ([savesPlan]); the library is told it is gone.
     * Refused, with nothing deleted, when a save there is newer than the kept one's, when a save cannot be copied,
     * or when the folder is not inside one of [roots] (the person's game folders). Returns the line for the page.
     */
    fun remove(context: Context, old: Old, kept: File, roots: List<File>): String {
        val name = label(old.version)
        val root = roots.firstOrNull { root ->
            val base = root.absolutePath.trimEnd('/')
            old.folder.absolutePath.startsWith("$base/")
        } ?: return "$name was not removed: it is not in one of your game folders"
        val plan = savesPlan(old.folder, kept, besideFor(context, old.entry))
        if (plan.newer.isNotEmpty()) {
            val first = plan.newer.first()
            val more = if (plan.newer.size > 1) " and ${plan.newer.size - 1} more" else ""
            return "$name was not removed: its save $first$more is newer than the one in the version you keep"
        }
        for ((from, to) in plan.copy) {
            to.parentFile?.mkdirs()
            val copied = runCatching { from.copyTo(to); to.setLastModified(from.lastModified()) }.isSuccess
            if (!copied) return "$name was not removed: its save ${from.relativeTo(old.folder).path} could not be copied"
        }
        // A folder only partly emptied is still there, and stays in the library until a retry removes the rest.
        SafeDelete.clearWithin(root, old.folder)?.let { reason -> return "$name was only partly removed: $reason" }
        LibraryPaths.report(context, PathChange.removed(old.folder))
        val saves = when (plan.copy.size) {
            0 -> ""
            1 -> ". Its 1 save file was copied first"
            else -> ". Its ${plan.copy.size} save files were copied first"
        }
        return "Removed $name$saves"
    }
}
