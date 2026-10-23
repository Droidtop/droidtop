package dev.droidtop.library.integrations

import dev.droidtop.pluginhost.DownloadJobs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Downloads the person adds (docs/SPEC.md 12a, "Adding a download"): what was offered, and what it is called. */
class ManualDownloadsTest {
    private val torrent = "d8:announce14:http://t.test/4:infod6:lengthi1e4:name1:a12:piece lengthi16384e6:pieces20:aaaaaaaaaaaaaaaaaaaaee".toByteArray()
    private val metalink = "﻿<?xml version=\"1.0\"?>\n<metalink xmlns=\"urn:ietf:params:xml:ns:metalink\"><file name=\"a\"/></metalink>".toByteArray()

    @After
    fun forget() = ManualDownloads.forget()

    @Test
    fun aLinkOrAMagnetIsOfferedAndAnythingElseIsSaidWhyNot() {
        assertTrue(ManualDownloads.read("https://example.org/files/Some%20Game.zip") is ManualDownloads.Offer.Link)
        val magnet = ManualDownloads.read("magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335aa7c1367a88a&dn=Some+Game%20v2")
        assertEquals("Some Game v2", (magnet as ManualDownloads.Offer.Magnet).label)
        assertEquals("That is not a torrent's magnet link", ManualDownloads.read("magnet:?dn=just+a+name"))
        assertEquals("Type or paste a link first", ManualDownloads.read(""))
        assertEquals("Give an https link, a magnet link, or pick a .torrent or .metalink file", ManualDownloads.read("ftp://example.org/a"))
        assertNull(ManualDownloads.offerText("https://example.org/a.zip"))
        assertEquals("a.zip", ManualDownloads.pending?.label)
    }

    @Test
    fun downloadsAreNamedForThePerson() {
        assertEquals("Some Game.zip", ManualDownloads.linkName("https://example.org/files/Some%20Game.zip?token=x"))
        assertEquals("example.org", ManualDownloads.linkName("https://example.org/"))
        assertEquals("Magnet download", ManualDownloads.magnetName("magnet:?xt=urn:btih:abc"))
    }

    @Test
    fun aFileIsATorrentOrAMetalinkOrRefused() {
        assertNull(ManualDownloads.offerFile("Some Game.torrent", torrent))
        assertEquals(DownloadJobs.SWARM_TORRENT, (ManualDownloads.pending as ManualDownloads.Offer.File).swarm)
        assertEquals("Some Game", ManualDownloads.pending?.label)
        assertNull(ManualDownloads.offerFile("Release.meta4", metalink))
        assertEquals(DownloadJobs.SWARM_METALINK, (ManualDownloads.pending as ManualDownloads.Offer.File).swarm)
        assertEquals("That file is not a .torrent or a Metalink", ManualDownloads.offerFile("notes.txt", "hello".toByteArray()))
        assertTrue(DownloadJobs.looksLikeMetalink(metalink))
        assertFalse(DownloadJobs.looksLikeMetalink("<html><body>metalink</body>".toByteArray().copyOf(0)))
    }
}
