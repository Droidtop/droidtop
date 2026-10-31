package dev.droidtop.library

import dev.droidtop.pluginhost.DownloadSystems

/**
 * A source record's downloads as one page shows them (docs/SPEC.md 7g, Droidtop/tracker#845): which System choices
 * there are, which one is picked, the picked one's builds, and what is apart from them. The record rows read it, and
 * so will droidtop's pick, Updates (#833) and the game page's Versions tab, so they cannot disagree. Pure.
 *
 * The System choices are the thread's own download groupings, not single systems (the owner, 2026-10-30: with
 * Windows/Linux as one download and Android as another, "two options are shown"). A choice is named by its key, its
 * systems in droidtop's order joined by "+" ("windows+linux"); a single system is its own key, so a pick kept from
 * before ("windows") still finds the choice that holds it.
 */
object DownloadLines {
    /** The record's downloads for the picked choice, and the ones apart from any choice. */
    data class Lines(
        /** The System choices, by key, in droidtop's order; a picker is shown only with two or more. */
        val options: List<String>,
        /** The choice shown, by key; null when the links do not differ by system. */
        val picked: String?,
        /** What droidtop says about the picked choice (Android: not trackable), or null. */
        val notice: String?,
        /** The links for the picked choice, or all the builds when the links do not differ by system. */
        val builds: List<UpdateSources.Download>,
        /** Patches, extras and links for any system, shown apart from the builds. */
        val apart: List<UpdateSources.Download>,
    )

    private const val JOIN = "+"

    /** The systems a choice's [key] stands for. */
    fun systemsOf(key: String): List<String> = key.split(JOIN).filter { it.isNotEmpty() }

    /** "Windows/Linux" for "windows+linux". */
    fun label(key: String): String = systemsOf(key).joinToString("/") { DownloadSystems.LABELS[it] ?: it }

    /**
     * The System choices [downloads] give: each distinct set of systems a build is for, in droidtop's system order.
     * Builds decide, so a patch for Windows alone does not add a "Windows" choice beside "Windows/Linux"; with no
     * build tagged by system, the other links' sets are used.
     */
    fun options(downloads: List<UpdateSources.Download>): List<String> {
        fun setsOf(list: List<UpdateSources.Download>) =
            list.map { d -> DownloadSystems.ALL.filter { it in d.systems } }.filter { it.isNotEmpty() }.distinct()
        val builds = downloads.filter { isBuild(it) }
        val sets = setsOf(builds).ifEmpty { setsOf(downloads) }
        return sets
            .sortedWith(compareBy<List<String>> { DownloadSystems.ALL.indexOf(it.first()) }.thenBy { it.size })
            .map { it.joinToString(JOIN) }
    }

    /**
     * The choice shown for [picked] (a key, or a single system kept from before): that choice, else the first holding
     * that system, else the first with Windows, then Linux, else the first. Null with no choices.
     */
    fun picked(options: List<String>, picked: String?): String? =
        options.firstOrNull { it == picked }
            ?: picked?.let { p -> options.firstOrNull { p in systemsOf(it) } }
            ?: options.firstOrNull { DownloadSystems.WINDOWS in systemsOf(it) }
            ?: options.firstOrNull { DownloadSystems.LINUX in systemsOf(it) }
            ?: options.firstOrNull()

    /** The page's lines for [downloads] with [picked] as the person's choice ([picked]'s rules). */
    fun of(downloads: List<UpdateSources.Download>, picked: String?): Lines {
        val options = options(downloads)
        if (options.isEmpty()) {
            val (builds, rest) = downloads.partition { isBuild(it) }
            return Lines(options, null, null, builds, rest)
        }
        val choice = picked(options, picked)!!
        val systems = systemsOf(choice)
        // A link for any of the choice's systems is listed under it: Windows' own patch shows with Windows/Linux.
        val forChoice = downloads.filter { d -> d.systems.any { it in systems } }
        return Lines(
            options = options,
            picked = choice,
            notice = systems.firstNotNullOfOrNull { DownloadSystems.notice(it) },
            builds = forChoice,
            apart = downloads.filter { it.systems.isEmpty() },
        )
    }

    /** A build of the game, not a patch, an extra or something else beside it. */
    fun isBuild(d: UpdateSources.Download): Boolean = d.role == "build" && !DownloadSystems.isExtra(d.section)
}
