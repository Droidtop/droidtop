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

    private class Entry(val baseVolume: () -> Float)

    private val videos = LinkedHashMap<Player, Entry>()

    init {
        AudioHandOff.register(this)
    }

    override val name = "themed videos"

    /** A themed video registers its player with the volume it plays at (0 when the theme sets audio=false). */
    fun register(player: Player, baseVolume: () -> Float) {
        videos[player] = Entry(baseVolume)
    }

    fun unregister(player: Player) {
        videos.remove(player)
    }

    /** Players this hand-off already released, so the video element's own disposal does not release them twice. */
    private val released: MutableSet<Player> = Collections.newSetFromMap(WeakHashMap())

    /** The video element is going away: release its player unless a hand-off already did. */
    fun dispose(player: Player) {
        videos.remove(player)
        if (!released.remove(player)) player.release()
    }

    override suspend fun release(fade: Boolean): String? {
        if (videos.isEmpty()) return null
        val audible = videos.filter { (player, entry) -> player.isPlaying && entry.baseVolume() > 0f }
        if (fade && audible.isNotEmpty()) {
            for (step in 1..FADE_STEPS) {
                val left = 1f - step.toFloat() / FADE_STEPS
                audible.forEach { (player, entry) -> player.volume = entry.baseVolume() * left }
                delay(FADE_MS / FADE_STEPS)
            }
        }
        val players = videos.keys.toList()
        videos.clear()
        players.forEach {
            it.volume = 0f
            it.stop()
            it.release()
            released += it
        }
        return "${players.size} ExoPlayer(s) released, ${audible.size} were audible"
    }

    /** Nothing to do: the video elements create new players once [AudioHandOff.handedOff] is false again. */
    override suspend fun reopen() = Unit
}
