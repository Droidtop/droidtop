package dev.droidtop.runtime.windows

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.future.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import timber.log.Timber

private fun corruptionReset() = ReplaceFileCorruptionHandler {
    Timber.e("Preferences (somehow got) corrupted, resetting.")
    emptyPreferences()
}

private val Context.gameNativeStore by preferencesDataStore(name = "PluviaPreferences", corruptionHandler = corruptionReset())
private val Context.winlatorStore by preferencesDataStore(name = "WinlatorPreferences", corruptionHandler = corruptionReset())

/**
 * One DataStore preferences file, read from memory (Droidtop/tracker#765). Once [open], a collector keeps the file's
 * latest values in [snapshot], so a read is a map lookup, never a blocking read of the file; only a read that comes
 * before the first load waits for it, once. [WindowsBackbone] opens and loads both files off the main thread when the
 * runtime starts, so the reads from the Winlator view and launch code find them in memory.
 */
internal class StoredPrefs(private val storeOf: (Context) -> DataStore<Preferences>) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var store: DataStore<Preferences>? = null

    @Volatile
    private var snapshot: Preferences? = null

    /** Idempotent, and does no disk work itself. */
    @Synchronized
    fun open(context: Context) {
        if (store != null) return
        val opened = storeOf(context.applicationContext)
        store = opened
        scope.launch { opened.data.collect { snapshot = it } }
    }

    /** Waits, suspending, until the file has been read once; for a caller that can load it ahead of the reads. */
    suspend fun load() {
        if (snapshot == null) snapshot = checkNotNull(store) { "preferences not open" }.data.first()
    }

    val isOpen: Boolean get() = store != null

    private fun current(): Preferences =
        snapshot ?: runBlocking { checkNotNull(store) { "preferences not open" }.data.first() }.also { snapshot = it }

    fun <T> get(key: Preferences.Key<T>, defaultValue: T): T = current()[key] ?: defaultValue

    fun <T> getOrNull(key: Preferences.Key<T>): T? = current()[key]

    /** Writes off the calling thread; the future completes once the value is in the file (and in memory). */
    fun <T> set(key: Preferences.Key<T>, value: T): CompletableFuture<Unit> = scope.future {
        edit { it[key] = value }
    }

    suspend fun edit(change: (MutablePreferences) -> Unit) {
        snapshot = checkNotNull(store) { "preferences not open" }.edit { change(it) }
    }

    fun launchEdit(change: (MutablePreferences) -> Unit) {
        scope.launch { edit(change) }
    }
}

/**
 * The preferences the Windows runtime reads, in the same DataStore file and
 * under the same keys GameNative's `PrefManager` used ("PluviaPreferences"):
 * GameNative's runtime ran inside droidtop's process and kept them there, so
 * keeping the file and the keys is the whole migration (nothing is moved or
 * rewritten). Adapted from GameNative's app/gamenative/PrefManager.kt
 * (GPL-3.0), cut to the keys droidtop's runtime still reads: the Wine
 * registry defaults a prefix falls back to, the
 * folder scanner's folders, the touchpad options, power control, and the
 * Steam sign-in GameNative kept (read once by droidtop's Steam carry-over).
 */
object PrefManager {

    private val prefs = StoredPrefs { it.gameNativeStore }

    /** Idempotent, no disk work: opens GameNative's file and Winlator's ([Winlator]). */
    @JvmStatic
    fun init(context: Context) {
        prefs.open(context)
        Winlator.init(context)
    }

    /**
     * Opens both files and waits, suspending, until both have been read once, so no later read blocks. Done off
     * the main thread when the runtime starts ([WindowsBackbone]); reading before anything writes also keeps a write
     * racing the first read on a fresh install from making DataStore rethrow FileNotFoundException.
     */
    suspend fun load(context: Context) {
        init(context)
        prefs.load()
        Winlator.prefs.load()
    }

    fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        getPref(booleanPreferencesKey(key), defaultValue)

    fun getString(key: String, defaultValue: String): String =
        getPref(stringPreferencesKey(key), defaultValue)

    fun getFloat(key: String, defaultValue: Float): Float =
        getPref(floatPreferencesKey(key), defaultValue)

    fun setFloat(key: String, value: Float): Unit =
        setPref(floatPreferencesKey(key), value)

    private fun <T> getPref(key: Preferences.Key<T>, defaultValue: T): T = prefs.get(key, defaultValue)

    private fun <T> setPref(key: Preferences.Key<T>, value: T) {
        prefs.set(key, value)
    }

    /**
     * Drops GameNative's component-list cache, which droidtop's own catalog
     * (utils.ComponentCatalog) replaced; false when the store is not open yet,
     * so the caller asks again later.
     */
    fun forgetComponentManifest(): Boolean {
        if (!prefs.isOpen) return false
        prefs.launchEdit { pref ->
            pref.remove(stringPreferencesKey("component_manifest_json"))
            pref.remove(longPreferencesKey("component_manifest_fetched_at"))
        }
        return true
    }

    /* Wine registry defaults a prefix falls back to (ContainerUtils.toContainerData) */
    private val RENDERER = stringPreferencesKey("renderer")
    val renderer: String get() = getPref(RENDERER, "gl")

