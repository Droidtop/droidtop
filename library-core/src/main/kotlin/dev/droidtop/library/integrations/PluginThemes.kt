package dev.droidtop.library.integrations

import android.content.Context
import dev.droidtop.library.theme.ThemeDownloader
import dev.droidtop.pluginhost.PluginCrashPolicy
import org.json.JSONArray
import org.json.JSONObject

/**
 * Themes from plugins (docs/plugin-api.md 3 C7, `theme.pack@1`, Droidtop/tracker#714): a plugin lists ES-DE themes
 * and each is a row of Browse themes beside ES-DE's own list, marked with the plugin's name. A theme is a git
 * repository droidtop clones and updates with the same downloader as ES-DE's own themes, into the same themes
 * folder, and the same engine reads it with the same limits: a theme can never run code, and a plugin never writes
 * the themes folder.
 */
object PluginThemes {
    const val POINT = "theme.pack"
    private const val BUDGET_MS = 10_000L
    private const val MAX = 100

    /** Every allowed provider's themes, in the order the plugins are listed. Network: off the main thread. */
    suspend fun entries(context: Context): List<ThemeDownloader.ThemeDownloadEntry> {
        val providers = providersOf(context.applicationContext, POINT)
        return providers.flatMap { (record, provided) ->
            val label = provided.label ?: record.manifest.label
            val policy = PluginCrashPolicy(context.applicationContext)
            val reply = try {
                policy.handle(record, newCall(POINT, "list", "themes", JSONObject().put("kinds", JSONArray(listOf("esde"))), BUDGET_MS), timeoutMs = BUDGET_MS, crashOnTimeout = false, userInitiated = true)
            } catch (e: Exception) {
                null
            } finally {
                policy.shutdown()
            }
            reply?.takeIf { it.ok }?.data?.let { parse(record.manifest.id, label, it) }.orEmpty()
        }
    }

    /**
     * A `list` answer (`{themes: [{id, name, author?, url, screenshots?: [https], variants?, colorSchemes?,
     * aspectRatios?, deprecated?}]}`) as Browse themes rows: an https git repository each, at most [MAX], the folder
     * named for the plugin and the theme so no plugin's theme takes another's (or ES-DE's) place. Pure.
     */
    fun parse(pluginId: String, label: String, reply: JSONObject): List<ThemeDownloader.ThemeDownloadEntry> {
        val themes = reply.optJSONArray("themes") ?: return emptyList()
        val seen = HashSet<String>()
        return (0 until minOf(themes.length(), MAX)).mapNotNull { i ->
            val theme = themes.optJSONObject(i) ?: return@mapNotNull null
            val id = theme.optString("id", "").trim().takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,64}")) && seen.add(it) } ?: return@mapNotNull null
            val name = theme.optString("name", "").trim().take(80).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val url = theme.optString("url", "").trim().takeIf { it.startsWith("https://") && it.length <= 500 && !it.contains(' ') } ?: return@mapNotNull null
            ThemeDownloader.ThemeDownloadEntry(
                name = name,
                reponame = folderName(pluginId, id),
                url = url,
                author = theme.optString("author", "").trim().take(80),
                deprecated = theme.optBoolean("deprecated", false),
                variants = strings(theme.optJSONArray("variants")),
                colorSchemes = strings(theme.optJSONArray("colorSchemes")),
                aspectRatios = strings(theme.optJSONArray("aspectRatios")),
                fontSizes = strings(theme.optJSONArray("fontSizes")),
                screenshots = strings(theme.optJSONArray("screenshots")).filter { it.startsWith("https://") }.take(8)
                    .map { ThemeDownloader.ThemeScreenshot(it, "") },
                source = label,
            )
        }
    }

    /** The themes folder a plugin's theme goes in: its plugin's id and its own, with nothing a path could hide in. Pure. */
    fun folderName(pluginId: String, id: String): String =
        ("plugin-" + pluginId + "-" + id).replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)

    private fun strings(array: JSONArray?): List<String> =
        (0 until (array?.length() ?: 0)).mapNotNull { array!!.optString(it).trim().takeIf { s -> s.isNotEmpty() }?.take(80) }.take(32)
}
