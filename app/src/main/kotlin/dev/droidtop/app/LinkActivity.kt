package dev.droidtop.app

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dev.droidtop.library.integrations.LinkRouter
import dev.droidtop.library.settings.CatalogScreenLink
import dev.droidtop.library.settings.UiModePrefs
import kotlinx.coroutines.launch

/**
 * droidtop's one entry point for links (docs/SPEC.md 12a "Links", Droidtop/tracker#459): its own `droidtop://` action
 * links, the third-party schemes a plugin registers (the disabled-until-needed aliases), every https link (droidtop is a general web-link handler that names no site) and text shared to droidtop. It has
 * no screen of its own: [LinkRouter] decides, and this opens the screen the answer names, says the line it carries,
 * or passes a web link nobody claims on to the browser. Where Settings is hidden (Kiosk, Kid) droidtop opens no link
 * itself; a web link still reaches the browser.
 */
class LinkActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = linkOf(intent)
        if (link == null) {
            Toast.makeText(this, "That isn't a link droidtop can open", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val context = applicationContext
        val hidden = UiModePrefs.get(this).hidesSettings
        lifecycleScope.launch {
            val web = link.startsWith("https://", ignoreCase = true)
            val result = when {
                !hidden -> LinkRouter.route(context, link)
                web -> LinkRouter.Result.Browser(link)
                else -> LinkRouter.Result.Message("Links are not opened in this UI mode")
            }
            when (result) {
                is LinkRouter.Result.Open -> {
                    result.message?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                    startActivity(CatalogScreenLink.intent(context, result.screenId))
                }
                is LinkRouter.Result.Message -> Toast.makeText(context, result.text, Toast.LENGTH_LONG).show()
                is LinkRouter.Result.Browser -> toBrowser(result.link)
            }
            finish()
        }
    }

    /** The link a VIEW carries, or the first web or droidtop link in shared text. */
    private fun linkOf(intent: Intent?): String? {
        intent ?: return null
        if (intent.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
            return Regex("""(https|droidtop)://\S+""").find(text)?.value?.trimEnd('.', ',', ')', '>', '"', '\'')
        }
        return intent.dataString
    }

    /**
     * Hands a web link to the browser, never back to droidtop: the person's default browser when it is another app,
     * else Android's chooser without droidtop in it.
     */
    private fun toBrowser(link: String) {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(link)).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.org/")).addCategory(Intent.CATEGORY_BROWSABLE)
        val default = packageManager.resolveActivity(probe, 0)?.activityInfo
        val started = if (default != null && default.packageName != packageName && default.packageName != "android") {
            runCatching { startActivity(Intent(view).setComponent(ComponentName(default.packageName, default.name))) }.isSuccess
        } else {
            false
        }
        if (started) return
        val chooser = Intent.createChooser(view, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 24) {
            chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(this, LinkActivity::class.java)))
        }
        runCatching { startActivity(chooser) }.onFailure { Toast.makeText(this, "No browser is installed to open $link", Toast.LENGTH_LONG).show() }
    }
}
