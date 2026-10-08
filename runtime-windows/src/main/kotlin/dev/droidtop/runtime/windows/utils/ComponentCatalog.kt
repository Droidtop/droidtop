package dev.droidtop.runtime.windows.utils

import android.content.Context
import dev.droidtop.runtime.windows.PrefManager
import dev.droidtop.runtime.windows.RuntimeDownloads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * droidtop's component catalog (docs/SPEC.md 5a): every Wine/Proton build,
 * DXVK, VKD3D, FEXCore, Box64/WowBox64 and Adreno driver build the Windows
 * runtime can offer, and every base-system file it fetches, each with the
 * SHA-256 its download is checked against. Published daily by
 * Droidtop/droidtop-components (its sources/feeds.json and mirror.json say
 * where each one comes from), replacing GameNative's component list and
 * download host. Read in the shape GameNative's manifest had
 * ([ManifestData]), so [ComponentRequests] and the option lists use it as
 * they used that.
 *
 * Sources: the catalog names them ([CatalogSource]); a person turns each one
 * on or off ([setEnabled]), and those not chosen keep the catalog's default
 * (droidtop's mirror and its own Wine builds on). [offered] is what the rows
 * show: enabled sources, and only what this runtime can run (engine
 * `bionic`); Linux builds wait for droidtop's Linux engine.
 */
object ComponentCatalog {
    const val SOURCE_MIRROR = "mirror"
    const val ENGINE_BIONIC = "bionic"

    private const val RELEASES = "https://github.com/Droidtop/droidtop-components/releases/download/"
    const val CATALOG_URL = RELEASES + "catalog/catalog.json"

    private const val ONE_DAY_MS = 24 * 60 * 60 * 1000L
    private const val PREFS = "droidtop_component_catalog"
    private const val KEY_JSON = "catalog_json"
    private const val KEY_FETCHED_AT = "fetched_at"
    private const val KEY_MIGRATED = "gamenative_list_dropped"
    private val json = Json { ignoreUnknownKeys = true }

    /** The catalog, from the copy kept on the device, fetched again when a day old. Disk and network. */
    suspend fun load(context: Context): ManifestData = withContext(Dispatchers.IO) {
        migrate(context)
        val prefs = prefs(context)
        val cached = parse(prefs.getString(KEY_JSON, null))
        if (cached != null && System.currentTimeMillis() - prefs.getLong(KEY_FETCHED_AT, 0) < ONE_DAY_MS) {
            return@withContext cached
        }
        runCatching { refresh(context) }
            .onFailure { Timber.w(it, "ComponentCatalog: fetch failed; using the copy on the device") }
            .getOrNull() ?: cached ?: ManifestData.empty()
    }

    /** Fetches the catalog now and keeps it; throws when it cannot. Network. */
    suspend fun refresh(context: Context): ManifestData = withContext(Dispatchers.IO) {
        val text = RuntimeDownloads.text(CATALOG_URL)
        val parsed = parse(text) ?: error("the component catalog could not be read")
        prefs(context).edit().putString(KEY_JSON, text).putLong(KEY_FETCHED_AT, System.currentTimeMillis()).apply()
        parsed
    }

    /** When the catalog on the device was fetched, or 0. Disk. */
    fun fetchedAt(context: Context): Long = prefs(context).getLong(KEY_FETCHED_AT, 0)

    /** What the option rows offer: the enabled sources' items this runtime can run. */
    suspend fun offered(context: Context): ManifestData {
        val data = load(context)
        return data.copy(
            items = data.items.mapValues { (_, entries) ->
                entries.filter { it.engine == ENGINE_BIONIC && isEnabled(context, data, it.source) }
            },
        )
    }

    /**
     * Every item this runtime can run, enabled source or not: what a prefix
     * already names is fetched even after its source was turned off.
     */
    suspend fun runnable(context: Context): ManifestData {
        val data = load(context)
        return data.copy(items = data.items.mapValues { (_, entries) -> entries.filter { it.engine == ENGINE_BIONIC } })
    }

    fun isEnabled(context: Context, data: ManifestData, sourceId: String): Boolean {
        val default = data.sources.firstOrNull { it.id == sourceId }?.defaultEnabled ?: (sourceId == SOURCE_MIRROR)
        return prefs(context).getBoolean("enabled_$sourceId", default)
    }

    fun setEnabled(context: Context, sourceId: String, enabled: Boolean) {
        prefs(context).edit().putBoolean("enabled_$sourceId", enabled).apply()
    }

    /**
     * Where the base-system file [path] is (a path as the runtime asks for it:
     * `imagefs_bionic.txz`, `container_files/extras.tzst`), with its SHA-256
     * when the catalog has it. Without a catalog the file is still found: each
     * folder is one release of droidtop-components ([defaultFileUrl]).
     */
    suspend fun file(context: Context, path: String): CatalogFile =
        load(context).files[path] ?: CatalogFile(defaultFileUrl(path))

    /** `container_files/extras.tzst` is the asset extras.tzst of the release container-files; a bare name is in base. */
    fun defaultFileUrl(path: String): String {
        val folder = path.substringBeforeLast('/', "")
        val release = if (folder.isEmpty()) "base" else folder.replace('_', '-')
        return RELEASES + release + "/" + path.substringAfterLast('/')
    }

    fun parse(text: String?): ManifestData? {
        if (text.isNullOrBlank()) return null
        return try {
            json.decodeFromString<ManifestData>(text)
        } catch (e: Exception) {
            Timber.e(e, "ComponentCatalog: parse failed")
            null
        }
    }

    /** GameNative's component list cache in the runtime's DataStore is never read again; drop it once. */
    private fun migrate(context: Context) {
        val prefs = prefs(context)
        if (prefs.getBoolean(KEY_MIGRATED, false)) return
        if (PrefManager.forgetComponentManifest()) prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
