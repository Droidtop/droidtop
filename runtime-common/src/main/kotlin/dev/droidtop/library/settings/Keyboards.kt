package dev.droidtop.library.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager

/**
 * droidtop's keyboard ownership, and the user's control over it.
 *
 * droidtop ships **Hacker's Keyboard** (`:input-keyboard`, forked from
 * klausw/hackerskeyboard) and wants to be the active input method,
 * because a device that is meant to replace a computer needs a keyboard
 * a computer's software can actually be driven from: Ctrl, Alt, Esc, Tab,
 * arrow keys, and the function row. A terminal, Wine, and any real
 * desktop application are unusable on a stock phone keyboard that has
 * none of those. That is the whole reason it is forked in rather than
 * suggested as a download.
 *
 * Being the active IME also has a second, real effect worth stating
 * plainly rather than leaving as a hidden benefit: from Android 10
 * onwards, only a focused app or the **current input method** may read
 * the clipboard. droidtop's host↔container clipboard bridge therefore
 * works properly exactly when its own keyboard is the active one.
 *
 * **The user keeps control, and is told why.** droidtop cannot and does
 * not silently set the system's input method — changing
 * `Settings.Secure.DEFAULT_INPUT_METHOD` needs `WRITE_SECURE_SETTINGS`,
 * which a normal app is not granted, and per standing direction handheld
 * and launcher features must never depend on root. So this class is
 * entirely: enumerate what's installed, explain the reason, and open
 * Android's own pickers. Every switch is an act the user performs.
 */
object Keyboards {

    data class Keyboard(
        val id: String,
        val label: String,
        val isDroidtops: Boolean,
        val isCurrent: Boolean,
    )

    /** droidtop's own IME id prefix — the forked Hacker's Keyboard. */
    private const val OWN_PACKAGE = "org.pocketworkstation.pckeyboard"

    /** Every enabled input method, with droidtop's own marked. */
    fun enabled(context: Context): List<Keyboard> {
        val imm = context.getSystemService(InputMethodManager::class.java) ?: return emptyList()
        val current = currentId(context)
        return runCatching {
            imm.enabledInputMethodList.map { info ->
                Keyboard(
                    id = info.id,
                    label = info.loadLabel(context.packageManager).toString(),
                    isDroidtops = info.packageName == OWN_PACKAGE,
                    isCurrent = info.id == current,
                )
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Whether droidtop's own keyboard is merely INSTALLED but not enabled
     * — the state that needs a trip to Android's input-method settings
     * before it can even be picked.
     */
    fun ownKeyboardEnabled(context: Context): Boolean =
        enabled(context).any { it.isDroidtops }

    fun ownKeyboardActive(context: Context): Boolean =
        enabled(context).any { it.isDroidtops && it.isCurrent }

    fun currentId(context: Context): String? = runCatching {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
    }.getOrNull()

    /**
     * Android's own keyboard switcher. This is the honest way to change
     * input method from inside droidtop: the system draws it, and the
     * user chooses. No permission required, and nothing droidtop can do
     * behind their back.
     */
    fun showPicker(context: Context) {
        context.getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
    }

    /**
     * Android's input-method settings, where a keyboard is ENABLED before
     * it can be selected at all. Needed the first time, since an
     * installed-but-not-enabled IME does not appear in the picker.
     */
    fun openSystemSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /**
     * The one line droidtop shows to explain itself. Stated as a reason,
     * not a demand — per standing direction that droidtop wants to own
     * the device as much as it usefully can, while the user stays in
     * charge and is told why.
     */
    const val WHY: String =
        "droidtop includes Hacker's Keyboard because desktop software needs Ctrl, Alt, Esc, " +
            "Tab, arrows and function keys — a terminal or a Windows app can't be driven " +
            "without them. You can switch back to any other keyboard at any time."
}
