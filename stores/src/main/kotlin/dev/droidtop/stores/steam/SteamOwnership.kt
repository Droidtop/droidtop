package dev.droidtop.stores.steam

import `in`.dragonbra.javasteam.enums.ELicenseFlags

/**
 * Which apps the account itself owns, which it got free, and which it only
 * borrows, from its licences (docs/SPEC.md 7g, "Stores"; Droidtop/tracker#377).
 * Steam's own count of a person's games (the profile, Steam Web API
 * GetOwnedGames) is what their own live licences grant, with free games only
 * once played:
 *
 * - a licence another account holds (Steam Families: the licence list
 *   carries the lender's account id) is borrowed, not owned: [family];
 * - an expired or cancelled licence grants nothing (a free weekend that
 *   ended, a refund, a fraud lock);
 * - package 0, the free sub every account holds, names every free app on
 *   Steam whether or not the person ever added it, and grants nothing here
 *   (GetOwnedGames leaves it out unless `include_free_sub`);
 * - a package Steam bills as free ([FREE_BILLING]: NoCost, FreeOnDemand,
 *   FreeCommercialLicense in SteamKit's EBillingType) is a free game the
 *   person added: [free], the person's own only once Steam's own owned-games
 *   answer lists it ([SteamOwnedGames], free games only once played) or it
 *   is installed.
 *
 * Steam's answer settles the rest too: a game it lists is the person's own
 * whatever the licences say here.
 *
 * Every other live own licence ([paid]: a purchase, a key, a gift) owns. A
 * package whose billing type has not been read yet counts as paid, so nothing
 * is hidden before a sync has read it. A base game counts when a DLC of it is
 * granted, the way GameNative's ownership rule did (a free-to-start game whose
 * purchase is a DLC).
 */
class SteamOwnership(val paid: Set<Int>, val free: Set<Int>, val family: Set<Int>) {
    enum class Status {
        /** The person's own: paid for, listed by Steam as owned, or free and installed. */
        OWN,

        /** Free, added to the account, not listed by Steam and not installed. */
        FREE,

        /** Another account's, lent through a Steam Family. */
        FAMILY,

        NONE,
    }

    /**
     * [appId]'s standing; [dlc] is the DLC that name it as their base game,
     * [listed] whether Steam's owned-games answer lists it, [installed]
     * whether it is installed here.
     */
    fun statusOf(appId: Int, dlc: Collection<Int> = emptyList(), listed: Boolean = false, installed: Boolean = false): Status = when {
        appId in paid || dlc.any { it in paid } -> Status.OWN
        listed -> Status.OWN
        appId in free || dlc.any { it in free } -> if (installed) Status.OWN else Status.FREE
        appId in family || dlc.any { it in family } -> Status.FAMILY
        else -> Status.NONE
    }

    companion object {
        /** Steam's free sub: every account holds it and it lists every free app. */
        const val FREE_SUB = 0

        /** SteamKit's EBillingType numbers of a package that costs nothing: NoCost, FreeOnDemand, FreeCommercialLicense. */
        val FREE_BILLING: Set<Int> = setOf(0, 12, 15)

        /** The licence flags under which a licence grants nothing. */
        val ENDED: Set<ELicenseFlags> = setOf(
            ELicenseFlags.Expired,
            ELicenseFlags.CancelledByUser,
            ELicenseFlags.CancelledByAdmin,
            ELicenseFlags.CancelledByFriendlyFraudLock,
        )

        fun grants(licence: SteamLicense): Boolean =
            licence.packageId != FREE_SUB && licence.licenseFlags.none { it in ENDED }

        fun isFree(licence: SteamLicense): Boolean = licence.billingType in FREE_BILLING

        /** Whether [licence] is the account's own; with no account id known, every licence is. */
        fun isOwn(licence: SteamLicense, accountId: Int?): Boolean =
            accountId == null || licence.ownerAccountId.isEmpty() || accountId in licence.ownerAccountId

        fun of(licences: List<SteamLicense>, accountId: Int?): SteamOwnership {
            val paid = HashSet<Int>()
            val free = HashSet<Int>()
            val family = HashSet<Int>()
            for (licence in licences) {
                if (!grants(licence)) continue
                when {
                    !isOwn(licence, accountId) -> family += licence.appIds
                    isFree(licence) -> free += licence.appIds
                    else -> paid += licence.appIds
                }
            }
            free -= paid
            family -= paid
            family -= free
            return SteamOwnership(paid, free, family)
        }
    }
}
