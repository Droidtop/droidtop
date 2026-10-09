package dev.droidtop.library.integrations

import android.content.Context
import dev.droidtop.library.consoles.ConsoleSystemsRepository
import dev.droidtop.library.consoles.SystemFolders
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * A source's system hint (docs/plugin-api.md 1.6, the acquire reply's `system`): droidtop's id for the platform of the
 * game it just handed over, so a DOS zip found from global search lands in the DOS games folder where the DOS player
 * finds it. The hint decides the folder only when the Get games screen was not already that system's own.
 */
object AcquireSystemHint {
    /** The acquire reply value that carries the hint. */
    const val KEY = "system"

    private val ID = Regex("[a-z0-9][a-z0-9_-]{0,31}")

    /** [raw] when it looks like a system id, else null. Whether this build knows the system is [folderFor]'s answer. */
    fun valid(raw: String?): String? = raw?.trim()?.takeIf { ID.matches(it) }

    /** True when the download must be placed by [hint] instead of the screen's own destination. */
    fun overrides(hint: String?, hostContext: JSONObject): Boolean {
        if (hint == null) return false
        val destination = hostContext.optString("destination")
        return destination.isBlank() || hostContext.optJSONObject("system")?.optString("id") != hint
    }

    /** The games folder of system [id] under the person's games roots, or null when there is none. Off the main thread. */
    suspend fun folderFor(context: Context, id: String): File? = withContext(Dispatchers.IO) {
        val systemsById = ConsoleSystemsRepository.allSystems(context).associateBy { it.id }
        SystemFolders.all(context, systemsById).firstOrNull { (_, system) -> system.id == id }?.first
    }
}
