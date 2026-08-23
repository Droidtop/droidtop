package dev.droidtop.library.consoles

import android.content.Context

/**
 * The one real place every caller reads or writes console-platform
 * definitions through -- see [ConsoleSystemsDatabase]'s own doc comment
 * for why this replaced [ES_DE_CONSOLE_SYSTEMS] as the runtime source of
 * truth. [allSystems] seeds the database from [ES_DE_CONSOLE_SYSTEMS] the
 * first time it's ever called on a fresh install (an empty table), so
 * every existing built-in system is present and editable from the very
 * first real read, not just after some separate manual "import" step.
 */
object ConsoleSystemsRepository {
    suspend fun allSystems(context: Context): List<ConsoleSystemDef> {
        val dao = ConsoleSystemsDatabase.get(context).consoleSystemDao()
        seedIfEmpty(dao)
        return dao.getAll().map { it.toConsoleSystemDef() }
    }

    suspend fun upsert(context: Context, system: ConsoleSystemDef, isBuiltIn: Boolean = false) {
        ConsoleSystemsDatabase.get(context).consoleSystemDao().upsert(system.toEntity(isBuiltIn))
    }

    suspend fun delete(context: Context, id: String) {
        ConsoleSystemsDatabase.get(context).consoleSystemDao().delete(id)
    }

    /** Real, explicit "undo my platform edits, start over" action -- clears every built-in row (leaving any real user-added custom platform untouched) and reseeds from [ES_DE_CONSOLE_SYSTEMS]. */
    suspend fun restoreDefaults(context: Context) {
        val dao = ConsoleSystemsDatabase.get(context).consoleSystemDao()
        dao.clearBuiltIns()
        dao.upsertAll(ES_DE_CONSOLE_SYSTEMS.map { it.toEntity(isBuiltIn = true) })
    }

    private suspend fun seedIfEmpty(dao: ConsoleSystemDao) {
        if (dao.count() == 0) {
            dao.upsertAll(ES_DE_CONSOLE_SYSTEMS.map { it.toEntity(isBuiltIn = true) })
        }
    }
}

private fun ConsoleSystemDef.toEntity(isBuiltIn: Boolean): ConsoleSystemEntity = ConsoleSystemEntity(
    id = id,
    displayName = displayName,
    extensionsCsv = extensions.joinToString(","),
    retroArchCore = retroArchCore,
    isBuiltIn = isBuiltIn,
)

private fun ConsoleSystemEntity.toConsoleSystemDef(): ConsoleSystemDef = ConsoleSystemDef(
    id = id,
    displayName = displayName,
    extensions = extensionsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
    retroArchCore = retroArchCore,
)
