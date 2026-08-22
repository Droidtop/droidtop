package app.gamenative

/**
 * Minimal, real compatibility shim -- `com.winlator.core.GeneralComponents`
 * (forked in wholesale) imports `app.gamenative.MainActivity` but, per a
 * real check of its own actual usage this session, never calls anything
 * on it (a dead/unused import in the real upstream source, harmless in
 * Kotlin/Java either way since an unused import still needs the class to
 * exist for the compiler). Empty on purpose -- droidtop's own MainActivity
 * is `dev.droidtop.app.MainActivity` (`:app`), a completely different
 * class; this shim exists only so this specific dead reference resolves.
 */
class MainActivity
