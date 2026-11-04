package dev.droidtop.library.integrations

import dev.droidtop.pluginhost.AcquireDownloadDescriptor
import dev.droidtop.pluginhost.DownloadJobs
import dev.droidtop.pluginhost.WebSessionDownload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** A source's plain file-host address goes to the person's accounts first (Droidtop/tracker#638, owner 2026-11-04). */
class HostRouteTest {
    private fun d(url: String, session: String? = null, auth: String? = null, swarm: String? = null) =
        AcquireDownloadDescriptor(url, mapOf("Referer" to "https://source.example/"), "Game.zip", "abc", null, session = session, auth = auth, swarm = swarm)

    private val torbox = WebSessionDownload.direct("https://store.torbox.app/dl/1", "Game.zip", 42L, via = "via your TorBox")

    @Test
    fun `a host address an account serves is replaced, keeping the source's name and digest`() {
        val routed = HostRoute.route(listOf(d("https://pixeldrain.com/u/abc")), served = { true }) { listOf(torbox) }.single()
        assertEquals("https://store.torbox.app/dl/1", routed.descriptor.url)
        assertEquals("Game.zip", routed.descriptor.fileName)
        assertEquals("abc", routed.descriptor.sha256)
        assertEquals(42L, routed.descriptor.size)
        // The source's headers were for the host; the account's address needs none of them.
        assertNull(routed.descriptor.headers["Referer"])
        assertEquals("via your TorBox", routed.via)
    }

    @Test
    fun `a capture, a GitHub asset, a torrent, an address nothing serves, or several files stay as given`() {
        val asked = mutableListOf<String>()
        val resolve: (String) -> List<WebSessionDownload>? = { asked += it; null }
        val given = listOf(
            d("https://pixeldrain.com/u/a", session = "token"),
            d("https://github.com/x/releases/a.zip", auth = "github"),
            d("magnet:?xt=urn:btih:abc", swarm = DownloadJobs.SWARM_MAGNET),
            d("https://romsite.example/file.zip"),
            d("https://pixeldrain.com/u/b"),
        )
        val routed = HostRoute.route(given, served = { "pixeldrain" in it }, resolve = resolve)
        routed.forEachIndexed { i, r -> assertSame(given[i], r.descriptor); assertNull(r.via) }
        // Only the one plain served address was asked.
        assertEquals(listOf("https://pixeldrain.com/u/b"), asked)
        val two = HostRoute.route(listOf(given[4]), served = { true }) { listOf(torbox, torbox) }.single()
        assertSame(given[4], two.descriptor)
    }

    @Test
    fun `the host's own download says why a debrid account was passed over`() {
        assertEquals("direct from PixelDrain", dev.droidtop.pluginhost.hosts.HostLinks.directLine("PixelDrain", emptyList()))
        assertEquals(
            "direct from PixelDrain (TorBox doesn't serve pixeldrain.com)",
            dev.droidtop.pluginhost.hosts.HostLinks.directLine("PixelDrain", listOf("TorBox doesn't serve pixeldrain.com", "later")),
        )
    }
}
