package dev.droidtop.shell.gamepad

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * ONE place in the Gaming shell, and one key for it.
 *
 * The shell is three levels deep and no deeper: a section (Games, Apps,
 * Settings), a group inside Games (a console system, a collection, the PC
 * category) and one game's detail. Everything else -- the Quick Menu, the
 * launch screen, the screensaver, a settings sub-screen -- is drawn over
 * wherever the user is rather than being somewhere else.
 */
internal sealed interface ShellPlace {
    val key: String

    data class Section(val section: GamingSection) : ShellPlace {
        override val key get() = "section:${section.name}"
    }

    /** A group inside the Games section: `system:pc`, `collection:all`, ... */
    data class Group(val groupKey: String) : ShellPlace {
        override val key get() = "group:$groupKey"
    }

    data class Detail(val entryId: String) : ShellPlace {
        override val key get() = "detail:$entryId"
    }

    /**
     * A group's own options screen, opened from it and over it: the PC
     * surface's "Stores and folders" (sign in to a store, add a games
     * folder, set up Windows games). It is a LEVEL, not an overlay the
     * screen under it owns, because B out of it has to land back on the
     * grid it was opened from -- with the card the user was on still
     * under the cursor -- and a screen that holds that answer itself
     * loses it the moment anything is drawn instead of it.
     */
    data class Options(val groupKey: String) : ShellPlace {
        override val key get() = "options:$groupKey"
    }
}

/**
 * Where the shell is, and where it came from -- the one answer to "what
 * does B do here" and "what was I looking at".
 *
 * This exists because of build 542's second navigation defect: B from a PC
 * game's detail did not return to the PC grid at all. The detail was a
 * sibling branch of the section's own `when`, so opening it DESTROYED the
 * games screen underneath -- the drilled-into group and every list
 * position with it -- and closing it rebuilt that screen from scratch, at
 * the top of the carousel. Nothing was remembering anything; there was
 * nothing to return to.
 *
 * So the three levels are held here, outside the screens that draw them,
 * together with the entry the user was on in each one. A screen asks where
 * it is and what to focus; it no longer owns the answer and cannot lose
 * it.
 *
 * Deliberately NOT a list of arbitrary depth. The shell's levels are
 * fixed, one per screen kind, and a stack of pushes would let the same
 * place appear twice (detail -> another version's detail -> back -> back)
 * which is not what the user means by "back" here: opening another version
 * of a game from its own detail is a move sideways, and B from it still
 * means "back to the grid".
 */
internal class ShellBackStack(initialSection: GamingSection) {

    var section: GamingSection by mutableStateOf(initialSection)
        private set

    /** The drilled-into group inside [GamingSection.GAMES], by [ShellPlace.Group.groupKey]. */
    var groupKey: String? by mutableStateOf(null)
        private set

    /** The open game detail, by entry id. */
    var detailId: String? by mutableStateOf(null)
        private set

    /** Whether the open group's own options screen is up. */
    var optionsOpen: Boolean by mutableStateOf(false)
        private set

    /** Per place, the entry the user was on when they left it. */
    private val focus = mutableStateMapOf<String, String>()

    val place: ShellPlace
        get() {
            val detail = detailId
            val group = groupKey
            return when {
                detail != null -> ShellPlace.Detail(detail)
                group != null && optionsOpen -> ShellPlace.Options(group)
                group != null -> ShellPlace.Group(group)
                else -> ShellPlace.Section(section)
            }
        }

    /** The place B returns to, or null at the top of the shell. */
    val under: ShellPlace?
        get() = when {
            detailId != null -> groupKey?.let { ShellPlace.Group(it) } ?: ShellPlace.Section(section)
            groupKey != null && optionsOpen -> ShellPlace.Group(groupKey!!)
            groupKey != null -> ShellPlace.Section(section)
            else -> null
        }

    val canGoBack: Boolean get() = under != null

    /** Switching sections is a move at the top level: it leaves no group or detail open. */
    fun openSection(target: GamingSection) {
        section = target
        groupKey = null
        detailId = null
        optionsOpen = false
    }

    /**
     * Drill into a group, or (with null) leave the one that is open. Moving
     * from one group straight to its neighbour -- ES-DE's Left/Right quick
     * system select -- is the same call: a sideways move, not a level.
     */
    fun openGroup(key: String?) {
        groupKey = key
        detailId = null
        optionsOpen = false
    }

    fun openDetail(entryId: String) {
        detailId = entryId
    }

    /** Open the current group's own options screen. */
    fun openOptions() {
        if (groupKey != null) optionsOpen = true
    }

    /**
     * B. Closes the detail, else closes the group's options screen, else
     * leaves the group, else does nothing (the shell's top level is a
     * home screen: back goes nowhere).
     */
    fun back(): ShellPlace? {
        when {
            detailId != null -> detailId = null
            optionsOpen -> optionsOpen = false
            groupKey != null -> groupKey = null
            else -> return null
        }
        return place
    }

    /** Records the entry the user is on HERE, so returning here lands on it. */
    fun rememberFocus(entryId: String?) {
        val key = place.key
        if (entryId == null) focus.remove(key) else focus[key] = entryId
    }

    /** The entry to focus in [place], or null when this place has never been visited. */
    fun focusIn(place: ShellPlace): String? = focus[place.key]

    /** [focusIn] for where the shell is now. */
    val focusHere: String? get() = focus[place.key]
}
