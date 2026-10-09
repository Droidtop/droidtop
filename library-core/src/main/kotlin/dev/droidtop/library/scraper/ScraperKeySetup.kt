package dev.droidtop.library.scraper

import android.content.Context
import dev.droidtop.library.credentials.CredentialStore
import dev.droidtop.library.credentials.handoff.HandoffField
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME
import dev.droidtop.runtime.prefs.PrefsFile

/** One value a service takes: its id is the same in the phone page, the in-app key page and the vault. */
data class KeyField(val id: String, val label: String, val secret: Boolean, val storeKey: String)

/**
 * The scraper sources that take a credential of the person's own, and the
 * guide that gets them one (docs/SPEC.md 7h, "Supplying your own keys"). Steps
 * are labels of a few words, never prose: the screen shows them numbered beside
 * the official page's QR code. [gets] is what the source adds, as a label.
 */
enum class ScraperKeyService(
    val title: String,
    /** The official page where the credential is made. */
    val url: String,
    val steps: List<String>,
    /** The credit line a game's page carries when this source supplied data. */
    val credit: String,
    val gets: String,
    val fields: List<KeyField>,
) {
    IGDB(
        title = "IGDB",
        url = "https://dev.twitch.tv/console/apps",
        steps = listOf(
            "Open dev.twitch.tv/console",
            "Register an application",
            "Copy Client ID and Secret",
            "Paste them here",
        ),
        credit = "Data from IGDB.com",
        gets = "Descriptions, ratings, dates",
        fields = listOf(
            KeyField("client_id", "Client ID", secret = false, storeKey = CredentialStore.IGDB_CLIENT_ID),
            KeyField("client_secret", "Client Secret", secret = true, storeKey = CredentialStore.IGDB_CLIENT_SECRET),
        ),
    ),
    STEAMGRIDDB(
        title = "SteamGridDB",
        url = "https://www.steamgriddb.com/profile/preferences/api",
        steps = listOf(
            "Open steamgriddb.com",
            "Sign in",
            "Open Preferences, API",
            "Copy the API key",
            "Paste it here",
        ),
        credit = "Art from SteamGridDB",
        gets = "Covers, heroes, logos",
        fields = listOf(KeyField("api_key", "API key", secret = true, storeKey = CredentialStore.STEAMGRIDDB_API_KEY)),
    ),
    THEGAMESDB(
        title = "TheGamesDB",
        url = "https://forums.thegamesdb.net/",
        steps = listOf(
            "Open forums.thegamesdb.net",
            "Sign in or register",
            "Request an API key",
            "Copy the key",
            "Paste it here",
        ),
        credit = "Data from TheGamesDB",
        gets = "Descriptions, covers, dates",
        fields = listOf(KeyField("api_key", "API key", secret = true, storeKey = CredentialStore.THEGAMESDB_API_KEY)),
    ),
    SCREENSCRAPER(
        title = "ScreenScraper",
        url = "https://screenscraper.fr/membreinscription.php",
        steps = listOf(
            "Open screenscraper.fr",
            "Create a free account",
            "Confirm the email",
            "Enter the login here",
        ),
        credit = "Data from ScreenScraper.fr",
        gets = "Higher daily limit",
        fields = listOf(
            KeyField("username", "Username", secret = false, storeKey = CredentialStore.SCREENSCRAPER_USER),
            KeyField("password", "Password", secret = true, storeKey = CredentialStore.SCREENSCRAPER_PASSWORD),
        ),
    ),
    RETROACHIEVEMENTS(
        title = "RetroAchievements",
        url = "https://retroachievements.org/settings",
        steps = listOf(
            "Open retroachievements.org",
            "Sign in to your account",
            "Open Settings, Keys",
            "Copy the Web API Key",
            "Enter it here",
        ),
        credit = "Achievements from RetroAchievements.org",
        gets = "Achievements and progress",
        fields = listOf(
            KeyField("username", "Username", secret = false, storeKey = CredentialStore.RETROACHIEVEMENTS_USER),
            KeyField("api_key", "Web API Key", secret = true, storeKey = CredentialStore.RETROACHIEVEMENTS_API_KEY),
        ),
    ),
    ;

    /** The stored values, by field id; blank where none is set. */
    fun read(context: Context): Map<String, String> =
        fields.associate { it.id to CredentialStore.get(context, it.storeKey) }

    /** Stores [values] (by field id) in the encrypted store and clears the tested state. */
    fun write(context: Context, values: Map<String, String>) {
        fields.forEach { field -> values[field.id]?.let { CredentialStore.put(context, field.storeKey, it.trim()) } }
        ScraperKeyVerified.set(context, this, false)
    }

    fun isConfigured(context: Context): Boolean = read(context).values.all { it.isNotBlank() }

    /** The fields a phone page for this service asks for. */
    fun handoffFields(): List<HandoffField> =
        fields.map { HandoffField(id = it.id, label = it.label, secret = it.secret) }

    /** The word in `droidtop_<word>_verified`; the two older services keep their keys. */
    internal val prefsWord: String get() = name.lowercase()
}

