package dev.droidtop.library.integrations

import android.content.Context
import dev.droidtop.library.GamesRoots
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

    /**
     * The games folder of system [id] under the person's games roots. When there is none yet and droidtop knows the
     * system by its folder, one is made, named by the system's id as ES-DE names them
     * ([dev.droidtop.library.consoles.EsDeFolderStructure]), beside
     * the person's other system folders ([placeFor]): a DOS zip from Abandonware was refused with "there is no games
     * folder for dos" (Droidtop/tracker#846). Null for a system droidtop does not know or a folder that cannot be made.
     * [onMade] hears the folder made. Off the main thread.
     */
    suspend fun folderFor(context: Context, id: String, onMade: (File) -> Unit = {}): File? = withContext(Dispatchers.IO) {
        val systemsById = ConsoleSystemsRepository.allSystems(context).associateBy { it.id }
        val found = SystemFolders.all(context, systemsById)
        found.firstOrNull { (_, system) -> system.id == id }?.first?.let { return@withContext it }
        if (systemsById[id]?.canResolveFromFolder() != true) return@withContext null
        val place = placeFor(found.map { it.first }, GamesRoots.current(context)) ?: return@withContext null
        File(place, id).takeIf { it.isDirectory || it.mkdirs() }?.also(onMade)
    }

    /**
     * Where a new system folder goes: the folder most of the person's system folders are in (a "Roms" folder with
     * nsw and snes in it), the first such one on a tie, else the first games root. Null with no root. Pure.
     */
    fun placeFor(systemFolders: List<File>, roots: List<File>): File? =
        systemFolders.mapNotNull { it.parentFile }.groupingBy { it.path }.eachCount().entries
            .maxByOrNull { it.value }?.key?.let(::File) ?: roots.firstOrNull()
}
