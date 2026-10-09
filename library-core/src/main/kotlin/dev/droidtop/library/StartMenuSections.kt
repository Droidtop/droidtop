package dev.droidtop.library

/**
 * The sections of Desktop's Start menu that come from the library (docs/SPEC.md 2b): Games, Windows and
 * Android apps. The menu's other sections are not the library's (the container's own Linux apps, the
 * places, a plugin's shelves, the taskbar's pins).
 */
enum class StartSection(val title: String) {
    GAMES("Games"),
    WINDOWS("Windows"),
    ANDROID_APPS("Android apps"),
}

/** One library entry as the Start menu lists it: the entry and the name it is listed under. */
class StartLibraryItem(val entry: LibraryEntry, val label: String)

object StartMenuSections {
    /**
     * Which section a kind belongs to: a Wine profile is a Windows shortcut, what [LibraryKinds.APPS] holds
     * is an Android app, every other kind (console ROMs, engine games, store games, Linux container
     * apps) is a game, as [LibraryKinds.GAMES] has it.
     */
    fun sectionOf(kind: LibraryEntryKind): StartSection = when {
        kind == LibraryEntryKind.WINE_PROFILE -> StartSection.WINDOWS
        kind in LibraryKinds.APPS -> StartSection.ANDROID_APPS
        else -> StartSection.GAMES
    }

    /**
     * Whether a container app named [name], with [genericName] under it, answers the search [text]: the text is
     * a part of either, ignoring case. Blank text answers nothing, since the search shows its suggestions then.
     * The container's apps are one more local source of the one search (docs/SPEC.md 12a, Droidtop/tracker#351).
     */
    fun appMatches(name: String, genericName: String?, text: String): Boolean {
        val wanted = text.trim()
        if (wanted.isEmpty()) return false
        return name.contains(wanted, ignoreCase = true) || genericName?.contains(wanted, ignoreCase = true) == true
    }

    /**
     * [entries] split into the sections, each in name order (the name a person reads, [GameNaming.displayName],
     * compared ignoring case). An entry the person hid is not listed. A section with nothing in it is absent.
     * Reads only the titles, so it is cheap to run on a background dispatcher for a large library.
     */
    fun group(entries: List<LibraryEntry>): Map<StartSection, List<StartLibraryItem>> =
        entries
            .filter { !it.hidden }
            .map { StartLibraryItem(it, GameNaming.displayName(it.title)) }
            .groupBy { sectionOf(it.entry.kind) }
            .mapValues { (_, items) -> items.sortedBy { it.label.lowercase() } }
}
