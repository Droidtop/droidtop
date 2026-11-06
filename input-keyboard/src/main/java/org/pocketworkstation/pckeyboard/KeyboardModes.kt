package org.pocketworkstation.pckeyboard

import android.content.Context
import android.content.res.Configuration
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The input method's modes (docs/SPEC.md 6a, "Modes", Droidtop/tracker#903 H12c): presentations of the same keyboard,
 * chosen from the tool strip's Modes button or by the pad, one per orientation. Every Hacker's layout (4-row, 5-row,
 * full) and setting stays as chosen; a mode changes where and how big the keys are drawn, or, for the number pad, which
 * keys. Only the input method has modes: the keyboards droidtop embeds ([KeyboardPanel]) keep the key grid shape and
 * its Layout button, and pick the compact layout on their own ([compactPanel]).
 *
 * One store per job. Normal, Split and One-handed left or right are the key grid shape setting's own values
 * ([KeyboardForm.PREF] and its landscape key: full, split, left, right), which the chooser writes exactly as the Layout
 * button does ([writes]); the mode setting ([PREF_MODE]) holds only what the shape cannot say: floating, the thumb split
 * and the number pad. So the chooser, the Layout button and the settings always agree ([chosen]), and in the input
 * method one-handed is one thing, the resizable one, whichever way it was picked.
 *
 * The rules are pure and JVM tested; [current], [choose] and [formFor] read and write the keyboard's own settings.
 */
object KeyboardModes {
    enum class Mode(val id: String) {
        NORMAL("normal"),
        SPLIT("split"),
        LEFT("left"),
        RIGHT("right"),
        FLOATING("floating"),
        THUMB("thumb"),
        NUMBER_PAD("numpad"),
        ;

        companion object {
            /** The mode stored as [id]; Normal for anything else, a blank or a missing one. */
            @JvmStatic
            fun of(id: String?): Mode = entries.firstOrNull { it.id == id } ?: NORMAL
        }
    }

    /** The mode chosen, for portrait; [key] gives landscape's. */
    const val PREF_MODE = "pref_keyboard_view_mode"

    /** The one-handed mode's width, a share of the screen's, as text ("0.8"). */
    const val PREF_ONE_HANDED_WIDTH = "pref_keyboard_one_handed_width"

    /** The floating keyboard's size: its share of the screen's width and its share of Hacker's key height ("0.85,0.9"). */
    const val PREF_FLOAT_SIZE = "pref_keyboard_float_size"

    /** Where the floating keyboard is: its top left corner as shares of the screen ("0.07,0.5"). */
    const val PREF_FLOAT_PLACE = "pref_keyboard_float_place"

    private const val LANDSCAPE_SUFFIX = "_landscape"

    /** The key [base] is kept under for one orientation: [base] for portrait, with "_landscape" for landscape. */
    @JvmStatic
    fun key(base: String, landscape: Boolean): String = if (landscape) base + LANDSCAPE_SUFFIX else base

    /** Every stored value whose change builds the keys again, both orientations (the place only moves the frame). */
    @JvmField
    val REBUILDING_KEYS: List<String> = listOf(PREF_MODE, PREF_ONE_HANDED_WIDTH, PREF_FLOAT_SIZE).flatMap { listOf(it, it + LANDSCAPE_SUFFIX) }

    // One-handed (HeliBoard's one-handed mode: a stored scale per orientation, clamped, and three buttons on the free side).

    const val ONE_HANDED_DEFAULT = 0.8f
    const val ONE_HANDED_MIN = 0.5f

    /** At most this share of the screen, so the free side keeps room for its three buttons. */
    const val ONE_HANDED_MAX = 0.9f

    /** [width] held between [ONE_HANDED_MIN] and [ONE_HANDED_MAX]; the default for nonsense. Pure. */
    @JvmStatic
    fun clampOneHanded(width: Float): Float = if (width.isNaN()) ONE_HANDED_DEFAULT else width.coerceIn(ONE_HANDED_MIN, ONE_HANDED_MAX)

    /** The stored one-handed width, or the default. Pure. */
    @JvmStatic
    fun oneHandedWidth(stored: String?): Float = clampOneHanded(stored?.trim()?.toFloatOrNull() ?: ONE_HANDED_DEFAULT)

