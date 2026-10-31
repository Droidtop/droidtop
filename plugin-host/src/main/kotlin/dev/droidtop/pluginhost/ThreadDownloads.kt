package dev.droidtop.pluginhost

import org.json.JSONArray
import org.json.JSONObject

/**
 * A forum thread's download post read into the `download` bundle droidtop already reads (docs/plugin-api.md 1.10;
 * Droidtop/tracker#838, the owner: "Downloads still don't auto-pick links. We need to implement a thread parser"). One
 * parser for every source plugin, behind `sources.parse_thread`, so a plugin in any language gives droidtop the same
 * entries and droidtop picks the build, the system and the mirror itself.
 *
 * What it reads, from the first post after its DOWNLOAD heading:
 * - a line with no links is a build's heading ("Steam Build"), or "Patches" / "Extras";
 * - a line "<platforms>: HOST - HOST - …" is one download per host, in the thread's order, covering every platform the
 *   label names ("Win/Linux" is one download for Windows and Linux, never two);
 * - a line under "Patches" or "Extras", or labelled so, gives each link as its own item;
 * - a `*` on the label or beside a link marks it unofficial.
 *
 * When there is nothing to read it says why ([Problem]): a browser check, a page for members only, no download
 * heading, or a layout it does not know. Pure: no network, no Android; it reads the HTML it is given.
 */
object ThreadDownloads {
    /** Why a thread gave no downloads, in one plain sentence each. */
    enum class Problem(val id: String, val sentence: String) {
        CHECK("browser_check", "The page is a browser check, not the thread"),
        MEMBERS("members_only", "The thread shows its links to members only"),
        NO_DOWNLOADS("no_downloads", "The thread's first post has no download section"),
        LAYOUT("layout", "The page has no thread post in a layout droidtop knows"),
    }

    enum class Role(val id: String) { BUILD("build"), PATCH("patch"), EXTRA("extra") }

    data class Entry(
        val section: String,
        val host: String,
        val label: String,
        val url: String,
        val systems: List<String>,
        val role: Role,
        val official: Boolean,
    )

    data class Read(val version: String?, val entries: List<Entry>, val problem: Problem?)

    /** The longest page read; a thread page is far smaller. */
    const val MAX_HTML = 4_000_000

    /** Links that are never downloads: a creator's pages and the forum's own, other than its masked download links. */
    private val NOT_DOWNLOADS = listOf(
        "patreon.com", "subscribestar.", "discord.gg", "discord.com", "twitter.com", "x.com", "instagram.com",
        "youtube.com", "youtu.be", "ko-fi.com", "facebook.com", "reddit.com", "steampowered.com/app",
    )

    /** Where the post ends: what follows the download section is the changelog, the previews or the author's notes. */
    private val END_HEADINGS = Regex("^(changelog|change log|previews?|screenshots?|developer notes?|dev notes?|support the dev|thread updated|release date)\\b", RegexOption.IGNORE_CASE)
    private val PATCH = Regex("\\b(patch(es)?|mods?|fix(es)?)\\b", RegexOption.IGNORE_CASE)
    private val EXTRA = Regex("\\b(extras?|walkthrough|guide|saves?|soundtrack|ost|art ?book|bonus)\\b", RegexOption.IGNORE_CASE)
    private val DOWNLOAD_HEADING = Regex("^\\s*downloads?\\s*:?\\s*$", RegexOption.IGNORE_CASE)
    private val VERSION_LINE = Regex("^\\s*version\\s*:\\s*(.{1,60})$", RegexOption.IGNORE_CASE)
    private val GUEST_LINKS = Regex("(you must be registered to see the links|you must be logged in to see the links)", RegexOption.IGNORE_CASE)

    /** [html], the thread page as the web view loaded it, read. Pure. */
    fun read(html: String): Read {
        val page = if (html.length > MAX_HTML) html.substring(0, MAX_HTML) else html
        if (isCheckPage(page)) return Read(null, emptyList(), Problem.CHECK)
        val post = firstPost(page) ?: return Read(null, emptyList(), if (guestPage(page)) Problem.MEMBERS else Problem.LAYOUT)
        val lines = lines(post)
        val version = lines.firstNotNullOfOrNull { line -> VERSION_LINE.find(line.text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } }
        val start = lines.indexOfFirst { it.links.isEmpty() && DOWNLOAD_HEADING.matches(it.text.trim().trimEnd('*').trim()) }
        if (start < 0) {
            return Read(version, emptyList(), if (GUEST_LINKS.containsMatchIn(post) || guestPage(page)) Problem.MEMBERS else Problem.NO_DOWNLOADS)
        }
        val entries = entries(lines.drop(start + 1))
        val problem = when {
            entries.isNotEmpty() -> null
            GUEST_LINKS.containsMatchIn(post) || guestPage(page) -> Problem.MEMBERS
            else -> Problem.NO_DOWNLOADS
        }
        return Read(version, entries, problem)
    }

