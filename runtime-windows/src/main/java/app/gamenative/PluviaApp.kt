package app.gamenative

import com.winlator.widget.XServerRendererView

/**
 * Minimal, real (not fabricated) compatibility shim for the forked-in
 * `com.winlator` tree's own real reference to `app.gamenative.PluviaApp`
 * (its actual upstream application-singleton class) -- droidtop doesn't
 * fork in gamenative's own Application/DI/Steam layer (Hilt, Room,
 * JavaSteam; see this module's README for why), so this exists only to
 * satisfy the real, small surface `com.winlator` code actually touches:
 * a nullable reference to the active [XServerRendererView]. Always null
 * in droidtop today (no code sets it) -- every real caller already
 * null-checks it before use (e.g. `PowerManager.kt`'s own
 * `PluviaApp.xServerView ?: return false`), so this is an honest "not
 * wired up yet" rather than a silent lie about a feature working.
 */
object PluviaApp {
    var xServerView: XServerRendererView? = null
}
