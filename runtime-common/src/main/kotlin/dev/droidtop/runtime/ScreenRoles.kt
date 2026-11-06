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
 * What the person said Desktop does with a screen other than its own (docs/SPEC.md 4c and 2b, "The desktop on a second
 * screen", Droidtop/tracker#908 step D5): asked once per screen the first time Desktop runs with it, kept per screen
 * beside its [ScreenRole]. Unset until then: there is no default answer.
 */
enum class DesktopScreenChoice(val label: String) {
    /** The desktop reaches onto this screen while it runs: a second output of the same desktop is drawn there. */
    EXTEND("Extend the desktop"),

    /** The screen keeps what its role gives it (the companion, as a rule) while the desktop runs. */
    COMPANION("Keep it as it is"),
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
 * new screen both write here; the screen orchestration, the Interface size and the companion read it. Desktop's own
 * answer per screen ([DesktopScreenChoice], whether the desktop extends to it) is kept here too, beside the role.
 */
object ScreenRoles {
    private const val PREFS = "screen_roles"

    /** Desktop's answer per screen ([DesktopScreenChoice]), by the same stable id; a file of its own, so [saved] never reads it as a role. */
    private const val DESKTOP_PREFS = "screen_desktop"

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

    /** Desktop's answer for every screen that has one, by screen id. Reads preferences: off the main thread. */
    fun desktopSaved(context: Context): Map<String, DesktopScreenChoice> =
        context.applicationContext.getSharedPreferences(DESKTOP_PREFS, Context.MODE_PRIVATE).all.mapNotNull { (key, value) ->
            (value as? String)?.let { stored -> DesktopScreenChoice.entries.firstOrNull { it.name == stored } }?.let { key to it }
        }.toMap()

    /** Remembers Desktop's [choice] for the screen [uniqueId] and re-runs the arrangement. Writes preferences: off the main thread. */
    fun setDesktop(context: Context, uniqueId: String, choice: DesktopScreenChoice) {
        context.applicationContext.getSharedPreferences(DESKTOP_PREFS, Context.MODE_PRIVATE).edit()
            .putString(uniqueId, choice.name).apply()
        DisplayArrangement.changed()
    }

    /**
     * The screens the desktop extends to while it runs on the display [desktopId]: every other screen in [screens]
     * whose Desktop answer in [choices] is [DesktopScreenChoice.EXTEND]. Pure. Nothing unanswered is extended to.
     */
    fun desktopExtends(screens: List<ScreenFacts>, desktopId: Int, choices: Map<String, DesktopScreenChoice>): Set<Int> =
        screens.filter { it.displayId != desktopId && choices[it.uniqueId] == DesktopScreenChoice.EXTEND }
            .map { it.displayId }.toSet()

    /**
     * The screen Desktop asks about next while it runs on [desktopId], or null: the lowest-numbered other screen with
     * no Desktop answer in [choices], not asked already while droidtop runs ([asked], by screen id), and not waiting
     * for its role ([roleUnanswered], display ids: the role question comes first, and a screen left unanswered there
     * gets nothing of droidtop's, so it is not asked about the desktop either). Pure.
     */
    fun desktopToAsk(
        screens: List<ScreenFacts>,
        desktopId: Int,
        choices: Map<String, DesktopScreenChoice>,
        roleUnanswered: Collection<Int>,
        asked: Set<String>,
    ): ScreenFacts? = screens
        .filter { it.displayId != desktopId && it.displayId !in roleUnanswered }
        .sortedBy { it.displayId }
        .firstOrNull { choices[it.uniqueId] == null && it.uniqueId !in asked }

    /**
     * Where Desktop's question starts for [screen] while the desktop is on [desktop]. Pure. The same rule as [suggest],
     * from what the screens report and never a device model: a markedly larger screen suggests extending the desktop
     * to it, a smaller or similar one keeping it as it is. Only where the cursor starts; nothing is applied unanswered.
     */
    fun suggestDesktop(screen: Size, desktop: Size): DesktopScreenChoice =
        if (suggest(screen, desktop) == ScreenRole.MAIN) DesktopScreenChoice.EXTEND else DesktopScreenChoice.COMPANION

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

/**
 * The displays the running desktop is drawn on besides its own (Droidtop/tracker#908 step D5), published by `:app`'s
 * DesktopScreens: before it opens the desktop on a display, and only after it has closed it there. The second-screen
 * orchestration keeps the companion and the idle cover off these displays (SecondScreenPlan), so neither covers the
 * desktop whichever runs first.
 */
object DesktopExtension {
    private val state = kotlinx.coroutines.flow.MutableStateFlow<Set<Int>>(emptySet())
    val displays: kotlinx.coroutines.flow.StateFlow<Set<Int>> get() = state

    fun set(displayIds: Set<Int>) {
        state.value = displayIds
    }
}
