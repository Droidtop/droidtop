package dev.droidtop.app.settings

import android.content.Context
import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.os.Build
import android.util.Log
import dev.droidtop.runtime.tasks.TaskManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Per-app volume and EQ, slice 0: the probe (Droidtop/tracker#836). Developer menu only, run on the rig, nothing kept
 * running. It answers the design's open questions on a real device before anything is built on them:
 * - whether this build has DynamicsProcessing ([AudioEffect.queryEffects], no grant);
 * - what `dumpsys audio` and AudioFlinger's dump say on this Android version, saved as fixtures for the parser;
 * - whether droidtop may attach DynamicsProcessing to another app's session, and whether a fast-path stream takes it:
 *   each started session is attached, turned 12 dB down for four seconds (listen for the dip) and released, and
 *   AudioFlinger's dump is saved again while it is attached.
 * The dumps go through the elevated shell (Shizuku or wireless debugging), which holds DUMP, into droidtop's own
 * files (Android/data/<package>/files/audio-probe), where `adb pull` reaches them. The summary goes to the log under
 * `droidtop.AudioProbe` and is the row's answer.
 */
internal object AudioProbe {
    private const val TAG = "droidtop.AudioProbe"
    private const val DIP_DB = -12f
    private const val DIP_MS = 4_000L

    private val PLAYER = Regex("""u/pid:(\d+)/(\d+).*?state:(\w+).*?sessionId:(\d+)""")

    /** Runs the probe; returns the summary line. Takes a few seconds per playing session. */
    suspend fun run(context: Context): String = withContext(Dispatchers.IO) {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "audio-probe").apply { mkdirs() }
        val stamp = System.currentTimeMillis()
        val lines = ArrayList<String>()
        fun note(line: String) {
            lines += line
            Log.i(TAG, line)
        }
        note("Android ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")

        val effects = runCatching { AudioEffect.queryEffects().toList() }.getOrDefault(emptyList())
        // DynamicsProcessing is Android 9's; before it there is nothing to attach.
        val dynamics = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) emptyList()
        else effects.filter { it.type == AudioEffect.EFFECT_TYPE_DYNAMICS_PROCESSING }
        note("effects ${effects.size}; DynamicsProcessing ${if (dynamics.isEmpty()) "absent" else dynamics.joinToString { "${it.name} (${it.implementor})" }}")
        note("DUMP held by droidtop itself: ${context.checkSelfPermission("android.permission.DUMP") == android.content.pm.PackageManager.PERMISSION_GRANTED}")

        val shell = TaskManager.shell
        fun dump(service: String, name: String): String? {
            val out = runCatching { shell.exec(listOf("dumpsys", service)) }.getOrNull()?.takeIf { it.exit == 0 }?.stdout
            if (out == null) {
                note("dumpsys $service: no answer (Shizuku or wireless debugging needed)")
                return null
            }
            File(dir, "$stamp-$name.txt").writeText(out)
            note("dumpsys $service: ${out.length} characters saved as $name")
            return out
        }
        val audio = dump("audio", "audio") ?: return@withContext lines.joinToString("\n")
        dump("media.audio_flinger", "flinger-before")

        val sessions = PLAYER.findAll(audio)
            .map { Triple(it.groupValues[1].toInt(), it.groupValues[3], it.groupValues[4].toInt()) }
            .filter { it.third > 0 }
            .distinctBy { it.third }
            .toList()
        note("players with a session: ${sessions.size} (${sessions.count { it.second == "started" }} started)")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && dynamics.isNotEmpty()) {
            attachEach(context, sessions.filter { it.second == "started" }, ::note) { session ->
                dump("media.audio_flinger", "flinger-attached-$session")
            }
        }
        note("files in ${dir.absolutePath}")
        lines.joinToString("\n")
    }

    /** Attaches DynamicsProcessing to each session, dips it, and releases it, saying what Android allowed. */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
    private suspend fun attachEach(
        context: Context,
        sessions: List<Triple<Int, String, Int>>,
        note: (String) -> Unit,
        dumpAttached: (Int) -> Unit,
    ) {
        for ((uid, state, session) in sessions) {
            val app = context.packageManager.getNameForUid(uid) ?: "uid $uid"
            val effect = runCatching { DynamicsProcessing(0, session, null) }
                .onFailure { note("$app session $session ($state): attach refused: ${it.message}") }
                .getOrNull() ?: continue
            try {
                effect.setInputGainAllChannelsTo(DIP_DB)
                val enabled = effect.setEnabled(true) == AudioEffect.SUCCESS
                note("$app session $session: attached, control ${effect.hasControl()}, enabled $enabled, " +
                    "channels ${effect.channelCount}; ${DIP_DB.toInt()} dB for ${DIP_MS / 1000} s")
                if (enabled) dumpAttached(session)
                delay(DIP_MS)
            } catch (e: Exception) {
                note("$app session $session: attached, but setting it failed: ${e.message}")
            } finally {
                runCatching { effect.release() }
            }
        }
    }
}
