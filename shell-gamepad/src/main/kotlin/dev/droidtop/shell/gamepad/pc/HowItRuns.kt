package dev.droidtop.shell.gamepad.pc

import android.content.Context
import dev.droidtop.library.GameNaming
import dev.droidtop.library.LaunchStrategyOverridePrefs
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.PcRunnerOptions
import dev.droidtop.library.PcRunners
import dev.droidtop.library.RunnerState
import dev.droidtop.library.displayName
import dev.droidtop.shell.gamepad.ActionRow

/**
 * Every way a game can run, for the dropdown beside its Play button (docs/SPEC.md "How it runs, beside Play",
 * mytop-project/tracker#965; the owner, 2026-11-27: "It should be under a dropdown arrow next to the "run button"", and
 * "look at how steam handles games with in-home streaming options"). As on Steam's page, one list holds the runners on
 * this device, the store's other ways to play (Steam's "Stream with Steam Link") and "Stream from <computer>" for each
 * paired computer that has the game.
 */
internal data class HowItRuns(
    val runners: PcRunners,
    /** The runner Play uses now. */
    val current: dev.droidtop.library.GameLaunchStrategy?,
    /** Each paired computer that has the game, with the key it has it under. */
    val streams: List<Pair<dev.droidtop.net.peer.Computer, String>>,
    val storeOptions: List<dev.droidtop.library.stores.StorePlayOption>,
) {
    /** Whether there is a choice to make: more than one way in all, so the dropdown is worth drawing. */
    val hasChoice: Boolean get() = runners.options.count { it.selectable } + streams.size + storeOptions.size > 1

    companion object {
        /** Reads it all: runner checks, the store's files and the paired computers' libraries. Off the main thread. */
        fun load(context: Context, entry: LibraryEntry): HowItRuns {
            val runners = PcRunnerOptions.forEntry(context, entry)
            val resolved = PcRunnerOptions.resolvedFor(context, entry, runners)
            val store = entry.ownStore()
            val storeOptions = store?.let {
                runCatching { it.playOptions(context, (entry.pcInfo?.storeId ?: entry.id).substringAfter(':')) }.getOrNull()
            }.orEmpty()
            return HowItRuns(runners, resolved?.option?.strategy, streamSources(context, entry), storeOptions)
        }

        /**
         * The paired computers that have [entry], with the key each has it under (docs/SPEC.md 7o, mytop-project/tracker#860),
         * by the key every device uses ([dev.droidtop.library.computers.ComputerLibrary.keysOf]). A computer's own game
         * streams from it with its Play already, so only the others. Reads files: off the main thread. The game menu's
         * "Stream from" rows read the same list.
         */
        fun streamSources(context: Context, entry: LibraryEntry): List<Pair<dev.droidtop.net.peer.Computer, String>> = runCatching {
            val keys = if (entry.id.startsWith("computer:")) {
                listOf(dev.droidtop.library.computers.ComputerLaunch.keyOf(entry.id, entry.pcInfo?.storeId))
            } else {
                dev.droidtop.library.computers.ComputerLibrary.keysOf(entry)
            }
            val computers = dev.droidtop.net.peer.Computers.list(context)
            val own = entry.id.takeIf { it.startsWith("computer:") }?.removePrefix("computer:")?.substringBefore(':')
            val found = LinkedHashMap<String, Pair<dev.droidtop.net.peer.Computer, String>>()
            keys.forEach { key ->
                dev.droidtop.library.computers.ComputerLibrary.computersWith(context, key, computers)
                    .forEach { pc -> found.putIfAbsent(pc.id, pc to key) }
            }
            dev.droidtop.library.computers.ComputerLaunch.order(found.values.map { it.first })
                .filter { it.id != own }
                .map { pc -> pc to found.getValue(pc.id).second }
        }.getOrNull().orEmpty()
    }
}

/**
 * The dropdown's rows. A runner on this device: pressing it makes it the game's runner, which Play then uses and which
 * stays until changed (Steam remembers a launch choice too); a runner that needs setup runs its setup first. A store's
 * other way to play and each "Stream from <computer>" start at once. [say] reports what happened, [onChanged] tells the
 * page to read the runner again, [close] closes the dropdown. Pure but for the actions.
 */
internal fun howItRunsRows(
    context: Context,
    entry: LibraryEntry,
    how: HowItRuns,
    launch: (suspend () -> String) -> Unit,
    onChanged: () -> Unit,
    close: () -> Unit,
): List<ActionRow> = buildList {
    how.runners.options.filter { it.selectable }.forEach { option ->
        val current = option.strategy == how.current
        add(
            ActionRow(
                option.strategy.displayName(how.runners.engine) + if (current) " (current)" else "",
                when (option.state) {
                    RunnerState.READY -> option.caveat ?: "Ready"
                    else -> option.reason ?: "Needs setup"
                },
                onSelect = {
                    close()
                    if (!current) {
                        LaunchStrategyOverridePrefs.set(context, entry.id, option.strategy)
                        onChanged()
                    }
                },
            ),
        )
    }
    how.storeOptions.forEach { option ->
        add(
            ActionRow(option.title, option.detail, onSelect = {
                close()
                launch {
                    val store = entry.ownStore() ?: return@launch "That store isn't signed in"
                    store.playWith(context, (entry.pcInfo?.storeId ?: entry.id).substringAfter(':'), option.id, GameNaming.displayName(entry.title))
                }
            }),
        )
    }
    how.streams.forEach { (pc, key) ->
        add(
            ActionRow(
                dev.droidtop.library.computers.ComputerLaunch.streamTitle(pc),
                "Starts it on ${pc.name} and shows it here, with its sound and your controls",
                onSelect = {
                    close()
                    launch { dev.droidtop.library.computers.ComputerLaunch.stream(context, pc, key, GameNaming.displayName(entry.title), null) }
                },
            ),
        )
    }
}
