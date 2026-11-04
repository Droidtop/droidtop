package dev.droidtop.shell.gamepad.pc

import org.junit.Assert.assertEquals
import org.junit.Test

/** The search a link sheet and the match picker start from, and what the person types over it (Droidtop/tracker#861). */
class SearchTextTest {
    @Test
    fun typedWordsAreSearchedAndBlankKeepsTheSuggestion() {
        assertEquals("Bare Witness", searchText("  Bare Witness ", "BareWitness-0.3-pc"))
        assertEquals("BareWitness-0.3-pc", searchText("   ", "BareWitness-0.3-pc"))
        assertEquals("Search for: A House In The Rift", searchRowTitle("A House In The Rift"))
    }
}
