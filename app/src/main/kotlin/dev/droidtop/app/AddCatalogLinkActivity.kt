package dev.droidtop.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dev.droidtop.library.integrations.PluginCatalogScreen
import dev.droidtop.library.integrations.PluginCatalogSources
import dev.droidtop.library.settings.CatalogScreenLink
import dev.droidtop.library.settings.UiModePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The "Add catalog" link (docs/SPEC.md 12a "Known catalogs"): `droidtop://add-catalog?address=<catalog address>`,
 * or the same on `https://droidtop.github.io/add-catalog`, which a catalog's own page can carry. It has no
 * screen of its own. It fetches the catalog the link names and opens More catalogs on that catalog's review,
 * its notice above Accept ([PluginCatalogScreen.prepareLink]); the link can neither add nor trust anything,
 * because adding is the person's Accept on that review. Where Settings is hidden (Kiosk, Kid) a link does nothing.
 *
 * A plain ComponentActivity: its manifest theme is the platform's translucent one (it draws nothing), which an
 * AppCompatActivity refuses at launch ("You need to use a Theme.AppCompat theme").
 */
class AddCatalogLinkActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val address = intent?.dataString?.let(PluginCatalogSources::addressFromLink)
        if (address == null) {
            Toast.makeText(this, "That isn't a catalog link", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (UiModePrefs.get(this).hidesSettings) {
            Toast.makeText(this, "Plugins are hidden in this UI mode", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val context = applicationContext
        lifecycleScope.launch {
            val problem = withContext(Dispatchers.IO) { PluginCatalogScreen.prepareLink(context, address) }
            if (problem != null) Toast.makeText(context, problem, Toast.LENGTH_LONG).show()
            startActivity(CatalogScreenLink.intent(context, PluginCatalogScreen.ID))
            finish()
        }
    }
}
