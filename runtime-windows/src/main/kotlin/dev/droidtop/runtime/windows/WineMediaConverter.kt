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
 */
internal object WineMediaConverter {
    private val BLANKS = listOf("blank.mkv", "blank.mka", "blank.ptna")

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
        )
    }

    /** [env] for [prefixRoot], after making its folder and unpacking the blank media if they are not there. Disk work. */
    fun prepare(context: Context, prefixRoot: File): Map<String, String> {
        val blankDir = File(context.filesDir, "mediaconv").apply { mkdirs() }
        for (name in BLANKS) {
            val out = File(blankDir, name)
            if (out.isFile && out.length() > 0) continue
            context.assets.open("mediaconv/$name").use { input -> out.outputStream().use { input.copyTo(it) } }
        }
        File(prefixRoot, "mediaconv").mkdirs()
        return env(prefixRoot, blankDir)
    }
}
