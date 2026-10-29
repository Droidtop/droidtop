package dev.droidtop.runtime

import android.content.Context
import android.view.Display
import kotlin.math.sqrt

/**
 * What the person said a screen is for (docs/SPEC.md 4c, "What each screen is for", Droidtop/tracker#776). The owner:
 * "I don't want droidtop making assumptions based on MY device. I want us to design a system that works on any
 * display". No screen is assumed better: droidtop's main UI stays on the screen it started on until a screen is
 * answered [MAIN].
 */
enum class ScreenRole(val label: String) {
    /** droidtop's menus (Gaming, Desktop) are drawn here; the screen they started on becomes the companion's. */
    MAIN("Main screen"),

    /** The companion is drawn here. */
    COMPANION("Companion"),

    /** droidtop draws nothing here and leaves the screen to Android, which mirrors the device. */
    MIRROR("Mirror"),

    /** droidtop covers the screen with its quiet surface and draws nothing else there. */
    NOTHING("Nothing"),
}

/**
 * Where droidtop's two surfaces go among the screens connected now, from what the person answered for each: [mainId]
 * the menus' screen, [companionId] the companion's (null: none), [unanswered] the screens no one has said anything
 * about yet, in id order, and [mirrored] the screens left to Android.
 */
data class ScreenArrangement(val mainId: Int, val companionId: Int?, val unanswered: List<Int>, val mirrored: Set<Int>)

/**
 * The one store of screen roles, kept per screen by Android's stable id for it ([ScreenFacts.uniqueId]: the same
 * screen keeps its answer across reconnects and reboots, whatever display id it comes up with), and the pure rules
 * that turn them into an arrangement and a suggestion. Settings > Displays and the question droidtop asks once per
 * new screen both write here; the screen orchestration, the Interface size and the companion read it.
 */
object ScreenRoles {
    private const val PREFS = "screen_roles"

    /** Every answer, by screen id. Reads preferences: off the main thread. */
    fun saved(context: Context): Map<String, ScreenRole> =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.mapNotNull { (key, value) ->
            (value as? String)?.let { stored -> ScreenRole.entries.firstOrNull { it.name == stored } }?.let { key to it }
        }.toMap()

