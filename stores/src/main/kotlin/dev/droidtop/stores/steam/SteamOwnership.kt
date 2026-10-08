package dev.droidtop.stores.steam

import `in`.dragonbra.javasteam.enums.ELicenseFlags

/**
 * Which apps the account itself owns and which it only borrows, from its
 * licences (docs/SPEC.md 7g, "Stores"; Droidtop/tracker#377). Steam's own
 * count of a person's games is what their own live licences grant:
 *
 * - a licence another account holds (Steam Families: the licence list
 *   carries the lender's account id) is borrowed, not owned; its games are
 *   listed apart, marked as the family's;
 * - an expired or cancelled licence grants nothing (a free weekend that
 *   ended, a refund, a fraud lock);
 * - package 0, the free sub every account holds, grants every free app on
 *   Steam whether or not the person ever added it. Steam's own owned-games
 *   answer leaves it out unless asked (Steam Web API GetOwnedGames,
 *   `include_free_sub`), and so does droidtop: a free game the person added
 *   comes with a licence of its own.
 *
 * A base game counts when a DLC of it is granted, the way GameNative's
 * ownership rule did (a free-to-start game whose purchase is a DLC).
 */
class SteamOwnership(val own: Set<Int>, val family: Set<Int>) {
    enum class Status { OWN, FAMILY, NONE }

    /** [appId]'s standing; [dlc] is the DLC that name it as their base game. */
    fun statusOf(appId: Int, dlc: Collection<Int> = emptyList()): Status = when {
        appId in own || dlc.any { it in own } -> Status.OWN
        appId in family || dlc.any { it in family } -> Status.FAMILY
        else -> Status.NONE
    }

    companion object {
        /** Steam's free sub: every account holds it and it lists every free app. */
        const val FREE_SUB = 0

        /** The licence flags under which a licence grants nothing. */
        val ENDED: Set<ELicenseFlags> = setOf(
            ELicenseFlags.Expired,
            ELicenseFlags.CancelledByUser,
            ELicenseFlags.CancelledByAdmin,
            ELicenseFlags.CancelledByFriendlyFraudLock,
        )

        fun grants(licence: SteamLicense): Boolean =
            licence.packageId != FREE_SUB && licence.licenseFlags.none { it in ENDED }

        /** Whether [licence] is the account's own; with no account id known, every licence is. */
        fun isOwn(licence: SteamLicense, accountId: Int?): Boolean =
            accountId == null || licence.ownerAccountId.isEmpty() || accountId in licence.ownerAccountId

        fun of(licences: List<SteamLicense>, accountId: Int?): SteamOwnership {
            val own = HashSet<Int>()
            val family = HashSet<Int>()
            for (licence in licences) {
                if (!grants(licence)) continue
                if (isOwn(licence, accountId)) own += licence.appIds else family += licence.appIds
            }
            family -= own
            return SteamOwnership(own, family)
        }
    }
}
