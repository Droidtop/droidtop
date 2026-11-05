package dev.droidtop.app.settings

import android.content.Context
import android.content.Intent
import dev.droidtop.app.RemoteInputActivity
import dev.droidtop.app.ScraperKeySetupActivity
import dev.droidtop.library.debrid.DebridAccounts
import dev.droidtop.library.scraper.ScraperKeyCheck
import dev.droidtop.library.scraper.ScraperKeyService
import dev.droidtop.library.scraper.ScraperKeyState
import dev.droidtop.library.scraper.ScraperKeyVerified
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogIcon
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.NestedScreenItem
import dev.droidtop.library.settings.TextInputItem
import dev.droidtop.pluginhost.debrid.RealDebridSignIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A source's own credential, set up on a Settings page like every other (Droidtop/tracker#869; the owner, 2026-11-04,
 * on the TorBox page: "We need to clean up and unify this page, it's a massive breach of UI rules"). It was its own
 * activity with Android's status bar over it, no top bar, footer or focus frame, two button styles, a numbered how-to
 * list, a QR code, three unlabelled status lines and a bare text box. Now it is a [CatalogScreen], drawn by every
 * Settings renderer in the shared page frame:
 * - **Status**: what the credential is (Connected, Not tested, Not set), with what the source gives and its credit
 *   line as the row's words.
 * - **Get the key**: Real-Debrid's sign-in with a code; the key page in droidtop's browser pane (the one part that is
 *   still its own window, [ScraperKeySetupActivity], because a web page cannot be a settings row); the page on this
 *   device; typing it from another device. The how-to is the hint under the row it belongs to, never a list.
 * - **The key's fields**: the app's one text-entry row (Paste is in its dialog), saved as typed.
 * - **Test** (it says it is testing while it runs) and **Clear**, which asks first.
 * One screen, deep-linked by the service's name ([SCREEN_ID]), so the game page's sign-in row opens the same page.
 */
object KeySetupCatalog {
    const val SCREEN_ID = "key_setup"

    /** The registered root: resolved only through its deep link, one page per service. */
    fun screen(): CatalogScreen = CatalogScreen(
        id = SCREEN_ID,
        title = "Keys",
        groups = { emptyList() },
        indexGroups = { emptyList() },
        forDeepLink = { name -> ScraperKeyService.entries.firstOrNull { it.name == name }?.let { page(it, it.title) } ?: screen() },
    )

    /** A source's row on its list: its state as the value, its page behind it. Built off the main thread. */
    fun row(context: Context, service: ScraperKeyService, title: String): CatalogItem {
        val state = ScraperKeyCheck.state(context, service)
        return NestedScreenItem(
            id = "accounts_" + service.name.lowercase(),
            title = title,
            inline = page(service, title),
            valueLabel = { state },
            icon = CatalogIcon.GLOBAL,
        )
    }

    /** Opens [service]'s page from outside Settings (a game page's sign-in row). */
    fun open(context: Context, service: ScraperKeyService) {
        context.startActivity(dev.droidtop.library.settings.CatalogScreenLink.intent(context, SCREEN_ID, service.name))
    }

    private fun page(service: ScraperKeyService, title: String): CatalogScreen {
        // Real-Debrid's code sign-in waits on the person; leaving the page stops the wait.
        val cancelled = AtomicBoolean(false)
        return CatalogScreen(
            id = "${SCREEN_ID}_${service.name.lowercase()}",
            title = title,
            needsNetwork = true,
            onLeave = { cancelled.set(true) },
            groups = { context -> withContext(Dispatchers.IO) { groups(context, service, cancelled) } },
        )
    }

