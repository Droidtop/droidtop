package dev.droidtop.input.chinese

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.pocketworkstation.pckeyboard.InputEngine
import org.pocketworkstation.pckeyboard.InputEngines
import org.pocketworkstation.pckeyboard.KeyboardThreads

/**
 * librime in this process (Droidtop/tracker#903 H8b): its data, its library and the one lock around its calls. librime
 * is a process singleton (one service, one deployer), so this is one too, which every [RimeEngine] shares.
 *
 * [start] runs once Chinese is first used (the engine's factory, or a field starting), never at app start. On the
 * keyboard's worker thread: the data is put in place if it is missing ([RimeData]: downloaded from the pinned release,
 * checked, unpacked, every file checked, moved in), then the libraries are loaded, librime is set up and initialized
 * over noBackupFilesDir/engine-rime/shared and /user, its maintenance runs and is waited for, and each schema the
 * keyboard uses must select. Then the state is Ready. Any failure is Unavailable with a plain reason, and the next field
 * tries again (no sooner than a minute later), as MozcRuntime does.
 *
 * The download uses HttpURLConnection, as MozcRuntime's does: the keyboard and its engines depend on nothing of
 * droidtop's, so they do not reach droidtop's download engine.
 */
internal object RimeRuntime : RimeEngine.Runtime {
    private const val TAG = "droidtop.Rime"
    private const val RETRY_MS = 60_000L

    /** How many candidates a context reads: the bar's pages, without asking librime for its whole list. */
    private const val CANDIDATES = 100

    private val worker by lazy { KeyboardThreads.single("keyboard-rime") }
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    /** The pin the build was made with: the release, and the data's checks. */
    val pin: RimePin by lazy { RimePin.parse(BuildConfig.RIME_PIN) }

    /** The list of the data's files, checked against the pin when first read. */
    private val manifest: RimeManifest by lazy { RimeManifest.parse(BuildConfig.RIME_DATA_FILES, pin) }

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

    /** Whether librime is initialized and its maintenance done; false again while forgetting restarts it. */
    @Volatile
    var running = false
        private set

    /** Whether the libraries are loaded; they then stay until the process ends. */
    @Volatile
    private var loaded = false

    // Main thread only.
    private var watcher: Runnable? = null
    private var loading = false
    private var lastFailure = 0L

    /** The context and the engine's profile directory (noBackupFilesDir/engine-rime), from the first factory call. */
    fun attach(context: Context, profile: InputEngines.Profile) {
        if (app == null) app = context.applicationContext ?: context
        if (this.profile == null) this.profile = profile
    }

    /** The profile for a settings screen that opens before any keyboard has set Chinese up. */
    fun attachIfNeeded(context: Context) {
        attach(context, InputEngines.Profile(context.applicationContext ?: context, RimeChinese.ID))
    }

