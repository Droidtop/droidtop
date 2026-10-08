package dev.droidtop.library.social

import android.content.Context

/**
 * The person's choice per app for [AppMessages] (docs/SPEC.md "Social: messages from your apps"), and the apps
 * that have posted a conversation, so their switches can be listed. Package names and one flag each; no
 * conversation content is ever stored. Read off the main thread like every settings read.
 */
object AppMessagesPrefs {
    private const val FILE = "app_messages_prefs"
    private const val CHOSEN_PREFIX = "chosen:"
    private const val SEEN = "seen"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The person's own choice for [packageName], or null when they have made none. */
    fun chosen(context: Context, packageName: String): Boolean? {
        val p = prefs(context)
        return if (p.contains(CHOSEN_PREFIX + packageName)) p.getBoolean(CHOSEN_PREFIX + packageName, true) else null
    }

    fun setChosen(context: Context, packageName: String, on: Boolean) {
        prefs(context).edit().putBoolean(CHOSEN_PREFIX + packageName, on).apply()
    }

    /** Every choice made, for one pass over a notification list without a read per notification. */
    fun allChosen(context: Context): Map<String, Boolean> =
        prefs(context).all.mapNotNull { (k, v) -> if (k.startsWith(CHOSEN_PREFIX) && v is Boolean) k.removePrefix(CHOSEN_PREFIX) to v else null }.toMap()

    /**
     * The apps that have posted a message-like notification: `<package>` is stored as `<package>|1` when it posted
     * MessagingStyle (on by default) and `<package>|0` otherwise.
     */
    fun seen(context: Context): Map<String, Boolean> =
        prefs(context).getStringSet(SEEN, emptySet()).orEmpty().associate { it.substringBeforeLast('|') to (it.endsWith("|1")) }

    /** Notes that [packageName] posted a message-like notification; writes only when that is news. */
    fun noteSeen(context: Context, packageName: String, messagingStyle: Boolean) {
        val now = seen(context)
        val was = now[packageName]
        if (was != null && (was || !messagingStyle)) return
        val next = HashSet<String>()
        for ((pkg, ms) in now) if (pkg != packageName) next += pkg + (if (ms) "|1" else "|0")
        next += packageName + (if (messagingStyle) "|1" else "|0")
        prefs(context).edit().putStringSet(SEEN, next).apply()
    }
}
