package dev.droidtop.library.consoles

import android.content.Context

/**
 * The one real place every caller reads or writes console-platform
 * definitions through -- see [ConsoleSystemsDatabase]'s own doc comment
 * for why this replaced the compiled-in list as the runtime source of
 * truth. [allSystems] seeds the database from [PlatformsDatabase] (the
 * data-driven platforms-database.json, docs/SPEC.md §7e2) the
 * first time it's ever called on a fresh install (an empty table), so
 * every existing built-in system is present and editable from the very
 * first real read, not just after some separate manual "import" step.
 *
 * A platform that a later database refresh adds is added here too, once:
 * the ids already offered are remembered, so a built-in the person deleted
 * stays deleted, and a row they edited is never overwritten by the refresh.
 */
object ConsoleSystemsRepository {
    suspend fun allSystems(context: Context): List<ConsoleSystemDef> {
        val dao = ConsoleSystemsDatabase.get(context).consoleSystemDao()
        seedNewBuiltIns(context, dao)
        // Ownership is integration policy from the refreshable platform
        // database, not a user-editable platform property. Join it at read
        // time so a refresh takes effect without overwriting user edits.
        val owners = PlatformsDatabase.builtIns(context).associate { it.id to it.ownedBy }
        return dao.getAll().map { it.toConsoleSystemDef().copy(ownedBy = owners[it.id]) }
    }

    suspend fun upsert(context: Context, system: ConsoleSystemDef, isBuiltIn: Boolean = false) {
        ConsoleSystemsDatabase.get(context).consoleSystemDao().upsert(system.toEntity(isBuiltIn))
    }

    suspend fun delete(context: Context, id: String) {
        ConsoleSystemsDatabase.get(context).consoleSystemDao().delete(id)
    }

    /** Real, explicit "undo my platform edits, start over" action -- clears every built-in row (leaving any real user-added custom platform untouched) and reseeds from [PlatformsDatabase]. */
    suspend fun restoreDefaults(context: Context) {
        val dao = ConsoleSystemsDatabase.get(context).consoleSystemDao()
        dao.clearBuiltIns()
        val builtIns = PlatformsDatabase.builtIns(context)
        dao.upsertAll(builtIns.map { it.toEntity(isBuiltIn = true) })
        rememberOffered(context, builtIns.map { it.id })
    }

    private const val PREFS = "console_systems_seed"
    private const val KEY_OFFERED = "offered_builtin_ids"

    /**
     * Inserts every built-in platform never offered before. On a fresh
     * install that is all of them. On an install that predates this record
     * but already has rows, everything currently built in counts as offered
     * (some of it may have been deleted on purpose), so only platforms a
     * later refresh brings are added.
     */
    private suspend fun seedNewBuiltIns(context: Context, dao: ConsoleSystemDao) {
        val builtIns = PlatformsDatabase.builtIns(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val offered = prefs.getStringSet(KEY_OFFERED, null)
        if (offered == null) {
            if (dao.count() == 0) dao.upsertAll(builtIns.map { it.toEntity(isBuiltIn = true) })
            rememberOffered(context, builtIns.map { it.id })
            return
        }
        val fresh = builtIns.filter { it.id !in offered }
        if (fresh.isEmpty()) return
        val existing = dao.getAll().mapTo(HashSet()) { it.id }
        dao.upsertAll(fresh.filter { it.id !in existing }.map { it.toEntity(isBuiltIn = true) })
        rememberOffered(context, offered + fresh.map { it.id })
    }

    private fun rememberOffered(context: Context, ids: Collection<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_OFFERED, ids.toHashSet()).apply()
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
