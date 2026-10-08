package dev.droidtop.runtime.windows

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * The preferences the Windows runtime reads, in the same DataStore file and
 * under the same keys GameNative's `PrefManager` used ("PluviaPreferences"):
 * GameNative's runtime ran inside droidtop's process and kept them there, so
 * keeping the file and the keys is the whole migration (nothing is moved or
 * rewritten). Adapted from GameNative's app/gamenative/PrefManager.kt
 * (GPL-3.0), cut to the keys droidtop's runtime still reads: the Wine
 * registry defaults a prefix falls back to, the component-list cache, the
 * folder scanner's folders, the touchpad options, power control, and the
 * Steam sign-in GameNative kept (read once by droidtop's Steam carry-over).
 */
object PrefManager {

    private val Context.datastore by preferencesDataStore(
        name = "PluviaPreferences",
        corruptionHandler = ReplaceFileCorruptionHandler {
            Timber.e("Preferences (somehow got) corrupted, resetting.")
            emptyPreferences()
        },
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var dataStore: DataStore<Preferences>

    /** Idempotent; disk work (the first read), so never on the main thread. */
    @Synchronized
    fun init(context: Context) {
        if (::dataStore.isInitialized) return
        dataStore = context.applicationContext.datastore
        // Read once before anything writes: on a fresh install a write racing
        // the first read made DataStore rethrow FileNotFoundException.
        runBlocking { dataStore.data.first() }
    }

    fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        getPref(booleanPreferencesKey(key), defaultValue)

    fun getString(key: String, defaultValue: String): String =
        getPref(stringPreferencesKey(key), defaultValue)

    fun getFloat(key: String, defaultValue: Float): Float =
        getPref(floatPreferencesKey(key), defaultValue)

    fun setFloat(key: String, value: Float): Unit =
        setPref(floatPreferencesKey(key), value)

    private fun <T> getPref(key: Preferences.Key<T>, defaultValue: T): T = runBlocking {
        dataStore.data.first()[key] ?: defaultValue
    }

    private fun <T> setPref(key: Preferences.Key<T>, value: T) {
        scope.launch {
            dataStore.edit { pref -> pref[key] = value }
        }
    }

    /* Component list cache (ManifestRepository) */
    private val COMPONENT_MANIFEST_JSON = stringPreferencesKey("component_manifest_json")
    var componentManifestJson: String
        get() = getPref(COMPONENT_MANIFEST_JSON, "")
        set(value) = setPref(COMPONENT_MANIFEST_JSON, value)

    private val COMPONENT_MANIFEST_FETCHED_AT = longPreferencesKey("component_manifest_fetched_at")
    var componentManifestFetchedAt: Long
        get() = getPref(COMPONENT_MANIFEST_FETCHED_AT, 0L)
        set(value) = setPref(COMPONENT_MANIFEST_FETCHED_AT, value)

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
    val clientId: Long? get() = runBlocking { dataStore.data.first()[CLIENT_ID] }

    private val STEAM_USER_STEAM_ID_64 = longPreferencesKey("steam_user_steam_id_64")
    val steamUserSteamId64: Long get() = getPref(STEAM_USER_STEAM_ID_64, 0L)

    private val EXTERNAL_STORAGE_PATH = stringPreferencesKey("external_storage_path")
    val externalStoragePath: String get() = getPref(EXTERNAL_STORAGE_PATH, "")
}
