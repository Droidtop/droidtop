package dev.droidtop.library

import dev.droidtop.shared.launchcontract.EnginehostConfig
import dev.droidtop.shared.launchcontract.EnginehostIntents
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The contract with Enginehost is held once, in droidtop-shared's `:launch-contract` (Droidtop/tracker#204).
 * droidtop's own names are the contract's, and what droidtop writes into an Enginehost config passes the
 * shared shape check.
 */
class EnginehostContractTest {
    @Test
    fun droidtopsNamesAreTheContractsNotCopiesOfThem() {
        assertEquals(EnginehostIntents.PACKAGE, EngineHost.PACKAGE_NAME)
        assertEquals(EnginehostIntents.ACTION_CONFIGURE, EngineHost.ACTION_CONFIGURE)
        assertEquals(EnginehostIntents.ACTION_CONFIGURE_SETTINGS, EngineHost.ACTION_CONFIGURE_SETTINGS)
        assertEquals(EnginehostIntents.ACTION_CONFIGURE_SAVES, EngineHost.ACTION_CONFIGURE_SAVES)
        assertEquals(EnginehostIntents.ACTION_GAME_ENDED, EngineHost.ACTION_GAME_ENDED)
    }

    @Test
    fun theConfigDroidtopSendsIsOneTheSharedCheckAccepts() {
        val target = EnginehostTarget(engine = "rpgmaker", engineContext = "vxace", runtimeRequirements = mapOf("ruby" to "1.9.2"))
        assertEquals(emptyList<String>(), EnginehostConfig.problems(target.toConfigJson(engineVersion = "3.0").toString()))
        // Without a version: what an inline config for a game with no version of its own looks like.
        assertEquals(emptyList<String>(), EnginehostConfig.problems(target.toConfigJson(engineVersion = null).toString(), requireVersion = false))
    }
}
