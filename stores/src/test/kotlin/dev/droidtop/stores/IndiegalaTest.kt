package dev.droidtop.stores

import dev.droidtop.library.StoreUpdate
import dev.droidtop.library.stores.StoreSignIn
import dev.droidtop.stores.indiegala.IndiegalaApi
import dev.droidtop.stores.indiegala.IndiegalaStore
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Indiegala: its collection, a product's downloads and the kept library (Droidtop/tracker#335). The answers are in the
 * shape Lacro59's playnite-indiegala-plugin reads (`GalaData.cs`, `ApiGameDetails.cs`), written here.
 */
class IndiegalaTest {
    private val userInfo = """{"user_found":"true","showcase_content":{"status_code":200,"content":{"user_collection":[
      {"id":1,"prod_name":"Grim Tales","prod_slugged_name":"grim-tales","prod_id_key_name":"key1","prod_dev_namespace":"devone",
       "prod_dev_image":"cover.jpg","galaclient_only":0,"version":[{"id":3,"version":"1.0.2"},{"id":9,"version":"1.1"},{"id":5}]},
      {"id":2,"prod_name":"Client Only","prod_slugged_name":"client-only","prod_id_key_name":"key2","prod_dev_namespace":"devtwo","galaclient_only":1},
      {"id":3,"prod_name":"No key"},
      {"id":4,"prod_name":"Grim Tales again","prod_id_key_name":"key1"}
    ]}}}"""

    @Test
    fun `the collection lists each game once with its cover, newest version and whether only the client has it`() {
        val products = IndiegalaApi.parseCollection(userInfo)!!
        assertEquals(listOf("key1", "key2"), products.map { it.key })
        val grim = products[0]
        assertEquals("Grim Tales", grim.title)
        assertEquals("https://www.indiegalacdn.com/imgs/devs/devone/products/key1/prodcover/cover.jpg", grim.coverUrl)
        assertEquals("1.1", grim.version)
        assertFalse(grim.clientOnly)
        assertTrue(products[1].clientOnly)
        assertNull(products[1].coverUrl)
        assertEquals("", products[1].version)
    }

    @Test
    fun `nobody signed in is no collection, an empty one is empty, and the data wrapper reads the same`() {
        assertNull(IndiegalaApi.parseCollection("""{"user_found":"false"}"""))
        assertNull(IndiegalaApi.parseCollection("not json"))
        assertEquals(emptyList<Any>(), IndiegalaApi.parseCollection("""{"user_found":"true"}"""))
        val wrapped = """{"data":{"user_found":"true","showcase_content":{"content":{"user_collection":[{"prod_name":"A","prod_id_key_name":"k"}]}}}}"""
        assertEquals(listOf("k"), IndiegalaApi.parseCollection(wrapped)!!.map { it.key })
    }

    @Test
    fun `a product's details give its https download links and newest version`() {
        val d = IndiegalaApi.parseDownloads(
            """{"status":"ok","product_data":{"downloadable_versions":{"win":"https://content.indiegala.com/a/Grim_Setup.zip?t=1","lin":"http://insecure/x.tar.gz","mac":""},
               "version":[{"id":2,"version":"2.0"}]}}""",
        )!!
        assertEquals("https://content.indiegala.com/a/Grim_Setup.zip?t=1", d.windows)
        assertNull("only https links are taken", d.linux)
        assertEquals("2.0", d.version)
        assertEquals("Grim_Setup.zip", IndiegalaApi.fileName(d.windows!!))
        assertNull(IndiegalaApi.parseDownloads("""{"status":"error"}"""))
        assertEquals("7", IndiegalaApi.newestVersion(JSONArray("""[{"id":7}]""")))
    }

    @Test
    fun `the kept library round trips and an update is another version`() {
        val product = IndiegalaApi.parseCollection(userInfo)!!.first()
        val rows = listOf(IndiegalaStore.Row(product, installPath = "/games/Grim", installedVersion = "1.0.2", installed = true))
        val back = IndiegalaStore.decode(IndiegalaStore.encode(rows)).single()
        assertEquals(rows.single(), back)
        assertEquals(emptyList<Any>(), IndiegalaStore.decode("nope"))
        assertEquals(StoreUpdate.AVAILABLE, IndiegalaStore.updateState("1.0.2", "1.1"))
        assertEquals(StoreUpdate.CURRENT, IndiegalaStore.updateState("1.1", "1.1"))
        assertNull(IndiegalaStore.updateState("", "1.1"))
    }

    @Test
    fun `the sign-in keeps the whole cookie header`() {
        assertEquals("a=1; auth=xyz", StoreSignIn.WebPage.cookieValue(" a=1; auth=xyz ", StoreSignIn.WebPage.ALL_COOKIES))
        assertNull(StoreSignIn.WebPage.cookieValue("  ", StoreSignIn.WebPage.ALL_COOKIES))
        assertNull(StoreSignIn.WebPage.cookieValue(null, StoreSignIn.WebPage.ALL_COOKIES))
    }
}
