package org.pocketworkstation.pckeyboard

import android.content.Context
import android.os.Looper
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The engines other than Hacker's Latin one, by language (Droidtop/tracker#903 H6; docs/SPEC.md 6a, "The engine
 * seam"). A conversion engine (Mozc for Japanese, librime for Chinese, libhangul for Korean, H7 to H9) registers a
 * [Factory] here; the input method asks [forLanguage] each time its language is set up, and uses [LatinEngine] when no
 * factory answers. None is registered in this slice, so every language types through Hacker's own path.
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
    }

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

    @JvmStatic
    fun register(factory: Factory) {
        factories.addIfAbsent(factory)
    }

    /** The first registered engine for [language]; null for Hacker's own Latin path. */
    @JvmStatic
    fun forLanguage(context: Context, language: String): InputEngine? =
        factories.firstNotNullOfOrNull { it.create(context, language, Profile(context.applicationContext ?: context, it.id)) }
}