    /** The entries from the lines after the DOWNLOAD heading, in the thread's order, each address once. */
    private fun entries(lines: List<Line>): List<Entry> {
        val out = LinkedHashMap<String, Entry>()
        var build: String? = null
        var role = Role.BUILD
        // A line of links with no label of its own carries on the line before it (hosts wrapped onto a new line).
        var lastLabel: String? = null
        for (line in lines) {
            val label = line.text.trim().trimEnd(':', '-', '–', ' ').trim()
            if (line.links.isEmpty()) {
                if (label.isEmpty()) continue
                if (END_HEADINGS.containsMatchIn(label) && out.isNotEmpty()) break
                if (label.length > 60) continue
                when {
                    PATCH.containsMatchIn(label) -> role = Role.PATCH
                    EXTRA.containsMatchIn(label) -> role = Role.EXTRA
                    else -> {
                        build = label.trimEnd('*').trim()
                        role = Role.BUILD
                    }
                }
                lastLabel = null
                continue
            }
            var own = label.ifEmpty { lastLabel.orEmpty() }
            for ((index, link) in line.links.withIndex()) {
                // A label later on the same line ("… Mac: MEGA") starts a new set of links.
                if (index > 0 && link.lead.contains(':')) {
                    link.lead.trimEnd(':', '-', '–', ' ').trim().takeIf { it.any(Char::isLetter) }?.let { own = it }
                }
                lastLabel = own
                val starred = own.contains('*')
                val clean = own.replace("*", "").trim()
                val lineRole = when {
                    PATCH.containsMatchIn(clean) && systemsOf(clean).isEmpty() -> Role.PATCH
                    EXTRA.containsMatchIn(clean) && systemsOf(clean).isEmpty() -> Role.EXTRA
                    else -> role
                }
                val url = link.href.takeIf { it.startsWith("https://", ignoreCase = true) } ?: continue
                if (NOT_DOWNLOADS.any { url.contains(it, ignoreCase = true) }) continue
                if (url.contains("f95zone.to", ignoreCase = true) && !url.contains("/masked/")) continue
                val text = link.text.replace("*", "").trim()
                // A link named for a platform ("Win", "Linux") on a line whose label names none is that platform's
                // download, and its host is the address's: such a thread read as one host called "Win"
                // (Droidtop/tracker#859).
                val forPlatform = lineRole == Role.BUILD && systemsOf(clean).isEmpty() && onlyPlatform(text)
                val systems = when {
                    lineRole != Role.BUILD -> emptyList()
                    forPlatform -> systemsOf(text)
                    else -> systemsOf(clean)
                }
                // A platform's name is never a host, whatever the label says.
                val host = if (lineRole == Role.BUILD && onlyPlatform(text)) hostName("", url) else hostName(text, url)
                val platform = if (forPlatform) text else clean
                val section = when (lineRole) {
                    Role.BUILD -> listOfNotNull(build, platform.ifEmpty { null }).joinToString(" · ").ifEmpty { "Download" }
                    Role.PATCH -> clean.ifEmpty { "Patches" }
                    Role.EXTRA -> clean.ifEmpty { "Extras" }
                }
                val itemLabel = if (lineRole == Role.BUILD) host else text.ifEmpty { host }
                val official = !(starred || link.text.contains('*') || link.starredAfter)
                if (url !in out) out[url] = Entry(section, host, itemLabel, url, systems, lineRole, official)
            }
        }
        return out.values.toList()
    }

    /** The systems a platform label names, in droidtop's words; empty when it names none. Pure. */
    fun systemsOf(label: String): List<String> {
        val words = label.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        return buildList {
            if (words.any { it == "win" || it == "windows" || it == "pc" || it == "win64" || it == "win32" }) add("windows")
            if (words.any { it == "linux" }) add("linux")
            if (words.any { it == "mac" || it == "macos" || it == "osx" }) add("mac")
            if (words.any { it == "android" || it == "apk" }) add("android")
        }
    }

