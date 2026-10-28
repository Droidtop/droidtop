package dev.droidtop.library.settings

import android.content.Context
import dev.droidtop.runtime.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The order of the Quick Menu's sections, the person's to set (owner, 2026-10-23: "So long as it's reorderable, the
 * order is fine"; Droidtop/tracker#610). One list of panels in preferences, read once off the main thread ([load]);
 * the rail and the Settings screen that changes it both read [order]. The section names live here too, so the rail
 * and that screen cannot call a section by two names.
 */
object QuickMenuOrder {
    private const val PREFS = "quick_menu_order"
    private const val KEY = "order"

    /** The Quick Menu's sections in droidtop's own order, with their names: the order a fresh install shows. */
    val DEFAULT: List<Pair<ControlPanel, String>> = listOf(
        ControlPanel.APPS to "Running apps",
        ControlPanel.NOTIFICATIONS to "Notifications",
        // Steam's Friends, moved off the left menu (owner, 2026-10-24: "Game sources go into the qam, as does social").
        ControlPanel.SOCIAL to "Social",
        ControlPanel.CONNECTIONS to "Connections",
        ControlPanel.SYSTEM to "Power and mode",
        ControlPanel.PERFORMANCE to "Performance",
        ControlPanel.AUDIO to "Audio",
        ControlPanel.DISPLAY to "Display",
        ControlPanel.DOWNLOADS to "Downloads",
        ControlPanel.PLUGINS to "Plugins",
        // The person's own name and picture (owner, 2026-10-28, Droidtop/tracker#586).
        ControlPanel.PROFILE to "Profile",
    )

    private val names = DEFAULT.toMap()
    private val state = MutableStateFlow(DEFAULT.map { it.first })

    /** The sections in the person's order; a section a later droidtop adds comes in at its own place. */
    val order: StateFlow<List<ControlPanel>> = state

    fun label(panel: ControlPanel): String = names[panel] ?: panel.name.lowercase().replaceFirstChar { it.uppercase() }

    /** Reads the stored order. Touches the preferences file: not for the main thread. */
    fun load(context: Context) {
        val stored = prefs(context).getString(KEY, null)?.split(',')?.mapNotNull { name -> DEFAULT.firstOrNull { it.first.name == name }?.first }
        state.value = complete(stored.orEmpty())
    }

    /** [stored] with every section it lacks put back at its default place, each once. Pure. */
    fun complete(stored: List<ControlPanel>): List<ControlPanel> {
        val result = stored.distinct().filter { it in names }.toMutableList()
        DEFAULT.forEachIndexed { index, (panel, _) -> if (panel !in result) result.add(index.coerceAtMost(result.size), panel) }
        return result
    }

    /** [current] with [panel] moved to [slot] and the section that was there moved to where [panel] was. Pure. */
    fun place(current: List<ControlPanel>, slot: Int, panel: ControlPanel): List<ControlPanel> {
        val from = current.indexOf(panel)
        if (from < 0 || slot !in current.indices || from == slot) return current
        return current.toMutableList().also {
            it[from] = it[slot]
            it[slot] = panel
        }
    }

    fun set(context: Context, order: List<ControlPanel>) {
        state.value = complete(order)
        prefs(context).edit().putString(KEY, state.value.joinToString(",") { it.name }).apply()
    }

    /** Settings > Shell: one row per slot, as the companion's tabs are chosen ("1st: Game"). */
    fun screen(): CatalogScreen = CatalogScreen(
        id = SCREEN_ID,
        title = words(R.string.quick_menu_order_quick_menu_order),
        subtitle = words(R.string.quick_menu_order_which_section_comes_where_on),
        groups = { context ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { load(context) }
            val current = state.value
            listOf(
                CatalogGroup(
                    id = "quick_menu_order_slots",
                    title = null,
                    items = current.mapIndexed { slot, panel ->
                        ChoiceItem(
                            id = "quick_menu_order_$slot",
                            title = ordinal(slot + 1),
                            options = current.map { ChoiceOption(it.name, label(it)) },
                            current = panel.name,
                            onSelect = { ctx, value ->
                                current.firstOrNull { it.name == value }?.let { chosen -> set(ctx, place(state.value, slot, chosen)) }
                            },
                        )
                    },
                ),
            )
        },
        live = order,
    )

    const val SCREEN_ID = "quick_menu_order"

    /** "1st", "2nd", "11th": a slot's name on the order screens (this one and [HomeRows]). */
    internal fun ordinal(n: Int): String = n.toString() + when {
        n % 100 in 11..13 -> "th"
        n % 10 == 1 -> "st"
        n % 10 == 2 -> "nd"
        n % 10 == 3 -> "rd"
        else -> "th"
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
