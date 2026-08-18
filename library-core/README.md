# library-core

Unified library/metadata layer, modeled on Playnite's plugin architecture
(no direct Android equivalent exists — this is a genuine gap the project
fills, not a fork). Native Android apps, Wine profiles, and Linux-container
apps are all `LibraryEntry` items from a `LibraryProvider`, aggregated into
one `Library`.

## Why this exists even though no launcher UI is being built yet

The project's near-term UI is `shell-default` (a normal touch-first grid),
but the goal is for the whole thing to eventually work as an actual
gamepad-driven launcher too (`shell-gamepad`, still a stub). Building the
data model launcher-ready now — metadata, artwork, playtime, a common launch
interface across APKs/Wine/containers — means that's a new shell later, not
a rearchitecture of this layer. Don't add anything here that only makes
sense for one shell or the other.

## Status

Interfaces only — no `LibraryProvider` implementations yet. First one worth
building is `NATIVE_ANDROID_APP` (trivial, `PackageManager` queries), which
proves the plugin model before wiring in the harder Wine/container
providers.
