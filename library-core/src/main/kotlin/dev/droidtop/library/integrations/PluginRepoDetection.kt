package dev.droidtop.library.integrations

import android.content.Context
import dev.droidtop.net.GitHubAuth
import dev.droidtop.net.GitHubTokenStore
import dev.droidtop.pluginhost.PluginRepoKeys
import org.json.JSONArray

/** A repository the signed-in person can reach that looks like a plugin repository. */
data class DetectedRepo(val repo: String, val isPrivate: Boolean)

/**
 * "Found for you" (docs/SPEC.md 12a "Plugin repositories"): after sign-in, the
 * repositories the person can access (their own, ones they collaborate on, an
 * organisation's they belong to) are read, and those that look like plugin
 * repositories are listed, so a repository the person is GIVEN access to is
 * detected without installing anything on it. A repository counts when it
 * carries the topic [TOPIC], or its name contains "plugin" and the newest
 * release that has one carries `droidtop-plugin-key.json`. The release check is
 * only made for the name match, at most [MAX_RELEASE_CHECKS] per pass, so a
 * person with hundreds of repositories costs a handful of requests. The result
 * is cached ([TTL_MS]); a rate limit keeps the previous list. Detection only
 * lists: trusting a found repository goes through the same confirmation as one
 * typed by name. Blocking: call off the main thread.
 */
object PluginRepoDetection {
    const val TOPIC = "droidtop-plugin"
    private const val PREFS = "plugin_repo_detection"
    private const val KEY_FOUND = "found"
    private const val KEY_AT = "at"
    private const val TTL_MS = 12L * 60 * 60 * 1000
    private const val MAX_PAGES = 5
    private const val MAX_RELEASE_CHECKS = 25

    data class Candidate(val repo: String, val isPrivate: Boolean, val topics: List<String>, val archived: Boolean)

    /** Repositories from `/user/repos`; unreadable input is an empty list. */
    fun parseRepos(json: String): List<Candidate> {
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("full_name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val topics = o.optJSONArray("topics")?.let { t -> (0 until t.length()).map { t.optString(it) } }.orEmpty()
            Candidate(name, o.optBoolean("private"), topics, o.optBoolean("archived"))
        }
    }

    fun hasTopic(c: Candidate): Boolean = TOPIC in c.topics

    /** Whether a candidate without the topic is worth a release request. */
    fun worthReleaseCheck(c: Candidate): Boolean =
        !c.archived && !hasTopic(c) && c.repo.substringAfter('/').contains("plugin", ignoreCase = true)

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The last list, no network; empty when never run. */
    fun cached(context: Context): List<DetectedRepo> =
        prefs(context).getString(KEY_FOUND, null).orEmpty().lines().filter { it.isNotBlank() }.map {
            DetectedRepo(it.substringBefore('\t'), it.substringAfter('\t', "") == "private")
        }

    fun isStale(context: Context): Boolean = System.currentTimeMillis() - prefs(context).getLong(KEY_AT, 0L) >= TTL_MS

    fun clear(context: Context) = prefs(context).edit().clear().apply()

    sealed interface Outcome {
        data class Done(val found: List<DetectedRepo>) : Outcome
        object NotSignedIn : Outcome
        data class RateLimited(val kept: List<DetectedRepo>) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    /** Refreshes the list when stale (or [force]); with no sign-in there is nothing to read. */
    fun refresh(context: Context, force: Boolean = false): Outcome {
        val token = GitHubTokenStore.get(context) ?: return Outcome.NotSignedIn
        if (!force && !isStale(context)) return Outcome.Done(cached(context))
        return try {
            val found = mutableListOf<DetectedRepo>()
            val toCheck = mutableListOf<Candidate>()
            for (page in 1..MAX_PAGES) {
                val url = "https://api.github.com/user/repos?per_page=100&page=$page&sort=pushed&affiliation=owner,collaborator,organization_member"
                val reply = get(url, token)
                if (reply.rateLimited) return Outcome.RateLimited(cached(context))
                if (reply.status == 401) return Outcome.Failed("GitHub no longer accepts the saved sign-in")
                if (reply.status != 200 || reply.body == null) return Outcome.Failed("GitHub answered HTTP ${reply.status}")
                val repos = parseRepos(reply.body)
                for (c in repos) {
                    if (hasTopic(c)) found += DetectedRepo(c.repo, c.isPrivate) else if (worthReleaseCheck(c)) toCheck += c
                }
                if (repos.size < 100) break
            }
            for (c in toCheck.take(MAX_RELEASE_CHECKS)) {
                val reply = get("https://api.github.com/repos/${c.repo}/releases?per_page=30", token)
                if (reply.rateLimited) return Outcome.RateLimited(cached(context))
                if (reply.status == 200 && reply.body != null && PluginRepoKeys.pickKeyAsset(reply.body) != null) {
                    found += DetectedRepo(c.repo, c.isPrivate)
                }
            }
            val list = found.distinctBy { it.repo.lowercase() }.sortedBy { it.repo.lowercase() }
            prefs(context).edit()
                .putString(KEY_FOUND, list.joinToString("\n") { it.repo + "\t" + if (it.isPrivate) "private" else "public" })
                .putLong(KEY_AT, System.currentTimeMillis())
                .apply()
            Outcome.Done(list)
        } catch (failure: Exception) {
            Outcome.Failed(failure.message ?: "no network")
        }
    }

    private class Reply(val status: Int, val body: String?, val rateLimited: Boolean)

    private fun get(url: String, token: String): Reply {
        val connection = GitHubAuth.open(url, token, 15_000, 30_000, mapOf("Accept" to "application/vnd.github+json"))
        try {
            val status = connection.responseCode
            val limited = status == 429 || (status == 403 && connection.getHeaderField("X-RateLimit-Remaining") == "0")
            val body = if (status == 200) connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } else null
            return Reply(status, body, limited)
        } finally {
            connection.disconnect()
        }
    }
}
