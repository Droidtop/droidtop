package dev.droidtop.stores.steam

/**
 * Which of an app's depots a download takes (GameNative's
 * SteamService.filterForDownloadableDepots, eligibleDepots,
 * resolveDownloadableDepots, getMainAppDepots and getDownloadableDepots, and
 * SteamUtils.effectiveDepotLanguage; GPL-3.0). Pure: what the account owns
 * and is licensed for comes in as arguments, so the rules are testable
 * without a session.
 *
 * Left out on purpose: GameNative's "prefer Linux depots" switch (droidtop
 * runs a Steam game through Wine, the way it runs every Windows game) and
 * password-unlocked beta branches (droidtop installs the public branch).
 */
internal object SteamDepots {

    /**
     * Whether [depot] is downloaded, in GameNative's eight steps: it has
     * something to download, it is for Windows, it is 64-bit or neutral (32-bit
     * only when [prefer64Bit] is false), it is a DLC the account owns, it is in
     * [language] (a DLC's only depot passes in any language), the package
     * grants it, it is not the Steam Deck build when another exists
     * ([preferNonDeck]), and it is not for Steam China.
     */
    fun wanted(
        depot: DepotInfo,
        prefer64Bit: Boolean,
        preferNonDeck: Boolean,
        language: String,
        ownedDlc: Map<Int, DepotInfo>?,
        licensedDepotIds: Set<Int>?,
        dlcAppIdsWithSingleDepots: Set<Int>?,
    ): Boolean {
        if (depot.manifests.isEmpty() && depot.encryptedManifests.isNotEmpty()) return false
        if (depot.manifests.isEmpty() && !depot.sharedInstall) return false
        if (!depot.isWindowsCompatible) return false
        if (depot.osArch == OSArch.Arch32 && prefer64Bit) return false
        if (depot.dlcAppId != SteamIds.INVALID_APP_ID && ownedDlc != null && !ownedDlc.containsKey(depot.depotId)) return false
        if (depot.language.isNotEmpty() && depot.language != language) {
            if (depot.dlcAppId == SteamIds.INVALID_APP_ID) return false
            if (dlcAppIdsWithSingleDepots != null && depot.dlcAppId !in dlcAppIdsWithSingleDepots) return false
        }
        if (depot.dlcAppId == SteamIds.INVALID_APP_ID && !depot.systemDefined && licensedDepotIds != null && depot.depotId !in licensedDepotIds) return false
        if (depot.steamDeck && preferNonDeck) return false
        if (depot.realm == SteamRealm.SteamChina) return false
        return true
    }

    /** The DLC apps that have exactly one depot among [depots]. */
    fun dlcAppIdsWithSingleDepot(depots: Map<Int, DepotInfo>): Set<Int> =
        depots.values.filter { it.dlcAppId != SteamIds.INVALID_APP_ID }.groupBy { it.dlcAppId }.filterValues { it.size == 1 }.keys

    /** The depots that pass every check but the architecture and Steam Deck preferences, from which those preferences are read. */
    fun eligible(
        depots: Map<Int, DepotInfo>,
        language: String,
        ownedDlc: Map<Int, DepotInfo>?,
        licensedDepotIds: Set<Int>?,
    ): Collection<DepotInfo> {
        val single = dlcAppIdsWithSingleDepot(depots)
        return depots.values.filter { wanted(it, prefer64Bit = false, preferNonDeck = false, language, ownedDlc, licensedDepotIds, single) }
    }

    /**
     * The language [depots] are taken in: [preferred] when the base game has
     * an installable depot in it (or a language-neutral one), else English,
     * else any language it has.
     */
    fun effectiveLanguage(
        depots: Map<Int, DepotInfo>,
        preferred: String,
        ownedDlc: Map<Int, DepotInfo>?,
        licensedDepotIds: Set<Int>?,
    ): String {
        fun DepotInfo.installable(): Boolean {
            val isDlc = dlcAppId != SteamIds.INVALID_APP_ID
            val hasContent = manifests.isNotEmpty() || sharedInstall
            val ownedIfDlc = !isDlc || ownedDlc == null || ownedDlc.containsKey(depotId)
            val licensedIfBase = isDlc || systemDefined || licensedDepotIds == null || depotId in licensedDepotIds
            return isWindowsCompatible && realm != SteamRealm.SteamChina && hasContent && ownedIfDlc && licensedIfBase
        }
        val base = depots.values.filter { it.dlcAppId == SteamIds.INVALID_APP_ID && it.installable() }
        val languages = base.filter { it.language.isNotEmpty() }.mapTo(mutableSetOf()) { it.language }
        return when {
            preferred in languages -> preferred
            base.any { it.language.isEmpty() } -> preferred
            "english" in languages -> "english"
            else -> languages.firstOrNull() ?: preferred
        }
    }

