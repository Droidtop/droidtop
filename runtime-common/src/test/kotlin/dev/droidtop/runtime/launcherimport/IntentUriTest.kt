package dev.droidtop.runtime.launcherimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Intents as the backups store them (IntentUri.kt). */
class IntentUriTest {
    @Test fun `an app launch, in short and long component form`() {
        val intent = IntentUri.parse("#Intent;action=android.intent.action.MAIN;category=android.intent.category.LAUNCHER;launchFlags=0x10200000;component=com.android.chrome/com.google.android.apps.chrome.Main;end")!!
        assertTrue(intent.isAppLaunch)
        assertEquals("com.android.chrome/com.google.android.apps.chrome.Main", intent.component)
        assertEquals(setOf("android.intent.category.LAUNCHER"), intent.categories)
        assertEquals("com.example/com.example.Main", IntentUri.parse("#Intent;component=com.example/.Main;end")!!.component)
    }

    @Test fun `Apex's own action is a launcher action, not an app`() {
        val intent = IntentUri.parse("#Intent;action=com.anddoes.launcher.ACTION;component=com.anddoes.launcher/.Launcher;S.LAUNCHER_ACTION=APP_DRAWER;end")!!
        assertEquals("APP_DRAWER", intent.launcherAction)
        assertFalse(intent.isAppLaunch)
    }

    @Test fun `Nova's named intent`() {
        val action = IntentUri.parse("App drawer\$#Intent;action=com.teslacoilsw.launcher.ACTION;S.com.teslacoilsw.launcher.extra.LAUNCHER_ACTION=APP_DRAWER;end")!!
        assertEquals("APP_DRAWER", action.launcherAction)
        val app = IntentUri.parse("Camera\$#Intent;action=android.intent.action.MAIN;component=com.android.camera2/com.android.camera.CameraLauncher;end")!!
        assertTrue(app.isAppLaunch)
        assertEquals("com.android.camera2/com.android.camera.CameraLauncher", app.component)
    }

    @Test fun `an intent that is not a launch is kept as data only`() {
        val view = IntentUri.parse("#Intent;action=android.intent.action.VIEW;component=com.a/.B;end")!!
        assertFalse(view.isAppLaunch)
        assertNull(IntentUri.parse(""))
        assertNull(IntentUri.parse("not an intent"))
        assertNull(IntentUri.normaliseComponent("/x"))
        assertNull(IntentUri.normaliseComponent("noslash"))
        assertEquals("com.a/com.a.B", IntentUri.normaliseComponent("com.a/.B"))
    }

    @Test fun `escaped values are decoded`() {
        assertEquals("com.a/com.a.B\$C", IntentUri.parse("#Intent;component=com.a/com.a.B%24C;end")!!.component)
    }
}
