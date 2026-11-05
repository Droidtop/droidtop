package dev.droidtop.library.credentials

/**
 * What each credential in [CredentialStore] is for, by service (Droidtop/tracker#12, the owner's "Keys and accounts"
 * page, 2026-10-28): the page lists these, says where each is used, and removes one by clearing its keys in the one
 * store, the same blank write each service's own page makes (`CredentialVault.put`: a blank value removes the entry).
 * Every key in [CredentialStore.KEYS] belongs to exactly one service (CredentialServicesTest), so a key added to the
 * store without a row here fails the build instead of going unlisted. Pure.
 */
object CredentialServices {
    data class Service(val id: String, val label: String, val usedFor: String, val keys: List<String>)

    val ALL: List<Service> = listOf(
        Service("screenscraper", "ScreenScraper", "Game art and details for console games", listOf(CredentialStore.SCREENSCRAPER_USER, CredentialStore.SCREENSCRAPER_PASSWORD)),
        Service("thegamesdb", "TheGamesDB", "Game details for console games", listOf(CredentialStore.THEGAMESDB_API_KEY)),
        Service("igdb", "IGDB", "Game details for PC and engine games", listOf(CredentialStore.IGDB_CLIENT_ID, CredentialStore.IGDB_CLIENT_SECRET)),
        Service("steamgriddb", "SteamGridDB", "Game art for PC and engine games", listOf(CredentialStore.STEAMGRIDDB_API_KEY)),
        Service("retroachievements", "RetroAchievements", "Achievements for console games", listOf(CredentialStore.RETROACHIEVEMENTS_USER, CredentialStore.RETROACHIEVEMENTS_API_KEY)),
        Service("torbox", "TorBox", "Downloads through a debrid service", listOf(CredentialStore.TORBOX_API_KEY)),
        Service("realdebrid", "Real-Debrid", "Downloads through a debrid service", listOf(CredentialStore.REALDEBRID_TOKEN, CredentialStore.REALDEBRID_SIGNIN)),
        Service("pixeldrain", "Pixeldrain", "Downloads from Pixeldrain", listOf(CredentialStore.PIXELDRAIN_API_KEY)),
        Service("gofile", "Gofile", "Downloads from Gofile", listOf(CredentialStore.GOFILE_API_TOKEN)),
    )

    /** The services with at least one key set, given what [get] reads for a key. */
    fun held(get: (String) -> String): List<Service> = ALL.filter { service -> service.keys.any { get(it).isNotBlank() } }
}
