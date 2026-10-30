package dev.droidtop.runtime

/**
 * Where droidtop's idle SECONDARY_HOME surface (`dev.droidtop.display.SecondaryDisplayActivity`) is resumed right now,
 * kept here so the modules that cannot see `:display` read the same value: `:display`'s orchestrator, and
 * `:shell-default`'s Home path, which does not start the surface again on a display that already shows it
 * (docs/SPEC.md 4c, "The Alternative forwarder keeps the second screen"). Null while it is resumed nowhere.
 */
object SecondaryCover {
    @Volatile
    var resumedDisplayId: Int? = null
}
