package dev.droidtop.app

import dev.droidtop.app.ExternalGameLaunch.Decision
import dev.droidtop.pluginhost.GrantState
import org.junit.Assert.assertEquals
import org.junit.Test

/** Another app asking droidtop to start a game (Droidtop/tracker#124): the plugin API's three states, asked on first use. */
class ExternalGameLaunchTest {

    @Test
    fun `an allowed app starts the game, whatever was put off this run`() {
        assertEquals(Decision.LAUNCH, ExternalGameLaunch.decide(GrantState.GRANTED, notNowThisRun = false))
        assertEquals(Decision.LAUNCH, ExternalGameLaunch.decide(GrantState.GRANTED, notNowThisRun = true))
    }

    @Test
    fun `a blocked app is refused without asking`() {
        assertEquals(Decision.BLOCKED, ExternalGameLaunch.decide(GrantState.DENIED, notNowThisRun = false))
    }

    @Test
    fun `an app nobody answered for is asked`() {
        assertEquals(Decision.ASK, ExternalGameLaunch.decide(null, notNowThisRun = false))
        assertEquals(Decision.ASK, ExternalGameLaunch.decide(GrantState.ASK, notNowThisRun = false))
    }

    @Test
    fun `Not now is not asked again in the same run`() {
        assertEquals(Decision.NOT_NOW, ExternalGameLaunch.decide(GrantState.ASK, notNowThisRun = true))
        assertEquals(Decision.NOT_NOW, ExternalGameLaunch.decide(null, notNowThisRun = true))
    }

    @Test
    fun `Not now is kept apart from a plugin of the same id`() {
        assertEquals("app:com.example.frontend", ExternalGameLaunch.refusalKey("com.example.frontend"))
    }
}