    /** Whether [text] names platforms and nothing else ("Win", "Win/Linux", "Android"). Pure. */
    fun onlyPlatform(text: String): Boolean {
        val words = text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        return words.isNotEmpty() && words.all { it in PLATFORM_WORDS }
    }

    private val PLATFORM_WORDS = setOf("win", "windows", "pc", "win64", "win32", "linux", "mac", "macos", "osx", "android", "apk", "x64", "64bit", "32bit")

    /** A link's host as the thread names it ("GOFILE"), else the address's host without "www.". Pure. */
    fun hostName(text: String, url: String): String {
        val t = text.trim()
        if (t.isNotEmpty() && t.length <= 30 && !t.contains("://") && !t.contains('/')) return t
        return Regex("^https?://(?:www\\.)?([^/:?#]+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1) ?: "Link"
    }

    /** `{version?, entries, problem?, message?}`: what `sources.parse_thread` answers. Pure. */
    fun toJson(read: Read): JSONObject = JSONObject().apply {
        read.version?.let { put("version", it) }
        put(
            "entries",
            JSONArray(
                read.entries.map { e ->
                    JSONObject()
                        .put("section", e.section)
                        .put("host", e.host)
                        .put("label", e.label)
                        .put("url", e.url)
                        .put("kind", "page")
                        .put("role", e.role.id)
                        .put("official", e.official)
                        .apply { if (e.systems.isNotEmpty()) put("systems", JSONArray(e.systems)) }
                },
            ),
        )
        read.problem?.let { put("problem", it.id).put("message", it.sentence) }
    }

    // --- reading the page -----------------------------------------------------------------------------------------

    /** A link, with the text just before it ([lead]: a label such as "Mac:", or a separator) and whether a `*` follows it. */
    private class Link(val href: String, val text: String, val lead: String, val starredAfter: Boolean)

    /** A line: its whole text when it has no links, else the text before its first link; and its links in order. */
    private class Line(val text: String, val links: List<Link>)

    private fun isCheckPage(html: String): Boolean {
        val lower = html.lowercase()
        return lower.contains("<title>just a moment") || lower.contains("challenge-platform") || lower.contains("cf-chl-") ||
            lower.contains("checking your browser")
    }

    /** XenForo marks a guest's page `data-logged-in="false"`. */
    private fun guestPage(html: String): Boolean =
        Regex("""<html\b[^>]*\bdata-logged-in\s*=\s*["']false["']""", RegexOption.IGNORE_CASE).containsMatchIn(html)

    /** The first post's body: from the first `bbWrapper` to the end of its article (XenForo's thread layout). */
    private fun firstPost(html: String): String? {
        val start = Regex("""<div\b[^>]*\bclass\s*=\s*["'][^"']*\bbbWrapper\b""", RegexOption.IGNORE_CASE).find(html)?.range?.first ?: return null
        val rest = html.substring(start)
        val end = listOf(rest.indexOf("</article", ignoreCase = true), rest.indexOf("js-selectToQuoteEnd", ignoreCase = true))
            .filter { it > 0 }.minOrNull() ?: rest.length
        return rest.substring(0, end)
    }

    private val BREAK = Regex("""<br\s*/?>|</(div|p|li|h[1-6]|blockquote|tr)\s*>|<hr\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val ANCHOR = Regex("""<a\b([^>]*)>(.*?)</a\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val HREF = Regex("""\bhref\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val TAG = Regex("<[^>]+>")
    private val SPACES = Regex("\\s+")

    /** The post as lines: each line's text without its links, and its links in order. */
    private fun lines(post: String): List<Line> = post.split(BREAK).map { chunk ->
        val links = mutableListOf<Link>()
        var at = 0
        for (m in ANCHOR.findAll(chunk)) {
            val lead = SPACES.replace(plain(chunk.substring(at, m.range.first)), " ").trim()
            val href = HREF.find(m.groupValues[1])?.groupValues?.get(1)?.let(::unescape)
            val after = plain(chunk.substring(m.range.last + 1).take(40)).trimStart()
            if (href != null) links += Link(href, SPACES.replace(plain(m.groupValues[2]), " ").trim(), lead, after.startsWith("*"))
            at = m.range.last + 1
        }
        val text = if (links.isEmpty()) plain(chunk) else links.first().lead
        Line(SPACES.replace(text, " ").trim(), links)
    }

    private fun plain(html: String): String = unescape(TAG.replace(html, " "))

    private fun unescape(s: String): String = s
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#039;", "'")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
}
