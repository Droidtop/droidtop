package dev.droidtop.shell.gamepad.pc

import android.content.Context
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.PlayStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
 * Home's rows in the order this person uses them (Droidtop/tracker#611): Usual now first when there is one (it is
 * about this hour), then the rows that started the most games ([use], starts from each row id), and rows used alike
 * keep the order they came in, so a new person sees today's order. Pure.
 */
internal fun rankHomeShelves(shelves: List<PcShelf>, use: Map<String, Int>): List<PcShelf> =
    shelves.withIndex()
        .sortedWith(
            compareByDescending<IndexedValue<PcShelf>> { it.value.id == SHELF_USUAL_NOW }
                .thenByDescending { use[it.value.id] ?: 0 }
                .thenBy { it.index },
        )
        .map { it.value }

/**
 * How many games each Home row has started (Droidtop/tracker#611), the ranking's sign of use: a preferences map, read
 * once off the main thread ([load]) and written off it ([noteStart]). On the device only.
 */
internal object HomeRowUse {
    private const val PREFS = "home_row_use"
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var counts: Map<String, Int>? = null

    /** The counts as stored. Touches the preferences file: not for the main thread. */
    fun load(context: Context): Map<String, Int> = counts ?: context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE).all
        .mapNotNull { (k, v) -> (v as? Int)?.let { k to it } }.toMap()
        .also { counts = it }

    /** A game was started from the Home row [shelfId]. */
    fun noteStart(context: Context, shelfId: String) {
        val app = context.applicationContext
        io.launch {
            val next = load(app).toMutableMap().apply { merge(shelfId, 1, Int::plus) }
            counts = next
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(shelfId, next.getValue(shelfId)).apply()
        }
    }
}
