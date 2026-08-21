package dev.droidtop.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalContext
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
 * ROM/game folder setup is entirely opt-in, decided on the welcome screen
 * itself -- not everyone using droidtop games at all (per direction), so
 * nothing game-related (including the storage permission request below)
 * is shown unless a user actively chooses to set it up. Two real,
 * documented limitations, not oversights:
 *  - [GamesRootPrefs.resolveStoragePath] resolves both primary shared
 *    storage and a real SD card (via the standard `/storage/<volumeId>/`
 *    mount convention most AOSP-based devices use), verified to actually
 *    exist before being trusted -- but a cloud-backed provider or an
 *    unusual mount layout still won't resolve to a `java.io.File`, since
 *    [dev.droidtop.library.GameEngineDetector] scans via `File`, not
 *    `DocumentFile`. Picking one of those shows an explicit message
 *    instead of silently doing nothing.
 *  - No app-picker/other library-source setup here yet (native Android
 *    apps are already auto-discovered by [dev.droidtop.library.NativeAppProvider]
 *    with no folder needed) -- only games folders, since that's the one
 *    real, currently-unreachable gap.
 */
class OnboardingActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { OnboardingScreen(onDone = { finish() }) }
    }
}

private enum class OnboardingStep { WELCOME, STORAGE_PERMISSION, GAMES_FOLDERS }

/**
 * [GamesRootPrefs.resolveStoragePath] can compute a perfectly correct real
 * path and it still won't matter -- Android 11+ blocks plain `java.io.File`
 * access outside the app's own sandbox unless the app holds "All files
 * access" (MANAGE_EXTERNAL_STORAGE), which a SAF folder grant alone does
 * NOT provide for File-based I/O (only for the ContentResolver/DocumentFile
 * APIs, which [dev.droidtop.library.GameEngineDetector] doesn't use). Real
 * risk this closes: a games folder that "resolves" successfully here but
 * then silently shows zero games afterward because reads are being denied
 * at the OS level, not because nothing's there -- confirmed as a real,
 * not hypothetical, gap after a live device test showed SD-card-stored
 * games failing to resolve. droidtop isn't Play-Store-distributed, so
 * requesting this permission directly (rather than working around it) is
 * legitimate here the same way it's standard for file-manager and
 * ROM-manager apps generally.
 */
private fun hasAllFilesAccess(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

@Composable
private fun OnboardingScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    var step by remember { mutableStateOf(OnboardingStep.WELCOME) }
    var unresolvedFolderWarning by remember { mutableStateOf(false) }
    var storageAccessGranted by remember { mutableStateOf(hasAllFilesAccess()) }
    // Re-read fresh each recomposition rather than held in isolated state --
    // GamesRootPrefs is the single source of truth, and MainActivity reads
    // it the same way, so there's no separate in-memory copy to drift.
    var rootsVersion by remember { mutableStateOf(0) }
    val roots = remember(rootsVersion) { GamesRootPrefs.gamesRootPaths(context) }

    val requestStorageAccess = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION doesn't reliably
        // report grant/deny via its own result code -- re-checking the real
        // system state directly is the only trustworthy signal.
        storageAccessGranted = hasAllFilesAccess()
        if (storageAccessGranted) step = OnboardingStep.GAMES_FOLDERS
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val resolved = GamesRootPrefs.resolveStoragePath(uri)
        if (resolved != null) {
            GamesRootPrefs.addGamesRoot(context, resolved)
            rootsVersion++
        }
        unresolvedFolderWarning = resolved == null
    }

    fun finish() {
        GamesRootPrefs.markOnboardingComplete(context)
        onDone()
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0B1220)), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(48.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (step) {
                OnboardingStep.WELCOME -> {
                    Text("Welcome to droidtop", color = Color.White, style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "droidtop turns this device into a real desktop, with a library " +
                            "that covers apps and, if you want it, games too.",
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Button(onClick = {
                        step = if (storageAccessGranted) OnboardingStep.GAMES_FOLDERS else OnboardingStep.STORAGE_PERMISSION
                    }) { Text("Set up game folders") }
                    TextButton(onClick = { finish() }) { Text("Skip -- I'm not using this for games") }
                }
                OnboardingStep.STORAGE_PERMISSION -> {
                    Text("One permission needed", color = Color.White, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "droidtop needs full storage access to read game files directly " +
                            "(including from an SD card) -- a folder picker alone isn't " +
                            "enough for that.",
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = {
                        requestStorageAccess.launch(
                            Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    }) { Text("Grant access") }
                    TextButton(onClick = { finish() }) { Text("Skip -- I'm not using this for games") }
                }
                OnboardingStep.GAMES_FOLDERS -> {
                    Text("Game folders", color = Color.White, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Add every folder where your Ren'Py, RPG Maker, or Kirikiri games " +
                            "live -- an SD card and internal storage both work. You can add " +
                            "more, or change these, later in Settings.",
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (roots.isNotEmpty()) {
                        LazyColumn(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            items(roots.toList()) { path ->
                                Text(path, color = Color.LightGray, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
                            }
                        }
                    }
                    if (unresolvedFolderWarning) {
                        Text(
                            "That folder couldn't be used directly (this device's storage " +
                                "or SD card layout doesn't match what droidtop expects yet, " +
                                "or it's a cloud-backed folder).",
                            color = Color.Gray,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Button(onClick = { pickFolder.launch(null) }) {
                        Text(if (roots.isEmpty()) "Add a folder" else "Add another folder")
                    }
                    TextButton(onClick = { finish() }) { Text(if (roots.isEmpty()) "Skip for now" else "Done") }
                }
            }
        }
    }
}
