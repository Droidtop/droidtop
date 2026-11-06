package org.pocketworkstation.pckeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts of the GIF and sticker library (Droidtop/tracker#903 H5): names, formats, search, the index file. */
class GifLibraryTest {
    private fun item(file: String, folder: String = "Reactions", kind: GifKind = GifKind.GIF) = GifItem(
        uri = "content://tree/$folder/$file",
        mime = GifFormats.GIF,
        kind = kind,
        name = GifNames.displayName(file),
        words = GifNames.words(file),
        tags = GifNames.words(folder, dropExtension = false),
        folder = "content://tree/$folder",
    )

    @Test
    fun `a file name is split into lower-case words`() {
        assertEquals(listOf("happy", "dance", "01"), GifNames.words("happy-dance_01.gif"))
        assertEquals(listOf("eye", "roll"), GifNames.words("eyeRoll.webp"))
        assertEquals(listOf("cat", "2", "jump"), GifNames.words("Cat2 jump.PNG"))
        assertEquals(listOf("ねこ", "dance"), GifNames.words("ねこ dance.gif"))
        assertEquals(listOf("my", "gifs"), GifNames.words("My GIFs", dropExtension = false))
        assertEquals("happy dance", GifNames.displayName("Happy_dance.gif"))
        assertEquals("___.gif", GifNames.displayName("___.gif"))
    }

    @Test
    fun `GIF, WebP and PNG are taken, by reported type or extension`() {
        assertEquals(GifFormats.GIF, GifFormats.accepted("a.gif", "image/gif"))
        assertEquals(GifFormats.WEBP, GifFormats.accepted("a", "IMAGE/WEBP"))
        assertEquals(GifFormats.PNG, GifFormats.accepted("a.png", null))
        assertEquals(GifFormats.GIF, GifFormats.accepted("a.GIF", "application/octet-stream"))
        assertNull(GifFormats.accepted("a.jpg", "image/jpeg"))
        assertNull(GifFormats.accepted("a.gif", "video/mp4"))
        assertNull(GifFormats.accepted("notes.txt", null))
    }

    private fun webp(flags: Int, chunk: String = "VP8X"): ByteArray {
        val bytes = ByteArray(GifFormats.WEBP_HEADER)
        "RIFF".forEachIndexed { i, c -> bytes[i] = c.code.toByte() }
        "WEBP".forEachIndexed { i, c -> bytes[8 + i] = c.code.toByte() }
        chunk.forEachIndexed { i, c -> bytes[12 + i] = c.code.toByte() }
        bytes[20] = flags.toByte()
        return bytes
    }

    @Test
    fun `an animated WebP is told by its VP8X animation bit`() {
        assertTrue(GifFormats.isAnimatedWebp(webp(0x02)))
        assertTrue(GifFormats.isAnimatedWebp(webp(0x12)))
        assertFalse(GifFormats.isAnimatedWebp(webp(0x10)))
        assertFalse(GifFormats.isAnimatedWebp(webp(0x02, chunk = "VP8 ")))
        assertFalse(GifFormats.isAnimatedWebp(webp(0x02).copyOf(12)))
    }

    @Test
    fun `the tab is the folder's word, else the format`() {
        assertEquals(GifKind.GIF, GifFormats.kindOf(GifFormats.GIF, false, emptyList()))
        assertEquals(GifKind.GIF, GifFormats.kindOf(GifFormats.WEBP, true, emptyList()))
        assertEquals(GifKind.STICKER, GifFormats.kindOf(GifFormats.WEBP, false, emptyList()))
        assertEquals(GifKind.STICKER, GifFormats.kindOf(GifFormats.PNG, false, emptyList()))
        assertEquals(GifKind.STICKER, GifFormats.kindOf(GifFormats.WEBP, true, listOf("whatsapp", "stickers")))
        assertEquals(GifKind.GIF, GifFormats.kindOf(GifFormats.PNG, false, listOf("my", "gifs")))
    }

    @Test
    fun `search ranks the name over the folder, every word required`() {
        val dance = item("happy-dance.gif")
        val happy = item("happy.gif")
        val sad = item("sad-cat.gif", folder = "Happy things")
        val other = item("wave.gif")
        val all = listOf(sad, dance, other, happy)
        assertEquals(listOf(happy, dance, sad), GifSearch.rank(all, "happy", emptyList()))
        assertEquals(listOf(dance), GifSearch.rank(all, "hap dan", emptyList()))
        assertEquals(listOf(dance), GifSearch.rank(all, "Happy-Dance!", emptyList()))
        assertEquals(emptyList<GifItem>(), GifSearch.rank(all, "hap zebra", emptyList()))
    }

    @Test
    fun `recently used come first, newest first, in search and in the tab`() {
        val a = item("alpha.gif")
        val b = item("beta.gif")
        val c = item("gamma.gif")
        assertEquals(listOf(c, a, b), GifSearch.rank(listOf(a, b, c), "", listOf(c.uri)))
        assertEquals(listOf(b, c, a), GifSearch.rank(listOf(a, b, c), " ", listOf(b.uri, c.uri, "content://gone")))
        val cat = item("cat.gif")
        val catNap = item("cat-nap.gif")
        assertEquals(listOf(catNap, cat), GifSearch.rank(listOf(cat, catNap), "cat", listOf(catNap.uri)))
        assertEquals(1, GifSearch.rank(listOf(a, b, c), "", emptyList(), limit = 1).size)
    }

    @Test
    fun `a run-together word finds its two words (CleverKeys' compound fallback)`() {
        val roll = item("eye-roll.gif")
        val other = item("eyes.gif")
        assertEquals(listOf(roll), GifSearch.rank(listOf(other, roll), "eyeroll", emptyList()))
        assertEquals(emptyList<GifItem>(), GifSearch.rank(listOf(roll), "eyer", emptyList()))
    }

    @Test
    fun `the index file keeps every field, tabs and line breaks included`() {
        val odd = GifItem("content://x/a%09b", GifFormats.WEBP, GifKind.STICKER, "a\tb\\c\nd", listOf("a", "b"), emptyList(), "content://x")
        val items = listOf(item("happy-dance.gif"), odd)
        assertEquals(items, GifIndexFile.decode(GifIndexFile.encode(items)))
        assertEquals(emptyList<GifItem>(), GifIndexFile.decode(GifIndexFile.encode(emptyList())))
        assertNull(GifIndexFile.decode("something else\n"))
        val damaged = GifIndexFile.HEADER + "\nGIF\tonly three\tfields\n" + GifIndexFile.encode(listOf(odd)).substringAfter('\n')
        assertEquals(listOf(odd), GifIndexFile.decode(damaged))
    }

    @Test
    fun `an image goes in only where the editor takes its type`() {
        assertTrue(GifInsertPolicy.editorAcceptsMime(arrayOf("image/gif"), "image/gif"))
        assertTrue(GifInsertPolicy.editorAcceptsMime(arrayOf("IMAGE/*"), "image/webp"))
        assertTrue(GifInsertPolicy.editorAcceptsMime(arrayOf("*/*"), "image/png"))
        assertFalse(GifInsertPolicy.editorAcceptsMime(arrayOf("image/png"), "image/gif"))
        assertFalse(GifInsertPolicy.editorAcceptsMime(emptyArray(), "image/gif"))
        assertFalse(GifInsertPolicy.editorAcceptsMime(null, "image/gif"))
        assertEquals(GifInsertPolicy.Action.COMMIT_MEDIA, GifInsertPolicy.decide(editorPresent = true, sensitive = false, editorAccepts = true))
        assertEquals(GifInsertPolicy.Action.NOT_ACCEPTED, GifInsertPolicy.decide(editorPresent = true, sensitive = false, editorAccepts = false))
        assertEquals(GifInsertPolicy.Action.SENSITIVE, GifInsertPolicy.decide(editorPresent = true, sensitive = true, editorAccepts = true))
        assertEquals(GifInsertPolicy.Action.NO_EDITOR, GifInsertPolicy.decide(editorPresent = false, sensitive = false, editorAccepts = true))
    }

    @Test
    fun `only the newest copies made for inserting stay`() {
        val files = listOf("a" to 10L, "b" to 30L, "c" to 20L, "d" to 40L)
        assertEquals(setOf("a", "c"), GifShareCache.stale(files, keep = 2).toSet())
        assertEquals(emptyList<String>(), GifShareCache.stale(files.take(2), keep = 2))
    }

    @Test
    fun `thumbnails are decoded no larger than the cell`() {
        assertEquals(100 to 50, GifThumbSize.fit(400, 200, 100))
        assertEquals(30 to 20, GifThumbSize.fit(30, 20, 100))
        assertEquals(1 to 100, GifThumbSize.fit(2, 1000, 100))
        assertEquals(100 to 100, GifThumbSize.fit(0, 0, 100))
        assertEquals(4, GifThumbSize.sampleSize(800, 400, 100))
        assertEquals(1, GifThumbSize.sampleSize(150, 150, 100))
    }

    @Test
    fun `the folder list and its names`() {
        assertEquals(listOf("content://a", "content://b"), GifFolderList.decode("content://a\n\ncontent://b\ncontent://a\n"))
        assertEquals(emptyList<String>(), GifFolderList.decode(null))
        assertEquals("content://a\ncontent://b", GifFolderList.encode(listOf("content://a", "content://b", "content://a")))
        assertEquals("GIFs", GifFolderList.label("primary:Pictures/GIFs"))
        assertEquals("GIFs", GifFolderList.label("primary:Pictures/GIFs/"))
        assertEquals("primary", GifFolderList.label("primary:"))
        assertEquals("1234-ABCD", GifFolderList.label("1234-ABCD"))
    }
}
