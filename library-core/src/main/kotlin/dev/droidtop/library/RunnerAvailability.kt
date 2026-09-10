package dev.droidtop.library

/**
 * What a runner's situation is for one game on THIS device — the model
 * docs/SPEC.md §7i calls for, and the reason the PC surface can be honest
 * instead of showing a Play button that is known in advance to fail.
 *
 * The last two states are deliberately different things, and the split is
 * the whole point:
 *
 * - [NOT_ON_THIS_DEVICE] is information the user can act on outside
 *   droidtop (root the console, plug something in). It is shown, dimmed,
 *   with its reason, because somebody who does not know an option exists
 *   cannot decide about it.
 * - [NOT_FOR_THIS_GAME] is a fact about the game, not the device (no
 *   `.exe`, so no Wine). It is hidden behind a "why not" expansion,
 *   because a column of "no" rows buries the real choice.
 */
enum class RunnerState {
    READY,
    NEEDS_SETUP,
    NOT_ON_THIS_DEVICE,
    NOT_FOR_THIS_GAME,
}

/**
 * The one named thing a [RunnerState.NEEDS_SETUP] row asks for. An id
 * rather than a lambda so the model stays pure and Android-free; the
 * surface maps the id to the real intent or dialog it already has.
 */
enum class RunnerAction(val id: String) {
    INSTALL_ENGINEHOST("install_enginehost"),
    INSTALL_ENGINEHOST_PLUGIN("install_enginehost_plugin"),
    CHOOSE_ENGINE_VERSION("choose_engine_version"),
    INSTALL_KIRIKIROID2("install_kirikiroid2"),
    SET_UP_WINDOWS_GAMES("set_up_windows_games"),
}

/**
 * One runner, for one game, with the single line the user reads about it.
 *
 * [caveat] is for a real limitation of a runner that is otherwise READY —
 * Kirikiroid2 opens its own app rather than this game — because an honest
 * capability beats a silent one.
 */
data class RunnerOption(
    val strategy: GameLaunchStrategy,
    val state: RunnerState,
    val reason: String? = null,
    val action: RunnerAction? = null,
    val caveat: String? = null,
) {
    /** Whether this row can be chosen at all: the two "no" states cannot. */
    val selectable: Boolean get() = state == RunnerState.READY || state == RunnerState.NEEDS_SETUP
}

/**
 * Every fact [RunnerAvailability.evaluate] needs, measured by the caller.
 *
 * Deliberately plain values rather than a `Context`: this model is the
 * one place the four runners' rules live, and keeping it Android-free is
 * what lets [RunnerAvailabilityTest] check all of them as plain JVM unit
 * tests with no Robolectric.
 *
 * Several fields default to the optimistic value. That is not a guess
 * about the device — it is the "not measured" case, used by
 * [GameLaunchStrategyResolver.resolve], whose question is narrower ("what
 * do this game's folder and the installed apps offer") than the surface's
 * ("what can happen on this console right now"). A caller that has the
 * device facts passes them; one that does not gets the narrower answer
 * instead of a fabricated one.
 */
data class RunnerFacts(
    /** Null for a PC entry with no engine detected — a Steam game is still a game with a Wine row. */
    val engine: GameEngine?,
    val hasWindowsExecutable: Boolean = false,
    val hasLinuxBuild: Boolean = false,
    /** Whether the engines database declares an enginehost mapping for [engine]. */
    val enginehostSupported: Boolean = true,
    val enginehostInstalled: Boolean = false,
    /** Whether enginehost's own UID can read the game folder — the path-only contract. */
    val enginehostCanReachFolder: Boolean = true,
    /** An `enginehost.json` in the folder, or a remembered manual choice. */
    val enginehostEngineVersionKnown: Boolean = false,
    /**
     * Whether an installed bundle covers this engine and version. Null
     * means not measured (enginehost's capabilities provider was not
     * read), which stays advisory: it never turns a resolvable launch
     * into a setup step.
     */
    val enginehostBundleCovers: Boolean? = null,
    val kirikiroid2Installed: Boolean = false,
    /** Whether the Windows environment (ImageFS + a container) has been provisioned. */
    val windowsEnvironmentReady: Boolean = true,
    /** Whether a renderer is actually attached to the Wine X server — see [RunnerAvailability.WINE_RENDERER_WIRED]. */
    val wineRendererWired: Boolean = true,
    /** Whether a container runtime that can really exec a Linux binary is present. Root-only today. */
    val linuxContainerAvailable: Boolean = true,
    /** Whether x86 binaries are registered for translation (`binfmt_misc` + FEX, docs/SPEC.md §3c). */
    val x86TranslationRegistered: Boolean = true,
    /** The engines database's declared priority; ranks what is already available, never decides availability. */
    val preferredOrder: List<GameLaunchStrategy> = emptyList(),
)

