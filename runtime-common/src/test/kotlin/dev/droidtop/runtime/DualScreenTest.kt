package dev.droidtop.runtime

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeStore(initial: Map<String, DualScreenRole> = emptyMap()) : DualScreenAssignmentStore {
    var saved: Map<String, DualScreenRole> = initial
    override suspend fun get(): Map<String, DualScreenRole> = saved
    override suspend fun set(assignment: Map<String, DualScreenRole>) {
        saved = assignment
    }
}

class DualScreenTest {
    private val primary = DisplayOutput("primary", 0, DisplayOutputKind.PRIMARY_SCREEN, 1920, 1080)
    private val secondary = DisplayOutput("second", 1, DisplayOutputKind.SECOND_SCREEN, 1920, 1080)

    @Test
    fun `with no saved choice, guesses primary is upper and second screen is lower`() = runBlocking {
        val coordinator = DualScreenCoordinator(FakeStore())

        val result = coordinator.resolve(listOf(primary, secondary))

        assertEquals(DualScreenRole.UPPER_OUTPUT, result[primary])
        assertEquals(DualScreenRole.LOWER_INPUT, result[secondary])
    }

    @Test
    fun `returns empty when there's no second screen`() = runBlocking {
        val coordinator = DualScreenCoordinator(FakeStore())
        assertTrue(coordinator.resolve(listOf(primary)).isEmpty())
    }

    @Test
    fun `swap flips the assignment and persists it`() = runBlocking {
        val store = FakeStore()
        val coordinator = DualScreenCoordinator(store)

        coordinator.swap(listOf(primary, secondary))
        val result = coordinator.resolve(listOf(primary, secondary))

        assertEquals(DualScreenRole.LOWER_INPUT, result[primary])
        assertEquals(DualScreenRole.UPPER_OUTPUT, result[secondary])
    }

    @Test
    fun `a persisted swap survives a fresh coordinator instance, same as a new session`() = runBlocking {
        val store = FakeStore()
        DualScreenCoordinator(store).swap(listOf(primary, secondary))

        // A brand new DualScreenCoordinator over the same store -- simulates the
        // assignment surviving app restart, per §4's "persisted per-output choice."
        val result = DualScreenCoordinator(store).resolve(listOf(primary, secondary))

        assertEquals(DualScreenRole.LOWER_INPUT, result[primary])
        assertEquals(DualScreenRole.UPPER_OUTPUT, result[secondary])
    }

    @Test
    fun `swapping twice returns to the original guess`() = runBlocking {
        val store = FakeStore()
        val coordinator = DualScreenCoordinator(store)

        coordinator.swap(listOf(primary, secondary))
        coordinator.swap(listOf(primary, secondary))
        val result = coordinator.resolve(listOf(primary, secondary))

        assertEquals(DualScreenRole.UPPER_OUTPUT, result[primary])
        assertEquals(DualScreenRole.LOWER_INPUT, result[secondary])
    }
}