/** Whether the stored credential of a service passed a test call; any change to it clears this. */
object ScraperKeyVerified {
    private fun key(service: ScraperKeyService) = "droidtop_${service.prefsWord}_verified"

    fun get(context: Context, service: ScraperKeyService): Boolean =
        PrefsFile(context, LAUNCHER_PREFS_FILE_NAME).getBoolean(key(service), false)

    fun set(context: Context, service: ScraperKeyService, value: Boolean) {
        PrefsFile(context, LAUNCHER_PREFS_FILE_NAME).putBoolean(key(service), value)
    }
}

/** What a source row and the guide show for a credential's state. */
object ScraperKeyState {
    const val NOT_SET = "Not set"
    const val NOT_TESTED = "Not tested"
    const val CONNECTED = "Connected"
    const val BUILT_IN = "Built in"
    const val NO_ACCOUNT = "No account"

    fun label(configured: Boolean, verified: Boolean): String = when {
        !configured -> NOT_SET
        verified -> CONNECTED
        else -> NOT_TESTED
    }

    /**
     * The one line a test call ends in: [CONNECTED], or why not. A rejected
     * credential says to check what was pasted; any other refusal gives its
     * status and the server's first line, bounded.
     */
    fun outcome(lookup: ScrapeLookup<*>): String = when (lookup) {
        is ScrapeLookup.Found, ScrapeLookup.NoMatch -> CONNECTED
        is ScrapeLookup.Refused ->
            if (ScraperReadiness.rejectedCredentials(lookup)) {
                "Rejected: check what you pasted"
            } else {
                "Refused (${lookup.httpStatus})" +
                    (lookup.reason?.lineSequence()?.firstOrNull()?.take(80)?.let { ": $it" } ?: "")
            }
    }

    /** A transport failure is the network's, not the credential's. */
    const val NO_CONNECTION = "No connection"
}

/** One real request per service; the caller runs it off the main thread. */
object ScraperKeyCheck {
    /** A title every source holds, so a working credential finds something. */
    private const val PROBE_TITLE = "Portal"

    private inline fun guarded(block: () -> String): String =
        try {
            block()
        } catch (e: java.io.IOException) {
            ScraperKeyState.NO_CONNECTION
        }

    /** Tests the stored credential of [service], records the result, and returns the line to show. */
    fun test(context: Context, service: ScraperKeyService): String {
        if (!service.isConfigured(context)) return ScraperKeyState.NOT_SET
        val values = service.read(context)
        val outcome = guarded {
            when (service) {
                ScraperKeyService.IGDB -> ScraperKeyState.outcome(
                    IgdbScraperClient.search(values.getValue("client_id"), values.getValue("client_secret"), PROBE_TITLE, limit = 1),
                )
                ScraperKeyService.STEAMGRIDDB -> ScraperKeyState.outcome(
                    SteamGridDbScraperClient.search(values.getValue("api_key"), PROBE_TITLE),
                )
                ScraperKeyService.THEGAMESDB -> ScraperKeyState.outcome(
                    TheGamesDbClient.searchCandidates(values.getValue("api_key"), "1", PROBE_TITLE, limit = 1),
                )
                ScraperKeyService.SCREENSCRAPER -> ScraperKeyState.outcome(
                    ScreenScraperClient.findMetadata(
                        systemeId = "1",
                        romName = "Sonic The Hedgehog (USA).md",
                        romSizeBytes = 0L,
                        devId = ScreenScraperPrefs.devId(context),
                        devPassword = ScreenScraperPrefs.devPassword(context),
                        userId = values.getValue("username"),
                        userPassword = values.getValue("password"),
                    ),
                )
                ScraperKeyService.RETROACHIEVEMENTS -> ScraperKeyState.outcome(
                    dev.droidtop.library.achievements.RetroAchievementsClient.profile(values.getValue("username"), values.getValue("api_key")),
                )
            }
        }
        // A network failure says nothing about the credential: keep the last verdict.
        if (outcome != ScraperKeyState.NO_CONNECTION) {
            ScraperKeyVerified.set(context, service, outcome == ScraperKeyState.CONNECTED)
        }
        return outcome
    }

    /** The state label for [service] from what is stored. */
    fun state(context: Context, service: ScraperKeyService): String {
        if (!service.isConfigured(context)) {
            return when (service) {
                ScraperKeyService.THEGAMESDB ->
                    if (TheGamesDbPrefs.builtInKey.isNotBlank()) ScraperKeyState.BUILT_IN else ScraperKeyState.NOT_SET
                ScraperKeyService.SCREENSCRAPER -> ScraperKeyState.NO_ACCOUNT
                else -> ScraperKeyState.NOT_SET
            }
        }
        return ScraperKeyState.label(configured = true, verified = ScraperKeyVerified.get(context, service))
    }
}
