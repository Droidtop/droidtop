package dev.droidtop.app

import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.widget.TextView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.droidtop.runtime.ScreenRoles
import dev.droidtop.runtime.DisplayControls
import dev.droidtop.runtime.DisplayOutputRepository
import dev.droidtop.runtime.ScreenClass
import dev.droidtop.runtime.ScreenNaming
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * System > Display's cards (docs/SPEC.md "The companion's tabs", Displays; Droidtop/tracker#414 slice C14): one per
 * screen, with its name, kind, size and role, then what droidtop can change on it through [DisplayControls], the one
 * write path: the main screen's brightness (Android's setting), the companion's own brightness (its window, never
 * below 10%) and Turn off, and the refresh rate where the screen lists more than one (through the helper app). What a
 * screen cannot use is not drawn; one line says so. Identify screens shows a large number on each screen.
 */
internal data class DisplayCard(
    val id: Int,
    val name: String,
    val builtIn: Boolean,
    val widthPx: Int,
    val heightPx: Int,
    val role: DisplayRole,
    val rates: List<Float>,
    val rate: Float?,
)

internal enum class DisplayRole(val label: String) { MAIN("Main screen"), COMPANION("Companion"), OTHER("Other screen") }

/** A screen's role: the main screen (where the shell is), the companion's, or another. Pure. */
internal fun displayRole(displayId: Int, mainDisplayId: Int, companionDisplayId: Int?): DisplayRole = when (displayId) {
    mainDisplayId -> DisplayRole.MAIN
    companionDisplayId -> DisplayRole.COMPANION
    else -> DisplayRole.OTHER
}

/** "Built-in, 1080x1920, portrait": the card's facts line. Pure. */
internal fun displayFacts(card: DisplayCard): String =
    "${if (card.builtIn) "Built-in" else "External"}, ${card.widthPx}x${card.heightPx}, ${if (card.heightPx > card.widthPx) "portrait" else "landscape"}"

/** The refresh rates worth offering: distinct, rounded to a tenth, highest first; none when there is only one. Pure. */
internal fun offeredRates(rates: List<Float>): List<Float> =
    rates.map { Math.round(it * 10) / 10f }.distinct().sortedDescending().takeIf { it.size > 1 }.orEmpty()

@Composable
internal fun CompanionDisplayCards() {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    val ownDisplay = view.display?.displayId
    val cards by produceState<List<DisplayCard>?>(null, version) {
        value = withContext(Dispatchers.IO) { readCards(context.applicationContext) }
    }
    val status = remember { mutableStateMapOf<Int, String>() }
    // The numbers come down with the page: a Presentation left showing after its page is gone leaks its window.
    val identifying = remember { IdentifyingScreens() }
    DisposableEffect(identifying) { onDispose { identifying.close() } }
    val list = cards ?: run { CompanionNote("Reading…"); return }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        list.forEach { card ->
            CompanionCard(card.name) {
                Text("${displayFacts(card)} · ${card.role.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when {
                    card.role == DisplayRole.MAIN && card.id == Display.DEFAULT_DISPLAY -> MainBrightness()
                    card.id == ownDisplay -> CompanionBrightness()
                    else -> CompanionNote("This screen controls its own brightness")
                }
                val rates = offeredRates(card.rates)
                if (rates.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        rates.forEach { rate ->
                            CompanionPill("${rate.toInt()} Hz", selected = card.rate?.let { Math.round(it * 10) / 10f } == rate) {
                                scope.launch {
                                    status[card.id] = withContext(Dispatchers.IO) {
                                        DisplayControls.setRefreshRate(card.id, card.widthPx, card.heightPx, rate)
                                    } ?: "${rate.toInt()} Hz"
                                    version++
                                }
                            }
                        }
                    }
                }
                status[card.id]?.let { CompanionNote(it) }
                if (card.id == ownDisplay) {
                    CompanionPill("Turn this screen off") { CompanionScreenPower.off() }
                }
            }
        }
        CompanionPill("Identify screens") { identifying.show(context, list) }
    }
}

/** The main screen's brightness: Android's own setting, with Modify system settings. */
@Composable
private fun MainBrightness() {
    val context = LocalContext.current
    val current by produceState<Int?>(null) { value = withContext(Dispatchers.IO) { DisplayControls.brightness(context) } }
    if (!DisplayControls.canWriteBrightness(context)) {
        CompanionNote("Brightness needs Modify system settings: System > Brightness")
        return
    }
    val level = current ?: return
    CompanionSliderRow(
        title = stringResource(R.string.companion_displays_brightness),
        value = level.toFloat(),
        range = 0f..255f,
        step = 255f / 20f,
        writeKey = "brightness:main",
        write = { next -> DisplayControls.setBrightness(context.applicationContext, kotlin.math.round(next).toInt()) },
    )
}