    /**
     * The width after the resize handle moved [dxPx] on a screen [screenPx] wide: a grid at the left edge grows as the
     * handle goes right, one at the right edge ([atEnd]) as it goes left. Clamped. Pure.
     */
    @JvmStatic
    fun oneHandedAfterDrag(width: Float, dxPx: Float, screenPx: Int, atEnd: Boolean): Float {
        if (screenPx <= 0) return clampOneHanded(width)
        val share = dxPx / screenPx
        return clampOneHanded(width + if (atEnd) -share else share)
    }

    // Floating (FlorisBoard's floating window: a size clamped between bounds, offsets kept inside the screen).

    /** The floating keyboard's [width] (a share of the screen's) and [heightScale] (a share of Hacker's key height). */
    data class FloatSize(val width: Float, val heightScale: Float)

    /** The narrowest floating keyboard: FlorisBoard's phone minimums, 55% upright and 28% sideways, a little wider for Hacker's rows. */
    @JvmStatic
    fun floatMinWidth(landscape: Boolean): Float = if (landscape) 0.3f else 0.55f

    /** The widest: the whole screen upright; sideways, three quarters, or there is nothing left to float over. */
    @JvmStatic
    fun floatMaxWidth(landscape: Boolean): Float = if (landscape) 0.75f else 1f

    @JvmStatic
    fun floatDefault(landscape: Boolean): FloatSize = if (landscape) FloatSize(0.5f, 0.8f) else FloatSize(0.85f, 0.85f)

    const val FLOAT_MIN_HEIGHT = 0.5f
    const val FLOAT_MAX_HEIGHT = 1.2f

    /** [size] held to the bounds for its orientation; the default for nonsense. Pure. */
    @JvmStatic
    fun clampFloatSize(size: FloatSize, landscape: Boolean): FloatSize {
        val default = floatDefault(landscape)
        val w = if (size.width.isNaN()) default.width else size.width.coerceIn(floatMinWidth(landscape), floatMaxWidth(landscape))
        val h = if (size.heightScale.isNaN()) default.heightScale else size.heightScale.coerceIn(FLOAT_MIN_HEIGHT, FLOAT_MAX_HEIGHT)
        return FloatSize(w, h)
    }

    /** The stored floating size ("width,height"), clamped, or the default. Pure. */
    @JvmStatic
    fun floatSize(stored: String?, landscape: Boolean): FloatSize {
        val parts = stored?.split(',')?.mapNotNull { it.trim().toFloatOrNull() }
        if (parts == null || parts.size != 2) return floatDefault(landscape)
        return clampFloatSize(FloatSize(parts[0], parts[1]), landscape)
    }

    @JvmStatic
    fun encode(size: FloatSize): String = String.format(Locale.ROOT, "%.4f,%.4f", size.width, size.heightScale)

    /**
     * The size after the corner moved [dxPx] across and [dyPx] down, on a screen [screenWidthPx] wide whose keys are
     * [keysHeightPx] tall at Hacker's own height: the corner follows the finger. Clamped. Pure.
     */
    @JvmStatic
    fun resized(size: FloatSize, dxPx: Float, dyPx: Float, screenWidthPx: Int, keysHeightPx: Int, landscape: Boolean): FloatSize {
        val w = if (screenWidthPx > 0) size.width + dxPx / screenWidthPx else size.width
        val h = if (keysHeightPx > 0) size.heightScale + dyPx / keysHeightPx else size.heightScale
        return clampFloatSize(FloatSize(w, h), landscape)
    }

    /** A frame on the screen, in pixels: its top left corner and size. */
    data class Frame(val x: Int, val y: Int, val width: Int, val height: Int)

    /**
     * [frame] kept on a screen [rootWidth] by [rootHeight] (FlorisBoard's `ImeWindowProps.Floating.constrained`): no
     * bigger than the screen, and its offsets inside it, so it can always be reached and moved back. Pure.
     */
    @JvmStatic
    fun clampFrame(frame: Frame, rootWidth: Int, rootHeight: Int): Frame {
        val w = frame.width.coerceIn(0, rootWidth.coerceAtLeast(0))
        val h = frame.height.coerceIn(0, rootHeight.coerceAtLeast(0))
        val x = frame.x.coerceIn(0, (rootWidth - w).coerceAtLeast(0))
        val y = frame.y.coerceIn(0, (rootHeight - h).coerceAtLeast(0))
        return Frame(x, y, w, h)
    }

