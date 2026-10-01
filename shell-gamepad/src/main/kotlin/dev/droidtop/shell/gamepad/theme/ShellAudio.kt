package dev.droidtop.shell.gamepad.theme

import androidx.media3.common.Player
import kotlinx.coroutines.delay

/**
 * The one place the shell's own audio is handed off when another app
 * takes over (docs/SPEC.md, launch audio hand-off; Droidtop/tracker#160).
 *
 * Everything droidtop itself can play is registered here: themed `video`
 * elements (ExoPlayer, audible by default per VideoComponent.cpp:254-255)
 * and the SoundPool navigation sounds ([EsDeNavigationSounds]). Cutting a
 * player or sample off mid-buffer is audible as a burst of static or a
 * pop, and it happens exactly while the launched app opens its own output,
 * so the hand-off is: ramp the volume to zero over [FADE_MS], then pause.
 * [quiesce] runs before the launch intent is dispatched. Every other way
 * another app can come in front (the Home button, a second-display
 * launch) is covered by the video's own ON_PAUSE observer, which mutes
 * then pauses, and by [EsDeNavigationSounds.fadeStop] from the host
 * activity's onPause.
 *
 * All calls are on the main thread, where ExoPlayer and Compose already
 * live; the registry needs no lock.
 */
object ShellAudio {
    const val FADE_MS = 120L
    private const val FADE_STEPS = 6

    private class Entry(val baseVolume: () -> Float)

    private val videos = LinkedHashMap<Player, Entry>()

    /** A themed video registers its player with the volume it plays at (0 when the theme sets audio=false). */
    fun register(player: Player, baseVolume: () -> Float) {
        videos[player] = Entry(baseVolume)
    }

    fun unregister(player: Player) {
        videos.remove(player)
    }

    /** Ramp every registered video to silence over [FADE_MS] and pause it; the video lifecycle observer resumes it when the shell is back in front. */
    suspend fun quiesce() {
        val audible = videos.filter { (player, entry) -> player.isPlaying && entry.baseVolume() > 0f }
        if (audible.isNotEmpty()) {
            for (step in 1..FADE_STEPS) {
                val left = 1f - step.toFloat() / FADE_STEPS
                audible.forEach { (player, entry) -> player.volume = entry.baseVolume() * left }
                delay(FADE_MS / FADE_STEPS)
            }
        }
        videos.keys.forEach { it.pause() }
    }
}
