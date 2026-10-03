package dev.droidtop.shell.gamepad.theme

import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import dev.droidtop.library.theme.EsDeTheme
import dev.droidtop.library.theme.EsDeThemeValue
import dev.droidtop.runtime.AudioHandOff
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    /** Quiet: the launch-static experiment mutes every navigation sound but the launch sample ([setQuiet]). */
    @Volatile private var quiet = false

    /** Paths whose file format [load] already logged (main thread only). */
    private val describedPaths = mutableSetOf<String>()

    private val ioScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    private val loadScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }

    /** Bumped by every [load] and hand-off release, so a slow earlier load never binds over a newer one. */
    private var loadGeneration = 0

    /** Where rewritten samples live: the app's cache folder, which Android names as java.io.tmpdir. */
    private fun normalizedDir() = File(System.getProperty("java.io.tmpdir") ?: ".", "nav-sounds")

    private fun obtainPool(): SoundPool = soundPool ?: SoundPool.Builder()
        .setMaxStreams(MAX_STREAMS)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
        .also {
            // A sample the pool cannot decode reports a non-zero status here, and then plays as nothing or noise.
            it.setOnLoadCompleteListener { _, sampleId, status ->
                AudioHandOff.mark("navigation sample $sampleId load complete, status $status (0 is ok)")
            }
            soundPool = it
            AudioHandOff.mark("SoundPool created (max $MAX_STREAMS streams, sonification usage)")
        }

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
        val generation = ++loadGeneration
        loadScope.launch {
            // SoundPool plays every sample as 16-bit PCM, so a theme's 24-bit, 32-bit or float wav is first
            // rewritten as 16-bit, off the main thread (docs/SPEC.md "Launch audio hand-off", tracker#160).
            val playable = withContext(Dispatchers.IO) {
                declared.filterValues { File(it).exists() }
                    .mapValues { (name, path) ->
                        val playable = playableWav(File(path), normalizedDir())
                        if (playable.path != path) AudioHandOff.mark("navigation sample $name is not 16-bit PCM, SoundPool loads a 16-bit copy")
                        playable.path
                    }
            }
            if (generation != loadGeneration || AudioHandOff.handedOff.value) return@launch
            val pool = obtainPool()
            soundIdByName = buildMap {
                for ((name, path) in playable) {
                    put(name, soundIdsByPath.getOrPut(path) { pool.load(path, 1).also { AudioHandOff.mark("navigation sample $it = $name, loading") } })
                }
            }
        }
        // What each file really is (format, rate, channels, bit depth, size), read off the main thread (tracker#160).
        for ((name, path) in declared) {
            if (!describedPaths.add(path)) continue
            ioScope.launch {
                val file = File(path)
                val info = wavInfo(file)
                AudioHandOff.mark(
                    "navigation sample file $name: " +
                        (info?.describe() ?: "not a plain RIFF wav, SoundPool decodes it itself") +
                        ", ${file.length()} bytes",
                )
            }
        }
    }

    /** Plays one of [ES_DE_NAVIGATION_SOUND_NAMES]; silent no-op when the active theme doesn't provide it (see this object's doc comment -- no bundled fallback sounds, deliberately). */
    fun play(name: String) {
        // A navigation sound only ever answers input to droidtop's own
        // shell, so the user is back even if no activity was paused (a
        // launch onto the other screen of a dual-screen device).
        if (AudioHandOff.handedOff.value) {
            AudioHandOff.mark("play $name: droidtop is handed off, reopening instead")
            AudioHandOff.reopen("navigation input")
            return
        }
        if (quiet && name != "launch") {
            AudioHandOff.mark("play $name: skipped, droidtop sounds are silenced")
            return
        }
        val id = soundIdByName[name]
        val stream = if (id != null) soundPool?.play(id, 1f, 1f, 1, 0, 1f) else null
        if (stream == null || stream == 0) {
            AudioHandOff.mark("play $name: nothing played (${if (id == null) "no sample bound" else "sample $id refused: not loaded yet, or no stream"})")
            return
        }
        val sounding = Sounding(stream, pathByName[name], SystemClock.elapsedRealtime())
        val live = synchronized(liveStreams) {
            liveStreams.addLast(sounding)
            while (liveStreams.size > MAX_STREAMS) liveStreams.removeFirst()
            liveStreams.size
        }
        AudioHandOff.mark("play $name: sample $id stream $stream started ($live live)")
    }

    override fun status(): String = if (soundPool == null) {
        "no SoundPool open"
    } else {
        "SoundPool open, ${synchronized(liveStreams) { liveStreams.size }} recent stream(s)" + if (quiet) ", muted" else ""
    }

    /** See [quiet]; the launch sample itself is the one thing it never mutes. */
    override fun setQuiet(quiet: Boolean) {
        this.quiet = quiet
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
        loadGeneration++
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
                AudioHandOff.mark("navigation sounds: waiting $left ms for the sounding sample")
                delay(left)
            }
        }
        AudioHandOff.mark("navigation sounds: stopping ${streams.size} stream(s), releasing the SoundPool")
        streams.forEach { pool.stop(it.streamId) }
        val samples = soundIdsByPath.size
        pool.release()
        soundPool = null
        describedPaths.clear()
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