    /** The depots of [depots] a download takes: the preferences read from [eligible], then every check. */
    fun resolve(
        depots: Map<Int, DepotInfo>,
        preferred: String,
        ownedDlc: Map<Int, DepotInfo>?,
        licensedDepotIds: Set<Int>?,
    ): Map<Int, DepotInfo> {
        val single = dlcAppIdsWithSingleDepot(depots)
        val language = effectiveLanguage(depots, preferred, ownedDlc, licensedDepotIds)
        val eligible = eligible(depots, language, ownedDlc, licensedDepotIds)
        val has64Bit = eligible.any { it.osArch == OSArch.Arch64 }
        val hasNonDeck = eligible.any { !it.steamDeck && it.isWindowsCompatible }
        return depots.filter { (_, depot) -> wanted(depot, has64Bit, hasNonDeck, language, ownedDlc, licensedDepotIds, single) }
    }

    /** One DLC app with depots of its own that the account owns, and the depots its licence grants (null: not known). */
    data class DlcApp(val app: SteamApp, val licensedDepotIds: Set<Int>?)

    /**
     * What one download fetches: [mainDepots] go to the game's own app (its
     * own depots and the DLC content it carries itself), [dlcDepots] to each
     * DLC app that has depots of its own ([DepotInfo.dlcAppId] set to it).
     */
    data class Plan(val mainDepots: Map<Int, DepotInfo>, val dlcDepots: Map<Int, DepotInfo>) {
        val all: Map<Int, DepotInfo> get() = mainDepots + dlcDepots
        val dlcAppIds: List<Int> get() = dlcDepots.values.map { it.dlcAppId }.distinct().sorted()
        val isEmpty: Boolean get() = mainDepots.isEmpty() && dlcDepots.isEmpty()
    }

    /**
     * The whole download of [app] in [language], with every DLC the account
     * owns (droidtop has no DLC picker: like the other stores, an install
     * takes what is owned). [ownedDlcAppIds] are the DLC apps the account
     * holds; [licensedDepotIds] what the game's own package, the shared
     * package and the owned DLC packages grant; [dlcPackageDepots] each owned
     * DLC's package depots, so a depot of the game that a DLC package grants
     * is filed under that DLC (GameNative's fix for Don't Starve's DLC list).
     * [alreadyDownloaded] drops depots a first install already has; an update
     * passes null and takes everything.
     */
    fun plan(
        app: SteamApp,
        language: String,
        ownedDlcAppIds: Set<Int>,
        licensedDepotIds: Set<Int>?,
        mainPackageDepots: Set<Int>,
        dlcPackageDepots: Map<Int, List<Int>>,
        dlcApps: List<DlcApp>,
        alreadyDownloaded: Set<Int>?,
    ): Plan {
        val ownedDlc = app.depots.filter { (_, depot) -> depot.dlcAppId == SteamIds.INVALID_APP_ID || depot.dlcAppId in ownedDlcAppIds }
        val base = resolve(app.depots, language, ownedDlc, licensedDepotIds)
        val dlcOnly = if (mainPackageDepots.isEmpty()) {
            emptyMap()
        } else {
            dlcPackageDepots.mapValues { (_, depots) -> depots.filter { it !in mainPackageDepots } }.filterValues { it.isNotEmpty() }
        }
        val hasContent = { depot: DepotInfo -> depot.manifests.isNotEmpty() || depot.encryptedManifests.isNotEmpty() }
        var main = base.mapValues { (_, depot) ->
            val owner = dlcOnly.entries.firstOrNull { depot.depotId in it.value }?.key
            depot.copy(dlcAppId = owner ?: depot.dlcAppId)
        }.filter { (_, depot) ->
            depot.dlcAppId == SteamIds.INVALID_APP_ID || (depot.dlcAppId in ownedDlcAppIds && hasContent(depot))
        }
        if (alreadyDownloaded != null) main = main.filterKeys { it !in alreadyDownloaded }

        val language64 = effectiveLanguage(app.depots, language, ownedDlc, licensedDepotIds)
        val has64Bit = eligible(app.depots, language64, ownedDlc, licensedDepotIds).any { it.osArch == OSArch.Arch64 }
        val dlc = buildMap {
            for ((dlcApp, licensed) in dlcApps) {
                if (dlcApp.id !in ownedDlcAppIds) continue
                val single = dlcAppIdsWithSingleDepot(dlcApp.depots)
                val dlcLanguage = effectiveLanguage(dlcApp.depots, language, null, licensed)
                val nonDeck = eligible(dlcApp.depots, dlcLanguage, null, licensed).any { !it.steamDeck && it.isWindowsCompatible }
                dlcApp.depots
                    .filter { (depotId, depot) ->
                        depotId !in main && hasContent(depot) &&
                            wanted(depot, has64Bit, nonDeck, dlcLanguage, null, licensed, single)
                    }
                    .forEach { (depotId, depot) -> put(depotId, depot.copy(dlcAppId = dlcApp.id)) }
            }
        }
        return Plan(main, dlc)
    }

    /** The bytes [plan] moves on [branch], for the free-space check and the progress weights. */
    fun downloadBytes(depot: DepotInfo, branch: String): Long =
        (depot.manifests[branch] ?: depot.encryptedManifests[branch] ?: depot.manifests["public"])?.downloadBytes ?: 0L

    /** The installed size of [plan] on [branch]. */
    fun installBytes(depots: Collection<DepotInfo>, branch: String): Long =
        depots.sumOf { (it.manifests[branch] ?: it.manifests["public"])?.size ?: 0L }
}
