package dev.droidtop.library.settings

import android.content.Context

/**
 * How much of the shell a person is allowed to see (real ES-DE's own UI
 * modes, which exist for the same reason: a handheld gets handed to
 * somebody else).
 *
 * FULL is droidtop as normal. KIOSK hides the parts that change the
 * device rather than play a game -- Settings, and the destructive
 * actions inside gamelist options. KID additionally shows only games
 * marked as kid-friendly (the `kidGame` flag droidtop already stores per
 * game and real ES-DE calls the same thing).
 *
 * The way OUT deliberately does not live in Settings, because Settings
 * is the thing being hidden: the Quick Menu's System tab keeps a row for
 * it. That is a real escape hatch a parent can find and a child is
 * unlikely to stumble into. An optional passkey ([UiModePasskey], none by
 * default) makes that row ask for it.
 *
 * What each mode shows and hides, on every surface, is [ControlAccess]'s
 * to say; this enum only names the modes.
 */
enum class UiMode(val label: String) {
    FULL("Full"),
    KIOSK("Kiosk (no settings)"),
    KID("Kid (kid-friendly games only)"),
    ;

    // Both answers come from ControlAccess, the one restriction model (docs/SPEC.md "UI modes and ControlAccess").
    val hidesSettings: Boolean get() = !ControlAccess.rules(this).settings
    val kidGamesOnly: Boolean get() = ControlAccess.rules(this).kidGamesOnly
}

object UiModePrefs {
    private const val PREFS_NAME = LAUNCHER_PREFS_FILE_NAME
    private const val KEY = "droidtop_ui_mode"

    fun get(context: Context): UiMode {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return UiMode.FULL
        return runCatching { UiMode.valueOf(raw) }.getOrDefault(UiMode.FULL)
    }

    fun set(context: Context, mode: UiMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY, mode.name).apply()
    }
}

/**
 * The live UI mode, so a change made in the Quick Menu reaches the shell
 * without a restart. Same "one writer bumps, everyone observes" shape
 * the theme and collection refreshes already use.
 */
object UiModeRefresh {
    private val state = kotlinx.coroutines.flow.MutableStateFlow(UiMode.FULL)
    val mode: kotlinx.coroutines.flow.StateFlow<UiMode> = state

    /** Call once at shell start so the flow reflects what is stored. */
    fun load(context: Context) {
        state.value = UiModePrefs.get(context)
    }

    fun set(context: Context, mode: UiMode) {
        UiModePrefs.set(context, mode)
        state.value = mode
    }
}

/**
 * The optional passkey for leaving Kid and Kiosk (docs/SPEC.md "UI modes and ControlAccess", Droidtop/tracker#414),
 * ES-DE's own UI mode passkey in droidtop's form. None by default: leaving is then one press, as before. Set, the
 * Quick Menu's Leave row asks for it by pad or touch ([ENTRY_ACTIVITY]). Stored as a salted SHA-256, never the
 * digits; forgetting it is recovered by clearing droidtop's storage ([RECOVERY]).
 */
object UiModePasskey {
    private const val PREFS_NAME = LAUNCHER_PREFS_FILE_NAME
    private const val KEY_HASH = "droidtop_ui_mode_passkey_hash"
    private const val KEY_SALT = "droidtop_ui_mode_passkey_salt"

    /** The passkey entry screen (`:app`), named so this module does not depend on it. */
    const val ENTRY_ACTIVITY = "dev.droidtop.app.UiModePasskeyActivity"

    const val RECOVERY = "Forgot it? Android Settings > Apps > droidtop > Clear storage resets droidtop's settings."

    val LENGTHS = 4..8

    data class Stored(val salt: String, val hash: String)

    /** A passkey is four to eight digits: what the entry screen's keypad can type, by pad or touch. */
    fun valid(passkey: String): Boolean = passkey.length in LENGTHS && passkey.all { it in '0'..'9' }

    fun hash(salt: String, passkey: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest("$salt:$passkey".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun make(passkey: String, salt: String = newSalt()): Stored = Stored(salt, hash(salt, passkey))

    /** Whether [entered] lets the person leave: anything does when no passkey is set. */
    fun matches(stored: Stored?, entered: String): Boolean = stored == null || hash(stored.salt, entered) == stored.hash

    fun stored(context: Context): Stored? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val hash = prefs.getString(KEY_HASH, null) ?: return null
        val salt = prefs.getString(KEY_SALT, null) ?: return null
        return Stored(salt, hash)
    }

    fun isSet(context: Context): Boolean = stored(context) != null

    /** Sets the passkey, or removes it for a blank [passkey]; an invalid one changes nothing. Returns whether it changed. */
    fun set(context: Context, passkey: String): Boolean {
        val edit = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        when {
            passkey.isBlank() -> edit.remove(KEY_HASH).remove(KEY_SALT)
            valid(passkey) -> make(passkey).let { edit.putString(KEY_SALT, it.salt).putString(KEY_HASH, it.hash) }
            else -> return false
        }
        edit.apply()
        return true
    }

    private fun newSalt(): String {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
