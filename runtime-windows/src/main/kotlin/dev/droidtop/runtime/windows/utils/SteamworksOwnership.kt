package dev.droidtop.runtime.windows.utils

import android.content.Context
import dev.droidtop.library.stores.StoreHolding
import dev.droidtop.library.stores.StoreLibraries

/**
 * Whether the Steam app a game starts as through the Steamworks shim is on the signed-in Steam account (docs/SPEC.md
 * 5b, "DRM: platform enablement, never piracy enablement"; Droidtop/tracker#718).
 *
 * Owner, 2026-11-05: "We don't add a check that explicitly checks an account and refuses to run. We DO add a check
 * that checks against an account, and warns the user if it can't find the game on it, with the general checkbox for
 * 'yes, I own this game'." So this only ever warns: [verdict] never stops a launch, and a person who ticks "Yes, I own
 * this game" for an app ID is not warned again for it.
 */
object SteamworksOwnership {
    enum class Verdict {
        /** The account holds the app (owned, shared with it by a Steam family, or a free licence it holds). */
        ON_ACCOUNT,

        /** Nobody is signed in to droidtop's Steam, so nothing could be checked. */
        NOT_SIGNED_IN,

        /** Signed in, and the account's games don't list the app (or list it as no longer held). */
        NOT_FOUND,

        /** Not found or not checked, but the person ticked "Yes, I own this game" for this app ID. */
        CONFIRMED,
    }

    /** Whether [verdict] calls for the warning. */
    fun warns(verdict: Verdict): Boolean = verdict == Verdict.NOT_FOUND || verdict == Verdict.NOT_SIGNED_IN

    /**
     * The verdict from what droidtop's Steam says: [signedIn], and how the account holds the app ([holding], null when
     * its games don't list it). [confirmed] is the person's "Yes, I own this game" for this app ID. Pure.
     */
    fun verdict(signedIn: Boolean, holding: StoreHolding?, confirmed: Boolean): Verdict {
        val onAccount = signedIn && holding != null && holding != StoreHolding.NOT_OWNED
        return when {
            onAccount -> Verdict.ON_ACCOUNT
            confirmed -> Verdict.CONFIRMED
            !signedIn -> Verdict.NOT_SIGNED_IN
            else -> Verdict.NOT_FOUND
        }
    }

    /** [verdict] for [appId], read from droidtop's Steam store. Database work; a failure to read counts as not found. */
    suspend fun check(context: Context, appId: Int, confirmed: Boolean): Verdict {
        val steam = StoreLibraries.byId("steam")
        val signedIn = steam != null && runCatching { steam.signedIn(context) }.getOrDefault(false)
        val holding = if (signedIn) {
            runCatching { steam!!.games(context) }.getOrNull()
                ?.firstOrNull { it.gameId == appId.toString() }?.holding
        } else {
            null
        }
        return verdict(signedIn, holding, confirmed)
    }

    /** What the warning says, for [appId] and a verdict that [warns]. */
    fun warning(verdict: Verdict, appId: Int): String = when (verdict) {
        Verdict.NOT_SIGNED_IN ->
            "droidtop can't check that you own Steam app $appId: you're not signed in to Steam. It still starts."
        else ->
            "Steam app $appId isn't in your signed-in Steam account's games. Steamworks is meant for games you own. It still starts."
    }
}
