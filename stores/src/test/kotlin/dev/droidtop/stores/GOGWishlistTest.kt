package dev.droidtop.stores

import dev.droidtop.stores.gog.GOGWishlist
import org.junit.Assert.assertEquals
import org.junit.Test

class GOGWishlistTest {
    @Test
    fun `the wished ids are the true entries, sorted`() {
        val body = """{"wishlist":{"1207658691":true,"1207666793":false,"1045342200":true}}"""
        assertEquals(listOf("1045342200", "1207658691"), GOGWishlist.parseIds(body))
    }

    @Test
    fun `an empty or missing wishlist is no ids`() {
        assertEquals(emptyList<String>(), GOGWishlist.parseIds("""{"wishlist":{}}"""))
        assertEquals(emptyList<String>(), GOGWishlist.parseIds("""{}"""))
    }
}
