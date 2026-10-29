package org.pocketworkstation.pckeyboard

import android.Manifest
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/**
 * Offline voice typing (Droidtop/tracker#343): the voice key's first provider once [OfflineVoice] has its engine and
 * model and the microphone is allowed. The voice key starts listening and the suggestion strip says so; the words
 * appear as composing text while they are spoken and are committed at each pause. The voice key again, a tap on the
 * strip, or leaving the field stops it. Nothing leaves the device.
 */
internal object OfflineVoiceTyping : VoiceProvider {
    private const val SAMPLE_RATE = 16_000
    private val main by lazy { Handler(Looper.getMainLooper()) }

    @Volatile
    private var session: Session? = null

    override fun available(service: InputMethodService): Boolean =
        OfflineVoice.ready &&
            service.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Starts listening, or stops when already listening (the voice key toggles). */
    override fun start(service: InputMethodService) {
        session?.let { it.stop(keep = true); return }
        session = Session(service).also { it.begin() }
    }

    /** The voice key, a tap on the strip: stop and keep what was heard. */
    @JvmStatic
    fun stop() {
        session?.stop(keep = true)
    }

    /** The field is gone: stop, and nothing more is written into whatever field comes next. */
    @JvmStatic
    fun finishInput() {
        session?.stop(keep = false)
    }

    /**
     * What the model heard, as typed text: the English model spells in capitals, so it is lower-cased, "i" on its
     * own is capitalised, and so is the first letter at the start of a sentence. Pure.
     */
    fun typed(heard: String, sentenceStart: Boolean): String {
        val words = heard.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
            .map { if (it == "i" || it.startsWith("i'")) "I" + it.substring(1) else it }
        val text = words.joinToString(" ")
        return if (sentenceStart) text.replaceFirstChar { it.uppercaseChar() } else text
    }

    /** Whether text ending in [before] (what precedes the cursor) is at the start of a sentence. Pure. */
    fun sentenceStart(before: CharSequence?): Boolean {
        val text = before?.toString()?.trimEnd(' ') ?: return true
        return text.isEmpty() || text.last() in ".!?\n"
    }

    /** The space to put before dictated words: none at the start of a field or after a space or a new line. Pure. */
    fun leadingSpace(before: CharSequence?): String {
        val last = before?.lastOrNull() ?: return ""
        return if (last == ' ' || last == '\n') "" else " "
    }

    private class Session(private val service: InputMethodService) {
        @Volatile private var running = true
        @Volatile private var keep = true
        private var composing = false

        fun begin() {
            service.currentInputConnection?.finishComposingText()
            hint(service.getString(R.string.offline_voice_listening))
            Thread({ run() }, "keyboard-dictation").start()
        }

        fun stop(keep: Boolean) {
            this.keep = keep
            running = false
        }

        private fun run() {
            val error = runCatching { listen() }.exceptionOrNull()
            main.post { finish(error) }
        }

