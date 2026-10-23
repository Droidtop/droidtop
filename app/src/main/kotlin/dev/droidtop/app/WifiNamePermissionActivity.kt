package dev.droidtop.app

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import dev.droidtop.library.settings.Notices
import dev.droidtop.net.MeteredGrants

/**
 * Asks for the location permission for one purpose only: to know which Wi-Fi network droidtop is on, so "Always allow
 * downloads" can be remembered for a metered one (docs/SPEC.md 12a, "A metered connection is allowed, never a job").
 * Reached only from that row, which says it is not recommended. Granted, the network is allowed at once; refused,
 * nothing changes. No screen of its own; the row's text already gave the reason.
 */
class WifiNamePermissionActivity : ComponentActivity() {
    private val request =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { answers ->
            if (answers[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
                val connection = MeteredGrants.connectionNow(this)
                if (connection?.kind == MeteredGrants.Kind.WIFI && connection.key != null) {
                    MeteredGrants.allowAlways(this, connection)
                    Notices.flash("Downloads may use ${connection.label} from now on", context = this)
                } else {
                    Notices.flash("Android did not say which Wi-Fi this is. Location may be switched off", context = this)
                }
            }
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            request.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
}