    private fun groups(context: Context, service: ScraperKeyService, cancelled: AtomicBoolean): List<CatalogGroup> {
        val state = ScraperKeyCheck.state(context, service)
        val stored = service.read(context)
        val key = service.name.lowercase()
        val pane = paneAvailable(context, service)
        val status = ActionItem(
            id = "key_${key}_status",
            title = "Status",
            subtitle = "${service.gets}. ${service.credit}",
            value = state,
        )
        val ways = buildList<CatalogItem> {
            if (service == ScraperKeyService.REALDEBRID) add(realDebridSignIn(cancelled))
            if (pane) {
                add(
                    ActionItem(
                        id = "key_${key}_pane",
                        title = "Open the key page here",
                        subtitle = stepsHint(service),
                        run = { ctx -> ctx.startActivity(ScraperKeySetupActivity.intent(ctx, service).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                    ),
                )
            }
            add(
                ActionItem(
                    id = "key_${key}_open",
                    title = "Open on this device",
                    // The address, so it can be opened on any other device too.
                    subtitle = service.url.removePrefix("https://").removePrefix("www.") +
                        if (pane) "" else ". " + stepsHint(service),
                    run = { ctx -> dev.droidtop.library.Links.open(ctx, service.url) },
                ),
            )
            add(
                ActionItem(
                    id = "key_${key}_remote",
                    title = "Type from another device",
                    subtitle = "Scan a code with a phone, then type the key there into the field you pick here",
                    run = { ctx -> RemoteInputActivity.open(ctx) },
                ),
            )
        }
        val fields = service.fields.map { field ->
            TextInputItem(
                id = "key_${key}_${field.id}",
                title = field.label,
                value = stored[field.id].orEmpty(),
                secret = field.secret,
                onChange = { ctx, text -> withContext(Dispatchers.IO) { service.write(ctx, mapOf(field.id to text.trim())) } },
            )
        }
        val check = buildList<CatalogItem> {
            add(
                AsyncActionItem(
                    id = "key_${key}_test",
                    title = "Test",
                    subtitle = "Asks ${service.title} once with what is saved",
                    value = state.takeIf { it != ScraperKeyState.NOT_SET },
                    run = { ctx, onStatus ->
                        onStatus("Testing")
                        withContext(Dispatchers.IO) { ScraperKeyCheck.test(ctx, service) }
                    },
                ),
            )
            if (state != ScraperKeyState.NOT_SET && state != ScraperKeyState.NO_ACCOUNT && state != ScraperKeyState.BUILT_IN) {
                add(
                    AsyncActionItem(
                        id = "key_${key}_clear",
                        title = "Clear",
                        subtitle = "Forgets what is saved for ${service.title} on this device",
                        confirmTitle = "Clear the saved ${service.title} key? Downloads and lookups through it stop until it is set again",
                        confirmKeep = "Keep it",
                        run = { ctx, _ ->
                            withContext(Dispatchers.IO) { service.write(ctx, service.fields.associate { it.id to "" }) }
                            "Cleared"
                        },
                    ),
                )
            }
        }
        return listOf(
            CatalogGroup(id = "key_${key}_state", title = null, items = listOf(status)),
            CatalogGroup(id = "key_${key}_ways", title = "Get the key", items = ways),
            CatalogGroup(id = "key_${key}_fields", title = if (service == ScraperKeyService.REALDEBRID) "Or paste a token" else "Your key", items = fields),
            CatalogGroup(id = "key_${key}_check", title = "Check", items = check),
        )
    }

    /**
     * Whether droidtop's browser pane can show [service]'s key page: the pane needs WebView profiles, and the page needs
     * a reading in key-pages.json. Reads an asset: off the main thread.
     */
    private fun paneAvailable(context: Context, service: ScraperKeyService): Boolean =
        dev.droidtop.app.KeyWebProfiles.supported() && runCatching {
            dev.droidtop.library.credentials.web.KeyPages.parse(
                context.assets.open("key-pages.json").bufferedReader().use { it.readText() },
            )[service.name] != null
        }.getOrDefault(false)

    /** The how-to as one hint under the row it belongs to: "Sign in, open Preferences, API, copy the API key". */
    internal fun stepsHint(service: ScraperKeyService): String =
        service.steps.filterNot { it.endsWith(" here") }.drop(1)
            .mapIndexed { index, step -> if (index == 0) step else step.replaceFirstChar { it.lowercase() } }
            .joinToString(", ")
            .ifEmpty { service.steps.firstOrNull().orEmpty() }

    /**
     * Real-Debrid's device sign-in (Droidtop/tracker#638) as a row that shows its progress: the short code to enter at
     * real-debrid.com/device and the time left, then what it came to. Approval stores the sign-in, whose token droidtop
     * refreshes itself; leaving the page stops the wait.
     */
    private fun realDebridSignIn(cancelled: AtomicBoolean) = AsyncActionItem(
        id = "key_realdebrid_code",
        title = "Sign in with a code",
        subtitle = "Shows a short code to enter at real-debrid.com/device on any device",
        run = { ctx, onStatus ->
            cancelled.set(false)
            withContext(Dispatchers.IO) {
                val flow = RealDebridSignIn()
                onStatus("Asking Real-Debrid for a code")
                val (issued, refused) = try {
                    flow.code()
                } catch (e: java.io.IOException) {
                    null to "Can't reach Real-Debrid. Try again when you are online"
                }
                if (issued == null) return@withContext refused ?: "Real-Debrid did not give a code"
                val where = issued.verificationUrl.removePrefix("https://")
                val outcome = try {
                    flow.await(issued, isCancelled = { cancelled.get() }, onWaiting = { left ->
                        onStatus("Enter ${issued.userCode} at $where · %d:%02d left".format(left / 60, left % 60))
                    })
                } catch (e: java.io.IOException) {
                    RealDebridSignIn.Outcome.Failed("Lost the connection to Real-Debrid")
                }
                when (outcome) {
                    is RealDebridSignIn.Outcome.SignedIn -> {
                        DebridAccounts.saveRealDebrid(ctx, outcome.credential)
                        ScraperKeyVerified.set(ctx, ScraperKeyService.REALDEBRID, true)
                        "Signed in"
                    }
                    RealDebridSignIn.Outcome.Expired -> "The code ran out before it was approved. Start again for a new one"
                    RealDebridSignIn.Outcome.Cancelled -> "Sign-in cancelled. Nothing was saved"
                    is RealDebridSignIn.Outcome.Failed -> outcome.reason
                }
            }
        },
    )
}
