package dev.droidtop.stores.steam

import `in`.dragonbra.javasteam.types.KeyValue
import java.util.Date

/**
 * An app's product info (PICS) as a [SteamApp] row: GameNative's
 * KeyValue.generateSteamApp (app.gamenative.utils.KeyValueUtils, GPL-3.0),
 * keeping the fields droidtop stores, plus each launch entry's arguments.
 */
internal object SteamPics {
    fun appFrom(kv: KeyValue): SteamApp = SteamApp(
        id = kv["appid"].asInteger(SteamIds.INVALID_APP_ID),
        depots = kv["depots"].children
            .filter { it.name?.toIntOrNull() != null }
            .associate { depot ->
                val depotId = depot.name!!.toInt()
                depotId to DepotInfo(
                    depotId = depotId,
                    dlcAppId = depot["dlcappid"].asInteger(SteamIds.INVALID_APP_ID),
                    depotFromApp = depot["depotfromapp"].asInteger(SteamIds.INVALID_APP_ID),
                    sharedInstall = depot["sharedinstall"].asBoolean(),
                    osList = OS.from(depot["config"]["oslist"].value),
                    osArch = OSArch.from(depot["config"]["osarch"].value),
                    manifests = manifestsOf(depot["manifests"].children),
                    encryptedManifests = manifestsOf(depot["encryptedManifests"].children),
                    language = depot["config"]["language"].value.orEmpty(),
                    realm = SteamRealm.from(depot["config"]["realm"].value.orEmpty()),
                    systemDefined = depot["systemdefined"].asBoolean(),
                    optionalDlcId = depot["config"]["optionaldlc"].asInteger(SteamIds.INVALID_APP_ID),
                    steamDeck = depot["config"]["steamdeck"].asBoolean(false),
                )
            },
        branches = kv["depots"]["branches"].children.associate { branch ->
            branch.name!! to BranchInfo(
                name = branch.name!!,
                buildId = branch["buildid"].asLong(),
                pwdRequired = branch["pwdrequired"].asBoolean(),
                timeUpdated = Date(branch["timeupdated"].asLong() * 1000L),
            )
        },
        name = kv["common"]["name"].value.orEmpty(),
        type = AppType.from(kv["common"]["type"].value),
        osList = OS.from(kv["common"]["oslist"].value),
        iconHash = kv["common"]["icon"].value.orEmpty(),
        headerImage = languageMap(kv["common"]["header_image"].children),
        libraryAssets = LibraryAssetsInfo(
            libraryCapsule = LibraryCapsuleInfo(
                image = languageMap(kv["common"]["library_assets_full"]["library_capsule"]["image"].children),
                image2x = languageMap(kv["common"]["library_assets_full"]["library_capsule"]["image2x"].children),
            ),
            libraryHero = LibraryHeroInfo(
                image = languageMap(kv["common"]["library_assets_full"]["library_hero"]["image"].children),
                image2x = languageMap(kv["common"]["library_assets_full"]["library_hero"]["image2x"].children),
            ),
            libraryLogo = LibraryLogoInfo(
                image = languageMap(kv["common"]["library_assets_full"]["library_logo"]["image"].children),
                image2x = languageMap(kv["common"]["library_assets_full"]["library_logo"]["image2x"].children),
            ),
        ),
        dlcForAppId = kv["extended"]["dlcforappid"].asInteger(kv["common"]["extended"]["dlcforappid"].asInteger(SteamIds.INVALID_APP_ID)),
        installDir = kv["common"]["config"]["installdir"].value.orEmpty(),
        config = ConfigInfo(
            installDir = kv["config"]["installdir"].value.orEmpty(),
            launch = kv["config"]["launch"].children.map { entry ->
                LaunchInfo(
                    executable = entry["executable"].value?.replace('\\', '/').orEmpty(),
                    workingDir = entry["workingdir"].value?.replace('\\', '/').orEmpty(),
                    description = entry["description"].value.orEmpty(),
                    type = entry["type"].value.orEmpty(),
                    configOS = OS.from(entry["config"]["oslist"].value),
                    configArch = OSArch.from(entry["config"]["osarch"].value),
                    arguments = entry["arguments"].value.orEmpty(),
                )
            },
        ),
    )

    private fun manifestsOf(children: List<KeyValue>): Map<String, ManifestInfo> = children.associate { manifest ->
        manifest.name!! to ManifestInfo(
            name = manifest.name!!,
            gid = manifest["gid"].asLong(),
            size = manifest["size"].asLong(),
            download = manifest["download"].asLong(),
        )
    }

    private fun languageMap(children: List<KeyValue>): Map<Language, String> = children.mapNotNull { kv ->
        val language = Language.from(kv.name)
        val value = kv.value
        if (language == Language.unknown || value == null) null else language to value
    }.toMap()
}
