package dev.droidtop.runtime.keyboard

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Android's per-display keyboard placement, by the framework's own values (`WindowManager.DISPLAY_IME_POLICY_*`).
 * A secondary display defaults to [FALLBACK_DISPLAY]: an editor there gets its keyboard window on the built-in
 * display (`DisplayWindowSettings.getImePolicyLocked`, `InputMethodManagerService.computeImeDisplayIdForTarget`).
 */
enum class DisplayImePolicy(val code: Int) {
    LOCAL(0),
    FALLBACK_DISPLAY(1),
    HIDE(2),
    ;

    companion object {
        fun fromCode(code: Int?): DisplayImePolicy? = entries.firstOrNull { it.code == code }
    }
}

/** How droidtop's own keyboard reaches another app's text field. */
enum class TypingRoute {
    /** droidtop's input method is selected and bound to the editor: its input connection (no privilege needed). */
    DROIDTOP_IME,

    /**
     * droidtop's accessibility service ([AccessibilityKeyboard]) is on and holds the focused text field: the field's
     * own accessibility actions (set text, paste, set selection, IME enter). Works whichever keyboard is selected.
     */
    ACCESSIBILITY,

    /** The elevated helper's `input -d <display>` (Shizuku or Sui). */
    ELEVATED_INPUT,

    /** None of them: the surface shows what would make it work. */
    NONE,
}

/** Which of droidtop's two overlays draws its keyboard over another app's text field, if either. */
enum class OverlayOwner {
    /** `ShowOverEditor`: droidtop's input method was asked for the keyboard; needs "Display over other apps". */
    INPUT_METHOD,

    /** droidtop's accessibility service: an accessibility overlay, which needs no other grant. */
    ACCESSIBILITY,

    NONE,
}

/** What typing into other apps on the second screen runs on right now: the value of the Displays row. */
enum class AppsKeyboard(val label: String) {
    ANDROID("Android keyboard"),
    DROIDTOP("droidtop keyboard"),
    ACCESSIBILITY("Accessibility"),
    NEEDS_OVERLAY("Needs display over apps"),
    OFF("Off"),
}

/**
 * The rules for typing on the add-on display (docs/SPEC.md 4c, "Typing on the add-on display",
 * Droidtop/tracker#314), with no Android in them so they are unit-tested.
 */
object AddonKeyboardRules {
    const val DEFAULT_DISPLAY = 0

    /** Whether Android draws its own keyboard on [displayId]: the built-in display, or one droidtop set local. */
    fun androidDrawsKeyboard(displayId: Int, localDisplays: Set<Int>): Boolean =
        displayId == DEFAULT_DISPLAY || displayId in localDisplays

    /** Whether a droidtop text field on [displayId] draws droidtop's keyboard in its own window. */
    fun ownFieldNeedsKeyboard(displayId: Int?, localDisplays: Set<Int>): Boolean =
        displayId != null && !androidDrawsKeyboard(displayId, localDisplays)

    /**
     * The route droidtop's keyboard types through into another app, best first: droidtop's input method's connection,
     * then droidtop's accessibility service's focused field, then the elevated helper's `input` command. The
     * companion's Keys panel and both overlays use this one order.
     */
    fun route(droidtopImeHasEditor: Boolean, accessibilityHasEditor: Boolean, elevatedShell: Boolean): TypingRoute = when {
        droidtopImeHasEditor -> TypingRoute.DROIDTOP_IME
        accessibilityHasEditor -> TypingRoute.ACCESSIBILITY
        elevatedShell -> TypingRoute.ELEVATED_INPUT
        else -> TypingRoute.NONE
    }

    /**
     * Who draws droidtop's keyboard over another app's text field on [editorDisplayId]: nobody where Android draws
     * one itself; the input method's overlay when droidtop's input method is the selected one and may draw over
     * apps; else the accessibility service's overlay when that service is on. One overlay at a time, never both.
     */
    fun overlayOwner(
        editorDisplayId: Int?,
        localDisplays: Set<Int>,
        droidtopImeSelected: Boolean,
        canDrawOverlays: Boolean,
        accessibilityOn: Boolean,
    ): OverlayOwner = when {
        editorDisplayId == null || androidDrawsKeyboard(editorDisplayId, localDisplays) -> OverlayOwner.NONE
        droidtopImeSelected && canDrawOverlays -> OverlayOwner.INPUT_METHOD
        accessibilityOn -> OverlayOwner.ACCESSIBILITY
        else -> OverlayOwner.NONE
    }

