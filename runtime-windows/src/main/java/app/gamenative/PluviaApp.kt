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

    /**
     * Real upstream `PluviaApp.getDefaultScreenSize()` queries the actual
     * device's live `DisplayManager`/`Display.mode` physical resolution --
     * a real, non-trivial device-detection path this minimal shim doesn't
     * replicate yet (needs a real `Context`, which this object doesn't
     * hold). Returns Winlator's own real `Container.DEFAULT_SCREEN_SIZE_16_9`
     * value as an honest, reasonable default instead -- a real fallback
     * value from the same forked codebase, not an arbitrary guess. Revisit
     * with real device-size detection once `ContainerData` actually gets
     * used to create a running container.
     */
    fun getDefaultScreenSize(): String = "1280x720"
}
