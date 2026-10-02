package dev.droidtop.library

import android.content.Context
import java.io.File

/**
 * The ONE [PcFolderScan.EngineVerdicts] of the process, kept in droidtop's
 * own files, so every walk that asks an engine question of a folder (the PC
 * walk in `PcLibrary`, the engine walk in [EngineGameProvider]) reads and
 * writes the same answers (docs/SPEC.md 7g). Until 2026-10-02 only the PC
 * walk kept verdicts, and only for rule 6; the engine walk, which is the
 * slow one on a card, kept nothing, so a rescan of unchanged folders asked
 * the card every engine question again (Droidtop/tracker#275).
 *
 * Callers [forRules] and [save] on a background thread: both touch the
 * file.
 */
object EngineVerdictStore {
    private const val FILE = "engine-verdicts.tsv"

    /** The file the first version of the store used, for the PC walk's rule 6 alone; its format is gone. */
    private const val OLD_FILE = "pc-engine-verdicts.tsv"

    private var current: PcFolderScan.EngineVerdicts? = null
    private var currentFingerprint: String? = null

    /** The verdicts kept for [defs], loaded from the file the first time and shared from then on; other rules start again from the file's own fingerprint check. */
    @Synchronized
    fun forRules(context: Context, defs: List<EngineDef>): PcFolderScan.EngineVerdicts {
        val fingerprint = PcFolderScan.EngineVerdicts.fingerprintOf(defs)
        current?.let { if (currentFingerprint == fingerprint) return it }
        File(context.filesDir, OLD_FILE).delete()
        val loaded = PcFolderScan.EngineVerdicts.load(File(context.filesDir, FILE), defs)
        current = loaded
        currentFingerprint = fingerprint
        return loaded
    }

    /** Writes the verdicts when any were added since the last write. */
    fun save(context: Context) {
        val verdicts = synchronized(this) { current } ?: return
        verdicts.save(File(context.filesDir, FILE))
    }
}
