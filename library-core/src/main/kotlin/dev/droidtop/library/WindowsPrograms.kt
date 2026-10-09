package dev.droidtop.library

import android.content.Context
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.CatalogScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A Windows launch that could not tell which program in the game's folder
 * is the game (docs/SPEC.md 7i, "Which program runs"): several programs
 * are equally likely and nothing in the folder says which. A failure the
 * person fixes in one step, so it carries what the fix needs ([entryId],
 * [gameRoot]) the way [dev.droidtop.library.consoles.NoEmulatorInstalled]
 * carries its system, and the shell offers the program choice
 * ([WindowsPrograms.screen]) on the spot instead of parsing English.
 */
class ProgramNotIdentified(
    val entryId: String,
    val title: String,
    val gameRoot: String,
) : IllegalStateException(
    "Can't launch $title: couldn't tell which program in $gameRoot is the game. " +
        "Choose it under the game's options, Program.",
)

/**
 * Which program a Windows game runs, and the person's choice of it: ONE
 * mechanism behind every place that offers the choice (the launch failure,
 * the game's options, its page, its Wine and graphics screen). The list is
 * [PcFolderClassifier]'s, the same rule that picks the program Play runs, so
 * the choice offers exactly the programs detection weighed and nothing it
 * collapsed (installers, uninstallers, redistributables, crash handlers).
 * The choice is kept as the game's [WineGameSettings.executable], which
 * [WindowsLaunchResolver] honours on every launch path.
 */
object WindowsPrograms {

    const val SCREEN_ID = "windows_game_program"

    /** One program the person can pick: its path below the game's folder, `/`-separated. */
    data class Program(val path: String, val role: ExeRole)

    /**
     * The programs of a game's folder: what detection runs on its own
     * ([detected], null when it cannot tell) and what the person chose
     * ([chosen], null when nothing is chosen or the chosen file is gone).
     */
    data class Choices(val programs: List<Program>, val detected: String?, val chosen: String?) {
        /** What Play runs now: the person's choice, else detection's. */
        val current: String? get() = chosen ?: detected
    }

    /** [gameRoot]'s programs for [entryId]. Disk work: never on the main thread. */
    fun choices(context: Context, entryId: String, gameRoot: File): Choices {
        val listed = PcFolderClassifier.read(gameRoot).filter { it.extension == "exe" }
        val facts = PcFolderClassifier.classify(listed, gameRoot.name)
        val programs = (listOfNotNull(facts.main) + facts.alternatives).map { Program(it, PcFolderClassifier.roleOf(it)) }
        val detected = GameExecutableResolver.windowsExecutable(gameRoot)?.let { relativePath(gameRoot, it) }
        // A choice whose file went away is not one any more; the resolver
        // passes over it the same way.
        val chosen = WineGameSettingsPrefs.get(context, entryId)?.executable?.takeIf { File(gameRoot, it).isFile }
        return Choices(programs, detected, chosen)
    }

    /**
     * Makes [path] (below the game's folder) the program [entryId] runs, or
     * hands the choice back to detection when [path] is null. Arguments and
     * a start folder an import saved belong to the program they were saved
     * with, so they are kept only while the program stays the same.
     */
    fun choose(context: Context, entryId: String, path: String?) {
        val old = WineGameSettingsPrefs.get(context, entryId)
        val next = when {
            path == null -> null
            old?.executable == path -> old
            else -> WineGameSettings(executable = path)
        }
        WineGameSettingsPrefs.setProgram(context, entryId, next)
    }

    /**
     * The choice as a settings screen: one row per program, the one Play
     * runs marked, and a row that hands the choice back to detection once
     * the person made one. The same screen wherever the choice is offered:
     * in a sheet from the launch failure, the game's options and its page,
     * and nested in the game's Wine and graphics screen.
     */
    fun screen(entryId: String, title: String, gameRoot: String): CatalogScreen = CatalogScreen(
        id = SCREEN_ID,
        title = "Program",
        subtitle = title,
        groups = { context -> withContext(Dispatchers.IO) { groups(context, entryId, File(gameRoot)) } },
        indexGroups = { _ -> emptyList() },
    )

    private fun groups(context: Context, entryId: String, gameRoot: File): List<CatalogGroup> {
        val choices = choices(context, entryId, gameRoot)
        val items = buildList<CatalogItem> {
            choices.programs.forEach { program ->
                add(
                    AsyncActionItem(
                        // The id names the row's state too, so the line a pick
                        // leaves on its row goes when the row's state changes.
                        id = "program:${program.path}" + if (program.path == choices.current) ":runs" else "",
                        title = program.path,
                        subtitle = when {
                            program.path == choices.chosen -> "Your choice"
                            program.path == choices.detected -> "The one droidtop picks"
                            program.role == ExeRole.TOOL -> "A settings screen, patcher, updater or editor"
                            else -> null
                        },
                        value = if (program.path == choices.current) "Runs" else null,
                        run = { ctx, _ ->
                            choose(ctx, entryId, program.path)
                            "Runs"
                        },
                    ),
                )
            }
            if (choices.chosen != null) {
                add(
                    AsyncActionItem(
                        id = "program:detect",
                        title = "Let droidtop pick",
                        subtitle = choices.detected?.let { "It would run $it" }
                            ?: "It can't tell which program is the game here",
                        run = { ctx, _ ->
                            choose(ctx, entryId, null)
                            "droidtop picks"
                        },
                    ),
                )
            }
        }
        return listOf(
            CatalogGroup(
                id = SCREEN_ID,
                title = when {
                    choices.programs.isEmpty() -> "No program in ${gameRoot.name}"
                    choices.current == null -> "droidtop can't tell which program is the game: choose one"
                    else -> "Which program starts the game"
                },
                items = items,
            ),
        )
    }

    /** [file]'s path below [root], `/`-separated, or null when it is not below it. */
    internal fun relativePath(root: File, file: File): String? {
        val base = runCatching { root.canonicalFile.path }.getOrNull() ?: return null
        val path = runCatching { file.canonicalFile.path }.getOrNull() ?: return null
        if (!path.startsWith(base + File.separator)) return null
        return path.substring(base.length + 1).replace(File.separatorChar, '/')
    }
}
