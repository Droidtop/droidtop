package dev.droidtop.library.scraper

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The "Fetch box art and details for your games?" question is asked once
 * (Droidtop/tracker#174): once answered, either way, a finished walk never
 * raises it again, and Settings can change the stored answer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScrapeOfferTest {
    private lateinit var context: Context
    private var started = 0

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        ScrapeOffer.set(context, ScrapeOffer.Answer.ASK)
        started = 0
        ScrapeOffer.starter = { started++ }
    }

    @After
    fun restoreStarter() {
        ScrapeOffer.starter = { LibraryScrapeJob.start(it, "Scrape all systems") }
    }

    @Test
    fun theDecisionFollowsTheStoredAnswerAndTheGamesFound() {
        assertEquals(ScrapeOffer.Action.ASK, ScrapeOffer.decide(ScrapeOffer.Answer.ASK, 12))
        assertEquals(ScrapeOffer.Action.START, ScrapeOffer.decide(ScrapeOffer.Answer.FETCH, 12))
        assertEquals(ScrapeOffer.Action.NOTHING, ScrapeOffer.decide(ScrapeOffer.Answer.NEVER, 12))
        assertEquals(ScrapeOffer.Action.NOTHING, ScrapeOffer.decide(ScrapeOffer.Answer.ASK, 0))
    }

    @Test
    fun theFirstFinishedWalkAsksAndAnEmptyLibraryDoesNot() {
        ScrapeOffer.walkFinished(context, gameCount = 0)
        assertFalse(ScrapeOffer.pending.value)
        ScrapeOffer.walkFinished(context, gameCount = 40)
        assertTrue(ScrapeOffer.pending.value)
    }

    @Test
    fun decliningIsRememberedAndNeverAskedAgain() {
        ScrapeOffer.walkFinished(context, 40)
        ScrapeOffer.respond(context, fetch = false)
        assertFalse(ScrapeOffer.pending.value)
        assertEquals(ScrapeOffer.Answer.NEVER, ScrapeOffer.current(context))

        ScrapeOffer.walkFinished(context, 40)
        assertFalse("an answered question is not asked again", ScrapeOffer.pending.value)
        ScrapeOffer.restore(context)
        assertFalse(ScrapeOffer.pending.value)
        assertEquals(0, started)
    }

    @Test
    fun acceptingStartsTheScrapeOnceAndLaterWalksStartItWithoutAsking() {
        ScrapeOffer.walkFinished(context, 40)
        ScrapeOffer.respond(context, fetch = true)
        assertEquals(1, started)
        assertEquals(ScrapeOffer.Answer.FETCH, ScrapeOffer.current(context))

        ScrapeOffer.walkFinished(context, 41)
        assertFalse(ScrapeOffer.pending.value)
        assertEquals(2, started)
    }

    @Test
    fun settingsCanChangeTheAnswerAndSettingAskReArmsTheQuestion() {
        ScrapeOffer.respond(context, fetch = false)
        ScrapeOffer.set(context, ScrapeOffer.Answer.ASK)
        ScrapeOffer.walkFinished(context, 40)
        assertTrue(ScrapeOffer.pending.value)
    }
}
