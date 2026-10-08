package dev.droidtop.stores.steam

import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.steam.handlers.steamapps.SteamApps
import `in`.dragonbra.javasteam.types.KeyValue
import kotlinx.coroutines.future.await

/**
 * Steam's branches ("betas") of a game (docs/SPEC.md 7g, "Stores",
 * Droidtop/tracker#313): which ones the picker lists, which depots a branch
 * has, and how a branch that asks for a password is opened. The password check
 * and the private depot section are the two calls the depot downloader makes
 * itself (Steam3Session.checkAppBetaPassword, getPrivateBetaDepotSection);
 * droidtop makes them first so the install plan knows the branch's real
 * sizes and depots before a byte is downloaded.
 */
internal object SteamBranches {
    /** The branch every game has, and the one an install follows unless told otherwise. */
    const val PUBLIC = SteamDownload.BRANCH


    /** The branches the picker lists: the public one first, then the rest by name. */
    fun listed(branches: Map<String, BranchInfo>): List<BranchInfo> {
        val rest = branches.values.filter { it.name != PUBLIC }.sortedBy { it.name.lowercase() }
        val public = branches[PUBLIC] ?: BranchInfo(PUBLIC, 0L, false, java.util.Date(0))
        return listOf(public) + rest
    }

    /**
     * The depots a game has on [branch]: those that list a manifest for it
     * (an encrypted one counts, [overlay] opens it), and the ones with no
     * manifests at all, which Steam fills from another app. The public branch
     * is every depot as it is.
     */
    fun forBranch(depots: Map<Int, DepotInfo>, branch: String): Map<Int, DepotInfo> {
        if (branch == PUBLIC) return depots
        return depots.filterValues { depot ->
            branch in depot.manifests || branch in depot.encryptedManifests ||
                (depot.manifests.isEmpty() && depot.encryptedManifests.isEmpty())
        }
    }

    /** [depots] with the manifests of [branch] that [section] opened put in place of the encrypted ones. */
    fun overlay(depots: Map<Int, DepotInfo>, section: Map<Int, ManifestInfo>, branch: String): Map<Int, DepotInfo> =
        depots.mapValues { (id, depot) ->
            val opened = section[id] ?: return@mapValues depot
            depot.copy(
                manifests = depot.manifests + (branch to opened),
                encryptedManifests = depot.encryptedManifests - branch,
            )
        }

    /**
     * The manifests of [branch] in a private depot section (Steam's answer to
     * PICSGetPrivateBeta): a child per depot id, each with
     * `manifests/<branch>/{gid,size,download}`.
     */
    fun parseSection(section: KeyValue, branch: String): Map<Int, ManifestInfo> =
        section.children.mapNotNull { depot ->
            val depotId = depot.name?.toIntOrNull() ?: return@mapNotNull null
            val manifest = depot["manifests"][branch]
            if (manifest == KeyValue.INVALID || manifest["gid"].value == null) return@mapNotNull null
            depotId to ManifestInfo(
                name = branch,
                gid = manifest["gid"].asLong(),
                size = manifest["size"].asLong(),
                download = manifest["download"].asLong(),
            )
        }.toMap()

    /**
     * Whether the check of a branch password opened [branch]: Steam answered
     * with the branch's key. A wrong password comes back OK with no keys at
     * all, and any other answer is [check]'s empty map.
     */
    fun unlocked(keys: Map<String, ByteArray>, branch: String): Boolean = keys.containsKey(branch)

    /** Steam's check of [password] for [appId]: the key of every branch it opens, by branch name. */
    suspend fun check(apps: SteamApps, appId: Int, password: String): Map<String, ByteArray> {
        val answer = apps.checkAppBetaPassword(appId, password).await()
        return if (answer.result == EResult.OK) answer.betaPasswords else emptyMap()
    }

    /** The access token Steam asks for the app's product info, or 0. */
    suspend fun accessToken(apps: SteamApps, appId: Int): Long =
        runCatching { apps.picsGetAccessTokens(appIds = listOf(appId), packageIds = emptyList()).await().appTokens[appId] }
            .getOrNull() ?: 0L

    /**
     * [app] as it is on [branch]: its depots limited to the branch, and for a
     * branch that asks a password, the password checked and the branch's real
     * manifests read in. Throws, saying what to do, when the password is
     * missing or Steam does not take it.
     */
    suspend fun resolve(apps: SteamApps, app: SteamApp, branch: String, password: String?): SteamApp {
        if (branch == PUBLIC) return app
        val locked = app.branches[branch]?.pwdRequired == true
        if (!locked) return app.copy(depots = forBranch(app.depots, branch))
        if (password.isNullOrBlank()) error("The $branch version needs its password. Enter it under DLC and versions")
        val keys = check(apps, app.id, password)
        val key = keys[branch] ?: error("Steam does not accept that password for $branch")
        val answer = apps.picsGetPrivateBeta(app.id, accessToken(apps, app.id), branch, key).await()
        if (answer.result != EResult.OK) error("Steam did not give the $branch version's files (${answer.result})")
        val opened = overlay(app.depots, parseSection(answer.depotSection, branch), branch)
        return app.copy(depots = forBranch(opened, branch))
    }
}