/**
 * The single computation behind both "what will launch" and "what the
 * user is told" — docs/SPEC.md §7i's availability model.
 *
 * Availability first, the database's priority second: the priority list
 * only ranks rows that are already available, which is what turns it from
 * an invisible constant into a stated default on the game.
 */
object RunnerAvailability {

    /**
     * Whether a renderer is attached to the headless X server
     * `BionicWineEngine` starts (`WineEngine.kt`). While this is false a
     * Wine launch produces no picture, so the Wine row reads "needs
     * setup" with that reason rather than offering Play.
     *
     * One table entry, on purpose: when the renderer seam lands this
     * constant is what flips (build-plan step 8), not a scattering of
     * conditions.
     */
    const val WINE_RENDERER_WIRED = false

    /**
     * Every runner's state for one game, ordered: the rows the user can
     * act on first (READY, then NEEDS_SETUP), each group in the database's
     * declared priority, then the dimmed device rows, then the hidden
     * game rows.
     */
    fun evaluate(facts: RunnerFacts): List<RunnerOption> = listOf(
        enginehost(facts),
        kirikiroid2(facts),
        wine(facts),
        linuxContainer(facts),
    ).sortedWith(
        compareBy(
            { it.state.ordinal },
            { facts.preferredOrder.indexOf(it.strategy).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE },
            { it.strategy.ordinal },
        ),
    )

    private fun enginehost(facts: RunnerFacts): RunnerOption {
        val strategy = GameLaunchStrategy.ENGINEHOST
        if (facts.engine == null || !facts.enginehostSupported) {
            return RunnerOption(strategy, RunnerState.NOT_FOR_THIS_GAME, "No enginehost plugin covers this game's engine")
        }
        if (!facts.enginehostInstalled) {
            return RunnerOption(strategy, RunnerState.NEEDS_SETUP, "Install enginehost", RunnerAction.INSTALL_ENGINEHOST)
        }
        if (!facts.enginehostCanReachFolder) {
            // No action id: nothing droidtop can press fixes this. The
            // fix is where the game lives, so the row says that and the
            // surface shows the reason instead of a button.
            return RunnerOption(
                strategy,
                RunnerState.NEEDS_SETUP,
                "enginehost can't read this folder - move the game to a shared games folder",
            )
        }
        if (!facts.enginehostEngineVersionKnown) {
            return RunnerOption(
                strategy,
                RunnerState.NEEDS_SETUP,
                "Pick which engine version this game uses",
                RunnerAction.CHOOSE_ENGINE_VERSION,
            )
        }
        // Advisory (§7d): a bundle list droidtop could not read leaves the
        // row Ready, because enginehost may still resolve the launch. Only
        // a list that was read and says "nothing covers this" becomes a
        // setup step, and its action asks enginehost to install it.
        if (facts.enginehostBundleCovers == false) {
            return RunnerOption(
                strategy,
                RunnerState.NEEDS_SETUP,
                "Install the ${facts.engine.pluginLabel()} plugin",
                RunnerAction.INSTALL_ENGINEHOST_PLUGIN,
            )
        }
        return RunnerOption(strategy, RunnerState.READY)
    }

    private fun kirikiroid2(facts: RunnerFacts): RunnerOption {
        val strategy = GameLaunchStrategy.KIRIKIROID2
        if (facts.engine != GameEngine.KIRIKIRI) {
            return RunnerOption(strategy, RunnerState.NOT_FOR_THIS_GAME, "Not a KiriKiri game")
        }
        if (!facts.kirikiroid2Installed) {
            return RunnerOption(strategy, RunnerState.NEEDS_SETUP, "Install Kirikiroid2", RunnerAction.INSTALL_KIRIKIROID2)
        }
        // Real limitation of the app's own intent surface, stated where
        // the choice is made rather than buried in a doc comment.
        return RunnerOption(strategy, RunnerState.READY, caveat = "Opens the app, not this game")
    }

