package dev.droidtop.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import dev.droidtop.app.update.AppSelfUpdate
import dev.droidtop.library.settings.FeatureSteps
import dev.droidtop.shell.gamepad.LocalShellWindow
import dev.droidtop.shell.gamepad.Measure
import dev.droidtop.shell.gamepad.MenuTokens
import dev.droidtop.shell.gamepad.NoticeDialogHost
import dev.droidtop.shell.gamepad.TypeRole
import dev.droidtop.shell.gamepad.currentShellWindow
import dev.droidtop.shell.gamepad.input.ownPadButtons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What's new (docs/SPEC.md 7b, "Update onboarding", Droidtop/tracker#727): the changelog of every build since the one
 * the person last saw, drawn in onboarding's scaffold, then the app-wide feature steps as the shell's confirm card over
 * it. Opened by [WhatsNew.onShellResumed] after an update, and from Settings > About droidtop > What's new with the
 * whole history ([EXTRA_ALL]), where it only shows the changelog. Closing it (Done, Back or B) is what counts as seen.
 */
class WhatsNewActivity : AppCompatActivity() {
    // The shell's input pipeline, as onboarding's (docs/SPEC.md 6e): the stick and hat move like the D-pad.
    private val padGate = dev.droidtop.shell.gamepad.input.PadGate(deliver = { event -> deliverKey(event) }, window = { window })

    private fun deliverKey(event: android.view.KeyEvent): Boolean = super.dispatchKeyEvent(event)

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean = padGate.dispatchKey(event)

    override fun dispatchGenericMotionEvent(event: android.view.MotionEvent): Boolean =
        padGate.dispatchMotion(event) || super.dispatchGenericMotionEvent(event)

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        padGate.noteTouch(event)
        return super.dispatchTouchEvent(event)
    }

    override fun onPause() {
        padGate.cancel()
        super.onPause()
    }

    private var closing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val all = intent.getBooleanExtra(EXTRA_ALL, false)
        dev.droidtop.shell.gamepad.input.ControllerLayouts.attach(this)
        setContent {
            dev.droidtop.app.ui.DroidtopTheme(gamingThemed = dev.droidtop.app.ui.rememberGamingThemed()) {
                CompositionLocalProvider(LocalShellWindow provides currentShellWindow()) {
                    var builds by remember { mutableStateOf<List<ChangelogBuild>?>(null) }
                    LaunchedEffect(Unit) {
                        builds = withContext(Dispatchers.IO) {
                            val bundled = WhatsNew.builds(applicationContext)
                            if (all) {
                                bundled
                            } else {
                                WhatsNew.since(bundled, WhatsNew.lastSeen(applicationContext), AppSelfUpdate.installedVersionCode(applicationContext))
                            }
                        }
                    }
                    BackHandler { close(all) }
                    Box(Modifier.fillMaxSize().ownPadButtons { close(all) }) {
                        OnboardingScaffold(
                            title = "What's new",
                            body = null,
                            progress = null,
                            onBack = null,
                            primary = StepAction("Done") { close(all) },
                        ) {
                            val shown = builds
                            when {
                                shown == null -> Unit
                                shown.isEmpty() -> StepNote("This build carries no changelog")
                                else -> shown.forEach { build ->
                                    StepSectionLabel(build.versionName)
                                    build.sections.forEach { (section, lines) ->
                                        Text(section, color = MenuTokens.OnSurface, style = TypeRole.body)
                                        lines.forEach { line ->
                                            Text(
                                                "• $line",
                                                color = MenuTokens.OnSurfaceMuted,
                                                style = TypeRole.body,
                                                modifier = Modifier.widthIn(max = Measure.bodyMaxWidth),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        // The app-wide feature steps are asked here, over the changelog they follow.
                        NoticeDialogHost()
                    }
                }
            }
        }
    }

    /** Closing after an update: this version is seen, then each app-wide step not yet seen shows once. */
    private fun close(all: Boolean) {
        if (closing) return
        closing = true
        if (all) {
            finish()
            return
        }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { WhatsNew.markCurrentSeen(applicationContext) }
            FeatureSteps.showAt(this@WhatsNewActivity, place = null)
            finish()
        }
    }

    companion object {
        /** Every build's changelog, from Settings, rather than those since the last one seen. */
        const val EXTRA_ALL = "dev.droidtop.app.WhatsNew.ALL"

        fun intent(context: Context, all: Boolean): Intent =
            Intent(context, WhatsNewActivity::class.java).putExtra(EXTRA_ALL, all)
    }
}