        @android.annotation.SuppressLint("MissingPermission") // available() checked RECORD_AUDIO before this session began.
        private fun listen() {
            OfflineVoice.load(service)
            val dir = OfflineVoice.modelDir(service)
            fun path(name: String) = File(dir, name).absolutePath
            val recognizer = OnlineRecognizer(
                config = OnlineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = path(OfflineVoice.ENCODER),
                            decoder = path(OfflineVoice.DECODER),
                            joiner = path(OfflineVoice.JOINER),
                        ),
                        tokens = path(OfflineVoice.TOKENS),
                        numThreads = 2,
                        modelType = "zipformer",
                    ),
                    enableEndpoint = true,
                ),
            )
            val stream = recognizer.createStream()
            val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minimum, SAMPLE_RATE / 5 * 2),
            )
            try {
                check(record.state == AudioRecord.STATE_INITIALIZED) { "the microphone could not be opened" }
                record.startRecording()
                val chunk = ShortArray(SAMPLE_RATE / 10)
                var shown = ""
                while (running) {
                    val n = record.read(chunk, 0, chunk.size)
                    check(n >= 0) { "the microphone stopped ($n)" }
                    if (n == 0) continue
                    stream.acceptWaveform(FloatArray(n) { chunk[it] / 32768f }, SAMPLE_RATE)
                    while (recognizer.isReady(stream)) recognizer.decode(stream)
                    val heard = recognizer.getResult(stream).text
                    if (recognizer.isEndpoint(stream)) {
                        if (heard.isNotBlank()) main.post { commit(heard) }
                        recognizer.reset(stream)
                        shown = ""
                    } else if (heard != shown) {
                        shown = heard
                        main.post { compose(heard) }
                    }
                }
                if (keep) {
                    stream.inputFinished()
                    while (recognizer.isReady(stream)) recognizer.decode(stream)
                    val last = recognizer.getResult(stream).text
                    if (last.isNotBlank()) main.post { commit(last) }
                }
            } finally {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
                record.release()
                stream.release()
                recognizer.release()
            }
        }

        /** What preceded the cursor when the utterance being heard began; read once per utterance. */
        private var before: CharSequence? = null

        private fun utterance(ic: android.view.inputmethod.InputConnection): String {
            if (!composing) before = ic.getTextBeforeCursor(64, 0)
            return leadingSpace(before)
        }

        /** Main thread: what is heard so far, as composing text, with a space before it when it needs one. */
        private fun compose(heard: String) {
            if (!keep || session !== this) return
            val ic = service.currentInputConnection ?: return
            ic.setComposingText(utterance(ic) + typed(heard, sentenceStart(before)), 1)
            composing = true
        }

        /** Main thread: a finished utterance, committed. */
        private fun commit(heard: String) {
            if (!keep || session !== this) return
            val ic = service.currentInputConnection ?: return
            ic.commitText(utterance(ic) + typed(heard, sentenceStart(before)), 1)
            composing = false
        }

        private fun finish(error: Throwable?) {
            if (session === this) session = null
            if (composing) service.currentInputConnection?.finishComposingText()
            if (error != null && keep) {
                hint(service.getString(R.string.offline_voice_failed, error.message ?: error.toString()))
            } else {
                (service as? LatinIME)?.clearVoiceHint()
            }
        }

        private fun hint(text: String) {
            (service as? LatinIME)?.showVoiceHint(text)
        }
    }
}

/** Settings > Voice input > Offline voice typing: download, or remove, the engine and model (Droidtop/tracker#343). */
internal object OfflineVoiceRow {
    const val KEY = "pref_offline_voice"
    private const val MIC_REQUEST = 3430

    @JvmStatic
    fun summary(activity: android.app.Activity): String =
        if (OfflineVoice.ready) activity.getString(R.string.offline_voice_on, OfflineVoice.INSTALLED_MB)
        else activity.getString(R.string.summary_offline_voice)

    @JvmStatic
    fun open(activity: android.app.Activity, row: android.preference.Preference) {
        val builder = android.app.AlertDialog.Builder(activity).setTitle(R.string.title_offline_voice)
        if (OfflineVoice.ready) {
            builder.setMessage(activity.getString(R.string.offline_voice_remove_body, OfflineVoice.INSTALLED_MB))
                .setPositiveButton(R.string.offline_voice_remove) { _, _ ->
                    row.setSummary(R.string.offline_voice_removing)
                    OfflineVoice.remove(activity) { activity.runOnUiThread { row.summary = summary(activity) } }
                }
        } else {
            builder.setMessage(
                activity.getString(
                    R.string.offline_voice_download_body,
                    OfflineVoice.ENGINE_DOWNLOAD_MB + OfflineVoice.MODEL_DOWNLOAD_MB,
                    OfflineVoice.INSTALLED_MB,
                ),
            ).setPositiveButton(R.string.offline_voice_download) { _, _ -> download(activity, row) }
        }
        builder.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun download(activity: android.app.Activity, row: android.preference.Preference) {
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_REQUEST)
        }
        row.setSummary(R.string.offline_voice_starting)
        OfflineVoice.install(activity, object : OfflineVoice.Progress {
            override fun step(line: String) {
                activity.runOnUiThread { row.summary = line }
            }

            override fun done(error: String?) {
                activity.runOnUiThread {
                    row.summary = error ?: summary(activity)
                }
            }
        })
    }
}
