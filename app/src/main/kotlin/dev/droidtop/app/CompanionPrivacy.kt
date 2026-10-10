package dev.droidtop.app

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.droidtop.runtime.systemstatus.PrivacyAccess
import dev.droidtop.runtime.systemstatus.SettingsLaunch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** "Camera, Microphone" for one app's uses, most recent first, with when. Pure. */
internal fun privacyLine(uses: List<PrivacyAccess.Use>): String =
    uses.sortedBy { it.agoMs }.joinToString(", ") { it.kind.label } + ", " + PrivacyAccess.agoText(uses.minOf { it.agoMs })

/**
 * System > Privacy beyond Android's dashboard link (docs/SPEC.md "The companion's tabs", Privacy; slice C17): with the
 * helper app, the apps that used the camera, microphone or location in the last 24 hours, each opening its App info;
 * then "What droidtop can access", each of droidtop's grants with whether it holds it and why it asks, opening
 * droidtop's own App info. Read when the card opens, off the main thread.
 */
@Composable
internal fun CompanionPrivacyCard() {
    val context = LocalContext.current
    val recent by produceState<Pair<Boolean, List<Pair<String, List<PrivacyAccess.Use>>>>?>(null) {
        value = withContext(Dispatchers.IO) {
            val uses = PrivacyAccess.recentUse()
            val pm = context.packageManager
            val byApp = uses.orEmpty().groupBy { it.packageName }.map { (pkg, list) ->
                val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
                "$label|$pkg" to list
            }.sortedBy { (_, list) -> list.minOf { it.agoMs } }
            (uses != null) to byApp
        }
    }
    val grants by produceState<List<PrivacyAccess.Grant>?>(null) {
        value = withContext(Dispatchers.IO) { PrivacyAccess.droidtopGrants(context.applicationContext) }
    }
    val colors = MaterialTheme.colorScheme
    fun appInfo(pkg: String) = SettingsLaunch.start(
        context,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        recent?.let { (available, apps) ->
            if (available) {
                Text("Last 24 hours", style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                if (apps.isEmpty()) CompanionNote("No app used the camera, microphone or location")
                apps.forEach { (key, uses) ->
                    val (label, pkg) = key.split('|', limit = 2).let { it[0] to it[1] }
                    PrivacyRow(label, privacyLine(uses)) { appInfo(pkg) }
                }
            }
        }
        Text("What droidtop can access", style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
        grants?.forEach { grant ->
            PrivacyRow(grant.label, (if (grant.held) "Allowed. " else "Not allowed. ") + grant.reason) { appInfo(context.packageName) }
        } ?: CompanionNote("Reading…")
    }
}

@Composable
private fun PrivacyRow(title: String, detail: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
