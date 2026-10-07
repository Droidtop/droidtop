package dev.droidtop.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import app.gamenative.ui.screen.auth.EpicOAuthActivity
import app.gamenative.ui.screen.auth.GOGOAuthActivity
import app.gamenative.utils.PlatformOAuthHandlers
import dev.droidtop.app.ui.DroidtopTheme
import kotlinx.coroutines.launch

/**
 * Signing in to GOG or Epic -- build-plan step 6 of the PC surface
 * (docs/SPEC.md 7i). Steam already has its own screen
 * ([SteamLoginActivity]); these two are OAuth in a web view, which is
 * gamenative's own `GOGOAuthActivity` / `EpicOAuthActivity` plus its
 * `PlatformOAuthHandlers` to exchange the returned code for a session and
 * pull the library down. A store droidtop runs itself signs in through
 * [StoreSignInActivity] instead (docs/SPEC.md 7g, "Stores").
 *
 * This Activity exists because an OAuth result is an Activity result: the
 * sign-in row that starts it lives in droidtop's own settings catalog,
 * which is data and cannot receive one. So it is deliberately a shim --
 * start the store's own flow, hand the code to the store's own handler,
 * say what happened, and get out of the way. No second web view and no
 * second token store.
 */
class PcStoreSignInActivity : AppCompatActivity() {

    private var status by mutableStateOf("Opening the sign-in page...")

    private val gog = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        onCode(result, GOGOAuthActivity.EXTRA_AUTH_CODE, GOGOAuthActivity.EXTRA_ERROR, Store.GOG)
    }

    private val epic = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        onCode(result, EpicOAuthActivity.EXTRA_AUTH_CODE, EpicOAuthActivity.EXTRA_ERROR, Store.EPIC)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DroidtopTheme(darkTheme = true, gamingThemed = dev.droidtop.app.ui.rememberGamingThemed()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(store()?.label ?: "Store sign-in", style = MaterialTheme.typography.headlineSmall)
                    Text(status, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        // Only on a first create: a rotation must not start the flow again
        // underneath the one already running.
        if (savedInstanceState != null) return
        when (store()) {
            Store.GOG -> gog.launch(Intent(this, GOGOAuthActivity::class.java))
            Store.EPIC -> epic.launch(Intent(this, EpicOAuthActivity::class.java))
            null -> status = "No store was named for this sign-in."
        }
    }

    private fun store(): Store? = Store.entries.firstOrNull { it.key == intent.getStringExtra(EXTRA_STORE) }

    private fun onCode(result: ActivityResult, codeExtra: String, errorExtra: String, store: Store) {
        val code = result.data?.getStringExtra(codeExtra)
        if (result.resultCode != RESULT_OK || code == null) {
            // Cancelling is a normal outcome, not a failure to report as one.
            status = result.data?.getStringExtra(errorExtra) ?: "Sign-in was cancelled."
            finish()
            return
        }
        status = "Signing in to ${store.label}..."
        lifecycleScope.launch {
            when (store) {
                Store.GOG -> PlatformOAuthHandlers.handleGogAuthentication(
                    context = this@PcStoreSignInActivity,
                    authCode = code,
                    coroutineScope = lifecycleScope,
                    onLoadingChange = {},
                    onError = { message -> status = message ?: "Sign-in failed." },
                    onSuccess = { status = "Signed in to GOG." },
                    onDialogClose = {},
                )
                Store.EPIC -> PlatformOAuthHandlers.handleEpicAuthentication(
                    context = this@PcStoreSignInActivity,
                    authCode = code,
                    coroutineScope = lifecycleScope,
                    onLoadingChange = {},
                    onError = { message -> status = message ?: "Sign-in failed." },
                    onSuccess = { status = "Signed in to Epic." },
                    onDialogClose = {},
                )
            }
            // The store's own handler owns the rest of the flow (token
            // exchange, then its library sync in its own scope), so this
            // screen is done either way rather than sitting on a spinner.
            finish()
        }
    }

    /** The stores GameNative still signs in through a web view. Steam is its own screen. */
    enum class Store(val key: String, val label: String) {
        GOG("gog", "GOG"),
        EPIC("epic", "Epic Games"),
    }

    companion object {
        const val EXTRA_STORE = "dev.droidtop.app.extra.PC_STORE"

        private const val CLASS_NAME = "dev.droidtop.app.PcStoreSignInActivity"

        fun intent(context: Context, store: Store): Intent =
            Intent().setClassName(context.packageName, CLASS_NAME).apply {
                putExtra(EXTRA_STORE, store.key)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }
}
