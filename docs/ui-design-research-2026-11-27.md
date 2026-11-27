# UI design review: task completion before visual polish

## Charter

Goal: identify substantial UI gaps with source evidence, distinguish defects from
design recommendations, and define acceptance criteria for the code push. Scope:
handheld and desktop software. Object: Droidtop, Enginehost and Pythia. Subjects:
navigation, completing tasks, input, accessibility, feedback and adaptive layouts.
Type: descriptive; the architecture is understood well enough to specify changes.

Questions: Q1 can people find and complete the main tasks in each mode? Q2 do pad,
touch, keyboard, narrow windows and accessibility have explicit support? Q3 which
claims are proven by source, inferred from design guidance, or still unknown?

Knowledge depth: working architecture knowledge, partial surface coverage. Source
baseline: Droidtop `e4e9834137`; Pythia's in-progress native UI `92f0162`, not its
packaged default. Enginehost's detailed source audit is separate. External sources
were opened on 2026-11-27. Live application and device testing is paused.

## Canonical sources

| Rank | Source | Purpose |
|---|---|---|
| 1 | [Android layout and navigation](https://developer.android.com/design/ui/mobile/guides/layout-and-content/layout-and-nav-patterns) | Primary versus secondary navigation and size adaptation |
| 1 | [Compose API accessibility defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults) and [semantics](https://developer.android.com/develop/ui/compose/accessibility/semantics) | Interactive roles, state, touch targets, announcements |
| 1 | [Compose focus](https://developer.android.com/develop/ui/compose/touch-input/focus/request-focus) and [list-detail](https://developer.android.com/develop/ui/compose/layouts/adaptive/list-detail) | Focus restoration and narrow/wide navigation |
| 1 | [Valve handheld recommendations](https://partner.steamgames.com/doc/steamhardware/recommendations) | Complete controller flows and text entry |
| 1 | [Apple settings](https://developer.apple.com/design/human-interface-guidelines/settings.md) and [disclosure controls](https://developer.apple.com/design/human-interface-guidelines/disclosure-controls.md) | Contextual options and progressive disclosure; platform-specific details do not automatically transfer |
| 2 | W3C [focus visibility](https://www.w3.org/WAI/WCAG22/Understanding/focus-not-obscured-minimum.html), [status messages](https://www.w3.org/WAI/WCAG22/Understanding/status-messages.html), [contrast](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html) and [target size](https://www.w3.org/WAI/WCAG22/Understanding/target-size-minimum.html) | Checkable accessibility criteria, with native-platform qualifications |
| 1 | [Nielsen's usability heuristics](https://www.nngroup.com/articles/ten-usability-heuristics/) | Status, task vocabulary, discoverability, exits and recovery |
| 3 | Current source and `ui-static-review-2026-11-27.md` | Evidence of this product's wiring; not observations of its rendered UI |

## Findings ledger

| ID | Claim and evidence | Rank | Answers |
|---|---|---|---|
| F1 | Android distinguishes primary destinations from sibling tabs and secondary actions, and adapts navigation to window size. See Android navigation above. | 1 | Q1, Q2 |
| F2 | Custom interactive components need semantic roles and state; Android recommends 48dp touch targets. Compose defaults and semantics above. Painting a switch is insufficient by itself. | 1 | Q2 |
| F3 | Valve recommends controller access to all functionality and on-screen text entry. This is a useful handheld criterion, not a claim that these apps require Steam APIs or certification. | 1 | Q2 |
| F4 | Focus must remain visible; changing status should be perceivable without unnecessarily moving focus. W3C focus/status references above; Compose exposes live-region and progress semantics. | 1–2 | Q2 |
| F5 | Context-specific options belong near their task; advanced detail can be disclosed while essential controls remain visible. Apple settings/disclosure above. | 1 | Q1 |
| F6 | Feedback, recognizable actions, exits and recovery matter throughout a task, not just on its first screen. Nielsen above. | 1 | Q1, Q3 |
| F7 | Droidtop toggle rows suppress value text and pass `switchOn` to a painted switch without a switch role/state on that route: `ui-shared/.../CatalogNavigator.kt:1653`, `:1741`; `ui-shared/.../MenuRow.kt:482`. The adjustment arrow at `MenuRow.kt:562` lacks a spoken Increase/Decrease label. | 3 | Q2, Q3 |
| F8 | Mobile search opens a page only for PC/engine games: `app/.../LauncherSearchActivity.kt:96`. Other library entries immediately launch, despite console/app detail support. The cross-mode page host initially excluded native apps too; candidate `ci/surfaces-static` addresses that host. | 3 | Q1, Q3 |
| F9 | Pythia's new UI places eight destinations in one `FlowRow` (`ui-native/.../LibraryScreen.kt:40`), without a compact/expanded navigation branch. This is a structural observation; the degree of visual overload is unmeasured. | 3 | Q1, Q2 |
| F10 | Pythia's new switch wrappers are buttons around `ShellSwitch` (`IntegrationsPages.kt:46`; `LibraryScreen.kt:230`; `DevicesPage.kt`), without explicit toggle state. The native screens also lack explicit initial/restored focus. Source-backed implementation gaps, not hardware observations. | 3 | Q2, Q3 |
| F11 | Pythia migration still lacks complete Review, collections, version/store-link editing, settings, tray integration and packaging parity. Its own `docs/native-ui-surfaces.md` records these. Backend-call counts are not a usability/completeness score. | 3 | Q1, Q3 |
| F12 | Counterevidence: Get games already exists outside Gaming (`LauncherSearchActivity.kt:45`, `getGamesIntent`), and the Updates place offers it. The old review's opposite claim is obsolete. TabRail already exposes tab semantics; the settings Info preview already announces switch state. | 3 | Q3 |

## Scope filter

Follow-up counterevidence from the handoff's colour proposal: current
`runtime-common/.../GamingThemeMapping.kt` already derives surfaces and fits
primary, secondary, placeholder, section and accent text against the surface set
with `ColorMath.ensure(..., TEXT_FLOOR)`. Its declarations and mapping are not
evidence of a missing contrast algorithm. The shared default palette explicitly
preserves the owner's blue-grey ground. An optional true-black theme remains a
design proposal; this review does not replace the approved default or certify
contrast over textured/rendered backgrounds from source alone.

Discarded retailer rankings, screenshot aesthetics and generic redesign recipes.
WCAG is used as a design reference, not a native conformance declaration. Its
24-CSS-pixel pointer minimum is not substituted for Android's 48dp touch guidance.
Controller completeness does not mean removing touch or pointer routes. Apple
menu/keyboard conventions are not applied wholesale to Android.

## Object filter

Removed the stale claim that Get games is Gaming-only (F12). A shared component
can be a drawing primitive: inspect its interactive caller before declaring it
inaccessible. Do not infer a production regression from an unfinished Pythia
branch. Current PC pages already have hero/status/action/tab machinery; the
DroidDeck proposals are ideas to assess, not proof that those pieces are absent.
No contrast ratio, physical text size, focus-occlusion result, task timing or
visual-quality rating is claimed without measurement.

## Conclusions

1. **Task parity and accessibility are immediate gaps.** Fix F7/F8/F10 before
   spending this batch on animation or decorative glass. A working entry point
   does not establish an understandable or complete flow (F2–F6).
2. **The new Pythia UI remains a migration, not a finished replacement.** Equal
   chips for every destination do not provide a deliberate information hierarchy;
   completed backend calls do not establish feature parity (F1, F9, F11).
3. **Use progressive disclosure without burying primary work.** Play/install,
   runner choice, a missing requirement's remedy, source sign-in/check state and
   job recovery belong with the task. Infrequent administration can be secondary
   (F5/F6). Proposed hierarchy changes remain recommendations until reconciled
   with the product's existing SPEC and task map.
4. **Visual quality needs a separate gate.** Source review can find missing
   semantics and routing, but cannot certify typography, spacing, contrast,
   artwork crop, focus clarity or perceived complexity. Keep those open (F3/F4).

## Next steps / Risks

| Priority | Work | Acceptance criteria | Evidence |
|---|---|---|---|
| First | Correct switch/adjustment semantics in Android and native Pythia | Each interactive toggle exposes its name, role, current state, enabled state and one action; arrows announce purpose rather than a glyph | F2, F7, F10 |
| First | Share game-page routing between Mobile search, grids and Desktop | Opening an available page does not launch a ROM/app unexpectedly; Play is a separate deliberate action; Back returns to the previous context | F6, F8 |
| First | Trace sign-in → browser check → retry → acquire → queue → indexed game | Waiting/failed/cancelled states say what happened and provide the appropriate next action; one check does not strand or duplicate the job | F4, F6; code trace still required |
| Next | Define primary/secondary navigation for native Pythia | Compact and expanded layouts preserve destinations and selection; secondary administration does not crowd Library/Review/Activity; no feature is removed merely to simplify navigation | F1, F5, F9, F11 |
| Next | Complete input/focus state and migration parity | Every task has a pad/keyboard route, explicit entry/return focus, touch/pointer controls and honest availability; tray/updater/packaging coverage precedes deleting old UIs | F2–F4, F10, F11 |
| Later, when authorized | Visual and hardware validation | Check narrow/wide, portrait/landscape, light/dark/high-contrast, large text and translated labels; measure source palette pairs and inspect focus behind overlays | F2–F4 |

Open risks: no live usability study, no current device screenshots inspected,
no complete 160-task source trace, native Compose accessibility/runtime parity
not established, and several workflows require real accounts or hardware.
Do not mark an entire area done because its source entry point was mapped.

Evidence review result: supported with the qualifications above. This was a
source-checked internal review, not an independent user study. No unsupported
timelines or numerical usability scores are assigned.