internal const val WAVE_FORMAT_EXTENSIBLE = 0xFFFE

/** What a .wav file's RIFF header says about it: the format a SoundPool has to decode. */
internal class WavInfo(
    val formatTag: Int,
    val channels: Int,
    val sampleRate: Long,
    val byteRate: Long,
    val bitsPerSample: Int,
    val dataBytes: Long,
    /** Where the `data` chunk's samples start in the file. */
    val dataOffset: Long = 0L,
    /** The sample encoding: [formatTag], or the sub-format a WAVE_FORMAT_EXTENSIBLE (0xFFFE) header names. */
    val encoding: Int = formatTag,
) {
    val durationMs: Long? get() = if (byteRate > 0) dataBytes * 1000 / byteRate else null

    /** One line for the log; flags what SoundPool is known to handle badly (anything but 16-bit integer PCM, a big sample). */
    fun describe(): String {
        val flags = buildList {
            if (formatTag != 1) add("format tag $formatTag is not plain integer PCM")
            if (bitsPerSample != 16) add("$bitsPerSample bit is not 16 bit")
            if (dataBytes > ONE_MEBIBYTE) add("over SoundPool's 1 MB sample size")
        }
        return "format $formatTag, $channels ch, $sampleRate Hz, $bitsPerSample bit, $dataBytes data bytes, " +
            "${durationMs ?: "unknown"} ms" + if (flags.isEmpty()) "" else " (${flags.joinToString("; ")})"
    }

    private companion object {
        const val ONE_MEBIBYTE = 1024L * 1024L
    }
}

/**
 * A PCM .wav file's RIFF header (the `fmt ` chunk's fields and the `data`
 * chunk's size), or null when it is not one. Reads only the chunk headers.
 */
internal fun wavInfo(file: File): WavInfo? = runCatching {
    RandomAccessFile(file, "r").use { raf ->
        fun u16(): Int {
            val b = ByteArray(2)
            raf.readFully(b)
            return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
        }
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
        var formatTag = 0
        var channels = 0
        var sampleRate = 0L
        var byteRate = 0L
        var bits = 0
        var encoding = 0
        while (raf.filePointer + 8 <= raf.length()) {
            val id = tag()
            val size = u32()
            val body = raf.filePointer
            when (id) {
                "fmt " -> {
                    formatTag = u16()
                    channels = u16()
                    sampleRate = u32()
                    byteRate = u32()
                    u16()
                    bits = u16()
                    encoding = formatTag
                    if (formatTag == WAVE_FORMAT_EXTENSIBLE && size >= 40) {
                        u16()
                        u16()
                        u32()
                        encoding = u16()
                    }
                }
                "data" -> return@use if (byteRate > 0) WavInfo(formatTag, channels, sampleRate, byteRate, bits, size, body, encoding) else null
            }
            raf.seek(body + size + (size and 1))
        }
        null
    }
}.getOrNull()

/** A PCM .wav file's playing time, or null when it is not one (see [wavInfo]). */
internal fun wavDurationMs(file: File): Long? = wavInfo(file)?.durationMs
