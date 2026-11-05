package dev.droidtop.stores.steam

import android.content.Context
import dev.droidtop.library.social.SocialPresence

/**
 * droidtop's own Steam settings (docs/SPEC.md 7g, "Stores", Droidtop/tracker#313):
 * the person's standing (which is also whether the connection is kept) and
 * whether a new message notifies; its Cloud saves switch is [dev.droidtop.stores.util.CloudSaveSetting], in this file. One small preferences file; read
 * off the main thread like every settings read.
 */
internal object SteamPrefs {
    private const val FILE = "steam_prefs"
    private const val PRESENCE = "presence"
    private const val NOTIFY_MESSAGES = "notify_messages"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Online unless the person chose otherwise: a signed-in Steam stays connected (owner, 2026-10-08). */
    fun presence(context: Context): SocialPresence = SocialPresence.from(prefs(context).getString(PRESENCE, null))

    fun setPresence(context: Context, presence: SocialPresence) {
        prefs(context).edit().putString(PRESENCE, presence.key).apply()
    }

    /** Whether the connection is kept at all: every standing but Offline. */
    fun stayConnected(context: Context): Boolean = presence(context) != SocialPresence.OFFLINE

    fun notifyMessages(context: Context): Boolean = prefs(context).getBoolean(NOTIFY_MESSAGES, true)

    fun setNotifyMessages(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(NOTIFY_MESSAGES, on).apply()
    }
}