    /** The Displays row's value: Android's own keyboard, else droidtop's by the overlay that would draw it. */
    fun appsKeyboard(androidShows: Boolean, droidtopImeSelected: Boolean, canDrawOverlays: Boolean, accessibilityOn: Boolean): AppsKeyboard = when {
        androidShows -> AppsKeyboard.ANDROID
        droidtopImeSelected && canDrawOverlays -> AppsKeyboard.DROIDTOP
        accessibilityOn -> AppsKeyboard.ACCESSIBILITY
        droidtopImeSelected -> AppsKeyboard.NEEDS_OVERLAY
        else -> AppsKeyboard.OFF
    }

    /** What one pass of the policy keeper does: displays to set local, and displays to give back their old policy. */
    data class PolicyPlan(val setLocal: Set<Int>, val restore: Map<Int, DisplayImePolicy>)

    /**
     * [applied] maps each display droidtop set local to the policy it had before. With the setting on, every
     * second display is (re)set local: the call is idempotent and Android may have forgotten a display that was
     * unplugged. With it off, every display droidtop changed gets its old policy back. Without elevated access
     * nothing can be changed either way, and the record is kept for when it returns.
     */
    fun plan(enabled: Boolean, elevated: Boolean, secondDisplays: Set<Int>, applied: Map<Int, DisplayImePolicy>): PolicyPlan = when {
        !elevated -> PolicyPlan(emptySet(), emptyMap())
        enabled -> PolicyPlan(secondDisplays, emptyMap())
        else -> PolicyPlan(emptySet(), applied)
    }

    /** `input` for one key on [displayId] (the `-d` option exists from Android 10). */
    fun inputKeyArgv(displayId: Int, keyCode: Int): List<String> =
        listOf("input", "-d", displayId.toString(), "keyevent", keyCode.toString())

    /**
     * `input` for typed text on [displayId], or null when there is nothing it can type. `input text` reads `%s` as a
     * space and takes no other escapes; it types through the virtual keyboard map, so only ASCII printable text.
     */
    fun inputTextArgv(displayId: Int, text: CharSequence): List<String>? {
        val typable = text.filter { it in ' '..'~' }.toString()
        if (typable.isEmpty()) return null
        return listOf("input", "-d", displayId.toString(), "text", typable.replace(" ", "%s"))
    }

    /** "15:1,16:1" to a map of display to policy, skipping anything malformed. */
    fun parseApplied(raw: String?): Map<Int, DisplayImePolicy> =
        raw.orEmpty().split(',').mapNotNull { part ->
            val (id, code) = part.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            val display = id.trim().toIntOrNull() ?: return@mapNotNull null
            val policy = DisplayImePolicy.fromCode(code.trim().toIntOrNull()) ?: return@mapNotNull null
            display to policy
        }.toMap()

    fun formatApplied(applied: Map<Int, DisplayImePolicy>): String =
        applied.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value.code}" }

    /** The `policy=N` line the policy tool prints. */
    fun parsePolicyLine(stdout: String): DisplayImePolicy? =
        stdout.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("policy=") }
            ?.removePrefix("policy=")?.toIntOrNull()?.let { DisplayImePolicy.fromCode(it) }
}

/**
 * Live state and the one setting, shared by the shells (which only read) and `:app` (which applies). The
 * setting rows live in the settings catalog here; the work behind them is installed by `:app`.
 */
object AddonKeyboard {
    private const val PREFS = "addon_keyboard"
    const val KEY_ANDROID_KEYBOARD = "android_keyboard_on_second_screen"
    private const val KEY_APPLIED = "applied_policies"

    private val local = MutableStateFlow<Set<Int>>(emptySet())

    /** Displays where droidtop made Android draw its own keyboard (confirmed by reading the policy back). */
    val localDisplays: StateFlow<Set<Int>> = local

    fun publishLocal(displays: Set<Int>) {
        local.value = displays
    }

    /** Installed by `:app`: re-runs the policy keeper off the main thread. */
    @Volatile
    var resync: ((Context) -> Unit)? = null

    fun androidKeyboardOnSecondScreen(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ANDROID_KEYBOARD, true)

    fun setAndroidKeyboardOnSecondScreen(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ANDROID_KEYBOARD, on).apply()
        resync?.invoke(context)
    }

    /** Reads droidtop's own preferences: not for the main thread's hot path. */
    fun applied(context: Context): Map<Int, DisplayImePolicy> =
        AddonKeyboardRules.parseApplied(prefs(context).getString(KEY_APPLIED, null))

    fun setApplied(context: Context, applied: Map<Int, DisplayImePolicy>) {
        prefs(context).edit().putString(KEY_APPLIED, AddonKeyboardRules.formatApplied(applied)).apply()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
