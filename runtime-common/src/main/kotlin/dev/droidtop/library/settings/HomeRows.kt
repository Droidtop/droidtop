package dev.droidtop.library.settings

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The person's say over Home's rows (Droidtop/tracker#611, the adaptive Home: "The person can always pin, hide or
 * reorder rows"): rows pinned to the top in their order, and rows hidden. Every other row takes the place Home's use
 * gives it. Home notes the rows it has shown ([noteSeen]), by id and title, so a plugin's shelf can be pinned or
 * hidden as droidtop's own can. It also keeps how many games each row has started ([use]), the ranking's sign of use,
 * so the person can forget that use here ([screen]). Preferences, read and written off the main thread.
 */
object HomeRows {
    private const val PREFS = "home_rows"
    private const val USE_PREFS = "home_row_use"
    private const val KEY_PINNED = "pinned"
    private const val KEY_HIDDEN = "hidden"
    private const val KEY_SEEN = "seen"
    const val SCREEN_ID = "home_rows"

    /** What the person set: [pinned] in order, and [hidden]. */
    data class Rules(val pinned: List<String> = emptyList(), val hidden: Set<String> = emptySet())

    private val state = MutableStateFlow(Rules())
    private var seen: Map<String, String> = emptyMap()
    private var loaded = false

    val rules: StateFlow<Rules> = state

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var useCounts: Map<String, Int>? = null

    /**
     * Clears the launch log Home's "Usual at this time" row reads (library-core's, set where the library is made):
     * this module cannot reach the play history itself. Null where nothing set it.
     */
    @Volatile
    var forgetLaunchLog: (suspend (Context) -> Unit)? = null

    /** How many games each row has started, as stored. Touches the preferences file: not for the main thread. */
    fun use(context: Context): Map<String, Int> = useCounts ?: usePrefs(context).all
        .mapNotNull { (k, v) -> (v as? Int)?.let { k to it } }.toMap()
        .also { useCounts = it }

    /** A game was started from the Home row [rowId]; written off the main thread. */
    fun noteStart(context: Context, rowId: String) {
        val app = context.applicationContext
        io.launch {
            val next = use(app).toMutableMap().apply { merge(rowId, 1, Int::plus) }
            useCounts = next
            usePrefs(app).edit().putInt(rowId, next.getValue(rowId)).apply()
        }
    }

    /** Forgets what Home learned from use: the rows' counts and the launch log. Not for the main thread. */
    suspend fun forgetUse(context: Context) {
        useCounts = emptyMap()
        usePrefs(context).edit().clear().apply()
        forgetLaunchLog?.invoke(context.applicationContext)
    }

    /** Reads what is stored. Touches the preferences file: not for the main thread. */
    fun load(context: Context): Rules {
        if (!loaded) {
            val p = prefs(context)
            state.value = Rules(
                pinned = p.getString(KEY_PINNED, "").orEmpty().split('\n').filter { it.isNotEmpty() },
                hidden = p.getString(KEY_HIDDEN, "").orEmpty().split('\n').filter { it.isNotEmpty() }.toSet(),
            )
            seen = p.getString(KEY_SEEN, "").orEmpty().lines().mapNotNull { line ->
                line.split('\t', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
            }.toMap()
            loaded = true
        }
        return state.value
    }

    /** Home showed these rows (id to title); written only when one is new or renamed. Disk: IO only. */
    fun noteSeen(context: Context, rows: List<Pair<String, String>>) {
        load(context)
        val next = seen + rows
        if (next == seen) return
        seen = next
        prefs(context).edit().putString(KEY_SEEN, next.entries.joinToString("\n") { "${it.key}\t${it.value}" }).apply()
    }

    /** One row's state: [SHOWN] (placed by use), [PINNED] (to the top, after the rows already pinned) or [HIDDEN]. */
    const val SHOWN = "shown"
    const val PINNED = "pinned"
    const val HIDDEN = "hidden"

    /** [rules] with row [id] set to [to]: pinning adds it at the end of the pinned rows; nothing else moves. Pure. */
    fun with(rules: Rules, id: String, to: String): Rules {
        val pinned = rules.pinned - id
        val hidden = rules.hidden - id
        return when (to) {
            PINNED -> Rules(pinned + id, hidden)
            HIDDEN -> Rules(pinned, hidden + id)
            else -> Rules(pinned, hidden)
        }
    }

    /** [rules] with [id] put at pinned place [slot], the row that was there taking its old place. Pure. */
    fun place(rules: Rules, slot: Int, id: String): Rules {
        val from = rules.pinned.indexOf(id)
        if (from < 0 || slot !in rules.pinned.indices || from == slot) return rules
        val next = rules.pinned.toMutableList().also {
            it[from] = it[slot]
            it[slot] = id
        }
        return rules.copy(pinned = next)
    }

    /**
     * [rules] with the pinned row [id] moved one place earlier ([by] -1) or later ([by] 1) among the pinned rows, as
     * the heading's Move up and Move down do. A row not pinned, or already at that end, stays. Pure.
     */
    fun move(rules: Rules, id: String, by: Int): Rules {
        val from = rules.pinned.indexOf(id)
        return if (from < 0) rules else place(rules, from + by, id)
    }

    /** Stores [rules] and shows them at once. Writes the preferences file: not for the main thread. */
    fun set(context: Context, rules: Rules) {
        state.value = rules
        prefs(context).edit()
            .putString(KEY_PINNED, rules.pinned.joinToString("\n"))
            .putString(KEY_HIDDEN, rules.hidden.joinToString("\n"))
            .apply()
    }

    /** Settings > Shell > Home rows: each row Home has shown with its state, the pinned rows' order, and Reset. */
    fun screen(): CatalogScreen = CatalogScreen(
        id = SCREEN_ID,
        title = "Home rows",
        subtitle = "Pin rows to the top of Home or hide them; the others go where your use puts them",
        groups = { context ->
            withContext(Dispatchers.IO) { load(context) }
            val rules = state.value
            val titles = seen
            val stateOptions = listOf(
                ChoiceOption(SHOWN, "Shown"),
                ChoiceOption(PINNED, "Pinned to top"),
                ChoiceOption(HIDDEN, "Hidden"),
            )
            listOfNotNull(
                CatalogGroup(
                    id = "home_rows_states",
                    title = null,
                    items = if (titles.isEmpty()) {
                        listOf(TextBlockItem(id = "home_rows_none", text = "Open Home once and its rows are listed here."))
                    } else {
                        titles.entries.sortedBy { it.value.lowercase() }.map { (id, title) ->
                            ChoiceItem(
                                id = "home_row_$id",
                                title = title,
                                options = stateOptions,
                                current = when (id) {
                                    in rules.pinned -> PINNED
                                    in rules.hidden -> HIDDEN
                                    else -> SHOWN
                                },
                                onSelect = { ctx, value -> set(ctx, with(state.value, id, value)) },
                            )
                        }
                    },
                ),
                rules.pinned.takeIf { it.size > 1 }?.let { pinned ->
                    CatalogGroup(
                        id = "home_rows_pinned",
                        title = "Pinned order",
                        items = pinned.mapIndexed { slot, id ->
                            ChoiceItem(
                                id = "home_rows_pinned_$slot",
                                title = QuickMenuOrder.ordinal(slot + 1),
                                options = pinned.map { ChoiceOption(it, titles[it] ?: it) },
                                current = id,
                                onSelect = { ctx, value -> set(ctx, place(state.value, slot, value)) },
                            )
                        },
                    )
                },
                CatalogGroup(
                    id = "home_rows_reset",
                    title = null,
                    items = listOf(
                        ActionItem(
                            id = "home_rows_reset_all",
                            title = "Reset",
                            subtitle = "No row pinned or hidden",
                            confirmTitle = "Show every row and unpin them all?",
                            run = { ctx -> set(ctx, Rules()) },
                        ),
                        AsyncActionItem(
                            id = "home_rows_forget_use",
                            title = "Forget when I play",
                            subtitle = "Clears the launch log behind Usual at this time, and the order your use gave the rows",
                            confirmTitle = "Forget when you play and which rows you use?",
                            run = { ctx, _ ->
                                withContext(Dispatchers.IO) { forgetUse(ctx) }
                                "Forgotten"
                            },
                        ),
                    ),
                ),
            )
        },
        live = rules,
    )

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun usePrefs(context: Context) = context.applicationContext.getSharedPreferences(USE_PREFS, Context.MODE_PRIVATE)
}
