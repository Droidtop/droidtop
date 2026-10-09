package dev.droidtop.library

import dev.droidtop.library.stores.StoreHolding

/**
 * The engine a game runs through, in the words its badge uses ("Ren'Py",
 * "RPG Maker MZ"); null for a kind that is no engine (a Windows or Linux
 * program, a remote PC, an app, a console ROM). The Kind facet's Engine
 * value and the capsule badge read this one answer (docs/SPEC.md 7i).
 */
fun LibraryEntryKind.engineFamily(): String? = when (this) {
    LibraryEntryKind.RENPY -> "Ren'Py"
    LibraryEntryKind.KIRIKIRI -> "KiriKiri"
    LibraryEntryKind.AUGUST -> "AUGUST"
    LibraryEntryKind.BURIKO -> "BGI"
    LibraryEntryKind.CATSYSTEM2 -> "CatSystem2"
    LibraryEntryKind.CMVS -> "CMVS"
    LibraryEntryKind.FLASH_AIR -> "Flash"
    LibraryEntryKind.RPG_MAKER_MV -> "RPG Maker MV"
    LibraryEntryKind.RPG_MAKER_MZ -> "RPG Maker MZ"
    LibraryEntryKind.RPG_MAKER_VX_ACE -> "RPG Maker VX Ace"
    LibraryEntryKind.RPG_MAKER_VX -> "RPG Maker VX"
    LibraryEntryKind.RPG_MAKER_XP -> "RPG Maker XP"
    LibraryEntryKind.RPG_MAKER_2000_2003 -> "RPG Maker 2000"
    LibraryEntryKind.GODOT -> "Godot"
    LibraryEntryKind.HTML -> "HTML"
    LibraryEntryKind.UNREAL -> "Unreal"
    LibraryEntryKind.UNITY -> "Unity"
    LibraryEntryKind.NATIVE_ANDROID_APP, LibraryEntryKind.WINE_PROFILE, LibraryEntryKind.LINUX_CONTAINER_APP,
    LibraryEntryKind.REMOTE_STREAM, LibraryEntryKind.CONSOLE_ROM,
    -> null
}

/** How much a capsule's badge says (the List option "Capsule badge", docs/SPEC.md 7i). */
enum class CapsuleBadgeStyle(val label: String) { FULL("Full"), KIND_ONLY("Kind only"), OFF("Off") }

/** The small generic mark a capsule carries for a holding that is not simply owned; never a store's logo, never words. */
enum class OwnershipMark { SHARED, LEFT }

/**
 * What a PC or engine game's origin reads, at each length it is drawn
 * (docs/SPEC.md 7i, "Badges", Droidtop/tracker#397 slice G):
 * [badge] on the capsule ("PC", "PC · GOG", "Engine · Ren'Py"; null when the
 * Capsule badge option is Off), [full] on the focus line and the page's
 * Source row ("Steam · Shared with you", "GOG · via Heroic", "Folder:
 * Games"), and [mark] for a holding a capsule shows as a glyph.
 */
data class OriginLabel(
    val kind: String,
    /** What follows the kind on the capsule (the engine, or the source when there are several); null for none. */
    val detail: String?,
    val full: String,
    val mark: OwnershipMark?,
    /** Whether the capsule draws a badge at all (the option is not Off). */
    val shown: Boolean = true,
) {
    /** The capsule's badge as one line of words; null when the option is Off. */
    val badge: String? get() = if (!shown) null else listOfNotNull(kind, detail).joinToString(" · ")
}

/**
 * The one function that names where a PC game came from (docs/SPEC.md 7i).
 * [origins] is how many sources the library has rows from (the Source
 * facet's values): a PC capsule names its source only when there is more
 * than one, so a Steam-only library reads "PC". An engine game names its
 * engine instead. [via] is the launcher it was imported through. Ownership is
 * never text on a capsule: Shared and No longer in your library are a [mark].
 * Pure: fields the entry carries and the caller's counts, never a lookup.
 */
fun originLabel(
    entry: LibraryEntry,
    origins: Int,
    style: CapsuleBadgeStyle = CapsuleBadgeStyle.FULL,
    roots: List<String> = emptyList(),
    via: String? = null,
): OriginLabel {
    val family = entry.kind.engineFamily()
    val kind = if (family != null) "Engine" else "PC"
    val source = PcSource.of(entry, roots)
    val detail = when {
        style != CapsuleBadgeStyle.FULL -> null
        family != null -> family
        source != null && origins > 1 -> source.label()
        else -> null
    }
    val holding = entry.pcInfo?.takeIf { PcSource.storeIdOf(it.storeId) != null }?.holding
    val full = listOfNotNull(
        source?.detail() ?: "Folder",
        holding?.takeIf { it != StoreHolding.OWNED }?.label,
        via?.let { "via ${PcLaunchers.label(it)}" },
    ).joinToString(" · ")
    val mark = when (holding) {
        StoreHolding.FAMILY -> OwnershipMark.SHARED
        StoreHolding.NOT_OWNED -> OwnershipMark.LEFT
        else -> null
    }
    return OriginLabel(kind, detail, full, mark, shown = style != CapsuleBadgeStyle.OFF)
}