    /**
     * Where a frame [width] by [height] goes on a screen [rootWidth] by [rootHeight]: at the stored place (shares of the
     * screen, "x,y"), else centred at the bottom, where a docked keyboard would be. Clamped. Pure.
     */
    @JvmStatic
    fun place(stored: String?, width: Int, height: Int, rootWidth: Int, rootHeight: Int): Frame {
        val parts = stored?.split(',')?.mapNotNull { it.trim().toFloatOrNull() }?.takeIf { it.size == 2 && it.none(Float::isNaN) }
        val x = parts?.let { (it[0] * rootWidth).roundToInt() } ?: ((rootWidth - width) / 2)
        val y = parts?.let { (it[1] * rootHeight).roundToInt() } ?: (rootHeight - height)
        return clampFrame(Frame(x, y, width, height), rootWidth, rootHeight)
    }

    /** [frame]'s place as shares of a [rootWidth] by [rootHeight] screen, for [place]. Pure. */
    @JvmStatic
    fun encodePlace(frame: Frame, rootWidth: Int, rootHeight: Int): String = String.format(
        Locale.ROOT,
        "%.4f,%.4f",
        if (rootWidth > 0) frame.x.toFloat() / rootWidth else 0f,
        if (rootHeight > 0) frame.y.toFloat() / rootHeight else 0f,
    )

    // The form each mode draws.

    /**
     * The key grid's form in [mode]: Normal's is [normal] (the key grid shape setting, H1), one-handed is the
     * [oneHandedWidth] at its edge, floating is the [floatSize], the thumb split is [thumb] where the screen is wide
     * enough and H1's split elsewhere, and the number pad is the full width. Pure.
     */
    @JvmStatic
    fun form(mode: Mode, normal: KeyboardForm.Form, oneHandedWidth: Float, floatSize: FloatSize, thumb: KeyboardForm.Form?): KeyboardForm.Form =
        when (mode) {
            Mode.NORMAL, Mode.SPLIT -> normal
            Mode.LEFT -> KeyboardForm.oneHanded(clampOneHanded(oneHandedWidth), atEnd = false)
            Mode.RIGHT -> KeyboardForm.oneHanded(clampOneHanded(oneHandedWidth), atEnd = true)
            Mode.FLOATING -> KeyboardForm.Form(Mode.FLOATING.id, floatSize.width, 0f, false, floatSize.heightScale)
            Mode.THUMB -> thumb ?: KeyboardForm.SPLIT
            Mode.NUMBER_PAD -> KeyboardForm.FULL
        }

    /**
     * The modes the chooser lists, in its order: Normal, Split (H1's, on every screen), one-handed left and right,
     * floating, the thumb split only where the screen is wide enough for one, the number pad. Pure.
     */
    @JvmStatic
    fun offered(thumbAvailable: Boolean): List<Mode> = Mode.entries.filter { it != Mode.THUMB || thumbAvailable }

    /** The modes the shape setting says, rather than the mode setting. */
    private val SHAPES = setOf(Mode.NORMAL, Mode.SPLIT, Mode.LEFT, Mode.RIGHT)

    /**
     * What choosing [choice] stores: the mode setting's value and the shape setting's, or null to leave the shape as it
     * is. Normal is the full width (normal mode, shape "full"); Split, left and right are the shape the Layout button
     * would store; floating, thumb split and the number pad leave the shape for when Normal or a shape is chosen. Pure.
     */
    @JvmStatic
    fun writes(choice: Mode): Pair<String, String?> = when (choice) {
        Mode.NORMAL -> Mode.NORMAL.id to KeyboardForm.FULL.id
        Mode.SPLIT -> Mode.NORMAL.id to KeyboardForm.SPLIT.id
        Mode.LEFT -> Mode.NORMAL.id to KeyboardForm.LEFT.id
        Mode.RIGHT -> Mode.NORMAL.id to KeyboardForm.RIGHT.id
        else -> choice.id to null
    }

