package dev.droidtop.stores.steam

import dev.droidtop.library.stores.StoreBranch
import dev.droidtop.library.stores.StoreContentChoice
import dev.droidtop.library.stores.StoreContentOptions
import dev.droidtop.library.stores.StoreExtra
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The DLC and branch picker's rules for Steam (docs/SPEC.md 7g, "Stores",
 * Droidtop/tracker#313). Pure: the plan, the names, the install row and the
 * choice come in, so the rules are testable without a database or a session.
 */
internal object SteamContent {
    /** One owned DLC with something to download, and what turning it on downloads. */
    data class Dlc(val appId: Int, val name: String, val bytes: Long)

    /**
     * The DLC with content in [everything], the plan made with every owned DLC
     * on. A DLC whose files sit among the game's own depots is downloaded on
     * the game's [branch]; a DLC app with depots of its own always on the
     * public branch (it is an app of its own, and DLC apps have no betas).
     */
    fun dlcIn(everything: SteamDepots.Plan, branch: String, nameOf: (Int) -> String): List<Dlc> {
        val rows = everything.mainDepots.values.filter { it.dlcAppId != SteamIds.INVALID_APP_ID }.map { it to branch } +
            everything.dlcDepots.values.map { it to SteamBranches.PUBLIC }
        return rows.groupBy { (depot, _) -> depot.dlcAppId }
            .map { (appId, depots) ->
                Dlc(appId, nameOf(appId).ifBlank { "DLC $appId" }, depots.sumOf { (depot, on) -> SteamDepots.downloadBytes(depot, on) })
            }
            .sortedBy { it.name.lowercase() }
    }

    /** What the picker draws for a game whose DLC are [dlc]. */
    fun options(
        app: SteamApp,
        dlc: List<Dlc>,
        choice: SteamChoice,
        installedDlc: Set<Int>,
        installed: Boolean,
    ): StoreContentOptions {
        val listed = SteamBranches.listed(app.branches)
        val chosen = if (listed.any { it.name == choice.branch }) choice.branch else SteamBranches.PUBLIC
        return StoreContentOptions(
            extras = dlc.map { StoreExtra(it.appId.toString(), it.name, it.bytes, selected = it.appId !in choice.excludedDlc, installed = it.appId in installedDlc) },
            branches = listed.map { branch ->
                StoreBranch(
                    id = branch.name,
                    title = branch.name,
                    locked = branch.pwdRequired,
                    unlocked = branch.pwdRequired && !choice.branchPasswords[branch.name].isNullOrBlank(),
                    build = branch.buildId.takeIf { it > 0 }?.let { "build $it" },
                    updatedMs = branch.timeUpdated.time.takeIf { it > 0 },
                    selected = branch.name == chosen,
                )
            },
            installed = installed,
        )
    }

    /** The stored choice [picked] makes, given every DLC the picker listed ([available]) and the choice before it. */
    fun choiceFrom(picked: StoreContentChoice, available: Set<Int>, before: SteamChoice): SteamChoice {
        val wanted = picked.extraIds.mapNotNull { it.toIntOrNull() }.toSet()
        return before.copy(
            branch = picked.branchId ?: SteamBranches.PUBLIC,
            excludedDlc = available - wanted,
        )
    }

    /**
     * Whether the files on the device differ from what [choice] asks, so an
     * install run has something to do: a DLC to fetch or to remove, or the
     * branch changed. [available] is every DLC with content, [installedDlc]
     * the ones the install row lists.
     */
    fun installDiffers(choice: SteamChoice, available: Set<Int>, installedDlc: Set<Int>, installedBranch: String): Boolean {
        val wanted = available - choice.excludedDlc
        return wanted != installedDlc.intersect(available) || choice.branch != installedBranch.ifBlank { SteamBranches.PUBLIC }
    }

    /** The DLC on the device that [choice] turns off. */
    fun removedDlc(choice: SteamChoice, available: Set<Int>, installedDlc: Set<Int>): Set<Int> =
        installedDlc.intersect(available) - (available - choice.excludedDlc)

    // The files of a DLC that is turned off.

    /**
     * The depots whose files belong to the removed DLC: depots of the game
     * that carry one of their ids, and the depots of the DLC apps' own
     * installs ([dlcOwnDepots]: DLC app id to the depots downloaded for it).
     */
    fun depotsOf(removed: Set<Int>, appDepots: Map<Int, DepotInfo>, dlcOwnDepots: Map<Int, List<Int>>): Set<Int> =
        buildSet {
            appDepots.values.filter { it.dlcAppId in removed }.forEach { add(it.depotId) }
            removed.forEach { dlcOwnDepots[it]?.let(::addAll) }
        }

    /**
     * The files to delete when depots are removed: those [removedFiles] list
     * that no depot that stays lists as well (a file two depots share stays).
     * Names are compared the way Windows does, case-insensitively with either
     * slash; the answer keeps the removed depots' spelling.
     */
    fun filesToDelete(removedFiles: Collection<String>, keptFiles: Collection<String>): List<String> {
        fun key(name: String) = name.replace('\\', '/').trim('/').lowercase()
        val kept = keptFiles.mapTo(HashSet(), ::key)
        return removedFiles.map { it.replace('\\', '/').trim('/') }.distinct().filter { key(it) !in kept }
    }

    /** `depot.config` (the depot downloader's record of the manifest each depot is at) without [depots]; unchanged text when it cannot be read. */
    fun withoutDepots(configJson: String, depots: Set<Int>): String {
        val root = runCatching { Json.parseToJsonElement(configJson).jsonObject }.getOrNull() ?: return configJson
        val installed = (root["installedManifestIDs"] as? JsonObject) ?: return configJson
        val kept = installed.filterKeys { it.toIntOrNull() !in depots }
        return Json { prettyPrint = true }.encodeToString(
            JsonObject.serializer(),
            JsonObject(root + ("installedManifestIDs" to JsonObject(kept))),
        )
    }
}
