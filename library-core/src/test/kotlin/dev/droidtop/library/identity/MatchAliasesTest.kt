package dev.droidtop.library.identity

import dev.droidtop.library.scraper.PcGameIds
import dev.droidtop.library.scraper.PcMatch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MatchAliasesTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun `a name is learned ignoring case, spacing, punctuation and a leading article`() {
        assertEquals(MatchAliases.keyOf("The Witch's House"), MatchAliases.keyOf("witchs-house"))
        assertEquals("", MatchAliases.keyOf("  -- "))
    }

    @Test fun `a pick teaches its name, a later search of the same name finds it, and a new pick replaces it`() = runBlocking {
        val first = PcMatch("Seven Day W Yandere Girl", "IGDB", year = 2021, ids = PcGameIds(igdbId = 1))
        MatchAliases.learn(context, "Seven Day W Yandere Girl", first)
        assertEquals(first, MatchAliases.find(context, "SevenDayWYandereGirl"))

        val second = first.copy(ids = PcGameIds(igdbId = 2))
        MatchAliases.learn(context, "seven day w yandere girl", second)
        assertEquals(second, MatchAliases.find(context, "Seven Day W Yandere Girl"))
        assertNull(MatchAliases.find(context, "Another Game"))
    }

    @Test fun `a name with nothing to compare teaches nothing`() = runBlocking {
        MatchAliases.learn(context, "!!", PcMatch("X", "IGDB"))
        assertNull(MatchAliases.find(context, "!!"))
    }
}
