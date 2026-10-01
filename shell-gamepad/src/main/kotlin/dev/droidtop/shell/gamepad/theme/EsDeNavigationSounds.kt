package dev.droidtop.shell.gamepad.theme

import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import dev.droidtop.library.theme.EsDeTheme
import dev.droidtop.library.theme.EsDeThemeValue
import dev.droidtop.runtime.AudioHandOff
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Real ES-DE navigation-sound names, transcribed verbatim from
 * `NavigationSounds::loadThemeNavigationSounds` (Sound.cpp:213-219, the
 * real local clone at /root/es-de-reference): systembrowse /
 * quicksysselect / select / back / scroll / favorite / launch -- exactly
 * seven, in this order (the C++ enum NavigationSoundsID indexes into a
 * vector filled in this exact sequence). A theme declares them as
 * `<sound name="systembrowse"><path>...</path></sound>` under the special
 * `all` view (THEMES.md "Navigation sounds" section -- the `all` view
 * exists ONLY for sounds, everything else uses system/gamelist), which
 * droidtop's parser already expands into both real views, so the same
 * `sound_<name>` element lands in "system" and "gamelist" alike.
 */
val ES_DE_NAVIGATION_SOUND_NAMES = listOf(
    "systembrowse", "quicksysselect", "select", "back", "scroll", "favorite", "launch",
)

/**
 * Pure, JVM-testable extraction of a parsed theme's navigation-sound
 * declarations: sound name -> resolved .wav path. Scans every view (the
 * parser has already expanded the theme's own `all` view into
 * system+gamelist, so either carries the full set; scanning all views is
 * the order-independent way to not care which). Unknown `<sound
 * name="...">` names are ignored -- real ES-DE only ever looks up the
 * seven real names above (Sound.cpp:213-219) and so does droidtop.
 * Deliberately does NOT touch the filesystem -- existence checking
 * happens at load time in [EsDeNavigationSounds.load], keeping this
 * function pure for unit tests.
 */
fun navigationSoundPaths(theme: EsDeTheme?): Map<String, String> {
    if (theme == null) return emptyMap()
    val result = mutableMapOf<String, String>()
    for (view in theme.views.values) {
        for (element in view.elements.values) {
            if (element.type != "sound") continue
            val name = element.key.removePrefix("sound_")
            if (name !in ES_DE_NAVIGATION_SOUND_NAMES) continue
            val path = element.valueOrNull<EsDeThemeValue.Path>("path")?.resolved ?: continue
            result.putIfAbsent(name, path)
        }
    }
    return result
}

/**
 * Real themed navigation-sound playback -- droidtop's equivalent of real
 * ES-DE's `NavigationSounds` singleton (Sound.cpp/Sound.h). Real ES-DE
 * loads each of the seven sounds from the theme's own `<sound>` elements,
 * falling back PER FILE to its own bundled default .wav resources
 * (`:/sounds/<name>.wav`, Sound::getFromTheme) when a theme doesn't
 * declare one or the declared file doesn't exist. droidtop deliberately
 * has NO bundled fallback sounds -- ES-DE's own .wav resources aren't
 * ours to redistribute and inventing replacement audio would violate this
 * project's no-fabricated-assets rule -- so an undeclared/missing sound
 * simply plays nothing, same honest-gap convention the badge/systemstatus
 * renderers already use for ES-DE's bundled icon art.
 *
 * SoundPool, not MediaPlayer: these are sub-second UI feedback samples
 * fired on every keypress -- SoundPool pre-decodes to PCM in memory and
 * plays with near-zero latency, exactly the job it exists for.
 * [AudioAttributes.USAGE_ASSISTANCE_SONIFICATION] is Android's own
 * category for UI interaction feedback, which is precisely what real
 * ES-DE's navigation sounds are.
 *
 * Real, honest simplification: ES-DE gates fast-scroll retriggering on
 * `isPlayingThemeNavigationSound` (GamelistBase's hold-to-fast-scroll
 * plays scroll/systembrowse only once the previous sample finished --
 * THEMES.md documents this play-to-completion behavior). SoundPool has no
 * is-playing query, and droidtop has no hold-to-fast-scroll on the wired
 * screens yet, so rapid same-sound triggers simply overlap -- fine for
 * the short samples THEMES.md itself tells theme authors to use.
 *
 * The bundled decaffe theme is a real, known no-op case, on the theme's
 * own terms: its navigationsounds.xml exists (declaring the real seven
 * sounds) but is never `<include>`d from theme.xml, AND its declared
 * `.wav` paths under `./core/sounds/` don't match where its .wav files actually
 * live (`./assets/sounds/`) -- both verified directly in the vendored
 * copy. Real ES-DE would silently use its bundled fallbacks there, which
 * masks the theme bug; droidtop plays nothing. A downloaded theme that
 * wires its sounds correctly (the include + real paths) gets real
 * playback with no further work.
 */
object EsDeNavigationSounds : AudioHandOff.Holder {
    private var soundPool: SoundPool? = null

    /** The theme [load] last bound, so [reopen] can load it into a fresh pool after a hand-off. */
    private var boundTheme: EsDeTheme? = null

    /** Each bound sound's file, so a hand-off can tell how long a sounding sample still has to play. */
    @Volatile private var pathByName: Map<String, String> = emptyMap()

    init {
        AudioHandOff.register(this)
    }

    override val name = "navigation sounds"

    /** SoundPool sample id per already-loaded absolute file path -- loads are cached across theme reloads/per-system reparses (the same wav set recurs for every system's parse of one theme). */
    private val soundIdsByPath = mutableMapOf<String, Int>()

