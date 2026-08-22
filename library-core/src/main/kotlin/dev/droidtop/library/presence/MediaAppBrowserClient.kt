package dev.droidtop.library.presence

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat

/**
 * Real, local, credential-free control of an installed media app -- per
 * direction, droidtop should never handle a streaming service's own
 * credentials itself (no OAuth, no developer app registration, no stored
 * tokens; this replaces an earlier Spotify-specific PKCE/Web-API client
 * that did all of that, removed). This uses exactly the mechanism Android
 * Auto, Wear OS, and Google Assistant use to browse/control media apps
 * without ever seeing the user's login: the standard `android.media.
 * browse.MediaBrowserService` API (`MediaBrowserCompat`/
 * `MediaControllerCompat`, `androidx.media`), bound directly to the app's
 * own exported service -- all local IPC, no network calls from droidtop.
 *
 * Generalized beyond Spotify per direction: any app implementing this same
 * standard service qualifies, not just one. [KnownMediaApps] holds the
 * real, device-verified targets found so far (`adb shell dumpsys package
 * <pkg>`, filtered for `android.media.browse.MediaBrowserService` --
 * verified against the actual apps installed on this project's own test
 * device, not guessed):
 *  - Spotify (`com.spotify.music`) -- verified.
 *  - YouTube -- verified against this device's actual installed build,
 *    which happens to be a ReVanced-patched APK (`app.revanced.android.
 *    youtube`); the service class name is real for that build specifically
 *    -- not yet confirmed whether the official `com.google.android.youtube`
 *    package uses the identical class name, so [KnownMediaApps.YOUTUBE]
 *    targets the package this device actually has, with a doc note to
 *    re-verify before assuming it matches the official app too.
 *  - Jellyfin (`org.jellyfin.mobile`) -- verified.
 *  - Tidal, YouTube Music: real, common apps that likely implement this
 *    same standard service (typical for any app with Android Auto
 *    support) but neither is installed on the test device, so their
 *    component names aren't verified here -- deliberately left out of
 *    [KnownMediaApps] rather than guessed; add once confirmed against a
 *    real install, same standard as everything else above.
 *
 * Requires the target app to be installed and already signed in --
 * entirely the user's own local session inside that app's own process;
 * droidtop only ever talks to it over this local service binding. If the
 * app isn't installed, connection simply fails (`onConnectionFailed`).
 *
 * Not yet tested against a real, connected session of any of these three
 * apps this session -- the component names are verified real, but
 * end-to-end browse/search/playback behavior (what each app's real content
 * tree looks like, whether `playFromSearch` is actually implemented by
 * each) needs a real run per app.
 */
object KnownMediaApps {
    data class Target(val packageName: String, val serviceClassName: String, val displayName: String)

    val SPOTIFY = Target(
        packageName = "com.spotify.music",
        serviceClassName = "com.spotify.mediabrowserservice.mediabrowserservice.SpotifyMediaBrowserService",
        displayName = "Spotify",
    )

    /** See this file's own doc comment -- verified against this device's real ReVanced build specifically. */
    val YOUTUBE = Target(
        packageName = "app.revanced.android.youtube",
        serviceClassName = "com.google.android.apps.youtube.app.extensions.mediabrowser.impl.MainAppMediaBrowserService",
        displayName = "YouTube",
    )

    val JELLYFIN = Target(
        packageName = "org.jellyfin.mobile",
        serviceClassName = "org.jellyfin.mobile.sessionbrowser.LibraryService",
        displayName = "Jellyfin",
    )

    val ALL = listOf(SPOTIFY, YOUTUBE, JELLYFIN)
}

class MediaAppBrowserClient(private val context: Context, private val target: KnownMediaApps.Target) {
    data class NowPlaying(
        val trackName: String,
        val artistName: String,
        val albumArtUri: String?,
        val isPlaying: Boolean,
        val positionMs: Long,
        val durationMs: Long,
    )

    fun interface PlaybackListener {
        fun onPlaybackChanged(nowPlaying: NowPlaying?)
    }

