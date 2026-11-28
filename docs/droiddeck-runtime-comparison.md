# DroidDeck runtime comparison

Source evidence for tracker #968. Droidtop baseline is `db41d6ec62`.
DroidDeck baseline is `ab99c1a3b3101cd84eebfa6c94adda40064f2ef6`.

| Proposal | What the checked source establishes |
|---|---|
| Adopt Proton with FEX | Droidtop already selects ARM64EC Proton with FEXCore on arm64 in `runtime-windows/.../DroidtopPcGameRuntime.kt`. `ContainerData.kt` stores FEXCore and Box64 options. DroidDeck has a separate [FEX catalog](https://github.com/Droid-Deck/DroidDeck/blob/ab99c1a3b3101cd84eebfa6c94adda40064f2ef6/app/src/main/java/com/droiddeck/launcher/session/FexCatalog.kt) and [Proton selection policy](https://github.com/Droid-Deck/DroidDeck/blob/ab99c1a3b3101cd84eebfa6c94adda40064f2ef6/app/src/main/java/com/droiddeck/launcher/session/ProtonDefault.kt). Adding these names is not a missing-capability fix. Replacing either launch path requires an ABI, container and settings comparison. |
| Adopt Turnip bundles | Droidtop already handles Turnip installation and selection in `runtime-windows/.../PrefixSetup.kt` and detects support in `GPUInformation.java`. DroidDeck's [driver importer](https://github.com/Droid-Deck/DroidDeck/blob/ab99c1a3b3101cd84eebfa6c94adda40064f2ef6/app/src/main/java/com/droiddeck/launcher/gpu/TurnipDriver.java) validates AdrenoTools packages and rejects incompatible glibc libraries. Compare the supported package ABI and validation, rather than assuming Turnip support is absent. |
| Replace the GameNative-derived stack | `runtime-windows/build.gradle.kts` identifies the existing Winlator/GameNative runtime sources and explicitly excludes GameNative's UI and JavaSteam. Droidtop's store implementations live in `:stores`. Store protocol replacements must be evaluated per provider. |

[DroidDeck's Steam client preparation](https://github.com/Droid-Deck/DroidDeck/blob/ab99c1a3b3101cd84eebfa6c94adda40064f2ef6/app/src/main/java/com/droiddeck/launcher/session/SteamClientPrefetch.kt)
prefetches the Valve client. Droidtop's `:stores` Steam path supplies depot
transfers through `SteamFetcher`. Prefetching the client is not a drop-in
replacement for the existing library, download, and install paths.

Droidtop already has separate Epic and GOG manifest and transfer code, EA
authentication, and Amazon downloads in `:stores`. The pinned DroidDeck
tree does not identify Legendary, Phobos, gogdl, or Maxima by filename.
That does not establish their absence from packaged components or service
dependencies. Verify the actual executable or service and its ownership,
authentication, install, and launch contracts before proposing a replacement.

The pinned tree includes Steam input integrations. A separately identifiable
WinNative patch and its compatibility with Droidtop's input DLLs remain
unverified. No runtime, driver, or input behavior was tested on a device.
