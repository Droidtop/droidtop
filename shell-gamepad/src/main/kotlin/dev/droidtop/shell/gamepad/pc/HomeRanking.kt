package dev.droidtop.shell.gamepad.pc

import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.PlayStart

/** Home's row of the games this person plays at this time of day (Droidtop/tracker#611, owner's "a", 2026-10-28). */
internal const val SHELF_USUAL_NOW = "usual-now"

/** How many hours either side of now a start still counts as "at this time of day". */
internal const val USUAL_HOUR_SPAN = 1

/** How many starts at this time of day make a game part of the habit: once is a try. */
internal const val USUAL_MIN_STARTS = 2

/**
 * "Usual now": the games in [all] started at least [USUAL_MIN_STARTS] times within [USUAL_HOUR_SPAN] hours of
 * [nowHour] over the launch log ([starts]), most such starts first, then by name; none until two games qualify, since
 * one card is not a row. [hourOf] gives a start's local hour (0 to 23). Hidden games stay out. Pure.
 */
internal fun usualNowShelf(starts: List<PlayStart>, all: List<LibraryEntry>, nowHour: Int, hourOf: (Long) -> Int): PcShelf? {
    fun near(hour: Int): Boolean {
        val gap = Math.floorMod(hour - nowHour, 24)
        return minOf(gap, 24 - gap) <= USUAL_HOUR_SPAN
    }
    val counts = starts.filter { near(hourOf(it.startedAtMs)) }.groupingBy { it.gameId }.eachCount()
    val byId = all.filter { !it.hidden }.associateBy { it.id }
    val games = counts.filter { (id, n) -> n >= USUAL_MIN_STARTS && id in byId }
        .entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { byId.getValue(it.key).title.lowercase() })
        .map { byId.getValue(it.key) }
    if (games.size < 2) return null
    return PcShelf(SHELF_USUAL_NOW, "Usual at this time", games.take(SHELF_LIMIT), games.size)
}

/**
 * Home's rows in the order this person uses them (Droidtop/tracker#611): the rows they pinned first, in their order
 * ([rules]), then Usual now when there is one (it is about this hour), then the rows that started the most games
 * ([use], starts from each row id), rows used alike keeping the order they came in, so a new person sees today's
 * order. Rows they hid are left out. Pure.
 */
internal fun rankHomeShelves(
    shelves: List<PcShelf>,
    use: Map<String, Int>,
    rules: dev.droidtop.library.settings.HomeRows.Rules = dev.droidtop.library.settings.HomeRows.Rules(),
): List<PcShelf> {
    val shown = shelves.filter { it.id !in rules.hidden }
    val pinned = rules.pinned.mapNotNull { id -> shown.firstOrNull { it.id == id } }
    return pinned + shown.filter { it.id !in rules.pinned }.withIndex()
        .sortedWith(
            compareByDescending<IndexedValue<PcShelf>> { it.value.id == SHELF_USUAL_NOW }
                .thenByDescending { use[it.value.id] ?: 0 }
                .thenBy { it.index },
        )
        .map { it.value }
}
