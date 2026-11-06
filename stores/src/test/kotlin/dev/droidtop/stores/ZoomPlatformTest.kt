package dev.droidtop.stores

import dev.droidtop.library.StoreUpdate
import dev.droidtop.stores.zoom.ZoomApi
import dev.droidtop.stores.zoom.ZoomStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ZOOM Platform: the sign-in token, the library pages, the installer files, the kept library and updates (Droidtop/tracker#335). */
class ZoomPlatformTest {
    @Test
    fun `the sign-in return address carries the launcher token`() {
        assertEquals("abc.DEF-1", ZoomApi.tokenIn("https://www.zoom-platform.com/li/return?li_token=abc.DEF-1"))
        assertEquals("a b", ZoomApi.tokenIn("https://www.zoom-platform.com/?x=1&li_token=a%20b#top"))
        assertNull(ZoomApi.tokenIn("https://www.zoom-platform.com/login?li=droidtop&return_li_token=true"))
        assertNull(ZoomApi.tokenIn("https://www.zoom-platform.com/?li_token="))
        assertTrue(ZoomApi.signInUrl.startsWith("https://www.zoom-platform.com/login?li=droidtop&return_li_token=true"))
    }

    @Test
    fun `the account name is read from loggedin`() {
        assertEquals("Mech", ZoomApi.nameOf("""{"name":"Mech"}"""))
        assertNull(ZoomApi.nameOf("""{"name":""}"""))
        assertNull(ZoomApi.nameOf("not json"))
    }

    @Test
    fun `a library page gives its games, its number and how many there are`() {
        val page = ZoomApi.pageOf(
            """{"current_page":1,"total_pages":3,"games":[
              {"id":101,"slug":"one","name":"Game One","poster_url":"https://cdn/one.jpg","store_url":"https://www.zoom-platform.com/product/one","operating_systems":["windows","Linux"]},
              {"id":102,"slug":"two-slug","name":"","poster_url":"http://insecure/two.jpg","operating_systems":[]},
              {"slug":"no-id","name":"No id"}
            ]}""",
        )
        assertEquals(1, page.current)
        assertEquals(3, page.total)
        assertEquals(listOf("101", "102"), page.games.map { it.id })
        assertEquals("Game One", page.games[0].title)
        assertEquals(listOf("windows", "linux"), page.games[0].systems)
        assertEquals("two-slug", page.games[1].title)
        assertNull(page.games[1].posterUrl)
        assertEquals(1, ZoomApi.pageOf("""{"games":[]}""").total)
    }

    @Test
    fun `the installers are the Windows files, else the Linux ones, never the extras`() {
        val both = """{
          "manual":[{"id":"m1","name":"Manual","filename":"manual.pdf","total_size":5}],
          "soundtrack":[{"id":"s1","name":"OST","filename":"ost.zip","total_size":9}],
          "windows":[{"id":"w1","name":"Setup","filename":"setup_game.exe","total_size":1000},
                     {"id":"w2","name":"Setup part 2","filename":"setup_game-1.bin","total_size":2000},
                     {"id":"w3","name":"Bad","filename":"../../evil.exe","total_size":1}],
          "linux":[{"id":"l1","name":"Linux","filename":"game.sh","total_size":900}]
        }"""
        val files = ZoomApi.installersOf(both)
        assertEquals(listOf("w1", "w2", "w3"), files.map { it.id })
        assertEquals(listOf("setup_game.exe", "setup_game-1.bin", "evil.exe"), files.map { it.fileName })
        assertEquals(3000L + 1L, files.sumOf { it.sizeBytes })
        assertEquals(listOf("l1"), ZoomApi.installersOf("""{"windows":[],"linux":[{"id":"l1","filename":"game.sh","total_size":900}]}""").map { it.id })
        assertEquals(emptyList<ZoomApi.File>(), ZoomApi.installersOf("""{"manual":[{"id":"m1","filename":"m.pdf"}]}"""))
        assertEquals(emptyList<ZoomApi.File>(), ZoomApi.installersOf("nope"))
    }

    @Test
    fun `a download link is https only`() {
        assertEquals("https://dl.zoom/x.exe?sig=1", ZoomApi.linkOf("""{"url":"https://dl.zoom/x.exe?sig=1"}"""))
        assertNull(ZoomApi.linkOf("""{"url":"http://dl.zoom/x.exe"}"""))
        assertNull(ZoomApi.linkOf("{}"))
    }

    @Test
    fun `an update is a change in the installer files`() {
        val files = listOf(ZoomApi.File("w1", "Setup", "setup.exe", 10), ZoomApi.File("w2", "Part", "setup-1.bin", 20))
        val installed = ZoomApi.signature(files)
        assertEquals("setup.exe:10|setup-1.bin:20", installed)
        assertEquals(StoreUpdate.CURRENT, ZoomStore.updateState(installed, installed))
        assertEquals(StoreUpdate.AVAILABLE, ZoomStore.updateState(installed, ZoomApi.signature(files.take(1))))
        assertNull(ZoomStore.updateState("", installed))
        assertNull(ZoomStore.updateState(installed, null))
    }

    @Test
    fun `the kept library reads back as written`() {
        val rows = listOf(
            ZoomStore.Row(ZoomApi.Game("101", "Game One", "https://cdn/one.jpg", "https://www.zoom-platform.com/product/one", listOf("windows")),
                installPath = "/g/Game One", installedFrom = "setup.exe:10", installed = true, fileCount = 2, listed = "setup.exe:10"),
            ZoomStore.Row(ZoomApi.Game("102", "Two", null, null, emptyList())),
        )
        assertEquals(rows, ZoomStore.decode(ZoomStore.encode(rows)))
        assertEquals(emptyList<ZoomStore.Row>(), ZoomStore.decode("broken"))
    }
}
