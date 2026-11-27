# Plugin and application surfaces: static review, 2026-11-27

Base: `e4e9834137`. The owner requested code and static analysis only. No app was opened, no rig action performed,
and no device result is claimed. This review follows the remaining handoff items and checks older review findings
against today's source before proposing changes.

## Coverage map: every checklist area

The 160-task checklist in the coordinator workspace has 17 areas. **Mapped** below means an entry point was located,
not that every task in that area was traced. **Traced** refers only to the paths in the next section. No row means
“works on a device”. Kotlin paths use these roots: `app` = `app/src/main/kotlin/dev/droidtop/app/`, `gamepad` =
`shell-gamepad/src/main/kotlin/dev/droidtop/shell/gamepad/`, `desktop` = `shell-desktop/src/main/kotlin/dev/droidtop/shell/desktop/`,
`library` = `library-core/src/main/kotlin/dev/droidtop/library/`.

| Area / tasks | Source entry points | Review coverage and remaining gaps |
|---|---|---|
| A. First run, 1–11 | `app/OnboardingActivity.kt`, its `Onboarding*Step.kt` screens | Mapped. Permission decline, interrupted setup, keyboard/theme cancellation and fresh-install reachability still need a complete source trace. |
| B. Modes, 12–17 | `app/ModeStartup.kt`, `app/ModeSwitcherActivity.kt`, `gamepad/ModeSwitcherSheet.kt` | Mapped. Back/Home, Recents, mode-disabled paths and rotation-state retention remain unreviewed in this pass. |
| C. Library, 18–33 | `library/LibraryEntry.kt`, `gamepad/query/LibraryQueryUi.kt`, `library/scraper/LibraryScrapeJob.kt` | Source-search section traced below; library merge/split, versions, large-list complexity and import paths still need separate review. |
| D. Launching, 34–47 | `app/GameLaunchActivity.kt`, `library/GameEngineDetector.kt`, `library/LaunchWatchdog.kt`, `gamepad/pc/HowItRuns.kt` | Mapped. Parent work owns cross-mode Android game pages. Each runner's setup/failure/return path and session cleanup remain separate traces. |
| E. Emulator setup, 48–53 | `app/settings/EmulatorsCatalog.kt`, `library/consoles/BiosDatabase.kt`, `library/integrations/AcquireBios.kt` | Mapped. Source of every file, explicit destination consent and cancelled BIOS/setup flows remain unreviewed. |
| F. PC/Wine/containers, 54–63 | `gamepad/pc/PcGamePage.kt`, `app/settings/WineOptionsCatalog.kt`, `app/settings/ContainersCatalog.kt` | Mapped. Runtime/store agents own ongoing replacement/build work. Executable selection, prefix reset, redistributables and architecture/VPN paths need their own traces. |
| G. Stores/accounts, 64–68 | `app/settings/StoresCatalog.kt`, `library/stores/StoreSyncs.kt`, `app/settings/PluginsCatalog.kt` | Plugin sync bypass traced and fixed here. Account sign-in/out, installs, uninstall, ownership merge and metered automatic sync are not established by that fix. |
| H. Downloads/updates/plugins, 69–79 | `app/settings/UpdatesPage.kt`, `app/settings/PluginsCatalog.kt`, `library/integrations/AcquireQueue.kt`, `AcquireIndexing.kt` | Main focus of this review. Guard and widget/sync fixes below; still trace background page lifetime, restart, sign-in session lifetime and unavailable-source routes in every mode. |
| I. Gaming, 80–88 | `gamepad/GamepadShell.kt`, `gamepad/SettingsCatalogView.kt`, `gamepad/query/LibraryQueryUi.kt` | Plugin paths traced. General theme rendering, hints, filtering, touch and place navigation remain unreviewed here. Contrast/motion proposals are not yet implemented. |
| J. Mobile, 89–94 | `shell-default` submodule, `gamepad/LauncherGamesScreen.kt`, `app/LauncherGamesActivity.kt` | Plugin widgets traced. Launcher workspace, drawer, third-party widgets, Recents and taskbar require the pinned launcher source and a dedicated review. |
| K. Desktop, 95–106 | `desktop/DesktopShell.kt`, `DesktopStartMenu.kt`, `library/integrations/DesktopPluginFacets.kt` | Plugin facet entry points mapped. Window-management/input, clipboard/USB/printing and task lifecycle not fully traced. Parent owns Android app page routing. |
| L. Companion, 107–114 | `app/CompanionTabs.kt`, `CompanionSurface.kt`, `CompanionPlugins.kt`, `CompanionNetwork.kt` | Plugin panel path traced. Display reinitialization, screen-off/wake, pointer/typing and game launch placement remain unreviewed. |
| M. Input, 115–120 | `input-keyboard/src/main/java/org/pocketworkstation/pckeyboard/`, `runtime-common/.../settings/Keyboards.kt`, `library/GamePadLayouts.kt` | Mapped. No claims about language engines, clipboard/autofill, pad text input or remapping correctness yet. |
| N. Enginehost, 121–140 | Separate Enginehost repo: `app/src/main/kotlin/dev/enginehost/MainActivity.kt`, `GameActivity.kt`, `LaunchActivity.kt`, `PluginCatalogActivity.kt`, `EnginehostSettingsActivity.kt` | Entry points located in the available Enginehost checkout only; current-main behavior not deeply audited here. Ren'Py per-line controller diagnosis remains evidence-dependent, as the handoff records. |
| O. Computers/network, 141–147 | `app/settings/ComputersCatalog.kt`, `StorageSharesCatalog.kt`, `app/CompanionNetwork.kt` | Mapped. Pairing identity, reconnection, shares, VPN and browser/session lifetimes remain separate traces; parent owns Pythia migration. |
| P. Settings/system, 148–153 | `app/settings/DroidtopWideSettings.kt`, `AppSettingsCatalogs.kt`, `gamepad/SettingsCatalogView.kt` | Shared settings root entry verified in source; plugin-specific rows traced. Whole-tree reachability, accessibility, storage/backup and developer-only visibility need further review. |
| Q. Failures/recovery, 154–160 | `library/diagnostics/CrashRecovery.kt`, `DiagnosticsArchive.kt`, `library/LaunchWatchdog.kt`, acquire queue/indexing | Mapped. Mid-operation process death, missing storage, full disk, lost network and undo coverage not proven. No destructive test was run. |

