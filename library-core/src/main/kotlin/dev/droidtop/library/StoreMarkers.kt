package dev.droidtop.library

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * What a store left in a game folder it installed, read by the PC folder
 * scan and written on the folder game's row (docs/SPEC.md 7g, "Store
 * markers", Droidtop/tracker#397 slice E): the store's id, the store's own
 * id for the game, the build the files are, and the DLC installed beside it.
 *
 * A game a store installed outside droidtop (a GOG offline installer, Heroic,
 * legendary) is a folder game to the walk; its marker is how it reads as the
 * store's game. Consumers read the row ([PcInfo.marker]); nothing else opens
 * these files.
 */
@Serializable
data class StoreMarker(
    /** The store's short id ("gog", "epic"), the same id a [dev.droidtop.library.stores.StoreLibrary] has. */
    val storeId: String,
    /** The store's own id for the game: GOG's product id, Epic's catalog item id. */
    val gameId: String,
    /** The build the files are, when the marker names one (GOG's `buildId`); null when it does not. */
    val buildId: String? = null,
    /** The store's ids of the DLC installed in the same folder (GOG: one `.info` per DLC). */
    val dlcIds: List<String> = emptyList(),
) {
    /** The library key the store's own row of this game has ("gog:1207658691"). */
    val key: String get() = "$storeId:$gameId"
}

/**
 * The markers droidtop reads, one mechanism for every store that leaves one
 * (docs/SPEC.md 7g, "Store markers"):
 *
 *  - **GOG**: `goggame-<id>.info`, a JSON file in the install root, one per
 *    product installed there. The game's own file has `rootGameId` equal to
 *    its `gameId` (or none); a DLC's names the game as its `rootGameId`.
 *    `buildId` is the build the installer was made from.
 *  - **Epic**: a `.egstore` folder in the install root holding a
 *    `<install id>.mancpn` JSON file whose `CatalogItemId` is the id Epic's
 *    library lists the game by (what droidtop's Epic rows are keyed by).
 *  - **Battle.net**: `.build.info` in the install root, the plain-text table
 *    Blizzard's installer (TACT) leaves: a header row of `Name!TYPE:size`
 *    columns and one row per branch. The active row's `Product` is the game's
 *    product code (`wow`, `pro`, `d3`) and its `Version` the build. Battle.net
 *    has no store droidtop signs in to, so the id is read as the store's and
 *    nothing is asked of it.
 *
 * Which names are markers is decided from a folder listing the walk already
 * read ([isMarkerName]); only a folder that has one is read further.
 */
object StoreMarkers {
    const val GOG = "gog"
    const val EPIC = "epic"

    /** Battle.net's marker id; the name a person reads is this with its first letter capitalised, which is what it is. */
    const val BATTLE_NET = "battle.net"
    private const val BUILD_INFO = ".build.info"
    private const val EGSTORE = ".egstore"
    private const val MANCPN = ".mancpn"
    private val GOG_INFO = Regex("""goggame-(\d+)\.info""", RegexOption.IGNORE_CASE)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Whether an entry named [name] in a game folder is a store's marker. */
    fun isMarkerName(name: String): Boolean =
        GOG_INFO.matches(name) || name.equals(EGSTORE, ignoreCase = true) || name.equals(BUILD_INFO, ignoreCase = true)

    /**
     * The marker in [folder], given the marker names its listing holds
     * ([isMarkerName]); null when there are none or none can be read. Reads
     * the marker files only: GOG's `.info` files, or one listing of
     * `.egstore` and its `.mancpn`, or `.build.info`. A GOG marker wins over an
     * Epic one, and an Epic one over a Battle.net one.
     */
    fun read(folder: File, names: List<String>): StoreMarker? {
        if (names.isEmpty()) return null
        val infos = names.filter { GOG_INFO.matches(it) }.mapNotNull { name ->
            runCatching { File(folder, name).readText() }.getOrNull()
        }
        gogOf(infos)?.let { return it }
        names.firstOrNull { it.equals(EGSTORE, ignoreCase = true) }?.let { egstore ->
            val manifests = File(folder, egstore).listFiles()
                ?.filter { it.isFile && it.name.endsWith(MANCPN, ignoreCase = true) }
                ?.sortedBy { it.name }
                .orEmpty()
            manifests.firstNotNullOfOrNull { file -> runCatching { epicOf(file.readText()) }.getOrNull() }?.let { return it }
        }
        val buildInfo = names.firstOrNull { it.equals(BUILD_INFO, ignoreCase = true) } ?: return null
        return runCatching { File(folder, buildInfo).readText() }.getOrNull()?.let(::battleNetOf)
    }

    /**
     * Battle.net's marker from the text of a `.build.info`: the header row names the columns (`Product!STRING:0`),
     * the rows after it hold the values, separated by `|`. The row marked `Active` is the build in use, else the
     * first; its `Product` is the game and its `Version` the build. Null when there is no product. Pure, for the tests.
     */
    fun battleNetOf(text: String): StoreMarker? {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val header = lines.firstOrNull()?.split('|')?.map { it.substringBefore('!').trim() } ?: return null
        val product = header.indexOf("Product").takeIf { it >= 0 } ?: return null
        val version = header.indexOf("Version")
        val active = header.indexOf("Active")
        val rows = lines.drop(1).map { it.split('|') }.filter { it.size > product }
        val row = rows.firstOrNull { active >= 0 && it.getOrNull(active)?.trim() == "1" } ?: rows.firstOrNull() ?: return null
        val code = row[product].trim().takeIf { it.isNotEmpty() } ?: return null
        return StoreMarker(BATTLE_NET, code, buildId = row.getOrNull(version)?.trim()?.takeIf { version >= 0 && it.isNotEmpty() })
    }

    /**
     * GOG's marker from the texts of a folder's `goggame-<id>.info` files:
     * the game is the file whose `rootGameId` is its own `gameId` (or has
     * none); every other product named there is DLC. A folder holding only
     * DLC files is the game they name as their root. Pure, for the tests.
     */
    fun gogOf(texts: List<String>): StoreMarker? {
        data class Info(val gameId: String, val rootGameId: String?, val buildId: String?)
        val infos = texts.mapNotNull { text ->
            val obj = parse(text) ?: return@mapNotNull null
            val gameId = obj.text("gameId") ?: return@mapNotNull null
            Info(gameId, obj.text("rootGameId"), obj.text("buildId"))
        }
        if (infos.isEmpty()) return null
        val base = infos.firstOrNull { it.rootGameId == null || it.rootGameId == it.gameId }
        val gameId = base?.gameId ?: infos.first().rootGameId ?: return null
        val dlc = infos.map { it.gameId }.filter { it != gameId }.distinct().sorted()
        return StoreMarker(GOG, gameId, buildId = base?.buildId, dlcIds = dlc)
    }

    /** Epic's marker from the text of a `.mancpn` file: its `CatalogItemId`. Pure, for the tests. */
    fun epicOf(text: String): StoreMarker? {
        val obj = parse(text) ?: return null
        val catalogId = obj.text("CatalogItemId") ?: return null
        return StoreMarker(EPIC, catalogId)
    }

    private fun parse(text: String): JsonObject? = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()

    /** A field as text, whether the file wrote it as a string or a number; null when absent or blank. */
    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
}
