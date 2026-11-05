package dev.droidtop.runtime.systemstatus

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState

/**
 * What is playing now, read from the newest active media session through droidtop's notification access
 * (`MediaSessionManager.getActiveSessions`): no account and no credentials. One reader for the companion's Now
 * playing card and Gaming's Quick Menu (Droidtop/tracker#716). Without access, or with no session, there is nothing.
 */
object MediaNow {
    /** droidtop's notification listener, the component the session list is asked for. */
    fun listener(context: Context): ComponentName = ComponentName(context.packageName, NotificationsStore.LISTENER_CLASS)

    /** The newest session's controller, or null. A binder call: off the main thread where it can be. */
    fun controller(context: Context): MediaController? = runCatching {
        context.getSystemService(MediaSessionManager::class.java)?.getActiveSessions(listener(context))?.firstOrNull()
    }.getOrNull()

    /** What [controller]'s session says, as the card and the tiles draw it; null for no controller or no title. */
    fun nowPlaying(context: Context, controller: MediaController?): NowPlaying? {
        controller ?: return null
        val metadata = controller.metadata
        val app = runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(controller.packageName, 0)).toString()
        }.getOrDefault(controller.packageName)
        return NowPlaying.from(
            title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
            artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST),
            app = app,
            state = controller.playbackState?.state,
            actions = controller.playbackState?.actions ?: 0L,
        )
    }

    /**
     * Calls [onChange] whenever the sessions change or the newest one's track or playing state does, until the
     * returned stop is called. Call on a thread with a Looper (the main thread): the callbacks arrive there.
     */
    fun watch(context: Context, onChange: () -> Unit): () -> Unit {
        val manager = context.getSystemService(MediaSessionManager::class.java) ?: return {}
        var watched: MediaController? = null
        val callback = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) = onChange()
            override fun onMetadataChanged(metadata: MediaMetadata?) = onChange()
            override fun onSessionDestroyed() = onChange()
        }
        fun follow(sessions: List<MediaController>?) {
            watched?.unregisterCallback(callback)
            watched = sessions?.firstOrNull()
            watched?.registerCallback(callback)
            onChange()
        }
        val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { follow(it) }
        val listening = runCatching {
            manager.addOnActiveSessionsChangedListener(sessionsListener, listener(context))
            follow(manager.getActiveSessions(listener(context)))
        }.isSuccess
        return {
            if (listening) runCatching { manager.removeOnActiveSessionsChangedListener(sessionsListener) }
            watched?.unregisterCallback(callback)
        }
    }
}
