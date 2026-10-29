package dev.droidtop.pluginhost

import android.content.SharedPreferences
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ContainedPreferencesTest {
    private val now = Executor { it.run() }
    private val folder: File = Files.createTempDirectory("prefs").toFile()

    private fun store(name: String = "rig") = ContainedPreferences.of(folder, name, now, now)

    @Test
    fun valuesOfEveryTypeAreReadBackAndKeptInAndroidsOwnFormat() {
        assertTrue(
            store().edit().putString("s", "a <b> & \"c\"").putInt("i", 7).putLong("l", 1L shl 40).putFloat("f", 1.5f)
                .putBoolean("b", true).putStringSet("set", setOf("x", "y")).commit(),
        )
        val text = File(folder, "shared_prefs/rig.xml").readText()
        assertTrue(text, text.contains("<int name=\"i\" value=\"7\" />"))
        assertTrue(text, text.contains("<string name=\"s\">a &lt;b&gt; &amp; &quot;c&quot;</string>"))
        // A new process reads what this one wrote.
        val again = ContainedPreferences.read(File(folder, "shared_prefs/rig.xml"))
        assertEquals("a <b> & \"c\"", again["s"])
        assertEquals(7, again["i"])
        assertEquals(1L shl 40, again["l"])
        assertEquals(1.5f, again["f"])
        assertEquals(true, again["b"])
        assertEquals(setOf("x", "y"), again["set"])
    }

    @Test
    fun aFileAndroidWroteIsReadAndABackupFromAnUnfinishedWriteWins() {
        val dir = File(folder, "shared_prefs").apply { mkdirs() }
        File(dir, "flutter.xml").writeText(
            """
            <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
            <map>
                <string name="flutter.name">old</string>
                <boolean name="flutter.on" value="false" />
            </map>
            """.trimIndent(),
        )
        assertEquals("old", store("flutter").getString("flutter.name", null))
        File(dir, "after.xml").writeText("<map><string name=\"k\">half-writ</string></map>")
        File(dir, "after.xml.bak").writeText("<map><string name=\"k\">whole</string></map>")
        assertEquals("whole", ContainedPreferences.read(File(dir, "after.xml"))["k"])
    }

    @Test
    fun aNullValueRemovesAndClearComesBeforeThePutsOfItsEditor() {
        val prefs = store()
        prefs.edit().putString("a", "1").putString("b", "2").commit()
        prefs.edit().putString("a", null).commit()
        assertFalse(prefs.contains("a"))
        prefs.edit().putString("c", "3").clear().commit()
        assertEquals(mapOf("c" to "3"), prefs.all)
    }

    @Test
    fun listenersHearEachChangedKeyAndAClearAsNull() {
        val prefs = store()
        prefs.edit().putString("a", "1").commit()
        val heard = ArrayList<String?>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> heard += key }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        prefs.edit().putString("a", "1").putString("b", "2").apply()
        assertEquals(listOf("b"), heard)
        heard.clear()
        prefs.edit().clear().commit()
        assertEquals(listOf<String?>(null), heard)
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        heard.clear()
        prefs.edit().putString("z", "1").commit()
        assertTrue(heard.isEmpty())
    }

    @Test
    fun oneInstancePerFileAndDeleteRemovesTheFile() {
        assertSame(store(), store())
        store().edit().putInt("i", 1).commit()
        assertTrue(ContainedPreferences.delete(folder, "rig"))
        assertFalse(File(folder, "shared_prefs/rig.xml").exists())
        assertNull(store().all["i"])
    }
}
