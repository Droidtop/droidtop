package dev.droidtop.stores.steam

import android.content.Context
import `in`.dragonbra.javasteam.base.PacketClientMsgProtobuf
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesCloudconfigstoreSteamclient.CCloudConfigStore_Download_Request
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesCloudconfigstoreSteamclient.CCloudConfigStore_Download_Response
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesCloudconfigstoreSteamclient.CCloudConfigStore_NamespaceVersion
import `in`.dragonbra.javasteam.steam.handlers.steamunifiedmessages.SteamUnifiedMessages
import `in`.dragonbra.javasteam.steam.handlers.steamunifiedmessages.UnifiedService
import `in`.dragonbra.javasteam.steam.handlers.steamunifiedmessages.callback.ServiceMethodResponse
import `in`.dragonbra.javasteam.steam.steamclient.SteamClient
import `in`.dragonbra.javasteam.types.AsyncJobSingle
import java.io.File
import kotlinx.coroutines.future.await
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * The person's Steam collections (docs/SPEC.md 7g, "Stores"; Droidtop/tracker#232):
 * the "Handheld friendly" and "Backlog" groupings Steam keeps in its cloud
 * config store, namespace 1 ("user-collections.<id>" entries). Read on each
 * library sync with the connection the sync already holds, and kept in one
 * small file so the library reads them without a connection. Static
 * collections only (an explicit "added" list); a dynamic collection is a
 * filter Steam evaluates itself, so it is counted and left out. Read only:
 * nothing is ever written back to Steam.
 *
 * Lifted from GameNative's SteamService.fetchSteamCollections,
 * SteamCollectionParser and CloudConfigStoreService (GPL-3.0), which are
 * not carried by a dependency any more.
 */
internal object SteamCollections {
    private const val KEY_PREFIX = "user-collections."
    private const val ATTEMPTS = 2
    private const val TIMEOUT_MS = 30_000L

    /** Steam's own hidden-games list, not a grouping the person made. */
    const val ID_HIDDEN = "hidden"

    data class Collection(val id: String, val name: String, val appIds: Set<Int>)

    /**
     * A dynamic collection: Steam's filters instead of a list of games (Droidtop/tracker#403). Kept as Steam wrote them
     * ([filterSpec], the raw JSON) until droidtop can map them onto its own filters as a saved view; the mapping needs
     * Steam's filter groups checked against real collections, which these copies (and the scan log) provide.
     */
    data class Dynamic(val id: String, val name: String, val filterSpec: String)

    data class Parsed(val collections: List<Collection>, val dynamic: List<Dynamic> = emptyList()) {
        /** How many collections droidtop does not show yet. */
        val skippedDynamic: Int get() = dynamic.size
    }

    data class Raw(val key: String, val value: String, val isDeleted: Boolean)

    fun file(context: Context): File = File(File(context.filesDir, "steam"), "collections.json")

    /** The collections among [entries]: static ones as lists, dynamic ones kept as Steam's filters; a malformed entry is skipped. */
    fun parse(entries: List<Raw>): Parsed {
        val collections = mutableListOf<Collection>()
        val dynamic = mutableListOf<Dynamic>()
        for (entry in entries) {
            if (!entry.key.startsWith(KEY_PREFIX) || entry.isDeleted) continue
            try {
                val json = JSONObject(entry.value)
                val added = json.optJSONArray("added")
                // optString answers the text "null" for a JSON null, so that and blank both mean absent.
                val id = json.optString("id").takeUnless { it.isBlank() || it == "null" } ?: entry.key.removePrefix(KEY_PREFIX)
                if (id.isBlank()) continue
                val name = json.optString("name").takeUnless { it.isBlank() || it == "null" } ?: id
                // Static collections carry an explicit "added" array; dynamic ones a "filterSpec".
                if (added == null) {
                    json.opt("filterSpec")?.takeUnless { it == JSONObject.NULL }?.let { dynamic += Dynamic(id, name, it.toString()) }
                    continue
                }
                val appIds = buildSet { for (i in 0 until added.length()) add(added.getInt(i)) }
                collections += Collection(id, name, appIds)
            } catch (failure: Exception) {
                Timber.tag("SteamCollections").w(failure, "Skipping a malformed collection entry ${entry.key}")
            }
        }
        return Parsed(collections, dynamic)
    }

