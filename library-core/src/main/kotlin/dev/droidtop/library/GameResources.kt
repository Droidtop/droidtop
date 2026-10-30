package dev.droidtop.library

import java.net.URLEncoder

/**
 * Where to read about a game beyond droidtop (DroidDeck's resources row, Droidtop/tracker#363; the way into the guide
 * reader of #817): PCGamingWiki's fixes and settings, GameFAQs' guides and walkthroughs, and a web search for a
 * walkthrough. Each is a search by the game's name, so nothing is asked of a site before the person presses, and a
 * link opens through [Links]. Pure.
 */
object GameResources {
    data class Resource(val label: String, val detail: String, val url: String)

    fun forGame(title: String): List<Resource> {
        val name = title.trim()
        if (name.isEmpty()) return emptyList()
        val q = URLEncoder.encode(name, Charsets.UTF_8.name())
        val walkthrough = URLEncoder.encode("$name walkthrough", Charsets.UTF_8.name())
        return listOf(
            Resource("PCGamingWiki", "Fixes, settings and where its saves are", "https://www.pcgamingwiki.com/w/index.php?search=$q"),
            Resource("Guides", "Walkthroughs and FAQs on GameFAQs", "https://gamefaqs.gamespot.com/search?game=$q"),
            Resource("Search the web", "For a walkthrough of this game", "https://duckduckgo.com/?q=$walkthrough"),
        )
    }
}
