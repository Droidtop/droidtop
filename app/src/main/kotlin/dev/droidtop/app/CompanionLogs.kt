package dev.droidtop.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Process
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.droidtop.library.LaunchDisplay
import dev.droidtop.library.settings.ControlAccess
import dev.droidtop.library.settings.ControlRow
import dev.droidtop.library.settings.UiModeRefresh
import dev.droidtop.runtime.systemstatus.LogReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** What Save and Share warn of, once, above their buttons. */
internal const val LOG_PRIVACY_LINE = "Logs can hold personal data, such as names, addresses and account names."

/** The filtered view as text, the lines Save writes and Share sends. Pure. */
internal fun logText(entries: List<LogReader.Entry>, filter: LogReader.Filter): String =
    entries.filter { LogReader.matches(it, filter) }.joinToString("\n") { it.line() }

/**
 * Performance > Logs (docs/SPEC.md "The companion's tabs", Logs; slice C18): Android's log with the helper app, else
 * droidtop's own; filters by level, by tag and by app (tap a line's tag or app to keep only that), and one tap for the
 * running game only; Pause; Save (the system file picker) and Share, both of the filtered view, under a one-line
 * personal-data warning. New lines are read every two seconds only while the card shows and is not paused. Not in Kid
 * or Kiosk ([ControlRow.LOGS]).
 */
@Composable
internal fun CompanionLogs() {
    val context = LocalContext.current
    val uiMode by UiModeRefresh.mode.collectAsState()
    if (!ControlAccess.shows(uiMode, ControlRow.LOGS)) return
    var entries by remember { mutableStateOf<List<LogReader.Entry>>(emptyList()) }
    var android by remember { mutableStateOf<Boolean?>(null) }
    var filter by remember { mutableStateOf(LogReader.Filter()) }
    var paused by remember { mutableStateOf(false) }
    var gameOnly by remember { mutableStateOf(false) }
    LaunchedEffect(paused) {
        while (!paused) {
            val since = entries.lastOrNull()?.time
            val (provider, fresh) = withContext(Dispatchers.IO) { LogReader.read(Process.myPid(), since) }
            android = provider
            entries = (entries + fresh).takeLast(LogReader.MAX_LINES)
            delay(REFRESH_MS)
        }
    }
    LaunchedEffect(gameOnly) {
        filter = if (!gameOnly) {
            filter.copy(pids = null)
        } else {
            val pkg = LaunchDisplay.runningPackageName
            filter.copy(pids = pkg?.let { withContext(Dispatchers.IO) { LogReader.pidsOf(it) } }.orEmpty())
        }
    }
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CompanionNote(if (android == false) "droidtop's own log (Android's needs the helper app)" else "Android's log")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            listOf(LogReader.Level.VERBOSE, LogReader.Level.DEBUG, LogReader.Level.INFO, LogReader.Level.WARN, LogReader.Level.ERROR).forEach { level ->
                CompanionPill(level.label, selected = filter.minLevel == level) { filter = filter.copy(minLevel = level) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            CompanionPill(if (paused) "Resume" else "Pause", selected = paused) { paused = !paused }
            if (android == true) CompanionPill("Running game only", selected = gameOnly) { gameOnly = !gameOnly }
            if (filter.tag.isNotBlank() || (filter.pids != null && !gameOnly)) {
                CompanionPill("Show all") { filter = filter.copy(tag = "", pids = null); gameOnly = false }
            }
        }
        CompanionNote(LOG_PRIVACY_LINE)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CompanionPill("Save") {
                LogSaveActivity.text = logText(entries, filter)
                context.startActivity(Intent(context, LogSaveActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            CompanionPill("Share") {
                val text = logText(entries, filter).takeLast(SHARE_CHARS)
                runCatching {
                    context.startActivity(
                        Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share log")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }
        val shown = entries.filter { LogReader.matches(it, filter) }.takeLast(SHOWN_LINES)
        if (shown.isEmpty()) CompanionNote("Nothing yet")
        shown.asReversed().forEach { entry ->
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${entry.level.letter} ${entry.time.substringAfter(' ')}", style = MaterialTheme.typography.labelSmall, color = if (entry.level >= LogReader.Level.WARN) colors.error else colors.onSurfaceVariant)
                    Text(entry.tag, style = MaterialTheme.typography.labelSmall, color = colors.primary, modifier = Modifier.clickable { filter = filter.copy(tag = entry.tag) })
                    Text("pid ${entry.pid}", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, modifier = Modifier.clickable { filter = filter.copy(pids = setOf(entry.pid)) })
                }
                Text(entry.message, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = colors.onSurface, maxLines = 4)
            }
        }
    }
}

/** The Logs card on Performance, only where the mode allows it. */
@Composable
internal fun CompanionLogsCard() {
    val uiMode by UiModeRefresh.mode.collectAsState()
    if (!ControlAccess.shows(uiMode, ControlRow.LOGS)) return
    CompanionCard("Logs") { CompanionLogs() }
}

private const val REFRESH_MS = 2_000L
private const val SHOWN_LINES = 200
private const val SHARE_CHARS = 100_000

/**
 * Save for the Logs card: Android's file picker needs an Activity result, which a companion host cannot always take,
 * so this transparent one asks where to save, writes the text off the main thread and closes.
 */
class LogSaveActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TITLE, "droidtop-log-${System.currentTimeMillis()}.txt")
        runCatching { startActivityForResult(intent, REQUEST) }.onFailure { finish() }
    }

    @Deprecated("Activity result API of the platform Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        val body = text
        text = ""
        if (resultCode == RESULT_OK && uri != null) {
            val resolver = contentResolver
            Thread { runCatching { resolver.openOutputStream(uri)?.use { it.write(body.toByteArray()) } } }.apply { isDaemon = true }.start()
        }
        finish()
    }

    companion object {
        private const val REQUEST = 7
        /** The text to save, handed over in this process (it can be larger than an Intent may carry). */
        @Volatile var text: String = ""
    }
}
