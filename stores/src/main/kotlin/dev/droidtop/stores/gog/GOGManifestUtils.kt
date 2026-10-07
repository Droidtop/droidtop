package dev.droidtop.stores.gog

/**
 * The file a GOG install keeps its manifest's post-install data in
 * (`_gog_manifest.json`: the installer-script and support-command steps
 * GOG's own client runs after an install). GameNative read it at launch to
 * run those steps in the game's Wine session; droidtop's launch does not run
 * them yet (docs/SPEC.md 7g, "Stores"), so the download only writes it.
 */
object GOGManifestUtils {
    const val MANIFEST_FILE_NAME = "_gog_manifest.json"
}
