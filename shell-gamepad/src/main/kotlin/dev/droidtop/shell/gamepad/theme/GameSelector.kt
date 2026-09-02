package dev.droidtop.shell.gamepad.theme

import dev.droidtop.library.LibraryEntry
import kotlin.random.Random

/**
 * Real ES-DE `<gameselector>` `selection` modes, transcribed from
 * `GameSelectorComponent::GameSelection` (GameSelectorComponent.h:38-42).
 * These are the only three values that exist. An earlier version of this
 * file claimed ES-DE also had `similar` and `sameSystem` modes; neither
 * appears anywhere in ES-DE's source, so that claim was invented and is
 * removed. An unrecognized value falls back to [RANDOM], exactly as
 * GameSelectorComponent.h:159-164 does.
 */
enum class EsDeGameSelection {
    RANDOM,
    LAST_PLAYED,
    MOST_PLAYED,
    ;

    companion object {
        /** GameSelectorComponent.h:144-165. */
        fun parse(value: String?): EsDeGameSelection = when (value) {
            "lastplayed" -> LAST_PLAYED
            "mostplayed" -> MOST_PLAYED
            // "random", absent, and every invalid value alike.
            else -> RANDOM
        }
    }
}

/**
 * Real implementation of ES-DE's `<gameselector>` element: picks
 * [gameCount] games from [entries] for other elements' own
 * `gameselectorEntry` (0-based index into the returned list) to reference.
 *
 * All three real `selection` modes are implemented, ported from
 * `GameSelectorComponent::refreshGames` (GameSelectorComponent.h:51-129)
 * and the two list builders it reads (`FileData::updateLastPlayedList`/
 * `updateMostPlayedList`, FileData.cpp:906-940):
 *
 *  * `random` -- ES-DE's own retry loop draws DISTINCT games unless
 *    `allowDuplicates` is set, and breaks out early once it has drawn
 *    every game the system has (GameSelectorComponent.h:72-74). So a
 *    `gameCount` larger than the library returns the whole library, not a
 *    padded-with-repeats list; this used to pad, which is why a small
 *    system's mosaic showed the same game several times.
 *  * `lastplayed` -- descending by last-played time, skipping games never
 *    played at all (`lastplayed == "0"`, GameSelectorComponent.h:107-108;
 *    droidtop's equivalent is a null [LibraryEntry.lastPlayedEpochMs]).
 *  * `mostplayed` -- descending by play count, skipping games with a zero
 *    count (GameSelectorComponent.h:122-123).
 *
 * Both ordered modes are STABLE in ES-DE (a `std::stable_sort` by name
 * first, then a `std::sort` by the key), and `sortedByDescending` is
 * stable in Kotlin too, so ties keep the incoming order either way.
 *
 * Kid-mode filtering (GameSelectorComponent.h:105/120) is deliberately not
 * ported: droidtop has no UI-mode setting for it, so there is no real
 * state to read. [LibraryEntry.kidGame] is modeled and would be the input
 * if such a setting is ever added.
 *
 * Callers are responsible for only re-invoking this when the focused
 * system actually changes (e.g. `remember(entries)` in Compose) -- calling
 * it every recomposition would re-randomize the selection on every frame,
 * which is not what a real ES-DE game-preview collage does (it's stable
 * until you move to a different platform).
 */
object GameSelector {
    fun select(
        entries: List<LibraryEntry>,
        gameCount: Int,
        allowDuplicates: Boolean,
        selection: EsDeGameSelection = EsDeGameSelection.RANDOM,
        random: Random = Random.Default,
    ): List<LibraryEntry> {
        if (entries.isEmpty() || gameCount <= 0) return emptyList()
        return when (selection) {
            // GameSelectorComponent.h:99-113.
            EsDeGameSelection.LAST_PLAYED ->
                entries.filter { it.lastPlayedEpochMs != null }
                    .sortedByDescending { it.lastPlayedEpochMs }
                    .take(gameCount)
            // GameSelectorComponent.h:114-128.
            EsDeGameSelection.MOST_PLAYED ->
                entries.filter { it.playCount > 0 }
                    .sortedByDescending { it.playCount }
                    .take(gameCount)
            // GameSelectorComponent.h:67-97.
            EsDeGameSelection.RANDOM ->
                if (allowDuplicates) {
                    List(gameCount) { entries.random(random) }
                } else {
                    entries.shuffled(random).take(gameCount)
                }
        }
    }
}
