package dev.droidtop.library

import android.content.Context
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class NativeAppLaunchTest {
    @Test
    fun anAppWithoutALaunchActivityIsUnavailableAndDoesNotPretendToLaunch() = runTest {
        val context: Context = RuntimeEnvironment.getApplication()
        val entry = LibraryEntry("dev.example.removed", "Removed game", LibraryEntryKind.NATIVE_ANDROID_APP)
        val state = NativeAppLaunch.state(context, entry.id)
        assertFalse(state.available)
        assertEquals(NativeAppLaunch.MISSING, state.reason)
        try {
            NativeAppProvider(context).launch(entry)
            fail("A missing launch target must fail visibly, not return success")
        } catch (failure: UserFacingException) {
            assertEquals(NativeAppLaunch.MISSING, failure.message)
            assertEquals(NativeAppLaunch.MISSING, userFacingErrorMessage(failure))
        }
    }
}
