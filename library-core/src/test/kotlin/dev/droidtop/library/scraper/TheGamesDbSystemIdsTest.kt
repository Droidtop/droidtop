package dev.droidtop.library.scraper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [TheGamesDbSystemIds.forSystemId]: ES-DE system ids to TheGamesDB platform ids, whatever the case. */
class TheGamesDbSystemIdsTest {
    @Test
    fun `known systems map to their platform id`() {
        assertEquals("4939", TheGamesDbSystemIds.forSystemId("vectrex"))
        assertEquals("4941", TheGamesDbSystemIds.forSystemId("coco"))
    }

    @Test
    fun `the lookup ignores case and a shared platform has one id`() {
        assertEquals("4941", TheGamesDbSystemIds.forSystemId("TRS-80"))
        assertEquals(TheGamesDbSystemIds.forSystemId("coco"), TheGamesDbSystemIds.forSystemId("trs-80"))
    }

    @Test
    fun `an unknown system has no id`() {
        assertNull(TheGamesDbSystemIds.forSystemId("nonesuch"))
    }
}
