package dev.droidtop.runtime.windows.utils

import android.content.Context
import dev.droidtop.library.stores.StoreHolding
import dev.droidtop.library.stores.StoreLibraries

/**
 * Whether a game that starts through the Steamworks shim is known to be the person's (docs/SPEC.md 5b, "DRM: platform
 * enablement, never piracy enablement"; Droidtop/tracker#718).
 *
 * Owner, 2026-11-05: "We don't add a check that explicitly checks an account and refuses to run. We DO add a check
 * that checks against an account, and warns the user if it can't find the game on it, with the general checkbox for
 * 'yes, I own this game'." And, since Steam state comes and goes: a persistent per-game "owned" flag, set once (the
 * person ticks "Yes, I own this game", or the first time droidtop's Steam lists the app on the signed-in account) and
 * never cleared by a sync, a network failure or a cache clear. A flagged game starts without any check; only a game
 * never flagged is checked, and then only warned about. Nothing is ever refused.
 *
 * The flag lives with the game's own options ([dev.droidtop.runtime.windows.WineOptions], `steamworks_owned`), in the
 * settings file droidtop's backup writes, so it survives cache clears and travels with backups.
 */
object SteamworksOwnership {
    enum class Verdict {
        /** The game carries the owned flag: no check, no warning. */
        FLAGGED,

        /** Not flagged yet, and the signed-in account holds the app (owned, shared by a family, or a held free licence): the caller flags it. */
        ON_ACCOUNT,

        /** Not flagged, and nobody is signed in to droidtop's Steam, so nothing could be checked. */
        NOT_SIGNED_IN,

        /** Not flagged, signed in, and the account's games don't list the app (or list it as no longer held). */
        NOT_FOUND,
    }

    /** Whether [verdict] calls for the warning and the "Yes, I own this game" checkbox. */
    fun warns(verdict: Verdict): Boolean = verdict == Verdict.NOT_FOUND || verdict == Verdict.NOT_SIGNED_IN

    /** Whether [verdict] means the caller should set the owned flag now. */
    fun flagsNow(verdict: Verdict): Boolean = verdict == Verdict.ON_ACCOUNT

    /**
     * The verdict from the game's [flagged] state and, only when not flagged, what droidtop's Steam says: [signedIn], and
     * how the account holds the app ([holding], null when its games don't list it). Pure.
     */
    fun verdict(flagged: Boolean, signedIn: Boolean, holding: StoreHolding?): Verdict = when {
        flagged -> Verdict.FLAGGED
        signedIn && holding != null && holding != StoreHolding.NOT_OWNED -> Verdict.ON_ACCOUNT
        !signedIn -> Verdict.NOT_SIGNED_IN
        else -> Verdict.NOT_FOUND
    }

    /** [verdict] for [appId]; a [flagged] game never touches Steam. Database work; a failure to read counts as not found. */
    suspend fun check(context: Context, appId: Int, flagged: Boolean): Verdict {
        if (flagged) return Verdict.FLAGGED
        val steam = StoreLibraries.byId("steam")
        val signedIn = steam != null && runCatching { steam.signedIn(context) }.getOrDefault(false)
        val holding = if (signedIn) {
            runCatching { steam!!.games(context) }.getOrNull()
                ?.firstOrNull { it.gameId == appId.toString() }?.holding
        } else {
            null
        }
        return verdict(flagged = false, signedIn = signedIn, holding = holding)
    }

    /** What the warning says, for [appId] and a verdict that [warns]. */
    fun warning(verdict: Verdict, appId: Int): String = when (verdict) {
        Verdict.NOT_SIGNED_IN ->
            "droidtop can't check that you own Steam app $appId: you're not signed in to Steam. It still starts."
        else ->
            "Steam app $appId isn't in your signed-in Steam account's games. Steamworks is meant for games you own. It still starts."
    }
}