    /** Asks Steam for the collections over [steam]; null when Steam did not answer. Network: never on the main thread. */
    suspend fun fetch(steam: SteamClient): Parsed? {
        val unified = steam.getHandler(SteamUnifiedMessages::class.java) ?: return null
        // The service has to be registered: JavaSteam routes a reply by service name, so a bare sendMessage never hears it.
        val service = runCatching { unified.createService(CloudConfigStoreService::class.java) }.getOrNull() ?: return null
        val request = CCloudConfigStore_Download_Request.newBuilder()
            .addVersions(CCloudConfigStore_NamespaceVersion.newBuilder().setEnamespace(1).setVersion(0L)) // 1: user collections; 0: all
            .build()
        repeat(ATTEMPTS) { attempt ->
            try {
                val job = service.download(request)
                job.timeout = TIMEOUT_MS
                val body = job.toFuture().await().body.build()
                val raw = body.dataList.flatMap { namespace ->
                    namespace.entriesList.map { Raw(it.key, it.value, it.isDeleted) }
                }
                return parse(raw)
            } catch (failure: kotlinx.coroutines.CancellationException) {
                throw failure
            } catch (failure: Exception) {
                Timber.tag("SteamCollections").w(failure, "Steam collections request ${attempt + 1} of $ATTEMPTS failed")
            }
        }
        return null
    }

    /** Not for the main thread. */
    fun save(context: Context, parsed: Parsed) {
        val target = file(context)
        target.parentFile?.mkdirs()
        val collections = JSONArray()
        parsed.collections.forEach { collection ->
            collections.put(
                JSONObject()
                    .put("id", collection.id)
                    .put("name", collection.name)
                    .put("apps", JSONArray(collection.appIds.sorted())),
            )
        }
        val temp = File(target.path + ".tmp")
        val dynamic = JSONArray()
        parsed.dynamic.forEach { dynamic.put(JSONObject().put("id", it.id).put("name", it.name).put("filterSpec", it.filterSpec)) }
        temp.writeText(JSONObject().put("collections", collections).put("dynamic", dynamic).toString())
        temp.renameTo(target)
    }

    /** The last answer saved; null when Steam has never given one. Not for the main thread. */
    fun load(context: Context): Parsed? = runCatching {
        val file = file(context).takeIf { it.isFile } ?: return null
        val json = JSONObject(file.readText())
        val array = json.optJSONArray("collections") ?: JSONArray()
        val collections = (0 until array.length()).mapNotNull { index ->
            val row = array.optJSONObject(index) ?: return@mapNotNull null
            val apps = row.optJSONArray("apps") ?: JSONArray()
            Collection(row.getString("id"), row.getString("name"), (0 until apps.length()).mapTo(HashSet()) { apps.getInt(it) })
        }
        val dynamicRows = json.optJSONArray("dynamic") ?: JSONArray()
        val dynamic = (0 until dynamicRows.length()).mapNotNull { index ->
            val row = dynamicRows.optJSONObject(index) ?: return@mapNotNull null
            Dynamic(row.optString("id"), row.optString("name"), row.optString("filterSpec")).takeIf { it.id.isNotBlank() }
        }
        Parsed(collections, dynamic)
    }.getOrNull()
}

/**
 * Minimal JavaSteam stub for the `CloudConfigStore` service: JavaSteam ships no
 * generated one, and a reply is routed by service name through the handlers
 * [SteamUnifiedMessages.createService] registers, so without a service of this
 * name the reply is dropped and the request times out.
 */
internal class CloudConfigStoreService(
    unifiedMessages: SteamUnifiedMessages,
) : UnifiedService(unifiedMessages) {

    override val serviceName: String = "CloudConfigStore"

    fun download(
        request: CCloudConfigStore_Download_Request,
    ): AsyncJobSingle<ServiceMethodResponse<CCloudConfigStore_Download_Response.Builder>> =
        unifiedMessages!!.sendMessage(
            CCloudConfigStore_Download_Response.Builder::class.java,
            "CloudConfigStore.Download#1",
            request,
        )

    override fun handleResponseMsg(methodName: String, packetMsg: PacketClientMsgProtobuf) {
        when (methodName) {
            "Download" -> postResponseMsg<CCloudConfigStore_Download_Response.Builder>(
                CCloudConfigStore_Download_Response::class.java,
                packetMsg,
            )
        }
    }

    override fun handleNotificationMsg(methodName: String, packetMsg: PacketClientMsgProtobuf) {
        // The store has no notifications droidtop consumes.
    }
}