## Traced paths

| Surface | Source path from entry to result | Static finding |
|---|---|---|
| Plugin page | `app/.../settings/PluginsCatalog.kt` → `PluginFacets.pageScreen` → `PluginViews` → catalog renderer | `plugin.page` already reaches the shared facet renderer. Badge chips, tones, icons and danger questions are present on this base. |
| Quick Menu and companion plugin pages | `PluginPanels.listed` → `panelScreen` → `PluginViews.screen` | Quick Menu and companion facets are preferred over the old panel where declared. Legacy tiles and More rows still have separate construction paths. |
| Declared plugin settings | `PluginSettingsRows` through the shared catalog | The old `settings.page` target is retired; do not revive it while consolidating panels. |
| Plugin app status | Plugin page / `PluginPanels.moreGroups` / emulator settings → `PluginAppStatus.screenFor` | Still a separate legacy capability, including a free-text job. Must be adapted without dropping managed-app launch or job functionality. |
| Plugin store sync | `PluginsCatalog` → store sync | Fixed here: use `StoreSyncs.run`, rather than bypassing successful-sync bookkeeping, hidden mirroring, automatic updates and metadata requests. |
| Aggregate Plugin status widget | `PluginStatusWidgetProvider` | Fixed here: use `PluginWidgets` sources/state and `PluginWidgetContent`, rather than a second legacy invoke and invented “OK” value. Each tile now keeps its id and obeys widget/game scope. |
| Configurable Plugin widget | `PluginWidgetConfigureActivity` → `PluginWidgets` → `PluginWidgetProvider` → action receiver | Shared widget transport and renderer exist. Full `widget` Facets consumption remains separate work; this change does not pretend the C10 templates are Facets. |
| Facet sections and forms | `FacetGuards.admit` / `unhidden` → `FacetViews` | Fixed here: child subject/placement checks, submit grant validation and nested hiding. JVM cases cover hostile and valid content. |
| Source search and acquisition | `LibraryQueryUi` → `UnifiedSearch` / `AcquireContentSources` → `PluginViews.acquire` → download queue → `AcquireIndexing` | The earlier blocked-source in-place ask exists on this base. Download/session lifetime and restart behavior require their own code audit; a successful source search does not prove an acquisition completes. |

Paths abbreviated as `Plugin*.kt` are under `library-core/src/main/kotlin/dev/droidtop/library/integrations/`,
unless named with another module. Guards and facet models are in `plugin-host/src/main/kotlin/dev/droidtop/pluginhost/`.

## Remaining work, in dependency order

1. Derive the legacy Panel, App status and store-action content into Facets, preserving current actions and grants.
   Simply hiding those rows when a plugin declares `plugin.page` would silently remove features: the contract does
   not promise that its page repeats every legacy managed-app or store action. Keep one guarded renderer after adaptation.
2. Move aggregate/configurable widgets onto the `widget` facet target, retaining existing widget configurations and
   per-entry identities. Status, quick and template widgets currently share calls but still use older wire models.
3. Make source search reachable outside Gaming, then trace sign-in → acquire → queue → index independently of page lifetime.
4. Review the DroidDeck visual proposals against the current shared components, especially contrast fitting and settings
   search. Those are product changes, not evidence that the existing routes are broken.

## Validation boundary

The changed guard rules have unit tests; the widget reuses existing renderer/protocol cases, with an added missing-value
case. Required Android build/checks validate the commit. They do not establish pad, touch, accessibility, light-mode or
layout usability. Those checks remain paused and are listed under **Needs a rig check** in the commit message.
