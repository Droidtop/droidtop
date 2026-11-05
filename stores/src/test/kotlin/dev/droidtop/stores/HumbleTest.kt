package dev.droidtop.stores

import dev.droidtop.library.StoreUpdate
import dev.droidtop.library.stores.StoreSignIn
import dev.droidtop.stores.humble.HumbleApi
import dev.droidtop.stores.humble.HumbleStore
import dev.droidtop.stores.util.SingleFileInstall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Humble Bundle: its orders, the file a device takes, the kept library and the sign-in cookie (Droidtop/tracker#335). */
class HumbleTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val orders = """{
      "KEY1": {"product":{"human_name":"Bundle"},"subproducts":[
        {"machine_name":"game_one","human_name":"Game One","icon":"https://hb/one.png","downloads":[
          {"platform":"mac","download_struct":[{"name":"Mac","url":{"web":"https://dl.humble.com/one.dmg?t=1"},"file_size":10,"md5":"m"}]},
          {"platform":"linux","download_struct":[{"name":"Linux","url":{"web":"https://dl.humble.com/one.tar.gz?t=1"},"file_size":20,"md5":"l"}]},
          {"platform":"windows","download_struct":[{"name":"Download","url":{"web":"https://dl.humble.com/one_setup.zip?gamekey=KEY1&ttl=9"},"file_size":30,"md5":"w"},
                                                    {"name":"BitTorrent only","url":{"bittorrent":"https://x.torrent"}}]}]},
        {"machine_name":"soundtrack","human_name":"Soundtrack","downloads":[]},
        {"machine_name":"","human_name":"Nameless","downloads":[{"platform":"windows","download_struct":[{"url":{"web":"https://dl/x.zip"}}]}]}
      ]},
      "KEY2": {"subproducts":[
        {"machine_name":"game_two","human_name":"Game Two","downloads":[{"platform":"linux","download_struct":[{"url":{"web":"https://dl/two.tar.gz"}}]}]},
        {"machine_name":"book","human_name":"A book","downloads":[{"platform":"ebook","download_struct":[{"url":{"web":"https://dl/book.pdf"}}]}]}
      ]}
    }"""

    @Test
    fun `the orders list each DRM-free game once, with its files that have a direct link, and no books`() {
        assertEquals(listOf("A", "B"), HumbleApi.parseOrderKeys("""[{"gamekey":"A"},{"gamekey":"B"},{"gamekey":""},{}]"""))
        val products = HumbleApi.parseProducts(orders)
        assertEquals(setOf("game_one", "game_two"), products.map { it.id }.toSet())
        val one = products.single { it.id == "game_one" }
        assertEquals("Game One", one.title)
        assertEquals("KEY1", one.gameKey)
        assertEquals(listOf("mac", "linux", "windows"), one.downloads.map { it.platform })
    }

    @Test
    fun `a device takes the Windows build, else the Linux one, and never a book or a soundtrack`() {
        val one = HumbleApi.parseProducts(orders).single { it.id == "game_one" }
        assertEquals("w", HumbleApi.choose(one.downloads)?.md5)
        assertEquals("l", HumbleApi.choose(one.downloads.filter { it.platform != "windows" })?.md5)
        assertNull(HumbleApi.choose(one.downloads.filter { it.platform == "mac" }))
        assertNull(HumbleApi.choose(listOf(HumbleApi.Download("ebook", "PDF", "https://dl/book.pdf", 1, ""), HumbleApi.Download("windows", "OST", "https://dl/ost.mp3?t=1", 1, ""))))
        assertEquals("one_setup.zip", HumbleApi.fileName("https://dl.humble.com/one_setup.zip?gamekey=KEY1&ttl=9"))
        assertTrue(SingleFileInstall.isArchive("one_setup.zip"))
        assertFalse(SingleFileInstall.isArchive("one_setup.exe"))
    }

    @Test
    fun `the kept library round trips without the links' signatures`() {
        val product = HumbleApi.parseProducts(orders).single { it.id == "game_one" }
        val rows = listOf(HumbleStore.Row(product, installPath = "/games/One", installedMd5 = "w", installed = true))
        val back = HumbleStore.decode(HumbleStore.encode(rows)).single()
        assertEquals("/games/One", back.installPath)
        assertTrue(back.installed)
        assertEquals("https://dl.humble.com/one_setup.zip", back.product.downloads.last().url)
        assertEquals(product.downloads.map { it.md5 }, back.product.downloads.map { it.md5 })
        assertEquals(emptyList<Any>(), HumbleStore.decode("not json"))
    }

    @Test
    fun `an update is a different file than the one installed`() {
        assertEquals(StoreUpdate.CURRENT, HumbleStore.updateState("abc", "ABC"))
        assertEquals(StoreUpdate.AVAILABLE, HumbleStore.updateState("abc", "def"))
        assertNull(HumbleStore.updateState("", "def"))
        assertNull(HumbleStore.updateState("abc", null))
    }

    @Test
    fun `the sign-in's session is its cookie, read off the header`() {
        assertEquals("s3cr3t", StoreSignIn.WebPage.cookieValue("a=1; _simpleauth_sess=s3cr3t; b=2", HumbleApi.SESSION_COOKIE))
        assertNull(StoreSignIn.WebPage.cookieValue("a=1", HumbleApi.SESSION_COOKIE))
        assertNull(StoreSignIn.WebPage.cookieValue("_simpleauth_sess=", HumbleApi.SESSION_COOKIE))
        assertNull(StoreSignIn.WebPage.cookieValue(null, HumbleApi.SESSION_COOKIE))
    }

    @Test
    fun `a downloaded file's MD5 is read whole`() {
        val file = temp.newFile("f.bin").apply { writeText("abc") }
        assertEquals("900150983cd24fb0d6963f7d28e17f72", SingleFileInstall.md5Of(file))
    }
}
