package dev.droidtop.app

import dev.droidtop.runtime.LauncherSelfFilter
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * droidtop's widgets as the manifest declares them (Droidtop/tracker#563). The rig found seven of the nine
 * missing from Standard's widget picker (2026-10-16, build 0.2.0-dev.1994): the launcher hid droidtop's own
 * package except for a hand list of classes. These tie the manifest, [DroidtopWidgets.PROVIDERS] and the
 * launcher's rule ([LauncherSelfFilter]) together.
 */
class WidgetManifestTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText().replace(Regex("(?s)<!--.*?-->"), "")

    /** Each `<receiver>` that declares the app widget provider metadata: its class and its label attribute. */
    private val widgetReceivers: Map<String, String?> =
        Regex("(?s)<receiver\\b(.*?)</receiver>").findAll(manifest)
            .map { it.groupValues[1] }
            .filter { "android.appwidget.provider" in it }
            .associate { body ->
                val name = Regex("android:name=\"([^\"]+)\"").find(body)!!.groupValues[1]
                val label = Regex("android:label=\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                name to label
            }

    @Test
    fun everyDeclaredWidgetIsOneDroidtopRefreshes() {
        assertEquals(DroidtopWidgets.PROVIDERS.map { it.name }.toSet(), widgetReceivers.keys)
    }

    @Test
    fun everyWidgetHasItsOwnPickerLabel() {
        widgetReceivers.forEach { (name, label) ->
            assertTrue("$name has no android:label, so pickers show the app's name", label?.startsWith("@string/widget_") == true)
        }
        assertEquals("two widgets share a label", widgetReceivers.size, widgetReceivers.values.toSet().size)
    }

    @Test
    fun standardShowsEveryWidgetWhileItHidesDroidtopsPackage() {
        widgetReceivers.keys.forEach { name ->
            assertTrue("$name would be filtered out of Standard's widget picker", LauncherSelfFilter.shown(name, isWidgetProvider = true))
        }
    }
}
