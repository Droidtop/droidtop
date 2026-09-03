package dev.droidtop.library

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent

/**
 * Launcher-wide launch-display targeting (docs/SPEC.md section 4,
 * handheld dual-screen roles): every game/app launch goes through
 * [start] so the whole launcher system honors one launch-screen model —
 * console ROM players, engine games (enginehost/Kirikiroid2), and native
 * apps alike. Desktop mode never sets a target (its windows are the
 * compositor's job, section 4).
 *
 * The model, per section 4c: a remembered per-game choice wins, then a
 * per-system default, then the chooser ("ask" is the configured
 * default — never silently assume a screen the first time), then the
 * globally configured target. Remembering happens in the chooser's own
 * "always" rows, and both levels are clearable ([LaunchScreenMemory]).
 * The priority chain itself is [LaunchScreenResolution], pure and
 * unit-tested.
 *
 * Everything here is process state, set by the shell (MainActivity
 * resolves it from DisplayRolePrefs + the live display list) rather than
 * each library-core call site re-reading preferences it shouldn't know
 * the shape of.
 */
data class LaunchDisplayOption(val displayId: Int?, val label: String)

/**
 * Which game a launch is for, so [LaunchDisplay] can look up and record
 * remembered screens. Published by [Library.launch] around the provider
 * call — the ONE launch path — rather than threaded through every
 * provider's signature.
 */
data class LaunchContext(val gameId: String, val systemId: String?)

object LaunchDisplay {
    @Volatile
    var targetDisplayId: Int? = null

    /**
     * The current second/addon display, for resolving a remembered
     * [LaunchScreen.SECOND] to a real id. Null when only one display is
     * attached.
     */
    @Volatile
    var secondDisplayId: Int? = null

    /**
     * Candidate displays to ask between, or null when no asking should
     * happen (single display, or an explicit target preference).
     */
    @Volatile
    var askOptions: List<LaunchDisplayOption>? = null

    /** See [LaunchContext]. Set by [Library.launch]; null for launches with no game identity (e.g. opening Kirikiroid2's own UI). */
    @Volatile
    var launchContext: LaunchContext? = null

    /**
     * Installed by the shell owning launch UI: presents [askOptions] and
     * invokes the continuation with the chosen option and whether to
     * remember it for this game — or never, if the user backs out (the
     * launch is simply abandoned; nothing was started yet).
     */
    @Volatile
    var chooser: ((options: List<LaunchDisplayOption>, canRemember: Boolean, onChosen: (chosen: LaunchDisplayOption, remember: Boolean) -> Unit) -> Unit)? = null

    /**
     * The display the most recent launch was sent to, kept until the shell
     * deliberately reclaims it — the "don't interfere with apps we've
     * launched" half of the home-press display reinit (see MainActivity's
     * role orchestration): a reinit re-asserts droidtop's surfaces on
     * every display EXCEPT one still parked here, so a game running on
     * the addon isn't covered by the shell or the widgets panel. Cleared
     * by an explicit shell entry (BackButtonMenu's Handheld item).
     */
    @Volatile
    var parkedDisplayId: Int? = null

    /** Invoked after every [start] — the shell hooks this to re-run its display-role orchestration (e.g. dismissing the widgets Presentation off a display a game just went to). */
    @Volatile
    var onLaunched: ((Int?) -> Unit)? = null

    /**
     * Invoked just BEFORE the launch intent is dispatched, with the
     * display the launch is going to. The shell hooks this to place
     * droidtop's idle surface on any secondary display the launch is
     * about to leave empty — an empty secondary display falls back to
     * Android's built-in MIRRORING of the default display, which is
     * exactly the "launching apps mirrors them" report (docs/SPEC.md
     * section 4c). Ordering matters: the covering surface starts first
     * so the game's own launch lands last and keeps input focus.
     */
    @Volatile
    var coverVacatedDisplays: ((launchTargetDisplayId: Int?) -> Unit)? = null

    fun start(context: Context, intent: Intent) {
        val ctx = launchContext
        val remembered = ctx?.let { LaunchScreenMemory.choiceFor(context, it.gameId, it.systemId) }
        val options = askOptions
        val ask = chooser
        val askable = options != null && options.size > 1 && ask != null

        when (val decision = LaunchScreenResolution.decide(remembered, secondDisplayId, askable, targetDisplayId)) {
            is LaunchScreenResolution.Decision.Start -> startOn(context, intent, decision.displayId)
            LaunchScreenResolution.Decision.Ask -> ask!!(options!!, ctx != null) { chosen, remember ->
                if (remember && ctx != null) {
                    LaunchScreenMemory.setGameChoice(
                        context,
                        ctx.gameId,
                        LaunchScreenResolution.screenFor(chosen.displayId, secondDisplayId),
                    )
                }
                startOn(context, intent, chosen.displayId)
            }
        }
    }

    private fun startOn(context: Context, intent: Intent, displayId: Int?) {
        coverVacatedDisplays?.invoke(displayId)
        if (displayId != null) {
            context.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle())
        } else {
            context.startActivity(intent)
        }
        parkedDisplayId = displayId
        onLaunched?.invoke(displayId)
    }
}
