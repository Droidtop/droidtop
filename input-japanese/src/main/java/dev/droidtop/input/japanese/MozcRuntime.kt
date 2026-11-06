package dev.droidtop.input.japanese

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.android.apps.inputmethod.libs.mozc.session.MozcJni
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Command
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Input
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Output
import org.pocketworkstation.pckeyboard.InputEngine
import org.pocketworkstation.pckeyboard.InputEngines
import org.pocketworkstation.pckeyboard.KeyboardThreads

/**
 * Mozc in this process (Droidtop/tracker#903 H7c): its dictionary, its library and the one lock around its calls.
 * Mozc keeps one session handler per process (mozcjni.cc's g_session_handler, made once by onPostLoad), so this is a
 * process singleton that every [MozcEngine] shares.
 *
 * [start] runs once Japanese is first used (the engine's factory, or a field starting), never at app start. On the
 * keyboard's worker thread: the dictionary is put in place if it is missing ([MozcData]: downloaded from the pinned
 * release, checked, unpacked, checked, moved in), then the library is loaded, MozcJni.initialize binds its natives,
 * onPostLoad gets the profile directory and the dictionary, and the dictionary's version must not be empty. Then the
 * state is Ready. Any failure is Unavailable with a plain reason, and the next field tries again (no sooner than a
 * minute later).
 *
 * The download uses HttpURLConnection, as the keyboard's offline voice download does (OfflineVoice): the keyboard
 * module and its engines depend on nothing of droidtop's, so they do not reach droidtop's download engine.
 */
internal object MozcRuntime : MozcEngine.Runtime {
    private const val TAG = "droidtop.Mozc"
    private const val RETRY_MS = 60_000L

    private val worker by lazy { KeyboardThreads.single("keyboard-mozc") }
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    /** The pin the build was made with: the release, and the dictionary's checks. */
    val pin: MozcPin by lazy { MozcPin.parse(BuildConfig.MOZC_PIN) }

    @Volatile
    private var app: Context? = null

    @Volatile
    private var profile: InputEngines.Profile? = null

    @Volatile
    override var state: InputEngine.State = InputEngine.State.Loading
        private set

    @Volatile
    override var progress: Int = -1
        private set

    /** Whether the library is loaded and onPostLoad made its session handler; it then stays until the process ends. */
    @Volatile
    var loaded = false
        private set

    // Main thread only.
    private var watcher: Runnable? = null
    private var loading = false
    private var lastFailure = 0L

    /** The context and the engine's profile directory (noBackupFilesDir/engine-mozc), from the first factory call. */
    fun attach(context: Context, profile: InputEngines.Profile) {
        if (app == null) app = context.applicationContext ?: context
        if (this.profile == null) this.profile = profile
    }

    override fun watch(onChange: Runnable?) {
        watcher = onChange
    }

    override fun start() {
        if (state == InputEngine.State.Ready || loading) return
        if (state is InputEngine.State.Unavailable && SystemClock.elapsedRealtime() - lastFailure < RETRY_MS) return
        val context = app ?: return
        val directory = profile ?: return
        loading = true
        publish(InputEngine.State.Loading, -1)
        worker.execute {
            val result = try {
                load(context, directory.directory())
            } catch (e: Exception) {
                Log.w(TAG, "Mozc did not start", e)
                InputEngine.State.Unavailable(context.getString(R.string.mozc_unavailable_library))
            }
            main.post {
                loading = false
                if (result is InputEngine.State.Unavailable) lastFailure = SystemClock.elapsedRealtime()
                publish(result, -1)
            }
        }
    }

