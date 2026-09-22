package dev.droidtop.library

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What a game needs at LAUNCH, so a launch reads a fact instead of
 * re-deriving it (docs/SPEC.md 7g, "one file per game is the truth").
 *
 * One case per real launch mechanism, matching [LibraryEntryKind]'s own
 * grouping: an engine game resolves through [EngineGameProvider], a ROM
 * through [dev.droidtop.library.consoles.ConsoleRomProvider], a PC game
 * through its store install. [None] is the honest "nothing cached yet"
 * state a record can be written in before its provider has anything to
 * say -- never used to mean a launch mechanism was tried and found
 * unavailable.
 */
@Serializable
sealed interface LaunchFacts {

    /**
     * An engine game's launch facts, filled at DETECTION time (the same
     * call [EngineGameProvider]'s own scan already makes -- see
     * [GameEngineDetector.detectGame]/`resolveEngineVersion`), so
     * [EngineGameProvider.launch] never has to re-walk the folder to find
     * out what a `scan()` already knew.
     */
    @Serializable
    @SerialName("engine")
    data class Engine(
        /** [GameEngineDetector.DetectedGame.gameRoot]'s own path -- not always [GameRecord.entry]'s id folder; see that class's doc comment. */
        val gameRoot: String,
        /** [GameEngine.name] -- droidtop's own internal engine vocabulary, not enginehost's. */
        val engine: String,
        val engineVersion: String? = null,
        /**
         * The real executable a Wine/Linux-container launch would run,
         * when one has already been resolved. Null is common and
         * harmless: [GameExecutableResolver] only ever runs once a
         * strategy that needs it is actually chosen, so most engine
         * records never fill this -- enginehost, the common case, needs
         * no executable at all.
         */
        val execFile: String? = null,
        val enginehostTarget: EnginehostTarget? = null,
        /** Same map as [EnginehostTarget.runtimeRequirements] -- kept alongside it so a reader of just [LaunchFacts] (no enginehost lookup) still has it. */
        val runtimeRequirements: Map<String, String> = emptyMap(),
    ) : LaunchFacts

    /**
     * A console ROM's launch facts. [dev.droidtop.library.LibraryEntry]
     * already carries [dev.droidtop.library.LibraryEntry.systemId] and
     * [dev.droidtop.library.LibraryEntry.altEmulator] directly (real,
     * scan-time facts, not re-derived at launch either) -- this exists so
     * a single-game view can read a ROM's launch facts from the record
     * alone, without the rest of [dev.droidtop.library.LibraryEntry].
     */
    @Serializable
    @SerialName("rom")
    data class Rom(
        val file: String,
        val systemId: String,
        val altEmulator: String? = null,
    ) : LaunchFacts

    /** A PC game's launch facts -- [storeInstall] is the store's own install-directory key (see [StoreInstall]), null for a folder-scanned PC game with no store behind it. */
    @Serializable
    @SerialName("pc")
    data class Pc(val storeInstall: String? = null) : LaunchFacts

    /** Nothing cached yet, or a kind this build's record store never fills (native apps, Wine profiles). */
    @Serializable
    @SerialName("none")
    object None : LaunchFacts
}

/**
 * One game, the source of truth for everything droidtop knows about it
 * (docs/SPEC.md 7g, "one file per game is the truth"): its
 * [LibraryEntry] as of the last walk that found or changed it, where it
 * came from, and its launch facts.
 *
 * [formatVersion] is this record's own shape version -- see
 * [GameRecord.FORMAT_VERSION] -- separate from
 * [dev.droidtop.library.FileLibraryIndexStore]'s slice format, since a
 * record and the index that is built from it can change shape on
 * different schedules.
 */
