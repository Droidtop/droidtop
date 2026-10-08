package dev.droidtop.pluginhost

import dev.droidtop.pluginhost.TestPlugins.obj
import org.json.JSONObject

/** Droidtop's side, in memory: records, grants, the sheet's answer and every effect the broker causes. */
internal class FakeEnv(vararg records: PluginRecord) : BrokerEnvironment {
    var now = 1_000_000L
    val records = records.associateBy { it.manifest.id }.toMutableMap()
    val states = mutableMapOf<String, MutableMap<String, GrantState>>()
    val wanted = mutableListOf<Pair<String, String>>()
    val audits = mutableListOf<Pair<String, AuditEntry>>()
    val prompts = mutableListOf<GrantPromptRequest>()
    var answer = GrantAnswer.ALLOW
    var user = false
    var remaining: Long? = null
    var chain: List<String> = emptyList()
    val installed = mutableSetOf<String>()
    val launched = mutableListOf<Triple<String, Map<String, String>, String?>>()
    val forwards = mutableListOf<Pair<String, PluginCall>>()
    var forwardReply: PluginReply = PluginReply.ok(obj("done" to true))
    var jobId: String? = "job-1"
    var choice: String? = null

    override fun nowMs() = now
    override fun record(pluginId: String) = records[pluginId]
    override fun resolution() = PluginApiResolver.resolve(records.values.toList())
    override fun providerChoice(api: String) = choice
    override fun grants(pluginId: String) = PluginGrants.Snapshot(states[pluginId].orEmpty())
    override fun setGrant(pluginId: String, permission: String, state: GrantState) {
        states.getOrPut(pluginId) { mutableMapOf() }[permission] = state
    }
    override fun noteWanted(pluginId: String, permission: String) {
        wanted += pluginId to permission
    }
    override fun audit(pluginId: String, entry: AuditEntry) {
        audits += pluginId to entry
    }
    override fun userInitiated(pluginId: String) = user
    override fun remainingMs(pluginId: String) = remaining
    override fun prompt(request: GrantPromptRequest): GrantAnswer {
        prompts += request
        return answer
    }
    override fun isOfficial(origin: String) = origin == "droidtop"
    override fun trustBadge(origin: String) = if (origin == "droidtop") "Official" else "Added by you"
    override fun installId(pluginId: String) = "install-$pluginId"
    override fun hostFacts(): JSONObject = obj("droidtopVersion" to "0.2.0", "mode" to "gaming")
    override fun appInstalled(packageName: String) = packageName in installed
    override fun launchApp(packageName: String) = packageName in installed
    override fun launchAppWithExtras(packageName: String, extras: Map<String, String>, action: String?): Boolean {
        launched += Triple(packageName, extras, action)
        return packageName in installed
    }
    val toasts = mutableListOf<Pair<String, String>>()
    override fun toast(pluginLabel: String, text: String): Boolean {
        toasts += pluginLabel to text
        return true
    }
    var systemsReply: JSONObject = obj("ready" to true, "systems" to org.json.JSONArray())
    var systemsAsked = 0
    override fun librarySystems(): JSONObject {
        systemsAsked++
        return systemsReply
    }
    var roots: List<String> = emptyList()
    override fun gamesRoots() = roots
    val filesReports = mutableListOf<Pair<String, dev.droidtop.library.settings.PathChange>>()
    override fun libraryFilesChanged(pluginId: String, change: dev.droidtop.library.settings.PathChange): Boolean {
        filesReports += pluginId to change
        return true
    }
    /** The real vault over a temporary folder and a stand-in cipher, so the tests see what is stored. */
    val vaultDir: java.io.File by lazy { kotlin.io.path.createTempDirectory("vault").toFile() }
    var vaultStore: PluginVault? = null
    override fun vault(): PluginVault? = vaultStore
    val socialChanges = mutableListOf<Pair<String, JSONObject>>()
    override fun socialChanged(pluginId: String, change: JSONObject): Boolean {
        socialChanges += pluginId to change
        return true
    }
    override fun chainServedBy(pluginId: String) = chain
    override fun forward(provider: PluginRecord, call: PluginCall, timeoutMs: Long): PluginReply {
        forwards += provider.manifest.id to call
        lastTimeout = timeoutMs
        return forwardReply
    }
    var lastTimeout = 0L
    override fun startBrokeredJob(caller: PluginRecord, provider: PluginRecord, call: PluginCall) = jobId
    override fun brokeredJobStatus(caller: PluginRecord, jobId: String) = PluginReply.error(PluginErrorCode.NOT_FOUND, "no such job")
}
