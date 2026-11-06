package dev.droidtop.input.japanese

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import org.pocketworkstation.pckeyboard.InputEngine
import org.pocketworkstation.pckeyboard.InputEngines

/**
 * Japanese for droidtop's keyboard (Droidtop/tracker#903 H7c; docs/SPEC.md 6a, "The engine seam"). :app calls
 * [install] at start (Seams.kt), which registers Mozc's engine for the input language "ja" with [InputEngines] and adds
 * the "Japanese" row to the keyboard's settings. :input-keyboard knows nothing of this module.
 */
object MozcJapanese {
    /** The engine's id, which names its profile directory: noBackupFilesDir/engine-mozc. */
    const val ID = "mozc"

    /** The open Mozc dictionary's size on the device (mozc.data, 19,001,738 bytes), in MB as the settings say it. */
    const val DICTIONARY_MB = 19

    @JvmStatic
    fun install() {
        InputEngines.register(Factory)
        InputEngines.registerSettings(SettingsRow)
    }

    /** Mozc for "ja" and "ja_JP": one engine for the process, as Mozc has one session handler for it. */
    private object Factory : InputEngines.Factory {
        override val id: String = ID

        private var engine: MozcEngine? = null

        override fun serves(language: String): Boolean = language.substringBefore('_') == "ja"

        override fun create(context: Context, language: String, profile: InputEngines.Profile): InputEngine? {
            if (!serves(language)) return null
            MozcRuntime.attach(context, profile)
            val made = engine ?: MozcEngine(MozcRuntime).also { engine = it }
            // The first use of Japanese: the dictionary downloads now if it is not here, never at app start.
            MozcRuntime.start()
            return made
        }
    }

    /**
     * Keyboard settings > "Japanese": which dictionary it uses and whether it is downloaded; "Forget what it learned",
     * which asks first; "Delete the dictionary".
     */
    private object SettingsRow : InputEngines.SettingsRow {
        override val key: String = InputEngines.KEY_PREFIX + ID

        @Volatile
        private var present = false

        @Volatile
        private var note: Int = 0

        override fun title(context: Context): CharSequence = context.getString(R.string.mozc_settings_title)

        override fun summary(context: Context): CharSequence {
            MozcRuntime.attachIfNeeded(context)
            present = MozcRuntime.dictionaryPresent()
            val line = when {
                present -> context.getString(R.string.mozc_settings_dictionary_present, DICTIONARY_MB)
                MozcRuntime.loaded -> context.getString(R.string.mozc_settings_dictionary_deleted)
                else -> context.getString(R.string.mozc_settings_dictionary_absent, DICTIONARY_MB)
            }
            val said = note
            note = 0
            return if (said != 0) context.getString(said) + "\n" + line else line
        }

        override fun open(activity: Activity, changed: Runnable) {
            MozcRuntime.attachIfNeeded(activity)
            val forget = activity.getString(R.string.mozc_settings_forget)
            val delete = activity.getString(R.string.mozc_settings_delete)
            val items = if (present) arrayOf<CharSequence>(forget, delete) else arrayOf<CharSequence>(forget)
            AlertDialog.Builder(activity)
                .setTitle(R.string.mozc_settings_title)
                .setItems(items) { _, which ->
                    if (which == 0) confirmForget(activity, changed) else deleteDictionary(changed)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        private fun confirmForget(activity: Activity, changed: Runnable) {
            AlertDialog.Builder(activity)
                .setTitle(R.string.mozc_settings_forget)
                .setMessage(R.string.mozc_settings_forget_body)
                .setPositiveButton(R.string.mozc_settings_forget_action) { _, _ ->
                    MozcRuntime.forget {
                        note = R.string.mozc_settings_forgotten
                        changed.run()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        private fun deleteDictionary(changed: Runnable) {
            MozcRuntime.deleteDictionary {
                // While loaded, the row's line already says it was deleted and what happens next.
                note = if (MozcRuntime.loaded) 0 else R.string.mozc_settings_deleted
                changed.run()
            }
        }
    }
}
