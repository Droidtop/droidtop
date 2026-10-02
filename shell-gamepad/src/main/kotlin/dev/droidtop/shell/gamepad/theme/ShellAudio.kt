package dev.droidtop.shell.gamepad.theme

import androidx.media3.common.Player
import dev.droidtop.runtime.AudioHandOff
import java.util.Collections
import java.util.WeakHashMap
import kotlinx.coroutines.delay

/**
 * The themed `video` elements' part of the launch audio hand-off
 * ([AudioHandOff], docs/SPEC.md "Launch audio hand-off",
 * Droidtop/tracker#160). Their ExoPlayers play audible by default
 * (VideoComponent.cpp:254-255), and a paused ExoPlayer still holds its
 * AudioTrack, so on a hand-off each one is faded to silence over
 * [FADE_MS] and then released. While [AudioHandOff.handedOff] is true the
 * video element draws its static image instead of creating a player, and
 * a fresh player is created when the user is back.
 *
 * All calls are on the main thread, where ExoPlayer and Compose already
 * live; the registry needs no lock.
 */
object ShellAudio : AudioHandOff.Holder {
    const val FADE_MS = 120L
    private const val FADE_STEPS = 6

    private class Entry(val baseVolume: () -> Float, val listener: Player.Listener) {
        /** Whether the video was playing when it was silenced, so unsilencing resumes it. */
        var resumeOnUnquiet = false
    }

    private val videos = LinkedHashMap<Player, Entry>()

    /** True while the launch-static experiment has the preview silenced and paused ([setQuiet]). */
    private var quietNow = false

    init {
        AudioHandOff.register(this)
    }

    override val name = "themed videos"

    /** A themed video registers its player with the volume it plays at (0 when the theme sets audio=false). */
    fun register(player: Player, baseVolume: () -> Float) {
        // The timeline (tracker#160): when the preview's output starts and stops.
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                AudioHandOff.mark("preview video isPlaying=$isPlaying volume=${player.volume}")
            }
        }
        player.addListener(listener)
        val entry = Entry(baseVolume, listener)
        videos[player] = entry
        AudioHandOff.mark("preview video registered (base volume ${baseVolume()}, ${videos.size} open)")
        if (quietNow) {
            entry.resumeOnUnquiet = player.playWhenReady
            player.volume = 0f
            player.pause()
        }
    }

    fun unregister(player: Player) {
        videos.remove(player)?.let { player.removeListener(it.listener) }
    }

    /**
     * The experiment's silence: every preview video is muted and paused
     * (its player and AudioTrack stay), and resumed at its own volume when
     * unsilenced. Not the hand-off: nothing is released here.
     */
    override fun setQuiet(quiet: Boolean) {
        if (quiet == quietNow) return
        quietNow = quiet
        for ((player, entry) in videos) {
            if (quiet) {
                entry.resumeOnUnquiet = player.playWhenReady
                player.volume = 0f
                player.pause()
            } else {
                player.volume = entry.baseVolume()
                if (entry.resumeOnUnquiet) player.play()
            }
        }
        AudioHandOff.mark("preview video ${if (quiet) "paused and muted" else "resumed"} (${videos.size} open)")
    }

    /** Players this hand-off already released, so the video element's own disposal does not release them twice. */
    private val released: MutableSet<Player> = Collections.newSetFromMap(WeakHashMap())

    /** The video element is going away: release its player unless a hand-off already did. */
    fun dispose(player: Player) {
        videos.remove(player)
        if (!released.remove(player)) {
            AudioHandOff.mark("preview video released (element left the screen)")
            player.release()
        }
    }

    override fun status(): String =
        if (videos.isEmpty()) {
            "no preview player"
        } else {
            "${videos.size} preview player(s), ${videos.keys.count { it.isPlaying }} playing"
        }

    override suspend fun release(fade: Boolean): String? {
        if (videos.isEmpty()) return null
        val audible = videos.filter { (player, entry) -> player.isPlaying && entry.baseVolume() > 0f }
        if (fade && audible.isNotEmpty()) {
            AudioHandOff.mark("preview video fade begins (${audible.size} audible, $FADE_MS ms)")
            for (step in 1..FADE_STEPS) {
                val left = 1f - step.toFloat() / FADE_STEPS
                audible.forEach { (player, entry) -> player.volume = entry.baseVolume() * left }
                delay(FADE_MS / FADE_STEPS)
            }
        }
        val players = videos.keys.toList()
        videos.clear()
        AudioHandOff.mark("preview video stop and release begin (${players.size} player(s))")
        players.forEach {
            it.volume = 0f
            it.stop()
            it.release()
            released += it
        }
        AudioHandOff.mark("preview video released")
        return "${players.size} ExoPlayer(s) released, ${audible.size} were audible"
    }

    /** Nothing to do: the video elements create new players once [AudioHandOff.handedOff] is false again. */
    override suspend fun reopen() = Unit
}
