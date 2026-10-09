package dev.droidtop.runtime

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Whether a screen is part of the device or attached to it, from Android's own facts ([ScreenNames.classify]). */
enum class ScreenClass { BUILT_IN, EXTERNAL }

/**
 * One panel of a device profile (droidtop-platforms `hardware/<device>.json`, `displays[]`): a built-in panel or the
 * device's own add-on display (an external display that belongs to the device), where it physically sits, and its
 * native size when the row records it.
 */
data class ScreenPanel(
    val builtIn: Boolean,
    /** "top" or "bottom"; null when the row does not say. */
    val position: String?,
    val widthPx: Int? = null,
    val heightPx: Int? = null,
)

/** What droidtop knows about one connected screen, for naming it. */
data class ScreenFacts(
    val displayId: Int,
    /** Stable across reconnects and reboots ([DisplayOutput.uniqueId]): the key a person's own name is stored under. */
    val uniqueId: String,
    /** Android's name for it ("Built-in Screen", "DP Screen", a monitor's own name). */
    val androidName: String,
    val screenClass: ScreenClass,
    val nativeWidthPx: Int,
    val nativeHeightPx: Int,
)

/**
 * The names droidtop gives screens (docs/SPEC.md 4c, "Screen names", Droidtop/tracker#162). Pure, so the rules are
 * unit-tested with made-up display lists; [ScreenNaming] applies them to the live ones.
 *
 * 1. A name the person gave the screen in Settings > Displays, kept per [ScreenFacts.uniqueId].
 * 2. A device profile that knows the physical arrangement names its panels by position: "Top screen" and
 *    "Bottom screen" (the AYN Thor's two built-in screens; the Retroid Pocket 5's built-in screen and the add-on
 *    display above it). A panel is matched by its native size when the profile records one (either orientation for a
 *    built-in panel, the exact orientation for an add-on, so a landscape monitor is never taken for a portrait add-on),
 *    otherwise only when it is the one screen of its class left and the one panel of its kind left.
 * 3. Otherwise a built-in screen is "Built-in screen", or "Built-in screen 1", "Built-in screen 2" ... in display-id
 *    order when there are several (a device can have more than one: never assume one built-in screen), and an external
 *    screen goes by its own Android name ("External screen" when it has none).
 * 4. Names that still collide are numbered in display-id order: two monitors both called "DP Screen" are
 *    "DP Screen 1" and "DP Screen 2".
 */
object ScreenNames {
    /**
     * Built-in or external, from Android's display type (`Display.getType`: TYPE_INTERNAL is 1; external, Wi-Fi,
     * overlay and virtual are not built in) when droidtop could read it. Without it: the default display is built in
     * and a presentation display is external, Android's public hint for a secondary output.
     */
    fun classify(displayId: Int, androidType: Int?, presentation: Boolean): ScreenClass = when {
        androidType != null -> if (androidType == TYPE_INTERNAL) ScreenClass.BUILT_IN else ScreenClass.EXTERNAL
        displayId == 0 -> ScreenClass.BUILT_IN
        presentation -> ScreenClass.EXTERNAL
        else -> ScreenClass.BUILT_IN
    }

    const val TYPE_INTERNAL = 1

    fun names(screens: List<ScreenFacts>, profile: List<ScreenPanel>, custom: Map<String, String>): Map<Int, String> {
        val sorted = screens.sortedBy { it.displayId }
        val named = LinkedHashMap<Int, String>()
        sorted.forEach { screen -> custom[screen.uniqueId]?.trim()?.takeIf { it.isNotEmpty() }?.let { named[screen.displayId] = it } }
        positionNames(sorted, profile).forEach { (id, name) -> if (id !in named) named[id] = name }
        val unnamedBuiltIn = sorted.filter { it.screenClass == ScreenClass.BUILT_IN && it.displayId !in named }
        val builtInCount = sorted.count { it.screenClass == ScreenClass.BUILT_IN }
        unnamedBuiltIn.forEachIndexed { index, screen ->
            named[screen.displayId] = if (builtInCount == 1) BUILT_IN else "$BUILT_IN ${index + 1}"
        }
        sorted.filter { it.displayId !in named }.forEach { named[it.displayId] = it.androidName.trim().ifEmpty { EXTERNAL } }
        // Same name twice: number every one of them, in display-id order.
        val byName = sorted.groupBy { named.getValue(it.displayId) }
        byName.filterValues { it.size > 1 }.forEach { (name, group) ->
            group.forEachIndexed { index, screen -> named[screen.displayId] = "$name ${index + 1}" }
        }
        return sorted.associate { it.displayId to named.getValue(it.displayId) }
    }