    private val CSMT = booleanPreferencesKey("csmt")
    val csmt: Boolean get() = getPref(CSMT, true)

    private val VIDEO_PCI_DEVICE_ID = intPreferencesKey("videoPciDeviceID")
    val videoPciDeviceID: Int get() = getPref(VIDEO_PCI_DEVICE_ID, 1728)

    private val OFFSCREEN_RENDERING_MODE = stringPreferencesKey("offScreenRenderingMode")
    val offScreenRenderingMode: String get() = getPref(OFFSCREEN_RENDERING_MODE, "fbo")

    private val STRICT_SHADER_MATH = booleanPreferencesKey("strictShaderMath")
    val strictShaderMath: Boolean get() = getPref(STRICT_SHADER_MATH, true)

    private val VIDEO_MEMORY_SIZE = stringPreferencesKey("videoMemorySize")
    val videoMemorySize: String get() = getPref(VIDEO_MEMORY_SIZE, "2048")

    private val MOUSE_WARP_OVERRIDE = stringPreferencesKey("mouseWarpOverride")
    val mouseWarpOverride: String get() = getPref(MOUSE_WARP_OVERRIDE, "disable")

    /* The folder scanner's folders (CustomGameScanner, PcLibrary) */
    private val CUSTOM_GAME_SCAN_ROOTS = stringPreferencesKey("custom_game_scan_roots")
    var customGameScanRoots: Set<String>
        get() = runCatching { Json.decodeFromString<Set<String>>(getPref(CUSTOM_GAME_SCAN_ROOTS, "[]")) }.getOrDefault(emptySet())
        set(value) = setPref(CUSTOM_GAME_SCAN_ROOTS, Json.encodeToString(value))

    private val CUSTOM_GAME_MANUAL_FOLDERS = stringPreferencesKey("custom_game_manual_folders")
    var customGameManualFolders: Set<String>
        get() = runCatching { Json.decodeFromString<Set<String>>(getPref(CUSTOM_GAME_MANUAL_FOLDERS, "[]")) }.getOrDefault(emptySet())
        set(value) = setPref(CUSTOM_GAME_MANUAL_FOLDERS, Json.encodeToString(value))

    /**
     * Adds [folder] to [customGameManualFolders] and returns once it is written, so the next read sees it (the
     * setter hands its write to a coroutine and returns before it lands). "Add a game" (docs/SPEC.md 7g).
     */
    suspend fun addCustomGameManualFolder(folder: String) {
        prefs.edit { pref ->
            val current = runCatching { Json.decodeFromString<Set<String>>(pref[CUSTOM_GAME_MANUAL_FOLDERS] ?: "[]") }.getOrDefault(emptySet())
            if (folder !in current) pref[CUSTOM_GAME_MANUAL_FOLDERS] = Json.encodeToString(current + folder)
        }
    }


    /* The Steam sign-in GameNative kept, read once by droidtop's Steam carry-over */
    private val CELL_ID = intPreferencesKey("cell_id")
    val cellId: Int get() = getPref(CELL_ID, 0)

    private val USER_NAME = stringPreferencesKey("user_name")
    val username: String get() = getPref(USER_NAME, "")

    private val REFRESH_TOKEN_ENC = byteArrayPreferencesKey("refresh_token_enc")
    val refreshToken: String
        get() {
            val encryptedBytes = getPref(REFRESH_TOKEN_ENC, ByteArray(0))
            return if (encryptedBytes.isEmpty()) "" else String(Crypto.decrypt(encryptedBytes))
        }

    private val CLIENT_ID = longPreferencesKey("client_id")
    val clientId: Long? get() = prefs.getOrNull(CLIENT_ID)

    private val STEAM_USER_STEAM_ID_64 = longPreferencesKey("steam_user_steam_id_64")
    val steamUserSteamId64: Long get() = getPref(STEAM_USER_STEAM_ID_64, 0L)

    private val EXTERNAL_STORAGE_PATH = stringPreferencesKey("external_storage_path")
    val externalStoragePath: String get() = getPref(EXTERNAL_STORAGE_PATH, "")

    /**
     * Winlator's own preferences file ("WinlatorPreferences"), under the keys its view and launch code use: the box64
     * and FEXCore presets, the last box86/box64 versions set up, the GPU found, the input-controls version and the
     * log switches. Its file and keys are kept, so nothing moves. Java reads it as `PrefManager.Winlator`.
     */
    object Winlator {
        internal val prefs = StoredPrefs { it.winlatorStore }

        @JvmStatic
        fun init(context: Context) = prefs.open(context)

        @JvmStatic
        fun getString(key: String, defaultValue: String): String = prefs.get(stringPreferencesKey(key), defaultValue)

        @JvmStatic
        fun putString(key: String, value: String): CompletableFuture<Unit> = prefs.set(stringPreferencesKey(key), value)

        @JvmStatic
        fun getBoolean(key: String, defaultValue: Boolean): Boolean = prefs.get(booleanPreferencesKey(key), defaultValue)

        @JvmStatic
        fun putBoolean(key: String, value: Boolean): CompletableFuture<Unit> = prefs.set(booleanPreferencesKey(key), value)
    }
}
