package dev.droidtop.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.droidtop.library.settings.NoticeTone
import dev.droidtop.library.settings.Notices
import dev.droidtop.library.stores.StoreWeb

/**
 * The one way droidtop opens a web address a person pressed (Droidtop/tracker#763): a page of a store droidtop runs
 * opens in that store's own view, signed in ([StoreWeb.openLink]); anything else goes to Android as a browsable link,
 * so an app that claims it (Steam's s.team approve page, an app store) gets it, and droidtop's own link handler passes
 * any other on to the browser. When nothing can open it, a flash says so and the answer is false.
 */
object Links {
    fun open(context: Context, url: String): Boolean {
        if (StoreWeb.openLink(context, url)) return true
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = runCatching { context.startActivity(view) }.isSuccess
        if (!opened) Notices.flash("Nothing on this device can open $url", tone = NoticeTone.Caution, context = context)
        return opened
    }
}
