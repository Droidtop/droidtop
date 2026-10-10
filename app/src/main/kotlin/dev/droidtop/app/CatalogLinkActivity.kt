package dev.droidtop.app

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dev.droidtop.library.integrations.PluginCatalogScreen
import dev.droidtop.library.integrations.PluginCatalogSources
import dev.droidtop.library.settings.CatalogScreenLink
import dev.droidtop.library.settings.UiModePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The two catalog links (docs/SPEC.md 12a "Known catalogs", "Install links"), which a catalog's own page can carry:
 * `droidtop://add-catalog?address=<catalog address>` and `droidtop://install-plugin?catalog=<catalog address>&id=<plugin id>`,
 * each also on `https://droidtop.github.io/`. It has no screen of its own. An add-catalog link fetches the catalog and
 * opens More catalogs on that catalog's review, its notice above Accept ([PluginCatalogScreen.prepareLink]); an
 * install-plugin link opens that plugin's install review ([PluginCatalogScreen.prepareInstallLink]). A link can neither
 * add, trust nor install anything, because each is the person's Accept or Install on the review it opens.
 * Where Settings is hidden (Kiosk, Kid) a link does nothing.
 */
class CatalogLinkActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = intent?.dataString
        val install = link?.let(PluginCatalogSources::installFromLink)
        val address = if (install == null) link?.let(PluginCatalogSources::addressFromLink) else null
        if (install == null && address == null) {
            Toast.makeText(this, "That isn't a catalog or plugin link", Toast.LENGTH_LONG).show()
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
            val opening = withContext(Dispatchers.IO) {
                if (install != null) PluginCatalogScreen.prepareInstallLink(context, install) else PluginCatalogScreen.prepareLink(context, address!!)
            }
            if (opening.problem != null) Toast.makeText(context, opening.problem, Toast.LENGTH_LONG).show()
            startActivity(CatalogScreenLink.intent(context, opening.screenId))
            finish()
        }
    }
}
