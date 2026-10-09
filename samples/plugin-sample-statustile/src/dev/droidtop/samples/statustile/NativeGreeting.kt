package dev.droidtop.samples.statustile

/**
 * The sample's native library (`native/greeting.c`), loaded the ordinary way. Contained, droidtop maps it from the
 * descriptor it handed over (docs/plugin-api.md 5.3, "Guarded hooks"), and the JNI method binds by its symbol name.
 */
object NativeGreeting {
    /** What the library says, or why it could not be loaded, for the panel. */
    fun line(): String = runCatching {
        System.loadLibrary("samplegreeting")
        hello()
    }.getOrElse { "not loaded: ${it.message ?: it::class.java.simpleName}" }

    @JvmStatic private external fun hello(): String
}
