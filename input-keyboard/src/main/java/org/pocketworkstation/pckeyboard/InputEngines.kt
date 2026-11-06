package org.pocketworkstation.pckeyboard

import android.app.Activity
import android.content.Context
import android.os.Looper
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The engines other than Hacker's Latin one, by language (Droidtop/tracker#903 H6; docs/SPEC.md 6a, "The engine
 * seam"). A conversion engine (Mozc for Japanese, librime for Chinese, libhangul for Korean, H7 to H9) registers a
 * [Factory] here; the input method asks [forLanguage] each time its language is set up, and uses [LatinEngine] when no
 * factory answers. The keyboard module names no engine: :app registers them at start (#903 H7c: Mozc for Japanese,
 * from :input-japanese), so the keyboard stays free of every engine's library and data, and the standalone keyboard
 * builds without them.
 */
object InputEngines {
    /** Makes the engine for a language. */
    interface Factory {
        /** The engine's own name ("mozc"), which names its [Profile] directory. Letters, digits and dashes. */
        val id: String

        /**
         * The engine for [language] (an input language code, "ja", "zh_CN"), or null when it has none for it. Main
         * thread, and cheap: the engine loads its data on a thread of its own, saying [InputEngine.State.Loading] meanwhile.
         */
        fun create(context: Context, language: String, profile: Profile): InputEngine?

        /**
         * Whether it types [language] (a language code, "ja"), without making an engine: the language list offers a
         * language Hacker's own path cannot type (Japanese, Korean, Chinese) only when an engine serves it.
         */
        fun serves(language: String): Boolean = false
    }

    /**
     * A row an engine adds to the keyboard's settings (#903 H7c: "Japanese", with its dictionary). The keyboard's
     * settings screen lists every registered row, after the languages.
     */
    interface SettingsRow {
        /** The row's preference key; [KEY_PREFIX] and the engine's id. */
        val key: String

        fun title(context: Context): CharSequence

        /** What the row says now. Called off the main thread, so it may look at files. */
        fun summary(context: Context): CharSequence

        /** The row was pressed; [changed] runs on the main thread when what it says may have changed. */
        fun open(activity: Activity, changed: Runnable)
    }

    /** Every [SettingsRow.key] starts with this, so the short settings screen keeps them (KeyboardProMode). */
    const val KEY_PREFIX = "pref_engine_"

    /**
     * Where an engine keeps what it learns (Droidtop/tracker#903 H7a): context.noBackupFilesDir/engine-<id>, so it is
     * neither backed up nor in the user's files. Resolving it touches the disk, so [directory] refuses the main thread.
     */
    class Profile(private val context: Context, val id: String) {
        /** The directory, made on first use. Never on the main thread. */
        fun directory(): File {
            check(Looper.myLooper() != Looper.getMainLooper()) { "engine profile on the main thread" }
            return File(context.noBackupFilesDir, "engine-$id").apply { mkdirs() }
        }
    }

    private val factories = CopyOnWriteArrayList<Factory>()
    private val rows = CopyOnWriteArrayList<SettingsRow>()

    @JvmStatic
    fun register(factory: Factory) {
        factories.addIfAbsent(factory)
    }

    @JvmStatic
    fun registerSettings(row: SettingsRow) {
        rows.addIfAbsent(row)
    }

    /** The registered settings rows, in the order they were registered. */
    @JvmStatic
    fun settingsRows(): List<SettingsRow> = rows.toList()

    /** Whether a registered engine types [language] ("ja", "ja_JP"). */
    @JvmStatic
    fun serves(language: String): Boolean = factories.any { it.serves(language) }

    /** The first registered engine for [language]; null for Hacker's own Latin path. */
    @JvmStatic
    fun forLanguage(context: Context, language: String): InputEngine? =
        factories.firstNotNullOfOrNull { it.create(context, language, Profile(context.applicationContext ?: context, it.id)) }
}
