package dev.droidtop.library

import java.io.File

/**
 * The seed databases these tests parse are the SHIPPED ones -- the point of
 * the tests is that a registry edit that breaks detection fails CI before it
 * reaches a device. They are no longer checked in: `platformDatabaseSeed`
 * copies them out of the pinned vendor/droidtop-platforms submodule into the
 * module's generated assets, and the test task depends on that copy, so this
 * is where a test finds them.
 */
object SeedAssets {
    private val dir = File("build/generated/platformDatabase/assets")

    fun read(name: String): String = File(dir, name).readText()
}
