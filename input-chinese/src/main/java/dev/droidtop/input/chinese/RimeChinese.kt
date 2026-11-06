package dev.droidtop.input.chinese

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import org.pocketworkstation.pckeyboard.InputEngine
import org.pocketworkstation.pckeyboard.InputEngines

/**
 * Chinese for droidtop's keyboard (Droidtop/tracker#903 H8b; docs/SPEC.md 6a, "Chinese through RIME"). :app calls
 * [install] at start (Seams.kt), which registers RIME's engine for the input language "zh" (zh_CN, zh_TW and the
 * rest) with [InputEngines] and adds the "Chinese" row to the keyboard's settings. :input-keyboard knows nothing of
 * this module. MozcJapanese does the same for Japanese.
 */
object RimeChinese {
    /** The engine's id, which names its profile directory: noBackupFilesDir/engine-rime. */
    const val ID = "rime"

    /** The data's size on the device (14,802,733 bytes unpacked, rime-engine.pin's data_size), in MB as the settings say it. */
    const val DATA_MB = 15

    @JvmStatic
    fun install() {
        InputEngines.register(Factory)
        InputEngines.registerSettings(SettingsRow)
    }

    /** RIME for "zh" and its regions: one engine per schema, all over the one librime the process has. */
    private object Factory : InputEngines.Factory {
        override val id: String = ID

        private val engines = HashMap<RimeSchemas.Choice, RimeEngine>()

        override fun serves(language: String): Boolean = RimeSchemas.serves(language)

        override fun create(context: Context, language: String, profile: InputEngines.Profile): InputEngine? {
            val choice = RimeSchemas.forLanguage(language) ?: return null
            RimeRuntime.attach(context, profile)
            val made = engines.getOrPut(choice) { RimeEngine(RimeRuntime, choice) }
            // The first use of Chinese: the data downloads now if it is not here, never at app start.
            RimeRuntime.start()
            return made
        }
    }

    /**
     * Keyboard settings > "Chinese": which dictionary it uses and whether it is downloaded; "Forget what it learned",
     * which asks first; "Delete the dictionary".
     */
    private object SettingsRow : InputEngines.SettingsRow {
        override val key: String = InputEngines.KEY_PREFIX + ID

        @Volatile
        private var present = false

        @Volatile
        private var note: Int = 0

        override fun title(context: Context): CharSequence = context.getString(R.string.rime_settings_title)

        override fun summary(context: Context): CharSequence {
            RimeRuntime.attachIfNeeded(context)
            present = RimeRuntime.dataPresent()
            val line = when {
                present -> context.getString(R.string.rime_settings_data_present, DATA_MB)
                RimeRuntime.running -> context.getString(R.string.rime_settings_data_deleted)
                else -> context.getString(R.string.rime_settings_data_absent, DATA_MB)
            }
            val said = note
            note = 0
            return if (said != 0) context.getString(said) + "\n" + line else line
        }

        override fun open(activity: Activity, changed: Runnable) {
            RimeRuntime.attachIfNeeded(activity)
            val forget = activity.getString(R.string.rime_settings_forget)
            val delete = activity.getString(R.string.rime_settings_delete)
            val items = if (present) arrayOf<CharSequence>(forget, delete) else arrayOf<CharSequence>(forget)
            AlertDialog.Builder(activity)
                .setTitle(R.string.rime_settings_title)
                .setItems(items) { _, which ->
                    if (which == 0) confirmForget(activity, changed) else deleteData(changed)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        private fun confirmForget(activity: Activity, changed: Runnable) {
            AlertDialog.Builder(activity)
                .setTitle(R.string.rime_settings_forget)
                .setMessage(R.string.rime_settings_forget_body)
                .setPositiveButton(R.string.rime_settings_forget_action) { _, _ ->
                    RimeRuntime.forget {
                        note = R.string.rime_settings_forgotten
                        changed.run()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        private fun deleteData(changed: Runnable) {
            RimeRuntime.deleteData {
                // While running, the row's line already says it was deleted and what happens next.
                note = if (RimeRuntime.running) 0 else R.string.rime_settings_deleted
                changed.run()
            }
        }
    }
}
