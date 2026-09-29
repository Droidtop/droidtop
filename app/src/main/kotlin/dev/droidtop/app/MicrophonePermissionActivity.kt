package dev.droidtop.app

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Asks for the microphone once, when the person switches the desktop's
 * Microphone row on (docs/SPEC.md 3d, Droidtop/tracker#80), and records the
 * answer as that row's setting: granted turns it on, refused leaves it off.
 * No screen of its own; the row's text already gave the reason.
 */
class MicrophonePermissionActivity : ComponentActivity() {
    private val request =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            DesktopSetupPrefs.setMicrophone(this, granted)
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) request.launch(Manifest.permission.RECORD_AUDIO)
    }
}