    /**
     * Remembers [role] for the screen [uniqueId] and re-runs the arrangement. One screen is the main one: answering
     * [ScreenRole.MAIN] for one makes any other that was main the companion.
     */
    fun set(context: Context, uniqueId: String, role: ScreenRole) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        if (role == ScreenRole.MAIN) {
            prefs.all.filter { (key, value) -> key != uniqueId && value == ScreenRole.MAIN.name }
                .keys.forEach { editor.putString(it, ScreenRole.COMPANION.name) }
        }
        editor.putString(uniqueId, role.name).apply()
        DisplayArrangement.changed()
    }

    /**
     * The arrangement for [screens] and [roles]. Pure. The default display (where droidtop starts) is never asked:
     * it holds the menus unless another screen is answered [ScreenRole.MAIN] (the lowest id if several), and then it is
     * the companion's. Otherwise the companion goes to a screen answered [ScreenRole.COMPANION] (the lowest id).
     */
    fun arrange(screens: List<ScreenFacts>, roles: Map<String, ScreenRole>): ScreenArrangement {
        val home = screens.firstOrNull { it.displayId == Display.DEFAULT_DISPLAY }?.displayId
            ?: screens.minOfOrNull { it.displayId } ?: Display.DEFAULT_DISPLAY
        val others = screens.filter { it.displayId != home }.sortedBy { it.displayId }
        val main = others.firstOrNull { roles[it.uniqueId] == ScreenRole.MAIN }?.displayId ?: home
        val companion = others.firstOrNull { it.displayId != main && roles[it.uniqueId] == ScreenRole.COMPANION }?.displayId
            ?: home.takeIf { main != home }
        return ScreenArrangement(
            mainId = main,
            companionId = companion,
            unanswered = others.filter { roles[it.uniqueId] == null }.map { it.displayId },
            mirrored = others.filter { roles[it.uniqueId] == ScreenRole.MIRROR }.map { it.displayId }.toSet(),
        )
    }

    /** [arrange] for the screens connected now. Reads preferences: off the main thread. */
    fun arrangement(context: Context, outputs: List<DisplayOutput>): ScreenArrangement =
        arrange(outputs.map(ScreenNaming::facts), saved(context))

    /** A screen's size as Android reports it: its native pixels and its pixels per inch (0 when it reports none). */
    data class Size(val widthPx: Int, val heightPx: Int, val xdpi: Float, val ydpi: Float, val builtIn: Boolean) {
        /** The diagonal in inches, or null when the density is missing or not believable. */
        val inches: Double?
            get() {
                if (xdpi !in PLAUSIBLE_DPI || ydpi !in PLAUSIBLE_DPI) return null
                val w = widthPx / xdpi.toDouble()
                val h = heightPx / ydpi.toDouble()
                return sqrt(w * w + h * h)
            }
    }

    /**
     * What the question suggests for [screen] while droidtop's menus are on [main]. Pure. From what the screens report,
     * never a device model: a markedly larger screen (a monitor, a TV, a lapdock) suggests [ScreenRole.MAIN], a smaller
     * or similar one [ScreenRole.COMPANION]. The physical diagonal decides when both report a believable density;
     * otherwise an external screen with at least twice the pixels counts as larger.
     */
    fun suggest(screen: Size, main: Size): ScreenRole {
        val a = screen.inches
        val b = main.inches
        val larger = if (a != null && b != null) {
            a >= b * LARGER_BY
        } else {
            !screen.builtIn && screen.widthPx.toLong() * screen.heightPx >= 2L * main.widthPx.toLong() * main.heightPx
        }
        return if (larger) ScreenRole.MAIN else ScreenRole.COMPANION
    }

    /** The size [output] reports. */
    fun sizeOf(output: DisplayOutput): Size = Size(
        widthPx = output.nativeWidthPx,
        heightPx = output.nativeHeightPx,
        xdpi = output.xdpi,
        ydpi = output.ydpi,
        builtIn = ScreenNaming.facts(output).screenClass == ScreenClass.BUILT_IN,
    )

    /**
     * Swaps the menus and the companion between their screens (the Quick Menu's Swap screens) and remembers it.
     * Returns a line for the person, since the screen they are reading may be the one that just changed. Reads and
     * writes preferences: off the main thread.
     */
    fun swap(context: Context): String {
        val outputs = DisplayOutputRepository(context).currentOutputsSnapshot()
        val facts = outputs.map(ScreenNaming::facts)
        val now = arrange(facts, saved(context))
        val names = ScreenNaming.names(context, outputs)
        return when {
            // The menus are on another screen: it becomes the companion's, and the menus come home.
            now.mainId != Display.DEFAULT_DISPLAY -> {
                facts.first { it.displayId == now.mainId }.let { set(context, it.uniqueId, ScreenRole.COMPANION) }
                "Main screen is now ${names[Display.DEFAULT_DISPLAY] ?: ScreenNames.BUILT_IN}."
            }
            // The companion is on another screen: the menus go there, and the companion comes home.
            now.companionId != null -> {
                facts.first { it.displayId == now.companionId }.let { set(context, it.uniqueId, ScreenRole.MAIN) }
                "Main screen is now ${names[now.companionId] ?: ScreenNames.EXTERNAL}."
            }
            facts.size < 2 -> "Only one screen is connected."
            else -> "No screen is the companion's: say what each screen is for in Settings > Displays."
        }
    }

    /** "Main screen" larger by at least this much on the diagonal. */
    private const val LARGER_BY = 1.5

    /** Densities a real panel reports; a bogus 0, 1 or 160 on a monitor is not a measurement. */
    private val PLAUSIBLE_DPI = 60f..1000f
}

/**
 * Re-runs the screen orchestration when a display preference changed: writing one changes no `DisplayManager` state,
 * so nothing would re-emit on its own and a change would appear to do nothing until the next unrelated display event.
 */
object DisplayArrangement {
    val refresh = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** Any display preference changed: re-run orchestration now. */
    fun changed() {
        refresh.value++
    }

    /** Re-runs detection and orchestration from scratch. */
    fun reinitialize(): String {
        changed()
        return "Displays reinitialized."
    }
}
