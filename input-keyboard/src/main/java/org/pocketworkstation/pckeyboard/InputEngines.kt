package org.pocketworkstation.pckeyboard

import android.content.Context
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The engines other than Hacker's Latin one, by language (Droidtop/tracker#903 H6; docs/SPEC.md 6a, "The engine
 * seam"). A conversion engine (Mozc for Japanese, librime for Chinese, libhangul for Korean, H7 to H9) registers a
 * [Factory] here; the input method asks [forLanguage] each time its language is set up, and uses [LatinEngine] when no
 * factory answers. None is registered in this slice, so every language types through Hacker's own path.
 */
object InputEngines {
    /** Makes the engine for [language] (an input language code, "ja", "zh_CN"), or null when it has none for it. */
    fun interface Factory {
        fun create(context: Context, language: String): InputEngine?
    }

    private val factories = CopyOnWriteArrayList<Factory>()

    @JvmStatic
    fun register(factory: Factory) {
        factories.addIfAbsent(factory)
    }

    /** The first registered engine for [language]; null for Hacker's own Latin path. Main thread, and cheap: engines load their data lazily. */
    @JvmStatic
    fun forLanguage(context: Context, language: String): InputEngine? = factories.firstNotNullOfOrNull { it.create(context, language) }
}
