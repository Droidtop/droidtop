package dev.droidtop.app

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
    private companion object {
        val SWARM_TYPES = setOf("application/x-bittorrent", "application/metalink4+xml", "application/metalink+xml")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fileOf(intent)?.let { file ->
            offerFile(file)
            return
        }
        val link = linkOf(intent)
        if (link == null) {
            dev.droidtop.library.settings.Notices.flash("That isn't a link droidtop can open", tone = dev.droidtop.library.settings.NoticeTone.Caution, context = this)
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
                    result.message?.let { dev.droidtop.library.settings.Notices.flash(it, context = context) }
                    startActivity(CatalogScreenLink.intent(context, result.screenId))
                }
                is LinkRouter.Result.Message -> dev.droidtop.library.settings.Notices.flash(result.text, context = context)
                is LinkRouter.Result.Browser -> toBrowser(result.link)
            }
            finish()
        }
    }

    /**
     * A .torrent or Metalink file opened with or shared to droidtop (its type says so, or its name ends that way), as
     * its address; null for anything else.
     */
    private fun fileOf(intent: Intent?): Uri? {
        intent ?: return null
        @Suppress("DEPRECATION")
        val uri = (if (intent.action == Intent.ACTION_SEND) intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri else intent.data) ?: return null
        if (uri.scheme != "content" && uri.scheme != "file") return null
        val type = intent.type ?: runCatching { contentResolver.getType(uri) }.getOrNull()
        val name = uri.lastPathSegment.orEmpty().lowercase()
        val swarm = type in SWARM_TYPES || listOf(".torrent", ".meta4", ".metalink").any { name.endsWith(it) }
        return uri.takeIf { swarm }
    }

    /** Offers a received file in Downloads > Add download, which asks where it goes; nothing downloads before that. */
    private fun offerFile(uri: Uri) {
        val context = applicationContext
        if (UiModePrefs.get(this).hidesSettings) {
            dev.droidtop.library.settings.Notices.flash("Downloads cannot be added in this UI mode", context = context)
            finish()
            return
        }
        lifecycleScope.launch {
            val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                dev.droidtop.library.integrations.ManualDownloads.readSmallFile(context, uri)
            }
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "Download"
            // Null when it was offered; otherwise why it was not.
            val why = if (bytes == null) {
                "That file could not be read, or is larger than a torrent can be"
            } else {
                dev.droidtop.library.integrations.ManualDownloads.offerFile(name, bytes)
            }
            if (why == null) {
                startActivity(CatalogScreenLink.intent(context, dev.droidtop.library.integrations.ManualDownloads.SCREEN_ID))
            } else {
                dev.droidtop.library.settings.Notices.flash(why, tone = dev.droidtop.library.settings.NoticeTone.Caution, context = context)
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
        runCatching { startActivity(chooser) }.onFailure { dev.droidtop.library.settings.Notices.flash("No browser is installed to open $link", tone = dev.droidtop.library.settings.NoticeTone.Caution, context = this) }
    }
}
