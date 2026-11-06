package dev.droidtop.shell.gamepad

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import dev.droidtop.library.settings.GamingSettingsCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Gaming's background music (Droidtop/tracker#725): the tracks of the person's music folder, shuffled and looped, while
 * the shell is in front and no game runs, at the volume of Settings > Shell behaviour > Background music (0, the
 * default, is off). It asks Android for audio focus as music, so another app's music or video pauses it until that
 * ends, and a short sound that asks to duck it (a notification) lowers it for a moment. It plays well under full
 * volume, so the navigation sounds stay heard over it. The folder is listed off the main thread; playback is
 * MediaPlayer's own, prepared asynchronously.
 */
object BackgroundMusic {
    private const val TAG = "droidtop.Music"
    private const val DUCKED = 0.2f

    private var shown = false
    private var gameRunning = false
    private var player: MediaPlayer? = null
    private var tracks: List<Uri> = emptyList()
    private var tracksFolder: String? = null
    private var next = 0
    private var volume = 0f
    private var focus: AudioFocusRequest? = null
    private var lostFocus = false

    /** The shell came to the front ([shownNow] true) or left it. */
    fun shellShown(context: Context, shownNow: Boolean) {
        shown = shownNow
        update(context.applicationContext)
    }

    /** A game started or ended: the music waits while one runs. */
    fun gameRunning(context: Context, running: Boolean) {
        gameRunning = running
        update(context.applicationContext)
    }

    /** The settings may have changed (the shell's Settings is inside the shell): read them again. */
    fun settingsChanged(context: Context) = update(context.applicationContext)

    private fun update(app: Context) {
        if (!shown || gameRunning) {
            pause(app)
            return
        }
        dev.droidtop.runtime.util.AppScopes.main.launch {
            val (level, folder) = withContext(Dispatchers.IO) {
                GamingSettingsCatalog.backgroundMusicVolume(app) to GamingSettingsCatalog.backgroundMusicFolder(app)
            }
            if (level <= 0 || folder == null) {
                stop(app)
                return@launch
            }
            volume = level / GamingSettingsCatalog.NAVIGATION_SOUNDS_MAX.toFloat() * MAX_SHARE
            if (folder != tracksFolder) {
                tracks = withContext(Dispatchers.IO) { listTracks(app, Uri.parse(folder)) }.shuffled()
                tracksFolder = folder
                next = 0
                player?.release()
                player = null
            }
            if (!shown || gameRunning || tracks.isEmpty()) return@launch
            if (!requestFocus(app)) return@launch
            val current = player
            if (current != null) {
                current.setVolume(volume, volume)
                if (!current.isPlaying) current.start()
            } else {
                playNext(app)
            }
        }
    }

    /** How loud full volume on the slider is, as a share of the stream: well under the navigation sounds. */
    private const val MAX_SHARE = 0.6f

    private fun playNext(app: Context) {
        if (tracks.isEmpty()) return
        val uri = tracks[next % tracks.size]
        next++
        player?.release()
        player = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            setOnPreparedListener { mp ->
                mp.setVolume(volume, volume)
                if (shown && !gameRunning && !lostFocus) mp.start()
            }
            setOnCompletionListener { playNext(app) }
            setOnErrorListener { _, what, extra ->
                Log.w(TAG, "Track $uri did not play ($what/$extra); the next one plays")
                playNext(app)
                true
            }
            runCatching {
                setDataSource(app, uri)
                prepareAsync()
            }.onFailure { Log.w(TAG, "Track $uri could not be opened", it) }
        }
    }

    private fun requestFocus(app: Context): Boolean {
        val audio = app.getSystemService(AudioManager::class.java) ?: return false
        val request = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        lostFocus = true
                        player?.takeIf { it.isPlaying }?.pause()
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> player?.setVolume(volume * DUCKED, volume * DUCKED)
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        lostFocus = false
                        player?.setVolume(volume, volume)
                        if (shown && !gameRunning) player?.start()
                    }
                }
            }
            .build()
            .also { focus = it }
        val granted = audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        lostFocus = !granted
        return granted
    }

    private fun pause(app: Context) {
        player?.takeIf { it.isPlaying }?.pause()
        focus?.let { app.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(it) }
    }

    private fun stop(app: Context) {
        pause(app)
        player?.release()
        player = null
    }

    /** The audio files directly in the picked folder, by their MIME type. Off the main thread. */
    private fun listTracks(app: Context, tree: Uri): List<Uri> = runCatching {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        app.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE),
            null, null, null,
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val mime = cursor.getString(1).orEmpty()
                    if (mime.startsWith("audio/")) add(DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)))
                }
            }
        }.orEmpty()
    }.getOrElse {
        Log.w(TAG, "The music folder could not be read", it)
        emptyList()
    }
}
