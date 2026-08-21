package dev.droidtop.library

/**
 * One "installed thing," modeled after Playnite's plugin architecture: a
 * native Android app, a Wine profile, and a Linux-container app are all
 * equally first-class entries here, not three different UI code paths.
 *
 * This is the seam a future launcher shell (:shell-gamepad) reads from —
 * building it this way now, even though that shell doesn't exist yet, is
 * the point: the library model needs to already be launcher-ready
 * (metadata, artwork, playtime) so bolting on a gamepad-console UI later is
 * a new shell module, not a rearchitecture.
 */
data class LibraryEntry(
    val id: String,
    val title: String,
    val kind: LibraryEntryKind,
    val artworkUri: String? = null,
    val playtimeSeconds: Long = 0,
    val lastPlayedEpochMs: Long? = null,
)

enum class LibraryEntryKind {
    NATIVE_ANDROID_APP,
    WINE_PROFILE,
    LINUX_CONTAINER_APP,

    /**
     * An app configured on a paired remote GameStream/Sunshine host, launched
     * via runtime-remote-stream rather than run locally. Same LibraryEntry
     * shape as anything else — the whole point of this model is that "runs
     * on a remote PC over Moonlight" isn't a special case in the UI.
     */
    REMOTE_STREAM,

    /** A Ren'Py/RPG Maker game, launched via JoiPlay (see [JoiPlayGameProvider]) — same category as any other emulator entry, kind named after the engine rather than the launcher. */
    RENPY,
    RPG_MAKER_MV,
    RPG_MAKER_MZ,
    RPG_MAKER_VX_ACE,
}

/**
 * One source of [LibraryEntry] items — installed-APK scanning, a
 * runtime-windows Wine profile store, a runtime-linux-* container's app
 * list, or (eventually) a Steam/Epic/GOG-style external source. Each is a
 * plugin in the Playnite sense: the library aggregates across all
 * registered providers into one list. [kinds] is a set, not a single
 * value, because one physical provider can genuinely cover several kinds
 * — [JoiPlayGameProvider] alone covers four different [GameEngine]s.
 */
interface LibraryProvider {
    val kinds: Set<LibraryEntryKind>
    suspend fun scan(): List<LibraryEntry>
    suspend fun launch(entry: LibraryEntry)
}

class Library(private val providers: List<LibraryProvider>) {
    suspend fun scanAll(): List<LibraryEntry> = providers.flatMap { it.scan() }

    suspend fun launch(entry: LibraryEntry) {
        providers.first { entry.kind in it.kinds }.launch(entry)
    }
}
