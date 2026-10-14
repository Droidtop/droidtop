package dev.droidtop.library.theme

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** A theme that lands while nobody chooses is offered, never imposed (Droidtop/tracker#532). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThemeOfferTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        ThemeOffer.respond(context, use = false)
    }

    @Test
    fun anOfferWaitsAndUsingItActivatesTheTheme() {
        ThemeOffer.offer(context, "art-book-next-es-de", "Art Book Next")
        assertEquals(ThemeOffer.Offer("art-book-next-es-de", "Art Book Next"), ThemeOffer.pending.value)
        assertNull(ThemePrefs.get(context))
        ThemeOffer.respond(context, use = true)
        assertNull(ThemeOffer.pending.value)
        assertEquals("art-book-next-es-de", ThemePrefs.get(context))
    }

    @Test
    fun laterLeavesTheActiveThemeAlone() {
        ThemeOffer.offer(context, "art-book-next-es-de", "Art Book Next")
        ThemeOffer.respond(context, use = false)
        assertNull(ThemeOffer.pending.value)
        assertNull(ThemePrefs.get(context))
    }

    @Test
    fun nothingIsOfferedOverAChosenTheme() {
        ThemePrefs.set(context, "slate-es-de")
        ThemeOffer.offer(context, "art-book-next-es-de", "Art Book Next")
        assertNull(ThemeOffer.pending.value)
    }

    @Test
    fun anUnansweredOfferComesBackAfterARestartUnlessAThemeWasChosen() {
        ThemeOffer.offer(context, "art-book-next-es-de", "Art Book Next")
        // A new process: the in-memory state is gone, the preferences are not.
        val kept = ThemeOffer.pending.value
        ThemeOffer.restore(context)
        assertEquals(kept, ThemeOffer.pending.value)
        ThemePrefs.set(context, "slate-es-de")
        ThemeOffer.restore(context)
        assertNull(ThemeOffer.pending.value)
    }
}
