package dev.droidtop.library.scraper

import android.content.Context

/**
 * The two optional scraper sources that need a credential of the person's
 * own, and the guide that gets them one (docs/SPEC.md 7h, "Keyless by
 * default"). Steps are labels of a few words, never prose: the screen shows
 * them numbered beside the official page's QR code.
 */
enum class ScraperKeyService(
    val title: String,
    /** The official page where the credential is made. */
    val url: String,
    val steps: List<String>,
    /** The credit line a game's page carries when this source supplied data. */
    val credit: String,
) {
    IGDB(
        title = "IGDB",
        url = "https://dev.twitch.tv/console",
        steps = listOf(
            "Open dev.twitch.tv/console",
            "Register an application",
            "Copy Client ID and Secret",
            "Paste them here",
        ),
        credit = "Data from IGDB.com",
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
    ),
}

/** What a source row and the guide show for a credential's state. */
object ScraperKeyState {
    const val NOT_SET = "Not set"
    const val NOT_TESTED = "Not tested"
    const val CONNECTED = "Connected"

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
        val outcome = guarded {
            when (service) {
                ScraperKeyService.IGDB -> ScraperKeyState.outcome(
                    IgdbScraperClient.search(ScraperPrefs.clientId(context), ScraperPrefs.clientSecret(context), PROBE_TITLE, limit = 1),
                )
                ScraperKeyService.STEAMGRIDDB -> ScraperKeyState.outcome(
                    SteamGridDbScraperClient.search(SteamGridDbPrefs.apiKey(context), PROBE_TITLE),
                )
            }
        }
        // A network failure says nothing about the credential: keep the last verdict.
        if (outcome != ScraperKeyState.NO_CONNECTION) {
            val ok = outcome == ScraperKeyState.CONNECTED
            when (service) {
                ScraperKeyService.IGDB -> ScraperPrefs.setVerified(context, ok)
                ScraperKeyService.STEAMGRIDDB -> SteamGridDbPrefs.setVerified(context, ok)
            }
        }
        return outcome
    }

    /** The state label for [service] from what is stored. */
    fun state(context: Context, service: ScraperKeyService): String = when (service) {
        ScraperKeyService.IGDB ->
            ScraperKeyState.label(ScraperPrefs.isConfigured(context), ScraperPrefs.verified(context))
        ScraperKeyService.STEAMGRIDDB ->
            ScraperKeyState.label(SteamGridDbPrefs.isConfigured(context), SteamGridDbPrefs.verified(context))
    }
}
