package dev.droidtop.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.droidtop.display.SecondaryDisplayContent
import dev.droidtop.library.settings.CompanionPrefs
import dev.droidtop.runtime.CompanionSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The companion on a device with one screen (docs/SPEC.md "The companion's tabs", One screen; Droidtop/tracker#414
 * slice C13): the same [CompanionTabs] every companion host draws, in a pull-down sheet from the top over Standard's
 * home screen, or ([CompanionSheet.EXTRA_TRAY]) a tray panel over Desktop's taskbar at the bottom right. A tap outside,
 * Back, or a pull on the sheet's handle closes it. The first time, a tip says how to open it again.
 */
class CompanionSheetActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tray = intent?.getBooleanExtra(CompanionSheet.EXTRA_TRAY, false) == true
        val mode = if (tray) SecondaryDisplayContent.Mode.DESKTOP else SecondaryDisplayContent.Mode.STANDARD
        setContent {
            dev.droidtop.app.ui.DroidtopTheme(darkTheme = true, gamingThemed = false) {
                val context = LocalContext.current
                LaunchedEffect(Unit) { withContext(Dispatchers.IO) { CompanionPrefs.load(context.applicationContext) } }
                val settings by CompanionPrefs.settings.collectAsState()
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { finish() }
                        .semantics { contentDescription = "Close the companion" },
                ) {
                    val panel = Modifier
                        .clip(RoundedCornerShape(if (tray) 16.dp else 0.dp))
                        .background(MaterialTheme.colorScheme.background)
                        // Taps inside the panel stay in it.
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    if (tray) {
                        Column(
                            modifier = panel
                                .align(Alignment.BottomEnd)
                                .padding(bottom = 0.dp)
                                .width(minOf(maxWidth * 0.9f, 440.dp))
                                .height(maxHeight * 0.8f),
                        ) {
                            SheetTip(settings.sheetTipSeen, tray = true)
                            Box(Modifier.weight(1f)) { CompanionTabs(mode) { CompanionSurfaceHost(null) } }
                        }
                    } else {
                        Column(modifier = panel.align(Alignment.TopCenter).fillMaxWidth().height(maxHeight * 0.88f)) {
                            SheetTip(settings.sheetTipSeen, tray = false)
                            Box(Modifier.weight(1f)) { CompanionTabs(mode) { CompanionSurfaceHost(null) } }
                            CloseHandle { finish() }
                        }
                    }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun SheetTip(seen: Boolean, tray: Boolean) {
        if (seen) return
        val context = LocalContext.current
        CompanionTip(
            if (tray) {
                "This is the companion. Open it again with Companion on the taskbar."
            } else {
                "This is the companion. Open it again from the home screen's menu (press and hold the home screen), " +
                    "or set Swipe down to Companion in Home settings and pull down on the home screen."
            },
        ) { CompanionPrefs.setSheetTipSeen(context.applicationContext) }
    }

    /** The sheet's bottom edge: a tap or a pull up of more than 24dp closes it. */
    @androidx.compose.runtime.Composable
    private fun CloseHandle(onClose: () -> Unit) {
        val pullPx = with(LocalDensity.current) { 24.dp.toPx() }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClickLabel = "Close the companion", onClick = onClose)
                .pointerInput(Unit) {
                    var pulled = 0f
                    detectVerticalDragGestures(onDragStart = { pulled = 0f }) { change, amount ->
                        pulled += amount
                        if (pulled < -pullPx) {
                            change.consume()
                            onClose()
                        }
                    }
                }
                .semantics { contentDescription = "Close the companion" },
        ) {
            Box(Modifier.size(width = 48.dp, height = 5.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.onSurfaceVariant))
        }
    }
}