    private var mediaBrowser: MediaBrowserCompat? = null
    private var mediaController: MediaControllerCompat? = null
    private var playbackListener: PlaybackListener? = null

    private val controllerCallback = object : MediaControllerCompat.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) = notifyListener()
        override fun onMetadataChanged(metadata: MediaMetadataCompat?) = notifyListener()
    }

    private val connectionCallback = object : MediaBrowserCompat.ConnectionCallback() {
        override fun onConnected() {
            val browser = mediaBrowser ?: return
            val controller = MediaControllerCompat(context, browser.sessionToken)
            mediaController = controller
            controller.registerCallback(controllerCallback)
            notifyListener()
        }

        override fun onConnectionSuspended() {
            mediaController?.unregisterCallback(controllerCallback)
            mediaController = null
        }

        override fun onConnectionFailed() {
            mediaController = null
        }
    }

    /** True once actually bound to the target app's real session, not just "connect() was called". */
    val isConnected: Boolean get() = mediaBrowser?.isConnected == true

    fun connect(listener: PlaybackListener? = null) {
        playbackListener = listener
        if (mediaBrowser?.isConnected == true) return
        mediaBrowser = MediaBrowserCompat(
            context,
            ComponentName(target.packageName, target.serviceClassName),
            connectionCallback,
            null,
        ).also { it.connect() }
    }

    fun disconnect() {
        mediaController?.unregisterCallback(controllerCallback)
        mediaController = null
        mediaBrowser?.disconnect()
        mediaBrowser = null
        playbackListener = null
    }

    fun nowPlaying(): NowPlaying? {
        val controller = mediaController ?: return null
        val metadata = controller.metadata ?: return null
        val state = controller.playbackState
        return NowPlaying(
            trackName = metadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE).orEmpty(),
            artistName = metadata.getString(MediaMetadataCompat.METADATA_KEY_ARTIST).orEmpty(),
            albumArtUri = metadata.getString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI),
            isPlaying = state?.state == PlaybackStateCompat.STATE_PLAYING,
            positionMs = state?.position ?: 0L,
            durationMs = metadata.getLong(MediaMetadataCompat.METADATA_KEY_DURATION),
        )
    }

    fun play() = mediaController?.transportControls?.play()
    fun pause() = mediaController?.transportControls?.pause()
    fun skipNext() = mediaController?.transportControls?.skipToNext()
    fun skipPrevious() = mediaController?.transportControls?.skipToPrevious()
    fun seekTo(positionMs: Long) = mediaController?.transportControls?.seekTo(positionMs)

    /**
     * Real, documented `MediaSessionCompat.Callback.onPlayFromSearch` --
     * whether the target app actually implements it varies per app (it's
     * the same call Google Assistant voice queries use, e.g. "play [query]
     * on Spotify"); not guaranteed universal, honest per-app verification
     * still needed.
     */
    fun playFromSearch(query: String) = mediaController?.transportControls?.playFromSearch(query, Bundle.EMPTY)

    /** Plays a specific item by the opaque media ID a [browse] callback handed back. */
    fun playFromMediaId(mediaId: String) = mediaController?.transportControls?.playFromMediaId(mediaId, Bundle.EMPTY)

    /**
     * Browses the target app's real content tree (library, playlists,
     * etc.) starting from [parentId], or the real browse root when null --
     * standard `MediaBrowserCompat.subscribe`, item IDs are opaque strings
     * defined by the target app's own service, not something droidtop
     * constructs.
     */
    fun browse(parentId: String? = null, onChildren: (List<MediaBrowserCompat.MediaItem>) -> Unit) {
        val browser = mediaBrowser ?: return
        val id = parentId ?: browser.root
        browser.subscribe(
            id,
            object : MediaBrowserCompat.SubscriptionCallback() {
                override fun onChildrenLoaded(parentId: String, children: MutableList<MediaBrowserCompat.MediaItem>) {
                    onChildren(children)
                }
            },
        )
    }

    private fun notifyListener() {
        playbackListener?.onPlaybackChanged(nowPlaying())
    }
}