    override fun watch(onChange: Runnable?) {
        // One engine per schema shares this runtime; only the live session's engine watches (the keyboard closes one
        // session before it makes the next), as with Mozc's one.
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
                Log.w(TAG, "RIME did not start", e)
                InputEngine.State.Unavailable(context.getString(R.string.rime_unavailable_library))
            }
            main.post {
                loading = false
                if (result is InputEngine.State.Unavailable) lastFailure = SystemClock.elapsedRealtime()
                publish(result, -1)
            }
        }
    }

    /** Worker thread: the data, then the libraries, then librime itself. */
    private fun load(context: Context, dir: File): InputEngine.State {
        val failure = RimeData.ensure(dir, pin, manifest, ::open) { percent -> main.post { if (loading) publish(InputEngine.State.Loading, percent) } }
        if (failure != null) {
            Log.w(TAG, "RIME's data is not in place: $failure")
            return InputEngine.State.Unavailable(reason(context, failure))
        }
        if (!loaded) {
            try {
                System.loadLibrary("rime")
                System.loadLibrary("droidtop_rime")
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "librime.so or libdroidtop_rime.so did not load", e)
                return InputEngine.State.Unavailable(context.getString(R.string.rime_unavailable_library))
            }
            loaded = true
        }
        synchronized(lock) {
            if (!running && !begin(context, dir)) return InputEngine.State.Unavailable(context.getString(R.string.rime_unavailable_data))
        }
        return InputEngine.State.Ready
    }

    /**
     * Under the lock, on the worker: librime initialized over the data and its maintenance waited for, and every schema
     * the keyboard uses selected once in a session of its own, so a key never finds one missing.
     */
    private fun begin(context: Context, dir: File): Boolean {
        val user = RimeData.user(dir).apply { mkdirs() }
        // glog writes files as well as logcat; they are kept to the last start.
        val logs = File(context.cacheDir, "rime-log").apply {
            deleteRecursively()
            mkdirs()
        }
        val level = if (BuildConfig.DEBUG) 0 else 1
        val deployed = RimeJni.start(RimeData.shared(dir).path, user.path, logs.path, level, pin.tag)
        if (!deployed) Log.w(TAG, "librime's maintenance reported a failure")
        val probe = RimeJni.createSession()
        val schemas = if (probe == 0L) false else RimeSchemas.SCHEMAS.all { RimeJni.selectSchema(probe, it) }
        if (probe != 0L) RimeJni.destroySession(probe)
        if (!schemas) {
            Log.w(TAG, "librime cannot select every schema the keyboard uses")
            RimeJni.stop()
            return false
        }
        running = true
        Log.i(TAG, "RIME ready: librime ${RimeJni.version()}, data ${pin.tag}")
        return true
    }

    private fun publish(next: InputEngine.State, percent: Int) {
        if (next === state && percent == progress) return
        state = next
        progress = percent
        watcher?.run()
    }

    /** A session call under the lock; [otherwise] while librime is not running (forgetting restarts it). */
    private inline fun <T> call(otherwise: T, block: () -> T): T = synchronized(lock) { if (running) block() else otherwise }

    override fun createSession(): Long = call(0L) { RimeJni.createSession() }

    override fun hasSession(session: Long): Boolean = call(false) { RimeJni.findSession(session) }

    override fun selectSchema(session: Long, schema: String): Boolean = call(false) { RimeJni.selectSchema(session, schema) }

    override fun setOption(session: Long, option: String, value: Boolean) = call(Unit) { RimeJni.setOption(session, option, value) }

    override fun processKey(session: Long, keysym: Int, mask: Int): Boolean = call(false) { RimeJni.processKey(session, keysym, mask) }

    override fun takeCommit(session: Long): String? = call(null) { RimeJni.getCommit(session) }

    override fun context(session: Long): RimeContext = call(RimeContext.EMPTY) { RimeContext.fromJni(RimeJni.getContext(session, CANDIDATES)) }

    override fun selectCandidate(session: Long, index: Int): Boolean = call(false) { RimeJni.selectCandidate(session, index) }

    override fun clear(session: Long) = call(Unit) { RimeJni.clearComposition(session) }

    override fun forget() {
        forget(null)
    }

    /**
     * Forgets what librime learned. Running, librime is finalized, so no database is open, its user dictionaries go
     * ([RimeData.forgetFiles]), and it starts again; the engine says Loading meanwhile. Not running, only the files go.
     * [done] then runs on the main thread. Called on the main thread.
     */
    fun forget(done: Runnable?) {
        val directory = profile
        val context = app
        val shown = state == InputEngine.State.Ready && !loading
        if (shown) {
            loading = true
            publish(InputEngine.State.Loading, -1)
        }
        worker.execute {
            var wasRunning = false
            var result: InputEngine.State = InputEngine.State.Ready
            try {
                if (directory != null) {
                    val dir = directory.directory()
                    synchronized(lock) {
                        wasRunning = running
                        if (wasRunning) {
                            running = false
                            RimeJni.stop()
                        }
                        RimeData.forgetFiles(RimeData.user(dir))
                        if (wasRunning && context != null && !begin(context, dir)) {
                            result = InputEngine.State.Unavailable(context.getString(R.string.rime_unavailable_data))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "RIME did not forget", e)
                if (context != null) result = InputEngine.State.Unavailable(context.getString(R.string.rime_unavailable_library))
            }
            main.post {
                if (shown) loading = false
                if (shown || wasRunning) {
                    if (result is InputEngine.State.Unavailable) lastFailure = SystemClock.elapsedRealtime()
                    publish(result, -1)
                }
                done?.run()
            }
        }
    }

    /** Whether the data is on the device. Off the main thread. */
    fun dataPresent(): Boolean {
        val directory = profile ?: return false
        return RimeData.present(directory.directory(), pin)
    }

    /**
     * Deletes the data ("Delete the dictionary"). librime reads its compiled tables mapped into memory and has its schemas
     * built in the user directory, so a running engine keeps typing until the keyboard's process ends; the next one
     * downloads the data again when Chinese is used. What it learned stays.
     */
    fun deleteData(done: Runnable?) {
        val directory = profile
        worker.execute {
            if (directory != null) RimeData.delete(directory.directory())
            if (done != null) main.post(done)
        }
    }

    @Throws(IOException::class)
    private fun open(url: String): RimeData.Response {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        val code = connection.responseCode
        if (code != HttpURLConnection.HTTP_OK) {
            connection.disconnect()
            return RimeData.Response(code, -1, null)
        }
        return RimeData.Response(code, connection.contentLengthLong, connection.inputStream)
    }

    private fun reason(context: Context, failure: RimeData.Failure): String {
        val mb = RimeChinese.DATA_MB
        return when (failure) {
            is RimeData.Failure.Network -> context.getString(R.string.rime_unavailable_network, mb)
            is RimeData.Failure.Server -> context.getString(R.string.rime_unavailable_server, failure.code)
            RimeData.Failure.Truncated -> context.getString(R.string.rime_unavailable_truncated)
            RimeData.Failure.Checksum, RimeData.Failure.Unpacked -> context.getString(R.string.rime_unavailable_checksum)
            RimeData.Failure.Storage -> context.getString(R.string.rime_unavailable_storage, mb)
        }
    }
}
