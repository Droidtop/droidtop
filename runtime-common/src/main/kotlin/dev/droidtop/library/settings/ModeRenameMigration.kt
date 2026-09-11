package dev.droidtop.library.settings

import android.content.Context

/**
 * Carries a person's stored settings across the Handheld -> Gaming rename
 * (docs/SPEC.md, "Modes and what each contributes"). Every droidtop
 * preference lives in one file, so this is one pass over it: keys whose
 * name carried the old word are rewritten, and the two keys that STORE a
 * mode id ("handheld") get their value rewritten.
 *
 * Runs exactly once. The marker is written in the same commit as the
 * migrated entries, so a process killed mid-write repeats the pass rather
 * than half-applying it, and a second run after the marker exists reads
 * nothing at all.
 */
object ModeRenameMigration {
    private const val PREFS_NAME = LAUNCHER_PREFS_FILE_NAME
    private const val MARKER = "droidtop_gaming_rename_applied"

    private const val OLD_MODE_VALUE = "handheld"
    private val VALUE_KEYS = setOf("droidtop_last_mode", "droidtop_default_mode")

    private val KEY_PREFIXES = listOf(
        "droidtop_mode_enabled_handheld" to "droidtop_mode_enabled_gaming",
        "pref_handheld_" to "pref_gaming_",
        "pref_global_enable_handheld" to "pref_global_enable_gaming",
        "pref_desktop_handheld_settings" to "pref_desktop_gaming_settings",
        "droidtop_handheld" to "droidtop_gaming",
        "handheld_system" to "gaming_system",
        // The secondary-display role is keyed by the display Mode's own
        // enum name (SecondScreenInputPrefs), which the rename moved too.
        "pref_second_screen_role_HANDHELD" to "pref_second_screen_role_GAMING",
    )

    /**
     * The whole decision, as a pure function of what is stored, so it is
     * unit-tested without Android: given today's entries, the entries to
     * write and the keys to drop.
     */
    fun migrate(existing: Map<String, Any?>): Migration {
        val writes = LinkedHashMap<String, Any?>()
        val removals = LinkedHashSet<String>()
        for ((key, value) in existing) {
            if (key == MARKER) continue
            val renamed = renameKey(key)
            val rewritten = if ((renamed ?: key) in VALUE_KEYS && value == OLD_MODE_VALUE) {
                Mode.GAMING.id
            } else {
                value
            }
            if (renamed != null) {
                removals += key
                writes[renamed] = rewritten
            } else if (rewritten != value) {
                writes[key] = rewritten
            }
        }
        return Migration(writes, removals)
    }

    private fun renameKey(key: String): String? {
        for ((old, new) in KEY_PREFIXES) {
            if (key.startsWith(old)) return new + key.removePrefix(old)
        }
        return null
    }

    data class Migration(val writes: Map<String, Any?>, val removals: Set<String>) {
        val isEmpty: Boolean get() = writes.isEmpty() && removals.isEmpty()
    }

    fun applyOnce(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(MARKER, false)) return
        val migration = migrate(prefs.all)
        val editor = prefs.edit()
        migration.removals.forEach(editor::remove)
        for ((key, value) in migration.writes) {
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                else -> Unit
            }
        }
        editor.putBoolean(MARKER, true)
        editor.apply()
    }
}
