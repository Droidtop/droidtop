package dev.droidtop.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.gamenative.ui.component.dialog.ContainerConfigDialog
import app.gamenative.utils.ContainerUtils
import com.winlator.container.Container
import com.winlator.container.ContainerData
import dev.droidtop.app.ui.DroidtopTheme
import dev.droidtop.runtime.windows.PcContainers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Prefix and graphics for one PC game -- build-plan step 7 of the PC
 * surface (docs/SPEC.md 7i's "prefix and graphics" row).
 *
 * The screen itself is gamenative's own `ContainerConfigDialog` and its
 * nine tabs (General, Graphics, Emulation, Controller, Wine,
 * WinComponents, Environment, Drives, Advanced), already compiled into the
 * APK and already cycling its tabs on the shoulder buttons. What droidtop
 * adds is the one thing gamenative has no question about: WHICH container
 * this game runs in. That answer comes from [PcContainers.forGame], the
 * same function the launch path uses, so the prefix a person configures
 * here is provably the prefix the game starts in.
 */
class PcContainerConfigActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val entryId = intent.getStringExtra(EXTRA_ENTRY_ID)
        val title = intent.getStringExtra(EXTRA_TITLE)
        setContent {
            DroidtopTheme(darkTheme = true) {
                ContainerConfig(entryId = entryId, gameTitle = title, onClose = { finish() })
            }
        }
    }

    companion object {
        /** droidtop's own PC entry id, so a game with its own container gets its own. */
        const val EXTRA_ENTRY_ID = "dev.droidtop.app.extra.PC_ENTRY_ID"
        const val EXTRA_TITLE = "dev.droidtop.app.extra.PC_TITLE"

        private const val CLASS_NAME = "dev.droidtop.app.PcContainerConfigActivity"

        fun intent(context: Context, entryId: String?, title: String?): Intent =
            Intent().setClassName(context.packageName, CLASS_NAME).apply {
                entryId?.let { putExtra(EXTRA_ENTRY_ID, it) }
                title?.let { putExtra(EXTRA_TITLE, it) }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }
}

@Composable
private fun ContainerConfig(entryId: String?, gameTitle: String?, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var container by remember(entryId) { mutableStateOf<Container?>(null) }
    var config by remember(entryId) { mutableStateOf<ContainerData?>(null) }
    var resolved by remember(entryId) { mutableStateOf(false) }

    LaunchedEffect(entryId) {
        val found = withContext(Dispatchers.IO) { PcContainers.forGame(context, entryId) }
        container = found
        config = found?.let { withContext(Dispatchers.IO) { runCatching { ContainerUtils.toContainerData(it) }.getOrNull() } }
        resolved = true
    }

    val current = container
    val data = config
    if (current != null && data != null) {
        ContainerConfigDialog(
            visible = true,
            title = gameTitle?.takeIf { it.isNotBlank() }?.let { "$it - ${current.name}" } ?: current.name,
            initialConfig = data,
            onDismissRequest = onClose,
            onSave = { updated ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching { ContainerUtils.applyToContainer(context, current, updated) }
                    }
                    onClose()
                }
            },
        )
    } else if (resolved) {
        // No prefix exists yet, which is a setup step rather than an
        // error: the game's own screen offers it by name.
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("No Windows prefix yet", style = MaterialTheme.typography.titleMedium)
            Text(
                "Run \"Set up Windows games\" first — there is no container to configure until " +
                    "one exists.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
