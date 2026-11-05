package dev.droidtop.app.settings

import android.content.Context
import android.content.Intent
import dev.droidtop.app.R
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
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.TextBlockItem
import dev.droidtop.library.settings.TextInputItem
import dev.droidtop.pluginhost.debrid.RealDebridSignIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * A credential's setup page (docs/SPEC.md 7h, "Supplying your own keys") as a Settings page of droidtop's: the same
 * frame, rows, hints and focus as every other page, wherever it is drawn (Droidtop/tracker#869). The owner, of the
 * page it replaces: "a massive breach of UI rules" (its own pills and a bare Paste link, a numbered how-to and a QR
 * code, three unlabelled status lines and a bare text box).
 *
 * The rows: the state as a row; the ways to the key (Real-Debrid's sign-in with a code, the key page in droidtop's
 * own browser pane where it works, the page in the browser with its steps as the row's Info, and Type from another
 * device); the key's fields as text rows (each with Paste in its dialog), stored as they are entered; Test; and Clear.
 */
object ScraperKeyScreen {
    /** [service]'s page. */
    fun screen(service: ScraperKeyService): CatalogScreen = CatalogScreen(
        id = "key_setup_${service.name.lowercase()}",
        title = service.title,
        subtitle = service.gets,
        groups = { context ->
            // Whether droidtop's browser pane can show the key page ([ScraperKeySetupActivity]): WebView asked on its thread.
            val pane = withContext(Dispatchers.Main) { dev.droidtop.app.KeyWebProfiles.supported() } &&
                withContext(Dispatchers.IO) { ScraperKeySetupActivity.keyPage(context, service) } != null
            withContext(Dispatchers.IO) { groups(context, service, pane) }
        },
    )

    private fun groups(context: Context, service: ScraperKeyService, paneAvailable: Boolean): List<CatalogGroup> {
        val id = service.name.lowercase()
        val state = ScraperKeyCheck.state(context, service)
        val stored = service.read(context)
        val ways = buildList<CatalogItem> {
            if (service == ScraperKeyService.REALDEBRID) add(realDebridSignIn(id))
            if (paneAvailable) {
                add(
                    ActionItem(
                        id = "${id}_pane",
                        title = context.getString(R.string.scraper_key_setup_open_the_key_page_here),
                        subtitle = "Sign in there, and droidtop reads the key off the page",
                        run = { ctx -> ctx.startActivity(ScraperKeySetupActivity.intent(ctx, service).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                    ),
                )
            }
            add(
                ActionItem(
                    id = "${id}_page",
                    title = "Open the key page in the browser",
                    // The steps are this row's Info (Y), where a long subtitle is shown whole: in-place guidance, not a list.
                    subtitle = (listOf(service.url.removePrefix("https://")) + service.steps).joinToString(". "),
                    run = { ctx -> dev.droidtop.library.Links.open(ctx, service.url) },
                ),
            )
            add(
                ActionItem(
                    id = "${id}_remote",
                    title = context.getString(R.string.scraper_key_setup_type_from_another_device),
                    subtitle = "Type the key on your phone or computer, into the field you choose here",
                    run = { ctx -> RemoteInputActivity.open(ctx) },
                ),
            )
        }
        val key = buildList<CatalogItem> {
            service.fields.forEach { field ->
                add(
                    TextInputItem(
                        id = "${id}_field_${field.id}",
                        title = field.label,
                        value = stored[field.id].orEmpty(),
                        secret = field.secret,
                        onChange = { ctx, text -> withContext(Dispatchers.IO) { service.write(ctx, mapOf(field.id to text)) } },
                    ),
                )
            }
            add(
                AsyncActionItem(
                    id = "${id}_test",
                    title = context.getString(R.string.scraper_key_setup_test),
                    subtitle = "Asks ${service.title} with what is entered",
                    run = { ctx, onStatus ->
                        onStatus("Testing")
                        withContext(Dispatchers.IO) { ScraperKeyCheck.test(ctx, service) }
                    },
                ),
            )
            if (state != ScraperKeyState.NOT_SET && state != ScraperKeyState.NO_ACCOUNT && state != ScraperKeyState.BUILT_IN) {
                add(
                    ActionItem(
                        id = "${id}_clear",
                        title = context.getString(R.string.scraper_key_setup_clear),
                        subtitle = "Removes the saved key from this device",
                        confirmTitle = "Clear the ${service.title} key?",
                        run = { ctx -> service.write(ctx, service.fields.associate { it.id to "" }) },
                    ),
                )
            }
        }
        return listOf(
            CatalogGroup(
                id = "${id}_state_group",
                title = null,
                items = listOf(ActionItem(id = "${id}_state", title = "Status", value = state)),
            ),
            CatalogGroup(id = "${id}_ways", title = "Get your key", items = ways),
            CatalogGroup(id = "${id}_key", title = "Your key", items = key),
            CatalogGroup(id = "${id}_credit", title = null, items = listOf(TextBlockItem(id = "${id}_credit_text", text = service.credit))),
        )
    }

    /**
     * Real-Debrid's device sign-in (Droidtop/tracker#638): a short code to enter at real-debrid.com/device on any
     * device, shown in the row while it waits. Approval stores the sign-in, whose token droidtop refreshes itself;
     * leaving the page stops the wait.
     */
    private fun realDebridSignIn(id: String) = AsyncActionItem(
        id = "${id}_code",
        title = "Sign in with a code",
        subtitle = "Approve it on any device, then the key is kept here",
        run = { ctx, onStatus ->
            withContext(Dispatchers.IO) {
                val flow = RealDebridSignIn()
                val (issued, refused) = try {
                    flow.code()
                } catch (e: java.io.IOException) {
                    null to "Can't reach Real-Debrid. Try again when you are online"
                }
                if (issued == null) return@withContext refused ?: "Real-Debrid gave no code"
                val at = issued.verificationUrl.removePrefix("https://")
                val context = currentCoroutineContext()
                val outcome = try {
                    flow.await(issued, isCancelled = { !context.isActive }, onWaiting = { left ->
                        onStatus("Enter ${issued.userCode} at $at · %d:%02d left".format(left / 60, left % 60))
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
