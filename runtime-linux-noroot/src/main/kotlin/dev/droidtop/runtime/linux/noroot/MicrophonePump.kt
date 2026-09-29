package dev.droidtop.runtime.linux.noroot

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File
import kotlin.concurrent.thread

/**
 * The device microphone as PulseAudio's `module-pipe-source` input
 * (docs/SPEC.md 3d Sockets row, Droidtop/tracker#80): [HostAudioServer]'s
 * PulseAudio reads raw PCM from the FIFO at [pipe], and this pumps
 * `AudioRecord` into it. Same server and socket as audio out, so a program
 * in a container sees an ordinary PulseAudio source (`droidtop_mic`, the
 * default source) with nothing extra to bind.
 *
 * Writes never block: PulseAudio stops reading its pipe source while no
 * program is recording, and a blocking write would leave a stale second of
 * audio queued in the FIFO for the next recording to hear first. A full
 * pipe therefore drops the chunk, and a missing reader (the server not yet
 * up, or restarted) is retried.
 */
internal class MicrophonePump(private val pipe: File) {
    @Volatile private var running = false
    private var worker: Thread? = null

    /** Makes the FIFO PulseAudio's `module-pipe-source` reads. */
    fun prepare() {
        pipe.delete()
        Os.mkfifo(pipe.absolutePath, 384) // 0600: only this app's processes
    }

    @SuppressLint("MissingPermission") // checked by the caller, HostAudioServer.microphoneProblem
    fun start() {
        stop()
        running = true
        worker = thread(name = "audio-bridge-mic", isDaemon = true) {
            val minBuffer = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val record = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuffer, CHUNK_BYTES * 4),
                )
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "could not create the recorder: ${e.message}")
                return@thread
            }
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "the recorder did not initialise")
                record.release()
                return@thread
            }
            try {
                record.startRecording()
                pumpLoop(record)
            } finally {
                runCatching { record.stop() }
                record.release()
            }
        }
    }

    private fun pumpLoop(record: AudioRecord) {
        val fd = openWriter() ?: return
        try {
            val buffer = ByteArray(CHUNK_BYTES)
            while (running) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) {
                    if (read < 0) return // the recorder failed (permission revoked, device lost)
                    continue
                }
                try {
                    Os.write(fd, buffer, 0, read)
                } catch (e: ErrnoException) {
                    when (e.errno) {
                        OsConstants.EAGAIN -> Unit // nobody is recording: drop it
                        else -> return // EPIPE: the server went away
                    }
                }
            }
        } finally {
            runCatching { Os.close(fd) }
        }
    }

    /** Opens the FIFO for writing once PulseAudio has it open for reading; null when stopped first. */
    private fun openWriter(): java.io.FileDescriptor? {
        while (running) {
            try {
                return Os.open(pipe.absolutePath, OsConstants.O_WRONLY or OsConstants.O_NONBLOCK, 0)
            } catch (e: ErrnoException) {
                if (e.errno != OsConstants.ENXIO) {
                    Log.w(TAG, "could not open the microphone pipe: ${e.message}")
                    return null
                }
                try {
                    Thread.sleep(RETRY_MS) // ENXIO: no reader yet
                } catch (_: InterruptedException) {
                    return null
                }
            }
        }
        return null
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
    }

    companion object {
        private const val TAG = "droidtop.audiobridge"
        const val RATE = 48_000
        private const val CHUNK_BYTES = 1920 // 20 ms of 48 kHz mono s16le
        private const val RETRY_MS = 250L
    }
}
