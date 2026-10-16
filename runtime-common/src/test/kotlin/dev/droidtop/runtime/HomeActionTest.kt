package dev.droidtop.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The one stored form of a gesture's or an icon swipe's action (HomeAction.kt). */
class HomeActionTest {
    @Test fun `actions and apps round-trip`() {
        for (action in listOf(HomeAction.Droidtop("OPEN_DRAWER"), HomeAction.Launch("com.a/com.a.Main"))) {
            assertEquals(action, HomeAction.decode(action.encoded))
        }
        assertEquals("app:com.a/com.a.Main", HomeAction.Launch("com.a/com.a.Main").encoded)
        // A gesture slot's stored enum name reads as the same action.
        assertEquals(HomeAction.Droidtop("LOCK_SCREEN"), HomeAction.decode("LOCK_SCREEN"))
    }

    @Test fun `nothing set is null`() {
        assertNull(HomeAction.decode(null))
        assertNull(HomeAction.decode(" "))
        assertNull(HomeAction.decode("NONE"))
        assertNull(HomeAction.decode("app:"))
        assertNull(HomeAction.decode("app:nocomponent"))
    }
}
