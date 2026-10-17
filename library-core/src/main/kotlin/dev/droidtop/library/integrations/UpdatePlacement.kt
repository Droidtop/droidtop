package dev.droidtop.library.integrations

import dev.droidtop.library.saves.SaveTemplates
import java.io.File
import org.json.JSONArray

/**
 * Where an update a source's `download` job fetched lands, and the saves that come along (docs/SPEC.md 7g, "Getting an
 * update"). 7m's rule: a new version folder `<name> v<version>` beside the game's newest one, never over a folder that
 * is there; the versions already here stay. Saves the game keeps in its own folder (the engine's `beside` places,
 * Detected saves) are copied from the newest version's folder into the new one before the library indexes it, so its
 * first launch carries on from them; the old folder keeps its copy.
 */
object UpdatePlacement {
    /** The job argument holding the newest version's folder, whose saves are copied. */
    const val ARG_SAVES_FROM = "savesFrom"

    /** The job argument holding the engine's `beside` save patterns (a JSON array), relative to a game's folder. */
    const val ARG_SAVES_BESIDE = "savesBeside"

    /**
     * The new version folder's name: [name] and the version as the source wrote it less a leading `v`, so `GameNaming`
     * reads it as the same game at that version. Characters no file system takes become a space. Null when [version]
     * gives nothing to name it by.
     */
    fun folderName(name: String, version: String?): String? {
        val bare = version?.trim()?.removePrefix("v")?.removePrefix("V")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val raw = "${name.trim()} v$bare"
        val clean = raw.map { if (it in "/\\:*?\"<>|" || it.code < 32) ' ' else it }.joinToString("")
            .replace(Regex("\\s+"), " ").trim().trimEnd('.')
        return clean.takeIf { it.isNotEmpty() && clean.length <= 200 }
    }

    /**
     * The job arguments for [newest]'s saves: none when the engine keeps none in the game's folder ([beside] empty), or
     * the game has no folder here.
     */
    fun savesArgs(newest: File?, beside: List<String>): Map<String, String> {
        if (newest == null || beside.isEmpty()) return emptyMap()
        return mapOf(ARG_SAVES_FROM to newest.absolutePath, ARG_SAVES_BESIDE to JSONArray(beside).toString())
    }

    /**
     * After the update was placed at [placed] (its new version folder): copies the saves the job's arguments name into
     * the same places inside it, never over a file the release itself brought. Returns the job's line for it, or null
     * when the job is not an update's or names no saves.
     */
    fun copySaves(placed: File, args: Map<String, String>): String? {
        if (args[dev.droidtop.pluginhost.DownloadJobs.ARG_VERSION_FOLDER] == null) return null
        val from = args[ARG_SAVES_FROM]?.let(::File)
        val beside = args[ARG_SAVES_BESIDE]?.let { text ->
            runCatching { JSONArray(text).let { array -> (0 until array.length()).map { array.getString(it) } } }.getOrNull()
        }.orEmpty()
        if (from == null || beside.isEmpty()) return "No saves were copied: droidtop knows of none kept in this game's folder"
        val copied = copyBeside(from, placed, beside)
        return when (copied) {
            0 -> "No saves were found in ${from.name} to copy"
            1 -> "Copied 1 save file from ${from.name}"
            else -> "Copied $copied save files from ${from.name}"
        }
    }

    /**
     * Copies every file [beside] names under [from] to the same relative path under [to]; a file already at that path
     * stays as it is. Returns how many were copied. Pure file work, for the tests.
     */
    fun copyBeside(from: File, to: File, beside: List<String>): Int {
        if (!from.isDirectory || !to.isDirectory) return 0
        val scan = SaveTemplates.collect(beside.map { SaveTemplates.BASE + "/" + it }, mapOf(SaveTemplates.BASE to listOf(from)))
        var copied = 0
        for (save in scan.files) {
            val relative = save.file.relativeToOrNull(from)?.path ?: continue
            if (relative.startsWith("..")) continue
            val target = File(to, relative)
            if (target.exists()) continue
            target.parentFile?.mkdirs()
            runCatching { save.file.copyTo(target) }.onSuccess { target.setLastModified(save.modifiedMs); copied++ }
        }
        return copied
    }
}
