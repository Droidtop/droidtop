package dev.droidtop.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.droidtop.app.settings.ScraperKeyScreen
import dev.droidtop.app.ui.DroidtopTheme
import dev.droidtop.library.credentials.web.KeyPageSpec
import dev.droidtop.library.credentials.web.KeyPages
import dev.droidtop.library.scraper.ScraperKeyService
import dev.droidtop.shell.gamepad.CatalogNavigator
import dev.droidtop.shell.gamepad.LocalShellWindow
import dev.droidtop.shell.gamepad.currentShellWindow
import dev.droidtop.shell.gamepad.groundBackground
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.HintBinding
import dev.droidtop.shell.gamepad.input.HintRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The guided setup for a source that needs the person's own credential (docs/SPEC.md 7h, "Supplying your own keys"),
 * opened from a game page's sign-in row and from the key page's "Open the key page here". Where droidtop's browser
 * pane can show the service's key page, it opens on that pane ([KeyWebPane]), which reads the key off the page when
 * the person confirms. Otherwise, and from the pane's "Other ways", it is the service's Settings page
 * ([ScraperKeyScreen]), drawn by the same [CatalogNavigator] as every other page, with the shell's hint row: the
 * page Settings > Accounts and sources opens in place (Droidtop/tracker#869).
 */
class ScraperKeySetupActivity : AppCompatActivity() {
    private val panelFocus = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val service = ScraperKeyService.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_SERVICE) }
        if (service == null) {
            finish()
            return
        }
        setContent {
            DroidtopTheme(gamingThemed = dev.droidtop.app.ui.rememberGamingThemed()) {
                CompositionLocalProvider(LocalShellWindow provides currentShellWindow()) {
                    var page by remember { mutableStateOf<KeyPageSpec?>(null) }
                    var loaded by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) {
                        page = withContext(Dispatchers.IO) { keyPage(this@ScraperKeySetupActivity, service) }
                        loaded = true
                    }
                    if (loaded) SetupHost(service, page, panelFocus.value, onClose = { finish() })
                }
            }
        }
    }

    // L1 and R1 move between the page and the steps beside it; the page otherwise swallows the pad.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_BUTTON_L1 || event.keyCode == KeyEvent.KEYCODE_BUTTON_R1)
        ) {
            panelFocus.value = !panelFocus.value
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        private const val EXTRA_SERVICE = "service"

        fun intent(context: Context, service: ScraperKeyService): Intent =
            Intent(context, ScraperKeySetupActivity::class.java).putExtra(EXTRA_SERVICE, service.name)

        /** [service]'s key page for the browser pane (`key-pages.json`), or null for none. Reads an asset: off the main thread. */
        fun keyPage(context: Context, service: ScraperKeyService): KeyPageSpec? = runCatching {
            KeyPages.parse(context.assets.open("key-pages.json").bufferedReader().use { it.readText() })[service.name]
        }.getOrNull()
    }
}

private enum class SetupMode { WEB, PAGE }

@Composable
private fun SetupHost(service: ScraperKeyService, page: KeyPageSpec?, panelFocus: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    val paneAvailable = page != null && KeyWebProfiles.supported()
    var mode by remember { mutableStateOf(if (paneAvailable) SetupMode.WEB else SetupMode.PAGE) }
    if (mode == SetupMode.WEB && page != null) {
        KeyWebPane(
            service = service,
            page = page,
            panelFocus = panelFocus,
            onOtherWays = { mode = SetupMode.PAGE },
            onFallback = {
                // The provider refused the pane: the browser opens on its page and the session to type from a phone over it.
                mode = SetupMode.PAGE
                dev.droidtop.library.Links.open(context, service.url)
                RemoteInputActivity.open(context)
            },
            onClose = onClose,
        )
        return
    }
    val screen = remember(service) { ScraperKeyScreen.screen(service) }
    Column(Modifier.fillMaxSize().groundBackground()) {
        Box(Modifier.weight(1f)) {
            CatalogNavigator(root = screen, onExit = onClose)
        }
        // The shell's hint row, as on every page this host draws (CatalogScreenActivity): A, B and Y dispatch inside the
        // navigator; on a touch screen these are the controls.
        HintRow(
            bindings = listOf(
                HintBinding(GamepadAction.A, "Select"),
                HintBinding(GamepadAction.B, "Back"),
                HintBinding(GamepadAction.Y, "Info"),
            ),
        )
    }
}