    /**
     * The mode the input method draws, and the chooser lights, from the stored [mode] and the shape in use now
     * ([shapeId], the key grid shape setting's choice for this orientation with H1's automatic default applied): floating,
     * the thumb split and the number pad from the mode; otherwise the shape's split, left, right, or Normal for the full
     * width. A stored "left" or "right" mode (H12c's first build kept them there; [migrated] moves them) still reads as
     * one-handed. Pure.
     */
    @JvmStatic
    fun chosen(mode: String?, shapeId: String?): Mode {
        val stored = Mode.of(mode)
        if (stored !in SHAPES || stored == Mode.LEFT || stored == Mode.RIGHT) return stored
        return when (shapeId) {
            KeyboardForm.SPLIT.id -> Mode.SPLIT
            KeyboardForm.LEFT.id -> Mode.LEFT
            KeyboardForm.RIGHT.id -> Mode.RIGHT
            else -> Mode.NORMAL
        }
    }

    /** The one-handed width a shape chosen before H12c had: the shape setting's fixed 70%. */
    const val SHAPE_ONE_HANDED_WIDTH = 0.7f

    /**
     * One orientation's settings moved to one store, or null when nothing changes: a "left" or "right" kept in the mode
     * setting by H12c's first build becomes the shape setting's ([mode] to "normal", the shape to it); and a one-handed
     * shape chosen before H12c keeps its 70% as the one-handed width, unless a width is already kept ([widthSet]).
     * Returns the mode, the shape and the width to store, each null when it stays. Pure.
     */
    @JvmStatic
    fun migrated(mode: String?, shape: String?, widthSet: Boolean): Triple<String?, String?, String?>? {
        val movedShape = mode?.takeIf { it == Mode.LEFT.id || it == Mode.RIGHT.id }
        val newShape = movedShape ?: shape
        val oneHanded = newShape == KeyboardForm.LEFT.id || newShape == KeyboardForm.RIGHT.id
        val width = if (oneHanded && !widthSet && movedShape == null) String.format(Locale.ROOT, "%.4f", SHAPE_ONE_HANDED_WIDTH) else null
        if (movedShape == null && width == null) return null
        return Triple(movedShape?.let { Mode.NORMAL.id }, movedShape, width)
    }

    /** Set once [migrate] has run. */
    const val PREF_MIGRATED = "pref_keyboard_modes_one_store"

    /** [migrated] for both orientations, once ([PREF_MIGRATED]). Runs after [KeyboardForm.migrate]. */
    @JvmStatic
    fun migrate(prefs: android.content.SharedPreferences) {
        if (prefs.getBoolean(PREF_MIGRATED, false)) return
        val edit = prefs.edit().putBoolean(PREF_MIGRATED, true)
        for (landscape in listOf(false, true)) {
            val change = migrated(
                prefs.getString(key(PREF_MODE, landscape), null),
                prefs.getString(KeyboardForm.prefFor(landscape), null),
                prefs.contains(key(PREF_ONE_HANDED_WIDTH, landscape)),
            ) ?: continue
            change.first?.let { edit.putString(key(PREF_MODE, landscape), it) }
            change.second?.let { edit.putString(KeyboardForm.prefFor(landscape), it) }
            change.third?.let { edit.putString(key(PREF_ONE_HANDED_WIDTH, landscape), it) }
        }
        edit.apply()
    }

    // The compact keyboard of droidtop's add-on screen and companion (KeyboardPanel).

    /**
     * A finger's press target, in inches: Android's 48dp at its 160 dpi, the finger size `ShellWindow.pressTarget` gives
     * a touch-first press target (Droidtop/tracker#740). The compact keyboard never makes a row shorter.
     */
    const val FINGER_INCHES = 0.3f

    /** The share of its usual height the compact keyboard asks for, before [FINGER_INCHES] holds it up. */
    const val COMPACT_HEIGHT_SHARE = 0.8f

    /** The rows of Hacker's 5-row layouts, which the compact keyboard draws. */
    const val COMPACT_ROWS = 5

    /**
     * Whether an embedded keyboard is drawn compact: on a screen other than the device's own ([onDefaultDisplay]
     * false: the add-on screen, where droidtop's companion and the keyboard over its apps are), when that screen is a
     * handheld's ([KeyboardForm.handheld]). An external monitor is not a handheld's, so it keeps the usual keyboard. Pure.
     */
    @JvmStatic
    fun compactPanel(onDefaultDisplay: Boolean, handheld: Boolean): Boolean = !onDefaultDisplay && handheld