@Serializable
data class GameRecord(
    val entry: LibraryEntry,
    val formatVersion: Int = FORMAT_VERSION,
    /** [LibraryProvider.indexKey] of the provider that found this game. */
    val provider: String,
    /** The games root this game was found under, or null for a part that is not under one (docs/SPEC.md 7g, [ScanStep.Segment.root]). */
    val root: String? = null,
    /** The part of the walk that found this game ([ScanStep.Segment.key]). */
    val part: String? = null,
    val launch: LaunchFacts = LaunchFacts.None,
) {
    companion object {
        /** Bump when [GameRecord]'s or [LibraryEntry]'s shape changes in a way a reader cannot absorb -- an unreadable record is a game to detect again, never an error (docs/SPEC.md 7g). */
        const val FORMAT_VERSION = 1
    }
}

/**
 * Where [GameRecord]s live: one JSON file per game, read when that ONE
 * game is needed (opened, focused in a detail view, launched) and never
 * for a list -- lists read the index (docs/SPEC.md 7g).
 *
 * A plain interface for the same reason as [LibraryIndexStore]: a
 * provider stays constructible in a JVM test with [NoOpGameRecordStore],
 * and every existing call site keeps working (re-detecting on every
 * launch, as it always did) with zero changes required until it is
 * handed a real store.
 */
interface GameRecordStore {
    fun get(id: String): GameRecord?
    fun put(record: GameRecord)
    /** Only ever called for a game whose root was removed (docs/SPEC.md 7g) -- nothing else deletes a record. */
    fun delete(id: String)
}

object NoOpGameRecordStore : GameRecordStore {
    override fun get(id: String): GameRecord? = null
    override fun put(record: GameRecord) {}
    override fun delete(id: String) {}
}

/**
 * One JSON file per game under [dir], named by a hash of the id and
 * sharded by its first byte so no directory grows without bound and no
 * game's own filename ever reaches the filesystem -- the j2me lesson
 * (docs/SPEC.md 7g): a folder scan once hung because a real filename held
 * characters illegal on exFAT but legal on ext4, and an id-derived
 * filename can carry the same kind of character a games root can.
 *
 * Written to a temp file and renamed into place, same crash-safety as
 * [FileLibraryIndexStore]. Written only when the record differs from
 * what is already stored -- a walk that finds the same game again writes
 * nothing, which is what makes "a record is written when a walk finds or
 * changes that game, and at no other time" (docs/SPEC.md 7g) true rather
 * than aspirational.
 *
 * A file this build cannot read (an older shape, a corrupt write, a
 * different [GameRecord.formatVersion]) is treated as no record -- the
 * game is detected again, never an error the user sees.
 */
class FileGameRecordStore(private val dir: File) : GameRecordStore {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "kind"
    }

    private fun hashOf(id: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun fileFor(id: String): File {
        val hash = hashOf(id)
        return File(File(dir, hash.substring(0, 2)), "$hash.json")
    }

    override fun get(id: String): GameRecord? {
        val file = fileFor(id)
        if (!file.isFile) return null
        return try {
            val record = json.decodeFromString(GameRecord.serializer(), file.readText())
            if (record.formatVersion != GameRecord.FORMAT_VERSION) {
                ScanLog.write(
                    "record: ${file.name} is format ${record.formatVersion}, this build reads " +
                        "${GameRecord.FORMAT_VERSION}; detecting again",
                )
                null
            } else {
                record
            }
        } catch (t: Throwable) {
            ScanLog.write("record: ${file.name} could not be read (${t.javaClass.simpleName}: ${t.message}); detecting again")
            null
        }
    }

    override fun put(record: GameRecord) {
        val file = fileFor(record.entry.id)
        val encoded = json.encodeToString(GameRecord.serializer(), record)
        // Write only when the record actually changed -- a walk that
        // finds the same game again (the common case, most of a rescan)
        // must not rewrite every record file it touches.
        if (file.isFile && runCatching { file.readText() }.getOrNull() == encoded) return
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(encoded)
        if (!tmp.renameTo(file)) {
            // Same fallback FileLibraryIndexStore.save uses: the old
            // record is still intact if this also fails, and the game is
            // simply written again next time it is found.
            file.delete()
            tmp.renameTo(file)
        }
    }

    override fun delete(id: String) {
        fileFor(id).delete()
    }
}