    /** The screens [profile] can name by position, matched as [names] says. */
    private fun positionNames(screens: List<ScreenFacts>, profile: List<ScreenPanel>): Map<Int, String> {
        val out = HashMap<Int, String>()
        for (builtIn in listOf(true, false)) {
            val panels = profile.filter { it.builtIn == builtIn && it.position != null }.toMutableList()
            val candidates = screens.filter { (it.screenClass == ScreenClass.BUILT_IN) == builtIn }.toMutableList()
            // Panels with a recorded size first: they match one screen exactly.
            for (panel in panels.filter { it.widthPx != null && it.heightPx != null }) {
                val match = candidates.firstOrNull { sizeMatches(panel, it, anyOrientation = builtIn) } ?: continue
                out[match.displayId] = positionName(panel.position!!) ?: continue
                candidates.remove(match)
                panels.remove(panel)
            }
            val sizeless = panels.filter { it.widthPx == null || it.heightPx == null }
            if (sizeless.size == 1 && candidates.size == 1 && panels.size == 1) {
                positionName(sizeless.single().position!!)?.let { out[candidates.single().displayId] = it }
            }
        }
        // A position named twice (a profile that does not fit what is connected) names nothing.
        return out.filterValues { name -> out.values.count { it == name } == 1 }
    }

    private fun sizeMatches(panel: ScreenPanel, screen: ScreenFacts, anyOrientation: Boolean): Boolean {
        val w = panel.widthPx ?: return false
        val h = panel.heightPx ?: return false
        return (screen.nativeWidthPx == w && screen.nativeHeightPx == h) ||
            (anyOrientation && screen.nativeWidthPx == h && screen.nativeHeightPx == w)
    }

    private fun positionName(position: String): String? = when (position) {
        "top" -> "Top screen"
        "bottom" -> "Bottom screen"
        else -> null
    }

    const val BUILT_IN = "Built-in screen"
    const val EXTERNAL = "External screen"
}

/**
 * The live screen names: the device profile (installed by :app once the hardware table is loaded), the person's own
 * names (Settings > Displays) and the last names the display orchestration computed, which rows such as the running
 * apps list read without touching a display or a file. [changes] ticks whenever a name may have changed, so the
 * orchestration recomputes the launch chooser's rows.
 */
object ScreenNaming {
    private const val PREFS = "screen_names"

    @Volatile
    var profile: List<ScreenPanel> = emptyList()
        private set

    @Volatile
    var current: Map<Int, String> = emptyMap()

    private val changesFlow = MutableStateFlow(0)
    val changes: StateFlow<Int> = changesFlow

    fun setProfile(panels: List<ScreenPanel>) {
        profile = panels
        changesFlow.value++
    }

    @Volatile
    private var custom: Map<String, String>? = null

    /** The person's names, by unique id. Reads a preferences file the first time: off the main thread. */
    fun customNames(context: Context): Map<String, String> =
        custom ?: context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all
            .mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap()
            .also { custom = it }

    /** Names [uniqueId]; a blank [name] gives the screen back its own name. */
    fun rename(context: Context, uniqueId: String, name: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        val trimmed = name.trim()
        if (trimmed.isEmpty()) prefs.remove(uniqueId) else prefs.putString(uniqueId, trimmed)
        prefs.apply()
        custom = customNames(context).toMutableMap().apply { if (trimmed.isEmpty()) remove(uniqueId) else put(uniqueId, trimmed) }
        changesFlow.value++
    }

    /** The facts [ScreenNames] needs, from a [DisplayOutput]. */
    fun facts(output: DisplayOutput): ScreenFacts = ScreenFacts(
        displayId = output.androidDisplayId,
        uniqueId = output.uniqueId,
        androidName = output.name,
        screenClass = ScreenNames.classify(output.androidDisplayId, output.androidType, output.isPresentation),
        nativeWidthPx = output.nativeWidthPx,
        nativeHeightPx = output.nativeHeightPx,
    )

    /** Names for [outputs] with the current profile and the person's names. Reads preferences once: off the main thread. */
    fun names(context: Context, outputs: List<DisplayOutput>): Map<Int, String> =
        ScreenNames.names(outputs.map(::facts), profile, customNames(context))

    /** The name for [displayId] as last computed, or the plain fallback before any was. */
    fun label(displayId: Int): String = current[displayId] ?: if (displayId == 0) ScreenNames.BUILT_IN else ScreenNames.EXTERNAL
}