    /**
     * The compact keyboard's height, in percent of a screen [screenHeightPx] tall at [ydpi]: [COMPACT_HEIGHT_SHARE] of
     * the [requested] height, never less than [rows] of [FINGER_INCHES] each, and never more than [requested]. A screen
     * reporting no believable dpi keeps the share alone. Pure.
     */
    @JvmStatic
    fun compactHeightPercent(requested: Float, screenHeightPx: Int, ydpi: Float, rows: Int = COMPACT_ROWS): Float {
        val wanted = requested * COMPACT_HEIGHT_SHARE
        if (screenHeightPx <= 0 || ydpi !in 80f..1000f) return wanted
        val floor = rows * FINGER_INCHES * ydpi / screenHeightPx * 100f
        return maxOf(wanted, floor).coerceAtMost(requested)
    }

    // Read and written in the keyboard's settings.

    @JvmStatic
    fun landscape(context: Context): Boolean = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /** The mode for [context]'s orientation ([chosen]); Normal wherever the keyboard is not the input method's. */
    @JvmStatic
    fun current(context: Context): Mode {
        if (!KeyboardAppearance.drawnByInputMethod(context)) return Mode.NORMAL
        val prefs = ToolsPrefs.prefs(context)
        val shape = KeyboardForm.forScreen(prefs.getString(KeyboardForm.PREF, null), prefs.getString(KeyboardForm.PREF_LANDSCAPE, null), context)
        return chosen(prefs.getString(key(PREF_MODE, landscape(context)), null), shape.id)
    }

    /**
     * Keeps [choice] for the current orientation ([writes]): the mode setting, and the shape setting as the Layout button
     * stores it. The input method builds itself again when either changes (GlobalKeyboardSettings). In memory at once;
     * written in the background.
     */
    @JvmStatic
    fun choose(context: Context, choice: Mode) {
        val landscape = landscape(context)
        val (mode, shape) = writes(choice)
        // The shape the keys are built with is read from the shared settings, which must hold it before the rebuild.
        if (shape != null) {
            if (landscape) LatinIME.sKeyboardSettings.formLandscapeChoice = shape else LatinIME.sKeyboardSettings.formChoice = shape
        }
        val edit = ToolsPrefs.prefs(context).edit()
        shape?.let { edit.putString(KeyboardForm.prefFor(landscape), it) }
        edit.putString(key(PREF_MODE, landscape), mode).apply()
    }

    /** The one-handed width for [context]'s orientation. */
    @JvmStatic
    fun oneHandedWidth(context: Context): Float = oneHandedWidth(ToolsPrefs.prefs(context).getString(key(PREF_ONE_HANDED_WIDTH, landscape(context)), null))

    @JvmStatic
    fun setOneHandedWidth(context: Context, width: Float) {
        ToolsPrefs.prefs(context).edit()
            .putString(key(PREF_ONE_HANDED_WIDTH, landscape(context)), String.format(Locale.ROOT, "%.4f", clampOneHanded(width))).apply()
    }

    /** The floating size for [context]'s orientation. */
    @JvmStatic
    fun floatSize(context: Context): FloatSize = landscape(context).let { floatSize(ToolsPrefs.prefs(context).getString(key(PREF_FLOAT_SIZE, it), null), it) }

    @JvmStatic
    fun setFloatSize(context: Context, size: FloatSize) {
        val landscape = landscape(context)
        ToolsPrefs.prefs(context).edit().putString(key(PREF_FLOAT_SIZE, landscape), encode(clampFloatSize(size, landscape))).apply()
    }

    @JvmStatic
    fun floatPlace(context: Context): String? = ToolsPrefs.prefs(context).getString(key(PREF_FLOAT_PLACE, landscape(context)), null)

    @JvmStatic
    fun setFloatPlace(context: Context, place: String) {
        ToolsPrefs.prefs(context).edit().putString(key(PREF_FLOAT_PLACE, landscape(context)), place).apply()
    }

    /** The form [context]'s keys are drawn in: [normal] (the shape setting's) in Normal and outside the input method. */
    @JvmStatic
    fun formFor(context: Context, normal: KeyboardForm.Form): KeyboardForm.Form {
        val mode = current(context)
        if (mode == Mode.NORMAL || mode == Mode.SPLIT) return normal
        return form(mode, normal, oneHandedWidth(context), floatSize(context), if (mode == Mode.THUMB) KeyboardForm.thumbForScreen(context) else null)
    }
}
