package dev.droidtop.library

import android.content.Context

/**
 * Which screen a launch should use, in the relative vocabulary
 * docs/SPEC.md section 4c directs (read off iiSU's own strings): the
 * built-in panel or the second/addon one, never a raw display id — ids
 * change across attach/detach and reboots, a role does not.
 */
enum class LaunchScreen(val label: String) {
    BUILT_IN("Built-in screen"),
    SECOND("Add-on screen"),
}

/**
 * Remembered launch-screen choices: a per-game preference over a
 * per-system default over nothing (docs/SPEC.md section 4c item 2 —
 * "a per-platform default, with a per-game override, and a way to clear
 * it"; the same default-plus-priority model section 7e2 directs for
 * emulator players, applied to displays).
 *
 * Storage is one small prefs file; resolution is [LaunchScreenResolution],
 * which is pure and unit-tested without any Android type.
 */
object LaunchScreenMemory {
    private const val PREFS_NAME = "launch_screen_choices"
    private const val GAME_PREFIX = "game:"
    private const val SYSTEM_PREFIX = "system:"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun read(context: Context, key: String): LaunchScreen? =
        prefs(context).getString(key, null)
            ?.let { stored -> runCatching { LaunchScreen.valueOf(stored) }.getOrNull() }

    fun gameChoice(context: Context, gameId: String): LaunchScreen? =
        read(context, GAME_PREFIX + gameId)

    fun systemChoice(context: Context, systemId: String): LaunchScreen? =
        read(context, SYSTEM_PREFIX + systemId)

    /** The effective remembered choice for a launch: game wins over system. */
    fun choiceFor(context: Context, gameId: String?, systemId: String?): LaunchScreen? =
        LaunchScreenResolution.remembered(
            game = gameId?.let { gameChoice(context, it) },
            system = systemId?.let { systemChoice(context, it) },
        )

    fun setGameChoice(context: Context, gameId: String, screen: LaunchScreen?) {
        prefs(context).edit().apply {
            if (screen == null) remove(GAME_PREFIX + gameId) else putString(GAME_PREFIX + gameId, screen.name)
        }.apply()
    }

    fun setSystemChoice(context: Context, systemId: String, screen: LaunchScreen?) {
        prefs(context).edit().apply {
            if (screen == null) remove(SYSTEM_PREFIX + systemId) else putString(SYSTEM_PREFIX + systemId, screen.name)
        }.apply()
    }
}

/**
 * The launch-screen decision as pure functions, so the whole priority
 * chain is testable without a device — which matters here more than
 * usual, because display work cannot be verified on hardware from this
 * environment (docs/SPEC.md section 6c).
 */
object LaunchScreenResolution {

    /** Per-game choice wins over the per-system default (section 4c). */
    fun remembered(game: LaunchScreen?, system: LaunchScreen?): LaunchScreen? = game ?: system

    /** What [LaunchDisplay.start] should do for one launch. */
    sealed class Decision {
        /** Start on this display id (null = the default display). */
        data class Start(val displayId: Int?) : Decision()

        /** Defer to the chooser dialog. */
        object Ask : Decision()
    }

    /**
     * The full priority chain for one launch:
     * 1. a remembered per-game/per-system choice — the user already
     *    answered for this game, so asking again would be noise;
     * 2. otherwise ask, when asking is configured and possible;
     * 3. otherwise the globally configured target display.
     *
     * A remembered SECOND with no second display currently attached
     * degrades to the default display rather than failing the launch:
     * the game still starts, on the only screen there is.
     */
    fun decide(
        remembered: LaunchScreen?,
        secondDisplayId: Int?,
        askable: Boolean,
        globalTarget: Int?,
    ): Decision = when {
        remembered == LaunchScreen.BUILT_IN -> Decision.Start(null)
        remembered == LaunchScreen.SECOND -> Decision.Start(secondDisplayId)
        askable -> Decision.Ask
        else -> Decision.Start(globalTarget)
    }

    /** The [LaunchScreen] a chosen display id means, for remembering it. */
    fun screenFor(displayId: Int?, secondDisplayId: Int?): LaunchScreen =
        if (displayId != null && displayId == secondDisplayId) LaunchScreen.SECOND else LaunchScreen.BUILT_IN
}
