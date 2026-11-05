package dev.droidtop.runtime.windows

import android.content.Context
import java.io.File

/**
 * The environment Proton's media converter needs (Droidtop/tracker#897, #601).
 *
 * The Wine builds droidtop runs carry Proton's GStreamer media converter, which Media Foundation decodes through. It
 * opens the files these variables name (a dump of the media it met, Valve's transcoded media, and a blank video and
 * audio it plays in place of media it cannot decode). Proton's launcher sets them (its `proton` script, the
 * MEDIACONV_* block); droidtop's launch did not, so the converter's audio element failed to start ("dump_fozdb_open:
 * Env MEDIACONV_AUDIO_D...", "audio_conv_change_state: Failed") and a Unity game's intro video never played: Clover
 * Days stayed black on the console. GameNative sets them only behind a per-container switch that is off by default,
 * with paths under its own package. These follow Proton: the databases in the prefix's own folder, the blank media
 * Proton ships (its media/ folder) unpacked once from droidtop's assets.
 *
 * The console run of main 3057 got further and showed three more gaps:
 * - STEAM_COMPAT_TRANSCODED_MEDIA_PATH: where the converter leaves its "placeholder used" tag files, which Steam sets
 *   under Proton. Unset, every stream logs "Env STEAM_COMPAT_TRANSCODED_MEDIA_PATH not set". It gets the prefix's
 *   mediaconv/ folder.
 * - "Wrong check sum": a dump database left damaged, most likely by a Wine process killed mid-write. The dumps are only
 *   Valve's record of media to transcode, which droidtop never uploads, so both are started afresh at each launch.
 * - The game's intro is ASF (WMV/WMA). The converter claims ASF at rank marginal + 1, which wins under Proton because
 *   Proton's GStreamer has no asfdemux. The base system's does (gst-plugins-ugly) but has no WMV decoder, so asfdemux
 *   took the file, found no decoder ("not-negotiated") and the game waited for its video for good. [GST_FEATURE_RANK]
 *   takes asfdemux out of autoplugging, so the converter gets ASF as it does under Proton and plays the blank video.
 * - The blank video itself: Proton's blank.mkv is AV1, which this Wine's winegstreamer has no mapping for
 *   ("wg_format_from_caps: Unhandled caps video/x-av1") and the image no decoder, so Media Foundation's source reader
 *   never produced a sample and Unity asked for one forever (main 3061). droidtop's blank.mkv is the same 4 s with
 *   Proton's Opus audio, but its video is uncompressed I420 (64x48, 5 fps, V_UNCOMPRESSED), which matroskademux
 *   hands over as video/x-raw and winegstreamer maps today.
 */
internal object WineMediaConverter {
    private val BLANKS = listOf("blank.mkv", "blank.mka", "blank.ptna")
    private val DUMPS = listOf("audiov2.foz", "video.foz")

    /**
     * GStreamer's feature ranks for Wine (GST_PLUGIN_FEATURE_RANK): GameNative's ximagesink first, and asfdemux out of
     * autoplugging (rank 0), so Proton's converter takes ASF media.
     */
    const val GST_FEATURE_RANK = "ximagesink:3000,asfdemux:0"

    /** The variables for a prefix whose files are under [prefixRoot], with the blank media in [blankDir]. Pure. */
    fun env(prefixRoot: File, blankDir: File): Map<String, String> {
        val dir = File(prefixRoot, "mediaconv")
        return linkedMapOf(
            "MEDIACONV_AUDIO_DUMP_FILE" to File(dir, "audiov2.foz").path,
            "MEDIACONV_VIDEO_DUMP_FILE" to File(dir, "video.foz").path,
            "MEDIACONV_AUDIO_TRANSCODED_FILE" to File(dir, "transcoded_audio.foz").path,
            "MEDIACONV_VIDEO_TRANSCODED_FILE" to File(dir, "transcoded_video.foz").path,
            "MEDIACONV_BLANK_VIDEO_FILE" to File(blankDir, "blank.mkv").path,
            "MEDIACONV_BLANK_AUDIO_FILE" to File(blankDir, "blank.ptna").path,
            "STEAM_COMPAT_TRANSCODED_MEDIA_PATH" to dir.path,
        )
    }

    /**
     * [env] for [prefixRoot], after making its folder, starting its dump databases afresh and unpacking the blank media if
     * they are not there. Disk work.
     */
    fun prepare(context: Context, prefixRoot: File): Map<String, String> {
        val blankDir = File(context.filesDir, "mediaconv").apply { mkdirs() }
        for (name in BLANKS) {
            val out = File(blankDir, name)
            // Replaced when it differs from the asset in size: an install that unpacked Proton's AV1 blank.mkv gets
            // droidtop's uncompressed one (#897). The assets are small, so reading one to compare costs little.
            val bytes = context.assets.open("mediaconv/$name").use { it.readBytes() }
            if (out.isFile && out.length() == bytes.size.toLong()) continue
            out.writeBytes(bytes)
        }
        val dir = File(prefixRoot, "mediaconv").apply { mkdirs() }
        resetDumps(dir)
        return env(prefixRoot, blankDir)
    }

    /** Deletes the dump databases in [dir], so a damaged one from an earlier run is never read. Returns how many went. */
    fun resetDumps(dir: File): Int = DUMPS.count { File(dir, it).let { f -> f.isFile && f.delete() } }
}
