package dev.droidtop.shell.gamepad.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import dev.droidtop.library.settings.CatalogPrefs
import dev.droidtop.library.settings.GamingSettingsCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn

/**
 * Keeps the interface sounds current while the Gaming shell is composed: the bundled set loaded
 * ([EsDeNavigationSounds.attachPack]) and Gaming's Navigation sounds switch observed, so turning it
 * off in Settings silences the next cue without a restart. The preference is read off the main thread.
 */
@Composable
internal fun SoundSync() {
    val context = LocalContext.current.applicationContext
    LaunchedEffect(context) {
        EsDeNavigationSounds.attachPack(context)
        callbackFlow {
            val prefs = CatalogPrefs.prefs(context)
            val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == null || key == GamingSettingsCatalog.ID_NAVIGATION_SOUNDS) trySend(GamingSettingsCatalog.navigationSoundsOn(context))
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            trySend(GamingSettingsCatalog.navigationSoundsOn(context))
            awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }.conflate().flowOn(Dispatchers.IO).collect { EsDeNavigationSounds.enabled = it }
    }
}

/**
 * The shell's interface-sound roles (docs/SPEC.md "Interface sounds", Droidtop/tracker#363 slice 10,
 * #211): Steam's cue vocabulary (a move, a press that went nowhere, a tab, a side menu opening and
 * closing, a modal showing and hiding, a toggle, a slider step, a page entered and left, a toast,
 * confirm, back, launch), restated as droidtop's own roles. Nothing of Valve's is used: each role
 * plays the active ES-DE theme's own sample where [themeName] names one of the seven navigation
 * sounds a theme can declare (ES-DE's NavigationSounds, Sound.cpp:213-219), else droidtop's bundled
 * set ([packFile], a CC0 pack once one is bundled), else nothing.
 *
 * [move] roles answer a direction: they are not played for a touch-driven move, as Steam plays no
 * sound for a pointer hover, and a held direction at an end does not bump again.
 */
enum class UiSound(val themeName: String?, val move: Boolean = false) {
    MOVE("scroll", move = true),
    BUMP(null, move = true),
    TAB("quicksysselect", move = true),
    SYSTEM("systembrowse", move = true),
    SLIDER("scroll", move = true),
    CONFIRM("select"),
    BACK("back"),
    LAUNCH("launch"),
    FAVORITE("favorite"),
    PANEL_OPEN("select"),
    PANEL_CLOSE("back"),
    PAGE_IN("select"),
    PAGE_OUT("back"),
    MODAL_SHOW(null),
    MODAL_HIDE(null),
    TOGGLE_ON("select"),
    TOGGLE_OFF("back"),
    TOAST(null),
    ;

    /** The bundled set's file stem for this role, under `assets/ui-sounds/` (`move.ogg`, `panel_open.wav`). */
    val packFile: String get() = name.lowercase()
}

/**
 * The shell's one toast (docs/SPEC.md "Interface sounds"): the toast cue, then Android's own toast.
 * The platform's toast is kept on purpose: Android draws it above every window, so it is seen over
 * a menu or the game page, where a toast drawn in the shell's own window would sit underneath them.
 */
fun shellToast(context: android.content.Context, text: String, long: Boolean = false) {
    EsDeNavigationSounds.play(UiSound.TOAST)
    android.widget.Toast.makeText(context, text, if (long) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT).show()
}

/** Where a role's sound comes from: the theme's sample, droidtop's bundled one, or nowhere. */
sealed interface UiSoundSource {
    data class Theme(val name: String) : UiSoundSource
    data class Pack(val file: String) : UiSoundSource
    data object None : UiSoundSource
}

/**
 * The theme overrides the bundled set, role by role (Droidtop/tracker#211): [themeSounds] are the
 * navigation sounds the active theme has bound, [packFiles] the bundled set's file stems. Pure.
 */
fun uiSoundSource(sound: UiSound, themeSounds: Set<String>, packFiles: Set<String>): UiSoundSource = when {
    sound.themeName != null && sound.themeName in themeSounds -> UiSoundSource.Theme(sound.themeName)
    sound.packFile in packFiles -> UiSoundSource.Pack(sound.packFile)
    else -> UiSoundSource.None
}

/**
 * At most one cue per [windowMs] (Steam's 50 ms): a burst of presses, or a move that also opens
 * something, makes one sound, not a clatter. The launch cue is never held back. Not thread-safe:
 * cues are played from the main thread.
 */
class CueThrottle(private val windowMs: Long = 50L) {
    private var lastMs = Long.MIN_VALUE / 2

    fun allow(sound: UiSound, nowMs: Long): Boolean {
        if (sound != UiSound.LAUNCH && nowMs - lastMs < windowMs) return false
        lastMs = nowMs
        return true
    }
}
