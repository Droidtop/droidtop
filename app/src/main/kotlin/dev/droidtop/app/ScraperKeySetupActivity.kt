package dev.droidtop.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.droidtop.app.ui.DroidtopTheme
import dev.droidtop.library.credentials.web.KeyPageSpec
import dev.droidtop.library.credentials.web.KeyPages
import dev.droidtop.library.scraper.ScraperKeyService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The service's own key page in droidtop's browser pane, which reads the key off the page when the person confirms
 * ([KeyWebPane]; docs/SPEC.md 7h, "Supplying your own keys"). Only the pane: everything else about a source's key
 * (its state, the other ways in, the fields, Test and Clear) is its Settings page in the shared frame
 * ([dev.droidtop.app.settings.KeySetupCatalog], Droidtop/tracker#869), whose "Open the key page here" row opens this.
 * A web page cannot be a settings row, so this one window stays.
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
                run {
                    var page by remember { mutableStateOf<KeyPageSpec?>(null) }
                    var loaded by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) {
                        page = withContext(Dispatchers.IO) {
                            runCatching {
                                KeyPages.parse(assets.open("key-pages.json").bufferedReader().use { it.readText() })[service.name]
                            }.getOrNull()
                        }
                        loaded = true
                    }
                    if (loaded) {
                        SetupHost(service, page, panelFocus.value, onClose = { finish() })
                    }
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
    }
}

@Composable
private fun SetupHost(
    service: ScraperKeyService,
    page: KeyPageSpec?,
    panelFocus: Boolean,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    // The Settings page offers this window only where the pane can show the page; otherwise there is nothing here.
    if (page == null || !KeyWebProfiles.supported()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    KeyWebPane(
        service = service,
        page = page,
        panelFocus = panelFocus,
        // The other ways are the Settings page this was opened from.
        onOtherWays = onClose,
        onFallback = {
            // The provider refused the pane: the browser opens on its page and the session to type from a phone over it.
            dev.droidtop.library.Links.open(context, service.url)
            RemoteInputActivity.open(context)
            onClose()
        },
        onClose = onClose,
    )
}
