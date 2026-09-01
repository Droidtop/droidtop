package dev.droidtop.runtime

import android.content.Context

/**
 * Which physical role a [DisplayOutput] plays in droidtop's dual-screen
 * handling — the general model §4 establishes (physical position, not
 * enumeration order; manual override + a persisted choice, the same
 * pattern Mjolnir uses for exactly this problem), applied consistently
 * across Desktop and Handheld modes. droidtop treats a dual-screen
 * handheld as a real dual-monitor computer throughout (per direction:
 * "we're treating this handheld like a full computer throughout") — this
 * is deliberately not a `:shell-gamepad`-only concept.
 */
enum class DualScreenRole { UPPER_OUTPUT, LOWER_INPUT }

/** Persists the current [DisplayOutput.id]-to-[DualScreenRole] assignment so a manual swap survives across sessions — §4's "persisted per-output choice," not re-derived every launch. */
interface DualScreenAssignmentStore {
    suspend fun get(): Map<String, DualScreenRole>
    suspend fun set(assignment: Map<String, DualScreenRole>)
}

/**
 * SharedPreferences-backed [DualScreenAssignmentStore] — no root, no
 * container backend involved, so this lives directly in runtime-common
 * rather than a backend-specific module.
 */
class PrefsDualScreenAssignmentStore(context: Context) : DualScreenAssignmentStore {
    private val prefs = context.getSharedPreferences("dual_screen_assignment", Context.MODE_PRIVATE)

    override suspend fun get(): Map<String, DualScreenRole> =
        prefs.all.mapNotNull { (id, value) ->
            (value as? String)?.let { roleName ->
                runCatching { DualScreenRole.valueOf(roleName) }.getOrNull()?.let { id to it }
            }
        }.toMap()

    override suspend fun set(assignment: Map<String, DualScreenRole>) {
        prefs.edit().apply {
            clear()
            assignment.forEach { (id, role) -> putString(id, role.name) }
        }.apply()
    }
}

/**
 * Resolves which [DisplayOutput] plays which [DualScreenRole]. Android
 * gives no reliable physical-position signal (§4 — `DisplayManager`
 * enumeration order isn't guaranteed to match which panel is physically
 * upper/lower), so [DisplayOutputKind] is only ever a *starting guess* —
 * [PRIMARY_SCREEN][DisplayOutputKind.PRIMARY_SCREEN] guessed as
 * [DualScreenRole.UPPER_OUTPUT], the first
 * [SECOND_SCREEN][DisplayOutputKind.SECOND_SCREEN] guessed as
 * [DualScreenRole.LOWER_INPUT] — immediately overridable via [swap] and
 * persisted from then on via [store], the same "manual swap + persisted
 * choice" behavior Mjolnir uses for this exact problem (§4).
 */
class DualScreenCoordinator(private val store: DualScreenAssignmentStore) {
    suspend fun resolve(outputs: List<DisplayOutput>): Map<DisplayOutput, DualScreenRole> {
        val primary = outputs.firstOrNull { it.kind == DisplayOutputKind.PRIMARY_SCREEN }
        val secondary = outputs.firstOrNull { it.kind == DisplayOutputKind.SECOND_SCREEN }
        if (primary == null || secondary == null) return emptyMap()

        val saved = store.get()
        val relevant = saved.filterKeys { it == primary.id || it == secondary.id }
        if (relevant.size == 2 && relevant.values.toSet() == setOf(DualScreenRole.UPPER_OUTPUT, DualScreenRole.LOWER_INPUT)) {
            return mapOf(
                primary to relevant.getValue(primary.id),
                secondary to relevant.getValue(secondary.id),
            )
        }

        // No (complete) saved choice yet -- fall back to the DisplayOutputKind guess.
        return mapOf(primary to DualScreenRole.UPPER_OUTPUT, secondary to DualScreenRole.LOWER_INPUT)
    }

    /** Flips the current UPPER_OUTPUT/LOWER_INPUT assignment and persists it — the actual "manual override" action a settings toggle calls. */
    suspend fun swap(outputs: List<DisplayOutput>) {
        val current = resolve(outputs)
        if (current.size != 2) return
        val flipped = current.entries.associate { (output, role) ->
            output.id to if (role == DualScreenRole.UPPER_OUTPUT) DualScreenRole.LOWER_INPUT else DualScreenRole.UPPER_OUTPUT
        }
        store.set(flipped)
    }
}

/**
 * The user-facing display actions: swap which physical panel is the main
 * output, and force a fresh detection pass.
 *
 * These live here rather than on the Activity because everything they
 * need -- [DisplayOutputRepository], [DualScreenCoordinator],
 * [PrefsDualScreenAssignmentStore] -- is already in this module, and a
 * settings catalog item only ever gets a `Context`.
 *
 * [refresh] is the signal back to whatever is orchestrating displays:
 * writing the assignment changes no `DisplayManager` state, so nothing
 * would re-emit on its own and a swap would appear to do nothing until
 * the next unrelated display event.
 */
object DisplayArrangement {
    val refresh = kotlinx.coroutines.flow.MutableStateFlow(0)

    /**
     * Flips which panel is [DualScreenRole.UPPER_OUTPUT] and remembers it.
     * Returns a line for the user, since the screen they are reading may
     * be the one that just moved.
     */
    suspend fun swap(context: Context): String {
        val outputs = DisplayOutputRepository(context).currentOutputsSnapshot()
        if (outputs.size < 2) return "Only one display is connected."
        DualScreenCoordinator(PrefsDualScreenAssignmentStore(context)).swap(outputs)
        refresh.value++
        return "Swapped. The main screen is now the other panel."
    }

    /** Re-runs detection and orchestration from scratch. */
    fun reinitialize(): String {
        refresh.value++
        return "Displays reinitialized."
    }
}