    /** Worker thread: the dictionary, then the library. */
    private fun load(context: Context, dir: File): InputEngine.State {
        val failure = MozcData.ensure(dir, pin, ::open) { percent -> main.post { if (loading) publish(InputEngine.State.Loading, percent) } }
        if (failure != null) {
            Log.w(TAG, "Mozc's dictionary is not in place: $failure")
            return InputEngine.State.Unavailable(reason(context, failure))
        }
        if (!loaded) {
            try {
                System.loadLibrary("mozc")
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "libmozc.so did not load", e)
                return InputEngine.State.Unavailable(context.getString(R.string.mozc_unavailable_library))
            }
            synchronized(lock) {
                if (!MozcJni.initialize() || !MozcJni.onPostLoad(dir.path, MozcData.file(dir).path)) {
                    return InputEngine.State.Unavailable(context.getString(R.string.mozc_unavailable_library))
                }
                loaded = true
            }
        }
        val version = synchronized(lock) { MozcJni.getDataVersion() }
        if (version.isNullOrEmpty()) return InputEngine.State.Unavailable(context.getString(R.string.mozc_unavailable_data))
        Log.i(TAG, "Mozc ready, dictionary $version")
        return InputEngine.State.Ready
    }

    private fun publish(next: InputEngine.State, percent: Int) {
        if (next === state && percent == progress) return
        state = next
        progress = percent
        watcher?.run()
    }

    override fun eval(input: Input): Output {
        check(loaded) { "Mozc is not loaded" }
        val bytes = Command.newBuilder().setInput(input).build().toByteArray()
        val answer = synchronized(lock) { MozcJni.evalCommand(bytes) }
        return Command.parseFrom(answer).output
    }

    override fun sync() {
        if (!loaded) return
        worker.execute { runCatching { eval(MozcCommands.syncData()) }.onFailure { Log.w(TAG, "Mozc did not save", it) } }
    }

    override fun forget() {
        forget(null)
    }

    /** Forgets what Mozc learned: told, when loaded; else its files go. [done] then runs on the main thread. */
    fun forget(done: Runnable?) {
        val directory = profile
        worker.execute {
            runCatching {
                if (loaded) MozcCommands.forget().forEach { eval(it) }
                else if (directory != null) MozcData.forgetFiles(directory.directory())
            }.onFailure { Log.w(TAG, "Mozc did not forget", it) }
            if (done != null) main.post(done)
        }
    }

    /** Whether the dictionary is on the device. Off the main thread. */
    fun dictionaryPresent(): Boolean {
        val directory = profile ?: return false
        return MozcData.present(directory.directory(), pin)
    }

    /**
     * Deletes the dictionary ("Delete the dictionary"). Mozc reads it mapped into memory, so a loaded engine keeps
     * typing from it until the keyboard's process ends; the next one downloads it again when Japanese is used.
     */
    fun deleteDictionary(done: Runnable?) {
        val directory = profile
        worker.execute {
            if (directory != null) MozcData.delete(directory.directory())
            if (done != null) main.post(done)
        }
    }

    /** The profile for a settings screen that opens before any keyboard has set Japanese up. */
    fun attachIfNeeded(context: Context) {
        attach(context, InputEngines.Profile(context.applicationContext ?: context, MozcJapanese.ID))
    }

    @Throws(IOException::class)
    private fun open(url: String): MozcData.Response {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        val code = connection.responseCode
        if (code != HttpURLConnection.HTTP_OK) {
            connection.disconnect()
            return MozcData.Response(code, -1, null)
        }
        return MozcData.Response(code, connection.contentLengthLong, connection.inputStream)
    }

    private fun reason(context: Context, failure: MozcData.Failure): String {
        val mb = MozcJapanese.DICTIONARY_MB
        return when (failure) {
            is MozcData.Failure.Network -> context.getString(R.string.mozc_unavailable_network, mb)
            is MozcData.Failure.Server -> context.getString(R.string.mozc_unavailable_server, failure.code)
            MozcData.Failure.Truncated -> context.getString(R.string.mozc_unavailable_truncated)
            MozcData.Failure.Checksum, MozcData.Failure.Unpacked -> context.getString(R.string.mozc_unavailable_checksum)
            MozcData.Failure.Storage -> context.getString(R.string.mozc_unavailable_storage, mb)
        }
    }
}