    @Volatile
    private var soundIdByName: Map<String, Int> = emptyMap()

    private fun obtainPool(): SoundPool = soundPool ?: SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
        .also { soundPool = it }

    /**
     * (Re)binds the seven navigation-sound names to whatever [theme]
     * declares. Cheap and idempotent for an unchanged theme (per-system
     * reparses of the same theme resolve the same wav paths, all cache
     * hits) -- called from the same LaunchedEffects that load themes, so
     * a live theme switch in Settings rebinds automatically. SoundPool
     * loading is async; a play() racing a first load is a silent no-op
     * (SoundPool's own documented behavior), which self-heals on the
     * next keypress.
     */
    fun load(theme: EsDeTheme?) {
        // null = "no theme loaded on this screen right now" (e.g. leaving
        // a gamelist back to the system list momentarily has no gamelist
        // theme), NOT "the active theme has no sounds" -- keep the current
        // bindings. A real theme that genuinely declares no sounds falls
        // through to the empty-declared clear below.
        if (theme == null) return
        boundTheme = theme
        val declared = navigationSoundPaths(theme)
        pathByName = declared
        if (declared.isEmpty()) {
            soundIdByName = emptyMap()
            return
        }
        // No pool while another app has the audio: [reopen] loads this theme.
        if (AudioHandOff.handedOff.value) return
        val pool = obtainPool()
        soundIdByName = buildMap {
            for ((name, path) in declared) {
                if (!File(path).exists()) continue
                put(name, soundIdsByPath.getOrPut(path) { pool.load(path, 1) })
            }
        }
    }

    /** Plays one of [ES_DE_NAVIGATION_SOUND_NAMES]; silent no-op when the active theme doesn't provide it (see this object's doc comment -- no bundled fallback sounds, deliberately). */
    fun play(name: String) {
        // A navigation sound only ever answers input to droidtop's own
        // shell, so the user is back even if no activity was paused (a
        // launch onto the other screen of a dual-screen device).
        if (AudioHandOff.handedOff.value) {
            AudioHandOff.reopen("navigation input")
            return
        }
        val id = soundIdByName[name] ?: return
        val stream = soundPool?.play(id, 1f, 1f, 1, 0, 1f) ?: return
        if (stream == 0) return
        val sounding = Sounding(stream, pathByName[name], SystemClock.elapsedRealtime())
        synchronized(liveStreams) {
            liveStreams.addLast(sounding)
            while (liveStreams.size > MAX_STREAMS) liveStreams.removeFirst()
        }
    }

    /**
     * Lets every sample still sounding play out, then releases the whole
     * SoundPool: a stopped stream keeps its AudioTrack open for reuse, so
     * stopping is not enough to leave the output to the launched app.
     *
     * Playing out rather than cutting is ES-DE's own launch order: it
     * plays the launch sound and holds the launch behind its launch screen
     * "for the navigation sound playing to be able to complete"
     * (ViewController.cpp:1069-1071), 3 s at its normal setting
     * (:1054-1056), which is also the cap here. Without [fade] (the app is
     * already coming in) the samples are stopped at once.
     */
    override suspend fun release(fade: Boolean): String? {
        val pool = soundPool ?: return null
        val streams = synchronized(liveStreams) { liveStreams.toList().also { liveStreams.clear() } }
        var waited = 0L
        if (fade && streams.isNotEmpty()) {
            val now = SystemClock.elapsedRealtime()
            val left = withContext(Dispatchers.IO) {
                streams.maxOf { s ->
                    val length = s.path?.let { wavDurationMs(File(it)) } ?: 0L
                    minOf(length, PLAY_OUT_CAP_MS) - (now - s.startedAt)
                }
            }
            if (left > 0) {
                waited = left
                delay(left)
            }
        }
        streams.forEach { pool.stop(it.streamId) }
        val samples = soundIdsByPath.size
        pool.release()
        soundPool = null
        soundIdsByPath.clear()
        soundIdByName = emptyMap()
        return "SoundPool released ($samples samples; waited ${waited} ms for a sounding sample to finish)"
    }

    override suspend fun reopen() {
        load(boundTheme)
    }

    private const val MAX_STREAMS = 4
    private const val PLAY_OUT_CAP_MS = 3000L

    private class Sounding(val streamId: Int, val path: String?, val startedAt: Long)
    private val liveStreams = ArrayDeque<Sounding>()
}

/**
 * A PCM .wav file's playing time from its RIFF header (the `fmt ` chunk's
 * byte rate and the `data` chunk's size), or null when it is not one.
 * Reads only the chunk headers.
 */
internal fun wavDurationMs(file: File): Long? = runCatching {
    RandomAccessFile(file, "r").use { raf ->
        fun u32(): Long {
            val b = ByteArray(4)
            raf.readFully(b)
            return (b[0].toLong() and 0xFF) or ((b[1].toLong() and 0xFF) shl 8) or
                ((b[2].toLong() and 0xFF) shl 16) or ((b[3].toLong() and 0xFF) shl 24)
        }
        fun tag(): String = ByteArray(4).also { raf.readFully(it) }.toString(Charsets.US_ASCII)
        if (tag() != "RIFF") return@use null
        u32()
        if (tag() != "WAVE") return@use null
        var byteRate = 0L
        while (raf.filePointer + 8 <= raf.length()) {
            val id = tag()
            val size = u32()
            val body = raf.filePointer
            when (id) {
                "fmt " -> {
                    raf.seek(body + 8)
                    byteRate = u32()
                }
                "data" -> return@use if (byteRate > 0) size * 1000 / byteRate else null
            }
            raf.seek(body + size + (size and 1))
        }
        null
    }
}.getOrNull()
