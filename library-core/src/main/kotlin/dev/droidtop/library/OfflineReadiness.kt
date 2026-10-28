package dev.droidtop.library

/**
 * Whether a game can be played with no network, from what the library entry already knows (docs/SPEC.md 7i, "The game
 * page", Droidtop/tracker#422): a person going travelling checks it before they lose the connection. Pure and read from
 * the entry alone, so it costs no disk or network work wherever it is shown.
 */
object OfflineReadiness {
    enum class Need {
        /** Its files are on this device and nothing it runs on is elsewhere. */
        NONE,

        /** Streamed from another machine ([LibraryEntryKind.REMOTE_STREAM]). */
        STREAMED,

        /** Installed on one of the person's paired computers, not here ([LibraryEntryKind.COMPUTER_GAME]). */
        OTHER_COMPUTER,

        /** A store game that is not downloaded to this device yet. */
        NOT_DOWNLOADED,

        /** The library marked its files missing ([LibraryEntry.missing]). */
        FILES_MISSING,
    }

    fun need(entry: LibraryEntry): Need = when {
        entry.kind == LibraryEntryKind.REMOTE_STREAM -> Need.STREAMED
        entry.kind == LibraryEntryKind.COMPUTER_GAME -> Need.OTHER_COMPUTER
        entry.missing -> Need.FILES_MISSING
        entry.pcInfo?.installed == false -> Need.NOT_DOWNLOADED
        else -> Need.NONE
    }

    /** The art the game's cards and page draw that comes from the internet rather than a file here. */
    fun remoteArt(entry: LibraryEntry): List<String> =
        listOfNotNull(entry.artworkUri, entry.heroUri, entry.logoUri)
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()

    /**
     * The game page's Offline row: the answer, and what to do about it when there is something. [artNotSaved] is how
     * many of [remoteArt] are not in the image cache yet (they show offline once they have been seen online).
     */
    fun line(need: Need, artNotSaved: Int): Pair<String, String?> = when (need) {
        Need.NONE ->
            if (artNotSaved == 0) {
                "Ready" to null
            } else {
                "Plays offline" to "Some of its art loads from the internet; open its page once while online to keep it"
            }
        Need.STREAMED -> "Needs the internet" to "It is streamed from another machine"
        Need.OTHER_COMPUTER -> "Needs the network" to "It is installed on another computer, not this device"
        Need.NOT_DOWNLOADED -> "Not downloaded" to "Download it before going offline"
        Need.FILES_MISSING -> "Files missing" to "Its files were not found the last time the library looked"
    }
}