/** The companion's own brightness: its window's, 10% at the lowest; Off goes lower. */
@Composable
private fun CompanionBrightness() {
    val level by DisplayControls.companionLevel.collectAsState()
    CompanionSliderRow(
        title = stringResource(R.string.companion_displays_brightness),
        value = level,
        range = DisplayControls.FLOOR..1f,
        step = 0.1f,
        writeKey = "brightness:companion",
        write = { next -> DisplayControls.setCompanionLevel(next) },
    )
}

private fun readCards(context: Context): List<DisplayCard> {
    val outputs = DisplayOutputRepository(context).currentOutputsSnapshot()
    val names = ScreenNaming.names(context, outputs)
    val companion = ScreenRoles.arrangement(context, outputs).companionId
    val manager = context.getSystemService(DisplayManager::class.java)
    val main = ForegroundShell.current()?.window?.decorView?.display?.displayId ?: Display.DEFAULT_DISPLAY
    return outputs.sortedBy { it.androidDisplayId }.map { output ->
        val display = manager?.getDisplay(output.androidDisplayId)
        val mode = display?.mode
        val facts = ScreenNaming.facts(output)
        DisplayCard(
            id = output.androidDisplayId,
            name = names[output.androidDisplayId] ?: output.name,
            builtIn = facts.screenClass == ScreenClass.BUILT_IN,
            widthPx = mode?.physicalWidth ?: output.widthPx,
            heightPx = mode?.physicalHeight ?: output.heightPx,
            role = displayRole(output.androidDisplayId, main, companion),
            rates = display?.supportedModes
                ?.filter { it.physicalWidth == mode?.physicalWidth && it.physicalHeight == mode.physicalHeight }
                ?.map { it.refreshRate }.orEmpty(),
            rate = mode?.refreshRate,
        )
    }
}

/**
 * Shows a large number on every screen for a few seconds (a Presentation on each screen but Android's default, where
 * a message says it), so the person can tell which is which; TalkBack hears the list. One set shows at a time: pressing
 * again replaces it, and [close] takes it down when the page goes.
 */
private class IdentifyingScreens {
    private var shown: List<Presentation> = emptyList()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val timeout = Runnable { close() }

    fun show(context: Context, cards: List<DisplayCard>) {
        close()
        val manager = context.getSystemService(DisplayManager::class.java) ?: return
        shown = cards.mapIndexedNotNull { index, card ->
            val number = index + 1
            if (card.id == Display.DEFAULT_DISPLAY) {
                dev.droidtop.library.settings.Notices.flash("Screen $number: ${card.name}", context = context.applicationContext)
                null
            } else {
                manager.getDisplay(card.id)?.let { display ->
                    runCatching { NumberPresentation(context, display, number, card.name).apply { show() } }.getOrNull()
                }
            }
        }
        handler.postDelayed(timeout, IDENTIFY_MS)
        // TalkBack hears every screen's number and name.
        if (context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)?.isEnabled == true) {
            dev.droidtop.library.settings.Notices.flash(cards.mapIndexed { i, c -> "Screen ${i + 1}: ${c.name}" }.joinToString(". "), context = context.applicationContext)
        }
    }

    fun close() {
        handler.removeCallbacks(timeout)
        shown.forEach { runCatching { it.dismiss() } }
        shown = emptyList()
    }
}

private const val IDENTIFY_MS = 5_000L

/** The large number on one screen; a tap closes it. */
private class NumberPresentation(context: Context, display: Display, private val number: Int, private val name: String) : Presentation(context, display) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(context).apply {
                text = "$number\n$name"
                textSize = 72f
                gravity = Gravity.CENTER
                // droidtop's palette in the current appearance (docs/SPEC.md 7k, "Light and dark").
                val palette = dev.droidtop.shell.gamepad.GamingTheme.palette
                setTextColor(palette.onSurface.toArgb())
                setBackgroundColor(palette.overlaySurface.copy(alpha = 0.8f).toArgb())
                contentDescription = "Screen $number, $name"
                setOnClickListener { dismiss() }
            },
        )
    }
}
