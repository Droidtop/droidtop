package dev.droidtop.runtime.launcherimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** The SharedPreferences `<map>` reader for both launchers' backups (PrefsXml.kt). */
class PrefsXmlTest {
    @Test fun `reads every value type a preferences file holds`() {
        val map = PrefsXml.parse(
            """<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
            <map>
                <string name="desktop_grid">6x5 subgrid</string>
                <int name="dock_grid_cols" value="5" />
                <long name="last" value="1700000000000" />
                <float name="label_size" value="1.25" />
                <boolean name="dock_enable" value="false" />
                <set name="hidden_apps">
                    <string>com.a/.Main</string>
                    <string>com.b/com.b.Main</string>
                </set>
                <null name="gone" />
                <string name="empty"></string>
            </map>""",
        )
        assertEquals("6x5 subgrid", map["desktop_grid"])
        assertEquals(5, map["dock_grid_cols"])
        assertEquals(1700000000000L, map["last"])
        assertEquals(1.25f, map["label_size"])
        assertEquals(false, map["dock_enable"])
        assertEquals(setOf("com.a/.Main", "com.b/com.b.Main"), map["hidden_apps"])
        assertFalse("gone" in map)
        assertEquals("", map["empty"])
    }

    @Test fun `entities are decoded and nothing else is expanded`() {
        val map = PrefsXml.parse(
            """<!DOCTYPE map [<!ENTITY x "boom">]><map><string name="a&amp;b">&lt;Tom&apos;s&gt; &#38; &#x41; &x;</string></map>""",
        )
        assertEquals("<Tom's> & A &x;", map["a&b"])
    }

    @Test fun `typed reads accept a value stored as another type`() {
        val prefs = Prefs(mapOf("i" to "7", "l" to 8L, "b" to "true", "s" to "a;b, c"))
        assertEquals(7, prefs.int("i"))
        assertEquals(8, prefs.int("l"))
        assertEquals(true, prefs.bool("b"))
        assertEquals(setOf("a", "b", "c"), prefs.stringSet("s"))
        assertNull(prefs.int("missing"))
    }
}
