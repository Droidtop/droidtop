package dev.droidtop.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * droidtop's real first-run flow -- previously nonexistent (see
 * OnboardingGate's own doc comment: before this, the only way to get games
 * into droidtop's library was `adb push`ing directly into the app's
 * private external-files dir). Gated by [OnboardingGate] from
 * `:shell-default`'s `LauncherApplication.onCreate()`, launched by
 * explicit component name since `:shell-default` can't compile-depend on
 * `:app`.
 *
 * Deliberately minimal for a first real version: welcome, then a single
 * SAF folder pick for where games live. Two real, documented limitations,
 * not oversights:
 *  - [GamesRootPrefs.resolvePrimaryStoragePath] only resolves folders
 *    picked from the device's primary shared storage -- an SD card or a
 *    cloud-backed provider won't resolve to a `java.io.File`, since
 *    [dev.droidtop.library.GameEngineDetector] scans via `File`, not
 *    `DocumentFile`. Picking one of those shows an explicit message
 *    instead of silently doing nothing.
 *  - No app-picker/other library-source setup here yet (native Android
 *    apps are already auto-discovered by [dev.droidtop.library.NativeAppProvider]
 *    with no folder needed) -- only the games folder, since that's the one
 *    real, currently-unreachable gap.
 */
class OnboardingActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { OnboardingScreen(onDone = { finish() }) }
    }
}

private enum class OnboardingStep { WELCOME, GAMES_FOLDER, DONE }

@Composable
private fun OnboardingScreen(onDone: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var step by remember { mutableStateOf(OnboardingStep.WELCOME) }
    var unresolvedFolderWarning by remember { mutableStateOf(false) }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        context.contentResolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        val resolved = GamesRootPrefs.resolvePrimaryStoragePath(uri)
        GamesRootPrefs.saveGamesRoot(context, uri, resolved)
        unresolvedFolderWarning = resolved == null
        step = OnboardingStep.DONE
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0B1220)), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(48.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (step) {
                OnboardingStep.WELCOME -> {
                    Text("Welcome to droidtop", color = Color.White, style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "droidtop turns this device into a real desktop, with a library " +
                            "that covers apps, games, and everything in between.",
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Button(onClick = { step = OnboardingStep.GAMES_FOLDER }) { Text("Get started") }
                }
                OnboardingStep.GAMES_FOLDER -> {
                    Text("Where are your games?", color = Color.White, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Pick the folder where your Ren'Py, RPG Maker, or Kirikiri games live. " +
                            "You can change this later in Settings.",
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = { pickFolder.launch(null) }) { Text("Choose folder") }
                    TextButton(onClick = { GamesRootPrefs.markOnboardingComplete(context); step = OnboardingStep.DONE }) {
                        Text("Skip for now")
                    }
                }
                OnboardingStep.DONE -> {
                    Text("All set", color = Color.White, style = MaterialTheme.typography.headlineSmall)
                    if (unresolvedFolderWarning) {
                        Text(
                            "That folder couldn't be used directly (only folders on this " +
                                "device's main storage are supported right now, not an SD " +
                                "card or cloud folder) -- you can try a different folder from " +
                                "Settings later.",
                            color = Color.Gray,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Button(onClick = { GamesRootPrefs.markOnboardingComplete(context); onDone() }) { Text("Done") }
                }
            }
        }
    }
}
