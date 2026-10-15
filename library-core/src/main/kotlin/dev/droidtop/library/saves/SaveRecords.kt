package dev.droidtop.library.saves

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import dev.droidtop.library.PlayHistoryDatabase

/**
 * An id a game is known by outside droidtop: a store's or a scraper's (docs/SPEC.md 7o "Saves", Droidtop/tracker#546).
 * `kind` is one of [GameIdKind]. Written by the PC scrape when it completes a record (the ids it learned were not
 * kept anywhere before), and by the matcher's reading of an entry's own store id.
 */
@Entity(tableName = "game_ids", primaryKeys = ["game_id", "kind"])
data class GameIdEntity(
    @ColumnInfo(name = "game_id") val gameId: String,
    val kind: String,
    val value: String,
)

/**
 * The match that decided which save rule a game uses, kept so the person can see it and correct it. [kind] is a
 * [SaveRuleKind]; [name] is what the rule is called (an engine id, an emulator's package, the key a paired computer
 * answered by, or a folder the person chose); [how] is a [SaveMatchHow]. Rule data is read when it is used, never
 * copied here.
 */
@Entity(tableName = "save_rules")
data class SaveRuleEntity(
    @PrimaryKey @ColumnInfo(name = "game_id") val gameId: String,
    val kind: String,
    val name: String,
    val how: String,
    @ColumnInfo(name = "checked_at") val checkedAt: Long,
)

object GameIdKind {
    const val STEAM = "steam"
    const val GOG = "gog"
    const val IGDB = "igdb"
    const val LUTRIS = "lutris"
    const val STEAMGRIDDB = "steamgriddb"
}

object SaveRuleKind {
    const val ENGINE = "engine"
    const val EMULATOR = "emulator"
    /** A rule a paired computer answered for, whose templates it took from the person's own entries, their Ludusavi custom games or the Ludusavi manifest. */
    const val COMPUTER = "computer"
    /** The person said not to look for this game's saves. */
    const val NONE = "none"
}

object SaveMatchHow {
    const val ENGINE = "engine"
    const val EMULATOR = "emulator"
    const val STEAM = "steam"
    const val GOG = "gog"
    const val SCRAPER = "scraper"
    const val FOLDER = "folder"
    const val TITLE = "title"
    /** The person's own correction: never replaced by an automatic match. */
    const val YOU = "you"
}

@Dao
interface SaveRecordsDao {
    @Query("SELECT * FROM game_ids WHERE game_id = :id")
    suspend fun idsOf(id: String): List<GameIdEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putId(row: GameIdEntity)

    @Query("SELECT * FROM save_rules WHERE game_id = :id")
    suspend fun ruleOf(id: String): SaveRuleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putRule(row: SaveRuleEntity)

    @Query("DELETE FROM save_rules WHERE game_id = :id")
    suspend fun deleteRule(id: String)

    @Query("DELETE FROM game_ids WHERE game_id = :id")
    suspend fun deleteIds(id: String)

    /** The missing game's ids and the person's correction become the replacing game's, into an empty place only. */
    @Transaction
    suspend fun moveTo(fromId: String, toId: String) {
        val have = idsOf(toId).map { it.kind }.toSet()
        for (row in idsOf(fromId)) if (row.kind !in have) putId(row.copy(gameId = toId))
        deleteIds(fromId)
        val from = ruleOf(fromId)
        if (from != null && ruleOf(toId) == null && from.how == SaveMatchHow.YOU) putRule(from.copy(gameId = toId))
        deleteRule(fromId)
    }
}

/** The game ids and save-rule matches of games. Disk work: never on the main thread. */
interface SaveRecordStore {
    suspend fun idsOf(gameId: String): Map<String, String>
    suspend fun putIds(gameId: String, ids: Map<String, String>)
    suspend fun ruleOf(gameId: String): SaveRuleEntity?
    suspend fun putRule(row: SaveRuleEntity)
    suspend fun clearRule(gameId: String)
    suspend fun moveTo(fromId: String, toId: String)
}

object NoOpSaveRecordStore : SaveRecordStore {
    override suspend fun idsOf(gameId: String): Map<String, String> = emptyMap()
    override suspend fun putIds(gameId: String, ids: Map<String, String>) = Unit
    override suspend fun ruleOf(gameId: String): SaveRuleEntity? = null
    override suspend fun putRule(row: SaveRuleEntity) = Unit
    override suspend fun clearRule(gameId: String) = Unit
    override suspend fun moveTo(fromId: String, toId: String) = Unit
}

class RoomSaveRecordStore(context: Context) : SaveRecordStore {
    private val dao = PlayHistoryDatabase.get(context).saveRecordsDao()

    override suspend fun idsOf(gameId: String): Map<String, String> = dao.idsOf(gameId).associate { it.kind to it.value }

    override suspend fun putIds(gameId: String, ids: Map<String, String>) {
        for ((kind, value) in ids) if (value.isNotBlank()) dao.putId(GameIdEntity(gameId, kind, value))
    }

    override suspend fun ruleOf(gameId: String): SaveRuleEntity? = dao.ruleOf(gameId)
    override suspend fun putRule(row: SaveRuleEntity) = dao.putRule(row)
    override suspend fun clearRule(gameId: String) = dao.deleteRule(gameId)
    override suspend fun moveTo(fromId: String, toId: String) = dao.moveTo(fromId, toId)
}
