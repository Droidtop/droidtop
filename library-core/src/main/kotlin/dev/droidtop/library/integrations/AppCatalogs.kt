package dev.droidtop.library.integrations

import android.content.Context
import android.os.Build
import dev.droidtop.pluginhost.AcquireDownloads
import dev.droidtop.pluginhost.DownloadJobs
import dev.droidtop.pluginhost.PluginGrants
import dev.droidtop.pluginhost.PluginRecord
import dev.droidtop.pluginhost.PluginResult
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * `apps.catalog@1` (docs/plugin-api.md 3 A12, docs/SPEC.md 10b "Installing apps", Droidtop/tracker#261): plugins that
 * list Android apps (an F-Droid repository client, a tracker of release pages), and droidtop, which does everything
 * that touches the device. Core owns installing and update tracking; a plugin supplies a catalog:
 *
 * - **The plugin** keeps its sources (repositories, tracked pages), fetches and verifies their indexes, answers
 *   searches and an app's page, lists its newest compatible versions ([latestPage]) and, asked for one version
 *   ([install]), returns the download and what the APK must be (package, version code, signing keys).
 * - **droidtop** downloads it as a Downloads job, checks the file is that package and version signed by one of those
 *   keys (and by the installed app's key, so a changed key is refused plainly), installs it through the one
 *   PackageInstaller path ([POST_INSTALL], `ApkInstaller` in `:app`), and decides what is an update by comparing the
 *   cached offers with the installed apps ([updates]): a plugin never learns which apps are installed and never says
 *   "update available".
 *
 * Adding a source starts from a link however it arrives (an fdroidrepos:// link through the [LinkRouter], a QR code,
 * the address field, a known source's press): the plugin's `open_link` answer is a review droidtop shows, and the
 * source is added only by the person's Accept (`add_source`).
 */
object AppCatalogs {
    const val POINT = "apps.catalog"

    /** The Downloads post step that checks and installs a catalog's APK; registered by `:app` at process start. */
    const val POST_INSTALL = "install_apk"

    const val ARG_PACKAGE = "appPackage"
    const val ARG_VERSION_CODE = "appVersionCode"
    const val ARG_SIGNERS = "appSigners"
    const val ARG_LABEL = "appLabel"

    /** At most this many apps per `latest` page, and pages per refresh. */
    const val PAGE = 500
    private const val MAX_PAGES = 40
    private const val MAX_ROWS = 200

    /** The installed, running plugins that provide the point and that the person allowed to. Manifests only. */
    fun providers(context: Context): List<PluginRecord> {
        val grants = PluginGrants.forContext(context)
        return providersOf(context, POINT).map { it.first }.distinctBy { it.manifest.id }
            .filter { PluginGrants.pointRefusal(it, grants.read(it.manifest.id), POINT) == null }
    }

    /** What the APK must be: checked by the post step against the file before anything is installed. */
    data class Expected(val packageName: String, val versionCode: Long, val signers: Set<String>) {
        fun toArgs(): Map<String, String> = mapOf(
            ARG_PACKAGE to packageName,
            ARG_VERSION_CODE to versionCode.toString(),
            ARG_SIGNERS to signers.joinToString(","),
        )

        companion object {
            fun fromArgs(args: Map<String, String>): Expected? {
                val name = args[ARG_PACKAGE]?.takeIf { PACKAGE.matches(it) } ?: return null
                return Expected(name, args[ARG_VERSION_CODE]?.toLongOrNull() ?: 0L, signersOf(args[ARG_SIGNERS]))
            }
        }
    }

    /** An app as the device has it: version code and name, and its signing certificates (SHA-256, lower-case hex). */
    data class PackageFacts(val packageName: String, val versionCode: Long, val versionName: String?, val signers: Set<String>)

    private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
    private val SHA256 = Regex("[0-9a-f]{64}")

    /** Signing-certificate digests from a comma list or a JSON array: SHA-256, lower-cased, colons removed; anything else dropped. */
    fun signersOf(text: String?): Set<String> = text.orEmpty().split(',').map { it.trim().lowercase().replace(":", "") }
        .filter { SHA256.matches(it) }.toSet()

    private fun signersOf(array: JSONArray?): Set<String> =
        signersOf((0 until (array?.length() ?: 0)).joinToString(",") { array!!.optString(it) })

    /** What every call carries so the plugin answers for this device: Android version, ABIs, the person's language. */
    fun device(): JSONObject = JSONObject()
        .put("sdk", Build.VERSION.SDK_INT)
        .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
        .put("locale", Locale.getDefault().toLanguageTag())

    // ------------------------------------------------------------------
    // What the plugin says, read (pure).
    // ------------------------------------------------------------------

    data class Source(
        val id: String,
        val name: String,
        val address: String,
        val enabled: Boolean,
        val updated: Long?,
        val apps: Int?,
        val fingerprint: String?,
        val note: String?,
    )

    data class KnownSource(val name: String, val address: String, val about: String?)

    data class Sources(val sources: List<Source>, val known: List<KnownSource>)

    fun parseSources(data: JSONObject): Sources {
        val sources = data.optJSONArray("sources").objects().mapNotNull { o ->
            val id = o.optString("id").trim().takeIf { it.isNotEmpty() && it.length <= 200 } ?: return@mapNotNull null
            Source(
                id = id,
                name = o.optString("name").trim().ifEmpty { id }.take(100),
                address = o.optString("address").trim().take(300),
                enabled = o.optBoolean("enabled", true),
                updated = o.optLong("updated", 0L).takeIf { it > 0 },
                apps = if (o.has("apps")) o.optInt("apps") else null,
                fingerprint = o.optString("fingerprint").trim().takeIf { it.isNotEmpty() }?.take(100),
                note = o.optString("note").trim().takeIf { it.isNotEmpty() }?.take(300),
            )
        }
        val known = data.optJSONArray("known").objects().mapNotNull { o ->
            val address = o.optString("address").trim().takeIf { it.isNotEmpty() && it.length <= 500 } ?: return@mapNotNull null
            KnownSource(o.optString("name").trim().ifEmpty { address }.take(100), address, o.optString("about").trim().takeIf { it.isNotEmpty() }?.take(300))
        }
        return Sources(sources, known)
    }

    /** The plugin's review of a source a link names: what the person reads before Accept. */
    data class Review(
        val token: String,
        val name: String,
        val address: String,
        val fingerprint: String?,
        /** True when the link named the fingerprint and the source's key matched it; false when droidtop shows the key for the person to compare. */
        val fingerprintFromLink: Boolean,
        val description: String?,
        val apps: Int?,
        val existing: Boolean,
        val note: String?,
    )

    fun parseReview(data: JSONObject): Review? {
        val o = data.optJSONObject("review") ?: return null
        val token = o.optString("token").trim().takeIf { it.isNotEmpty() && it.length <= 200 } ?: return null
        return Review(
            token = token,
            name = o.optString("name").trim().ifEmpty { "Unnamed" }.take(100),
            address = o.optString("address").trim().take(300),
            fingerprint = o.optString("fingerprint").trim().takeIf { it.isNotEmpty() }?.take(100),
            fingerprintFromLink = o.optBoolean("fingerprintFromLink", false),
            description = o.optString("description").trim().takeIf { it.isNotEmpty() }?.take(4000),
            apps = if (o.has("apps")) o.optInt("apps") else null,
            existing = o.optBoolean("existing", false),
            note = o.optString("note").trim().takeIf { it.isNotEmpty() }?.take(500),
        )
    }

    data class AntiFeature(val key: String, val label: String, val reason: String?)

    data class AppRow(
        val id: String,
        val name: String,
        val summary: String?,
        val version: String?,
        val versionCode: Long?,
        val source: String?,
        val antiFeatures: List<String>,
    )

    fun parseApps(data: JSONObject): List<AppRow> = data.optJSONArray("apps").objects().take(MAX_ROWS).mapNotNull { o ->
        val id = o.optString("id").trim().takeIf { PACKAGE.matches(it) } ?: return@mapNotNull null
        AppRow(
            id = id,
            name = o.optString("name").trim().ifEmpty { id }.take(100),
            summary = o.optString("summary").trim().takeIf { it.isNotEmpty() }?.take(300),
            version = o.optString("version").trim().takeIf { it.isNotEmpty() }?.take(60),
            versionCode = o.optLong("versionCode", 0L).takeIf { it > 0 },
            source = o.optString("source").trim().takeIf { it.isNotEmpty() }?.take(100),
            antiFeatures = o.optJSONArray("antiFeatures").strings().take(20),
        )
    }

    data class AppVersion(
        val version: String,
        val versionCode: Long,
        val size: Long?,
        val signers: Set<String>,
        val minSdk: Int?,
        val added: Long?,
        val antiFeatures: List<AntiFeature>,
        val whatsNew: String?,
    )

    data class AppPage(
        val id: String,
        val name: String,
        val summary: String?,
        val description: String?,
        val license: String?,
        val website: String?,
        val sourceCode: String?,
        val author: String?,
        val source: String?,
        val versions: List<AppVersion>,
    )

    fun parseApp(data: JSONObject): AppPage? {
        val o = data.optJSONObject("app") ?: return null
        val id = o.optString("id").trim().takeIf { PACKAGE.matches(it) } ?: return null
        fun text(name: String, max: Int) = o.optString(name).trim().takeIf { it.isNotEmpty() }?.take(max)
        return AppPage(
            id = id,
            name = text("name", 100) ?: id,
            summary = text("summary", 300),
            description = text("description", 8000),
            license = text("license", 100),
            website = text("website", 300),
            sourceCode = text("sourceCode", 300),
            author = text("author", 100),
            source = text("source", 100),
            versions = o.optJSONArray("versions").objects().take(20).mapNotNull { v ->
                val code = v.optLong("versionCode", 0L).takeIf { it > 0 } ?: return@mapNotNull null
                AppVersion(
                    version = v.optString("version").trim().ifEmpty { code.toString() }.take(60),
                    versionCode = code,
                    size = v.optLong("size", 0L).takeIf { it > 0 },
                    signers = signersOf(v.optJSONArray("signers")),
                    minSdk = if (v.has("minSdk")) v.optInt("minSdk") else null,
                    added = v.optLong("added", 0L).takeIf { it > 0 },
                    antiFeatures = v.optJSONArray("antiFeatures").objects().take(20).mapNotNull { a ->
                        val key = a.optString("key").trim().takeIf { it.isNotEmpty() }?.take(60) ?: return@mapNotNull null
                        AntiFeature(key, a.optString("label").trim().ifEmpty { key }.take(100), a.optString("reason").trim().takeIf { it.isNotEmpty() }?.take(1000))
                    },
                    whatsNew = v.optString("whatsNew").trim().takeIf { it.isNotEmpty() }?.take(2000),
                )
            },
        )
    }

    /** One newest version a catalog offers for an app: what the Updates place compares with the installed app. */
    data class Offer(val id: String, val name: String, val version: String, val versionCode: Long, val signers: Set<String>, val source: String?)

    fun parseOffers(data: JSONObject): Pair<List<Offer>, String?> =
        offersOf(data.optJSONArray("apps"), PAGE) to data.optString("next").trim().takeIf { it.isNotEmpty() && it.length <= 200 }

    private fun offersOf(array: JSONArray?, limit: Int): List<Offer> = array.objects().take(limit).mapNotNull { o ->
        val id = o.optString("id").trim().takeIf { PACKAGE.matches(it) } ?: return@mapNotNull null
        val code = o.optLong("versionCode", 0L).takeIf { it > 0 } ?: return@mapNotNull null
        Offer(
            id = id,
            name = o.optString("name").trim().ifEmpty { id }.take(100),
            version = o.optString("version").trim().ifEmpty { code.toString() }.take(60),
            versionCode = code,
            signers = signersOf(o.optJSONArray("signers")),
            source = o.optString("source").trim().takeIf { it.isNotEmpty() }?.take(100),
        )
    }

    /** What the person can do about an offer for an installed app. */
    sealed class UpdateState {
        /** A newer version signed by the installed app's key. */
        data class Available(val offer: Offer, val installed: PackageFacts, val pluginId: String, val pluginLabel: String) : UpdateState()

        /** A newer version signed by another key: Android would refuse it, so it is shown and not offered. */
        data class KeyDiffers(val offer: Offer, val installed: PackageFacts, val pluginLabel: String) : UpdateState()
    }

    /**
     * The updates the cached offers hold for the installed apps (pure): an offer is an update when its version code is
     * higher than the installed one; it is offered only when one of its signing keys is the installed app's (an offer
     * that names no key is offered, and the APK is checked against the installed key when it arrives). When several
     * catalogs offer one app, the highest version code wins, then the first catalog. droidtop itself is left to its
     * own updater.
     */
    fun updates(cached: List<CachedOffers>, installed: Map<String, PackageFacts>, ownPackage: String): List<UpdateState> {
        val best = HashMap<String, Pair<Offer, CachedOffers>>()
        for (catalog in cached) {
            for (offer in catalog.offers) {
                if (offer.id == ownPackage) continue
                val have = installed[offer.id] ?: continue
                if (offer.versionCode <= have.versionCode) continue
                val previous = best[offer.id]
                if (previous == null || offer.versionCode > previous.first.versionCode) best[offer.id] = offer to catalog
            }
        }
        return best.values.map { (offer, catalog) ->
            val have = installed.getValue(offer.id)
            val sameKey = offer.signers.isEmpty() || have.signers.isEmpty() || offer.signers.any { it in have.signers }
            if (sameKey) UpdateState.Available(offer, have, catalog.pluginId, catalog.pluginLabel) else UpdateState.KeyDiffers(offer, have, catalog.pluginLabel)
        }.sortedBy { state ->
            when (state) {
                is UpdateState.Available -> "0" + state.offer.name.lowercase()
                is UpdateState.KeyDiffers -> "1" + state.offer.name.lowercase()
            }
        }
    }

    // ------------------------------------------------------------------
    // The cached offers (one file; the Updates place reads only this).
    // ------------------------------------------------------------------

    data class CachedOffers(val pluginId: String, val pluginLabel: String, val checked: Long, val offers: List<Offer>)

    private fun offersFile(context: Context) = File(context.filesDir, "app_catalog_offers.json")

    private val cacheLock = Any()

    /** Every catalog's last offers, for the installed catalogs only. Disk; off the main thread. */
    fun cachedOffers(context: Context): List<CachedOffers> {
        val installed = providers(context).associateBy { it.manifest.id }
        val root = synchronized(cacheLock) { runCatching { JSONObject(offersFile(context).readText()) }.getOrNull() } ?: return emptyList()
        return root.keys().asSequence().mapNotNull { id ->
            val record = installed[id] ?: return@mapNotNull null
            val o = root.optJSONObject(id) ?: return@mapNotNull null
            CachedOffers(id, record.manifest.label, o.optLong("checked"), offersOf(o.optJSONArray("apps"), PAGE * MAX_PAGES))
        }.toList()
    }

    private fun writeCache(context: Context, record: PluginRecord, offers: List<Offer>) {
        synchronized(cacheLock) {
            val file = offersFile(context)
            val root = runCatching { JSONObject(file.readText()) }.getOrNull() ?: JSONObject()
            root.put(
                record.manifest.id,
                JSONObject().put("checked", System.currentTimeMillis()).put(
                    "apps",
                    JSONArray(offers.map { o ->
                        JSONObject().put("id", o.id).put("name", o.name).put("version", o.version).put("versionCode", o.versionCode)
                            .put("signers", JSONArray(o.signers.toList())).put("source", o.source ?: "")
                    }),
                ),
            )
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(root.toString())
            tmp.renameTo(file)
        }
    }

    // ------------------------------------------------------------------
    // Calls.
    // ------------------------------------------------------------------

    private fun args(): JSONObject = JSONObject().put("device", device())

    suspend fun sources(context: Context, record: PluginRecord): Result<Sources> {
        val reply = PluginViews.call(context, record, POINT, "sources", args())
        return if (reply.ok) Result.success(parseSources(reply.data)) else Result.failure(IllegalStateException(reply.message ?: "it did not answer"))
    }

    /** Asks [record] for its review of the source [link] names. A line to show when there is none. */
    suspend fun review(context: Context, record: PluginRecord, link: String): Result<Review> {
        val reply = PluginViews.call(context, record, POINT, dev.droidtop.pluginhost.PluginLinks.OP_OPEN, args().put("link", link.trim()), timeoutMs = 60_000L)
        if (!reply.ok) return Result.failure(IllegalStateException(reply.message ?: "it could not read that source"))
        return parseReview(reply.data)?.let { Result.success(it) }
            ?: Result.failure(IllegalStateException(reply.data.optString("message").ifBlank { "it returned no source to review" }))
    }

    /** Adds the reviewed source (the person's Accept): a job, because the plugin fetches the whole index; then refreshes the cached offers. */
    suspend fun accept(context: Context, record: PluginRecord, review: Review, onStatus: (String) -> Unit): String {
        val result = PluginViews.runJob(context, record, POINT, "add_source", args().put("token", review.token), "Add ${review.name}", onStatus)
        if (!result.ok) return "Not added: ${result.error ?: "it failed"}"
        refreshOffers(context, record)
        return result.values["message"] ?: "Added ${review.name}"
    }

    suspend fun quick(context: Context, record: PluginRecord, op: String, extra: JSONObject, done: String): String {
        val call = args()
        extra.keys().forEach { call.put(it, extra.get(it)) }
        val reply = PluginViews.call(context, record, POINT, op, call)
        return if (reply.ok) reply.data.optString("message").ifBlank { done } else "${record.manifest.label}: ${reply.message ?: "it failed"}"
    }

    /** Re-reads the indexes of [source] (null: every source), then the offers. A job. */
    suspend fun refresh(context: Context, record: PluginRecord, source: String?, onStatus: (String) -> Unit): String {
        val call = args().apply { source?.let { put("source", it) } }
        val result = PluginViews.runJob(context, record, POINT, "refresh", call, "Refresh ${record.manifest.label}", onStatus)
        if (!result.ok) return "Not refreshed: ${result.error ?: "it failed"}"
        val offers = refreshOffers(context, record)
        return (result.values["message"] ?: "Refreshed") + (offers?.let { ". $it" } ?: "")
    }

    /** Reads every page of [record]'s newest offers into the cache; returns a line about it, or null when it failed. */
    suspend fun refreshOffers(context: Context, record: PluginRecord): String? = withContext(Dispatchers.IO) {
        val all = ArrayList<Offer>()
        var after: String? = null
        for (page in 0 until MAX_PAGES) {
            val call = args().put("limit", PAGE).apply { after?.let { put("after", it) } }
            val reply = PluginViews.call(context, record, POINT, "latest", call)
            if (!reply.ok) return@withContext null
            val (offers, next) = parseOffers(reply.data)
            all += offers
            after = next ?: break
        }
        writeCache(context, record, all)
        "${all.size} apps listed"
    }

    suspend fun search(context: Context, record: PluginRecord, query: String): Result<List<AppRow>> {
        val reply = PluginViews.call(context, record, POINT, "search", args().put("query", query.trim()).put("limit", MAX_ROWS))
        return if (reply.ok) Result.success(parseApps(reply.data)) else Result.failure(IllegalStateException(reply.message ?: "it did not answer"))
    }

    suspend fun app(context: Context, record: PluginRecord, id: String): Result<AppPage> {
        val reply = PluginViews.call(context, record, POINT, "app", args().put("id", id))
        if (!reply.ok) return Result.failure(IllegalStateException(reply.message ?: "it did not answer"))
        return parseApp(reply.data)?.let { Result.success(it) } ?: Result.failure(IllegalStateException("it returned no app"))
    }

    /**
     * Installs or updates [id] at [versionCode] (0: the version the plugin suggests) from [record]: asks for the
     * download (`acquire`, a job), then runs it as a Downloads job whose post step checks the APK against what the
     * plugin promised and hands it to Android's installer. The plugin must name the package asked for, and give a
     * SHA-256 of the file or the signing keys, so droidtop always has something to check the bytes against.
     */
    suspend fun install(context: Context, record: PluginRecord, id: String, versionCode: Long, label: String, onStatus: (String) -> Unit): PluginResult {
        val call = args().put("id", id).put("versionCode", versionCode)
        val job = PluginViews.runJob(context, record, POINT, "acquire", call, "Get $label", onStatus)
        if (!job.ok) return PluginResult.failure("${record.manifest.label}: ${job.error ?: "it failed"}")
        val descriptor = AcquireDownloads.parse(job.values)?.singleOrNull()
            ?: return PluginResult.failure("${record.manifest.label} returned no usable download")
        if (descriptor.session != null || descriptor.unpack) return PluginResult.failure("an app download is one plain APK file")
        val expected = Expected.fromArgs(
            mapOf(
                ARG_PACKAGE to (job.values["packageName"] ?: ""),
                ARG_VERSION_CODE to (job.values["versionCode"] ?: "0"),
                ARG_SIGNERS to (job.values["signers"] ?: ""),
            ),
        ) ?: return PluginResult.failure("${record.manifest.label} did not say which app the download is")
        if (expected.packageName != id) return PluginResult.failure("${record.manifest.label} offered ${expected.packageName}, not $id")
        if (descriptor.sha256 == null && expected.signers.isEmpty()) {
            return PluginResult.failure("${record.manifest.label} gave neither a digest nor a signing key to check the app with")
        }
        return DownloadJobs.run(
            context = context,
            title = "Install $label",
            post = POST_INSTALL,
            url = descriptor.url,
            name = "app_${id.replace('.', '_')}_${expected.versionCode}.apk",
            sha256 = descriptor.sha256,
            maxBytes = descriptor.size ?: 0L,
            headers = descriptor.headers,
            sizeBytes = descriptor.size ?: 0L,
            extra = expected.toArgs() + (ARG_LABEL to label),
            onStatus = onStatus,
        )
    }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).trim().takeIf { s -> s.isNotEmpty() }?.take(60) }
}
