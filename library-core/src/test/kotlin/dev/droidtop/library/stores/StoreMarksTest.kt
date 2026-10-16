package dev.droidtop.library.stores

import org.junit.Assert.assertEquals
import org.junit.Test

/** Where a store's own mark is read from its home page (docs/SPEC.md 7j "Game sources", Droidtop/tracker#503). */
class StoreMarksTest {
    @Test
    fun touchIconsComeFirstThenIconsByDeclaredSizeThenTheFavicon() {
        val html = """
            <html><head>
            <link rel="icon" href="/img/favicon-16.png" sizes="16x16">
            <link rel="shortcut icon" href="https://cdn.example.com/fav.ico">
            <LINK REL="icon" HREF="/img/favicon-64.png" SIZES="64x64">
            <link rel="apple-touch-icon" sizes="180x180" href="/img/touch-180.png">
            <link rel="stylesheet" href="/site.css">
            </head></html>
        """.trimIndent()
        assertEquals(
            listOf(
                "https://store.example.com/img/touch-180.png",
                "https://store.example.com/img/favicon-64.png",
                "https://store.example.com/img/favicon-16.png",
                "https://cdn.example.com/fav.ico",
                "https://store.example.com/favicon.ico",
            ),
            StoreMarks.iconCandidates(html, "https://store.example.com/en/"),
        )
    }

    @Test
    fun aPageWithNoIconsStillTriesTheFavicon() {
        assertEquals(listOf("https://itch.io/favicon.ico"), StoreMarks.iconCandidates("", "https://itch.io/"))
    }

    @Test
    fun onlyWebLinksAreKept() {
        val html = """<link rel="icon" href="data:image/png;base64,AAAA"><link rel="icon" href="javascript:alert(1)">"""
        assertEquals(listOf("https://gog.example/favicon.ico"), StoreMarks.iconCandidates(html, "https://gog.example/"))
    }
}
