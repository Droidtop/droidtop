package org.pocketworkstation.pckeyboard

import android.view.View
import android.widget.EditText
import android.widget.ScrollView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyboardSetupTest {
    @Test fun setupFitsANarrowViewportAndScrollsToItsInstructions() {
        Robolectric.buildActivity(Main::class.java).setup().use { controller ->
            val activity = controller.get()
            val scroll = activity.findViewById<View>(android.R.id.content)
                .let { (it as android.view.ViewGroup).getChildAt(0) as ScrollView }
            scroll.measure(
                View.MeasureSpec.makeMeasureSpec(280, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY),
            )
            scroll.layout(0, 0, 280, 240)
            val content = scroll.getChildAt(0)
            assertTrue(content.measuredHeight > scroll.measuredHeight)
            for (id in listOf(R.id.main_setup_btn_configure_imes, R.id.main_setup_btn_set_ime,
                R.id.main_setup_btn_input_lang, R.id.main_setup_btn_get_dicts, R.id.main_setup_btn_settings)) {
                val button = activity.findViewById<View>(id)
                assertTrue(button.width > 0)
                assertTrue(button.width <= scroll.width)
            }
            val field = activity.findViewById<EditText>(R.id.main_setup_edit_test)
            assertEquals("", field.text.toString())
            assertEquals(activity.getString(R.string.main_setup_test), field.hint.toString())
        }
    }

    @Test fun dictionariesOpensTheExistingManagerThroughSettings() {
        Robolectric.buildActivity(Main::class.java).setup().use { controller ->
            val activity = controller.get()
            activity.findViewById<View>(R.id.main_setup_btn_get_dicts).performClick()
            val intent = shadowOf(activity).nextStartedActivity
            assertEquals(LatinIMESettings::class.java.name, intent.component?.className)
            assertTrue(intent.getBooleanExtra(LatinIMESettings.EXTRA_OPEN_DICTIONARIES, false))
            assertNull(intent.data)
        }
    }
}