    private fun wine(facts: RunnerFacts): RunnerOption {
        val strategy = GameLaunchStrategy.WINE_PREFIX
        if (!facts.hasWindowsExecutable) {
            return RunnerOption(strategy, RunnerState.NOT_FOR_THIS_GAME, "No Windows executable in this game's folder")
        }
        if (!facts.windowsEnvironmentReady) {
            return RunnerOption(
                strategy,
                RunnerState.NEEDS_SETUP,
                "Set up Windows games",
                RunnerAction.SET_UP_WINDOWS_GAMES,
            )
        }
        if (!facts.wineRendererWired) {
            // No action id: nothing the user can do makes this true. It is
            // still NEEDS_SETUP rather than NOT_ON_THIS_DEVICE because the
            // console is perfectly capable — this build is not.
            return RunnerOption(strategy, RunnerState.NEEDS_SETUP, "The Windows renderer isn't wired in this build")
        }
        return RunnerOption(strategy, RunnerState.READY)
    }

    private fun linuxContainer(facts: RunnerFacts): RunnerOption {
        val strategy = GameLaunchStrategy.LINUX_CONTAINER
        // KiriKiri is Windows-native with no official Linux port of the
        // engine, so a stray lib/*linux* folder never means this.
        if (facts.engine == GameEngine.KIRIKIRI) {
            return RunnerOption(strategy, RunnerState.NOT_FOR_THIS_GAME, "KiriKiri has no native Linux build")
        }
        if (!facts.hasLinuxBuild) {
            return RunnerOption(strategy, RunnerState.NOT_FOR_THIS_GAME, "No native Linux build in this game's folder")
        }
        // Root is desktop-only, and a Handheld game is never gated on it:
        // this row states the reason instead of offering a launch that
        // cannot work, and every other runner for the game stays offered.
        if (!facts.linuxContainerAvailable) {
            return RunnerOption(strategy, RunnerState.NOT_ON_THIS_DEVICE, "Running a native Linux build needs root")
        }
        if (!facts.x86TranslationRegistered) {
            // Also no action id: registering x86 binaries for translation
            // (docs/SPEC.md 3c) is container-side work droidtop has not
            // built, so the row states it rather than offering a button.
            return RunnerOption(
                strategy,
                RunnerState.NEEDS_SETUP,
                "this build's x86 translation isn't registered in the container",
            )
        }
        return RunnerOption(strategy, RunnerState.READY)
    }

    /**
     * The runner a game actually runs with, and why — the "Runs with" row.
     *
     * [override] is [LaunchStrategyOverridePrefs]' stored value, which
     * wins whenever it names a row the user could pick. Otherwise the
     * best available row wins, and the reason names the default so the
     * database's priority stops being invisible.
     *
     * Returns the dimmed [RunnerState.NOT_ON_THIS_DEVICE] row when that
     * is genuinely all a game has, rather than nothing: §7i's "where it
     * genuinely is the only one, the game says so with the reason".
     */
    fun resolve(options: List<RunnerOption>, override: String?, defaultLabel: String? = null): ResolvedRunner? {
        val chosen = override?.let { name -> options.firstOrNull { it.strategy.name == name && it.selectable } }
        if (chosen != null) return ResolvedRunner(chosen, "your choice")
        val best = options.firstOrNull { it.selectable }
        if (best != null) {
            val alternatives = options.count { it.selectable }
            val reason = when {
                alternatives <= 1 -> "the only runner for this game"
                defaultLabel != null -> "default for $defaultLabel"
                else -> "droidtop's default"
            }
            return ResolvedRunner(best, reason)
        }
        val dimmed = options.firstOrNull { it.state == RunnerState.NOT_ON_THIS_DEVICE } ?: return null
        return ResolvedRunner(dimmed, "the only runner this game offers")
    }

    /** How an engine is named in "Install the … plugin". */
    private fun GameEngine.pluginLabel(): String = when (this) {
        GameEngine.RENPY -> "Ren'Py"
        GameEngine.KIRIKIRI -> "KiriKiri"
        GameEngine.RPG_MAKER_MV, GameEngine.RPG_MAKER_MZ, GameEngine.RPG_MAKER_VX_ACE,
        GameEngine.RPG_MAKER_VX, GameEngine.RPG_MAKER_XP, GameEngine.RPG_MAKER_2000_2003,
        -> "RPG Maker"
        GameEngine.AUGUST -> "AUGUST"
        GameEngine.BURIKO -> "BGI"
        GameEngine.CATSYSTEM2 -> "CatSystem2"
        GameEngine.CMVS -> "CMVS"
        GameEngine.FLASH_AIR -> "Flash/AIR"
        GameEngine.GODOT -> "Godot"
        GameEngine.HTML -> "HTML"
        GameEngine.UNREAL -> "Unreal"
        GameEngine.UNITY -> "Unity"
    }
}

/** The resolved "Runs with" row plus the one-line reason it won. */
data class ResolvedRunner(val option: RunnerOption, val reason: String)
