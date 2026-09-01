package dev.droidtop.library

import android.content.Context
import java.io.File
import org.json.JSONObject

/**
 * The manual path for engines droidtop cannot version or classify itself.
 *
 * Without this, an undetectable game had exactly one outcome: droidtop
 * handed the user to enginehost's own CONFIGURE screen and made them
 * finish the job there. That is the wrong default for droidtop, which is
 * meant to do everything programmatically and keep its own decisions on
 * its own screens (docs/SPEC.md §7g's "actions where the thing is").
 * Being unable to *detect* a context is not a reason to stop being able
 * to *launch* one.
 *
 * The order becomes: detected values first; an answer already recorded
 * second; droidtop's own picker over what enginehost really has installed
 * third; and only then CONFIGURE, as an explicit fallback rather than the
 * forced route.
 *
 * Once the user has answered, the answer can be **persisted two ways**,
 * and both are genuinely useful:
 *
 * - **Into the game's own `enginehost.json`** ([writeFolderConfig]).
 *   That file is authoritative by contract, so this makes every future
 *   launch a plain LAUNCH from *any* caller — droidtop, enginehost's own
 *   library, anything else — and it travels with the game folder.
 * - **Into droidtop's preferences** ([set]). Used when the folder is not
 *   writable, which is common for games on an SD card, and available for
 *   anyone who would rather droidtop not touch their game directories.
 *
 * Writing the folder config is not "fabricating" one: it records an
 * answer the user explicitly gave, which is exactly what enginehost's own
 * CONFIGURE screen does — just without the forced hand-off.
 */
object EnginehostManualChoicePrefs {
    private const val PREFS_NAME = "com.android.launcher3.prefs"
    private const val KEY_PREFIX = "droidtop_enginehost_choice_"

    /** One resolved answer: the compatibility line and the runtime version. */
    data class Choice(val engineContext: String?, val engineVersion: String)

    private fun key(gameFolder: File) = KEY_PREFIX + gameFolder.absolutePath

    fun get(context: Context, gameFolder: File): Choice? {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(key(gameFolder), null)
            ?: return null
        // "<context>|<version>", with an empty context meaning "default".
        val parts = raw.split('|', limit = 2)
        if (parts.size != 2 || parts[1].isBlank()) return null
        return Choice(parts[0].ifBlank { null }, parts[1])
    }

    fun set(context: Context, gameFolder: File, choice: Choice?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (choice == null) {
            prefs.edit().remove(key(gameFolder)).apply()
        } else {
            prefs.edit()
                .putString(key(gameFolder), "${choice.engineContext.orEmpty()}|${choice.engineVersion}")
                .apply()
        }
    }

    /**
     * Writes the answer as the game's own `enginehost.json`, the form the
     * contract treats as authoritative.
     *
     * Refuses to overwrite an existing file: that one already wins by
     * contract, and replacing it would be droidtop overriding a value the
     * user or another tool deliberately set. Returns false when the folder
     * cannot be written, so the caller can fall back to [set].
     */
    fun writeFolderConfig(gameFolder: File, engine: String, choice: Choice): Boolean {
        val target = File(gameFolder, "enginehost.json")
        if (target.exists()) return false
        return runCatching {
            val json = JSONObject().apply {
                put("engine", engine)
                choice.engineContext?.let { put("engineContext", it) }
                put("engineVersion", choice.engineVersion)
            }
            target.writeText(json.toString(2))
            true
        }.getOrDefault(false)
    }
}

/**
 * One thing enginehost could actually run this game as, built from its
 * own installed-bundle list rather than from droidtop's guesses.
 *
 * Offering anything else would be dishonest: a context droidtop invents
 * resolves to no bundle, and the user gets a failure they cannot act on.
 */
data class EnginehostRunOption(
    val engineContext: String?,
    val engineVersion: String,
    /** What to show the user: "vxace · 3.01 (plugin 1.0.0)". */
    val label: String,
    val bundleId: String,
)
