# UI assessment: droidtop 0.1.0-dev-568 and Enginehost 0.1.5-dev-148 (2026-09-24)

Source: the 65 screenshots in `agents/other/ui-pass-2026-09-24/`, indexed by
`agents/other/device-dq-coordinator-20.md` (BlueStacks, 1920x1080 landscape, no physical pad;
droidtop driven by synthetic pad keyevents, Enginehost by taps). I looked at every screenshot.
The standard is `coordination/design/DESIGN-LANGUAGE.md` plus the user's rules: droidtop copies
ES-DE's general UI patterns, every handheld screen works fully from the pad, pointer and focus
are one selection, the two apps are separate but share a design language, and Enginehost is a
complete app on its own.

Screenshot names below drop the `.png` suffix. "Lesson" means an entry in the
DESIGN-LANGUAGE lessons log.

Rig caveats: BlueStacks' system bar and light system theme may make some findings look worse
than they would on the Retroid. Findings 1 and 18 need checking on the device before anyone
assumes the rig caused them. Both apps still have to handle a device whose system theme is light.

---

## 1. Ranked findings

### High

**H1. The Android status bar leaks into the shell and covers the top tab bar (droidtop)**
- Screenshots: 16, 17, 17a, 17b, 18, 21 to 40, 43, 44, 45, 46.
- What is wrong: after Android settings opens, a grey system status bar sits over the shell's
  top row. "Games / Apps / Settings" and the "R2 Quick Menu" chip are cut in half. The bar then
  stays for the rest of the session, including over the ES-DE carousel (18) and the Quick Menu
  (45, 46). That puts three clocks on one screen (46: status bar "1:09", theme "01:09", panel
  "1:09 AM"). This breaks the lesson "a themed view owns its whole surface; draw under no system
  bar".
- Fix: the shell's activity re-applies immersive mode (hides both system bars) in
  `onResume`/`onWindowFocusChanged` every time it comes back to the front, not only once at
  start. One owner decides system-bar visibility. Test it by opening a system screen and
  returning.

**H2. The Controller screen traps B, and neither the pad nor touch has a way out (droidtop)**
- Screenshots: 41, 42.
- What is wrong: pressing B on Settings > Controller is taken as a test press ("droidtop read
  the right face button") and the screen stays open. There is no hint row and no back arrow.
  Only system BACK leaves, and a handheld player may not have that. The screen also opens a
  separate `OnboardingActivity` with a different surface colour (#1a1a1a, where the shell uses
  black), a visible status bar, a text column capped at about 900 px next to full-width rows,
  and a Material "Done" pill in a different blue.
- Fix: B always means back on this screen. Make "Check it reads" an explicit capture: A on that
  row arms it, the next press is reported, then the row disarms. Only an armed capture may take
  B. Draw the standard hint row (A Select, B Back), and render the screen in the shell's own
  settings surface, not in a separate activity.

**H3. B from a nested settings page jumps to the Games carousel (droidtop)**
- Screenshots: 16 then 18.
- What is wrong: B on Settings > Android settings lands on the Games carousel, not on the
  Settings list. The person loses their place in Settings. This is the lesson "a screen opened
  FROM a level is a level" again.
- Fix: keep the settings sub-page stack in the hoisted navigation state, the same way the other
  sub-pages do it (they return correctly). Then check that each sub-page's B returns exactly one
  level, landing on the row it was opened from.

**H4. Three droidtop settings screens are a different app visually and cannot be driven from the pad (droidtop)**
- Screenshots: 14, 15, 19, 20 (launcher3 `SettingsActivity`); 41 (`OnboardingActivity`).
- What is wrong: Global settings (the FIRST row of Settings), Desktop mode and Standard mode
  leave the shell for a stock Material list. It has a grey background, blue sentence-case
  section headers where the shell uses grey uppercase, toggles, a status bar with an unreadable
  dark clock, a touch-only back arrow, and an unexplained wrench icon. It shows no focus
  highlight on any row and has no hint row. State is shown in the subtitle ("Default mode /
  Gaming"), not in a value column. "Standard mode" opens a page titled "Home settings" (lesson:
  one concept, one name). The rows link in circles ("Other shells" on each page links to the
  other two).
- Fix: render Global settings and Desktop mode with the shell's own settings row component
  inside the Settings section. Show booleans as On/Off in the value column, as droidtop does
  everywhere else. The launcher3 pages that must stay stock (icons, app drawer) belong only to
  Standard mode; reach them from a single row whose label matches the page title, and give
  them focus visibility.

**H5. Enginehost's screens have no pad affordances (Enginehost)**
- Screenshots: 47 to 60.
- What is wrong: no Enginehost screen shows a focused element. There is no hint row anywhere.
  Back is a touch-only arrow, and choosers are stock AlertDialogs with an all-caps CANCEL. The
  index says the rig switched to tapping because "those screens are touch-first". Enginehost is
  a complete app on a pad-driven handheld and must be fully usable from D-pad/A/B/shoulders.
- Fix: add Enginehost's own focus token (the same role as droidtop's: an accent ring plus a
  raised fill) to every clickable element. Add a bottom hint row generated from the actions
  bound on that screen. Route B/Escape to back on every screen and dialog. Replace the
  AlertDialog choosers with in-app sheets that take the pad (right-edge in landscape, per the
  design language).

**H6. The Console systems folder pages contradict themselves and are empty (droidtop)**
- Screenshots: 24, 25, 25a, 26, 27 to 35.
- What is wrong: every game folder in the list reads "Unrecognized -- open to assign a system".
  Opening one shows "System: (automatic, from the folder name)", which says it IS automatic.
  The page header promises "change its system, pick its emulator, or scrape artwork", but each
  folder page has exactly one row and a blank screen below it. These folders (Steam, GOG, EA,
  Humble, Ubisoft...) hold PC games that the library already detected (03: "157 with a detected
  engine"), so labelling them "Unrecognized" is wrong as well as alarming.
- Fix: say what the folder IS. For a folder whose contents are recognised per game (PC, engine
  and store folders), show "PC games, detected per game (N games)" and no system picker. For a
  folder that really is unrecognised, show the value "Not set", and open the picker with the
  system list plus the emulator and scrape rows the header promises. If those rows do not exist
  yet, delete the promise from the header.

**H7. The default scraper source is one the page says droidtop cannot use (droidtop)**
- Screenshot: 21 (also 24, "Artwork & metadata scraper: ScreenScraper").
- What is wrong: "Scraper source" is set to "ScreenScraper (ES-DE's default)". Directly above it,
  the page explains that ScreenScraper "needs a registered developer ID, which droidtop does not
  have yet". So the default scrape silently cannot work. That breaks "no status shown without
  its reason", and it matters most because the carousel and the grid are mostly missing art
  (01, 02, 03).
- Fix: default to a source that works without an account (the libretro database the page says
  "needs no account at all"). While ScreenScraper is unavailable, list it as disabled with the
  reason in its own row.

**H8. The hint rows promise actions that do nothing, and name the wrong buttons (droidtop)**
- Screenshots: 01, 02, 06 (Y INFO does nothing); 01 versus 07/09 ("L SWITCH SECTION" versus
  "R Switch section"); 09 to 44 (a "Y Info" hint on every settings page); 45 (A Open and X
  Dismiss with nothing listed).
- What is wrong: on the carousel, Y is shown as INFO and produces nothing. The theme's hint
  says L switches section while the shell's own row says R, and the rig switched with R1. Every
  settings page advertises Y Info, which the rig never saw do anything. The Notifications tab
  offers Open and Dismiss on an empty list. Lesson: "a row of hints is a promise".
- Fix: build every hint row from the actions actually bound on that screen, at that moment.
  Either give Y on the carousel an in-context menu or info panel (ES-DE's system-view pattern),
  or remove it. Label shoulder hints with the printed names (L1/R1) and show both directions.
  Hide Open/Dismiss/Clear all when the list is empty.

**H9. Enginehost's home has no library although the app is meant to be complete (Enginehost)**
- Screenshot: 47.
- What is wrong: home shows "Add a game folder to start." over an empty left panel, although
  this session launched many games through Enginehost. `enginehost/README.md` says the app "has
  its own game library". The same README, around line 133, still calls the home action
  "deliberately minimal... not a library", which is the framing it says was retired. The right
  column offers three overlapping entry points (Add a game, Scan a folder, Game setup) with no
  explanation of the difference.
- Fix: the home left panel is the library. It lists every game Enginehost has configured or
  launched, whichever front door was used, each opening that game's own setup/play screen.
  Collapse the entry points into one primary "Add games" action (folder or single game) and
  move Game setup into each game's own screen. Delete the stale README paragraph.

### Medium

**M1. Focus uses three different idioms in droidtop and is weak in settings lists (droidtop)**
- Screenshots: 03 and 07 (accent ring on cards), 05 and 09 to 44 (a lighter row background:
  about #2e on #121212), 08 (white-filled pill), 46 (accent ring on the slider), 45 (the tab is
  shown only by coloured text).
- What is wrong: in the settings lists the focused row is only slightly lighter than its
  neighbours. At arm's length on a 5.5" screen it is hard to find. The Play button on 04 gives
  no clear sign whether it is focused. Lesson: "one selection idiom per shell".
- Fix: one focus token for all chrome, the accent ring plus raised fill already used on cards
  and the Quick Menu, applied to rows, buttons, chips and tabs.

**M2. Settings mixes one-shot actions and system toggles into configuration, and duplicates the Quick Menu (droidtop)**
- Screenshots: 10, 12, 12a, 13, 24, 25a, 39, 44, 46.
- What is wrong:
  - Settings > SYSTEM (Network, Volume, Brightness, Do Not Disturb, VPN, Bluetooth, Software
    updates, Android settings) repeats the Quick Menu's System tab row for row, and Screens
    repeats its Gaming shell display / Games launch on / Swap screens tiles.
  - Actions sit among the configuration: Rescan library (in both Settings and Game folders),
    Swap screens, Reinitialize displays, Sync theme index, Find/Delete orphaned media, Scrape
    all systems, Update platform databases, Check and install now.
  - Network's subtitle carries "Battery 100%".
- Fix: the Quick Menu owns live system state and one-shot device actions; delete Settings >
  SYSTEM except "Software updates" and "Android settings". Library actions (rescan, scrape all,
  orphaned media) go in the carousel and grid in-context menus (the ES-DE pattern). Theme sync
  runs automatically when Browse themes opens.

**M3. Browse themes is an empty dead end (droidtop)**
- Screenshot: 43.
- What is wrong: "No themes indexed yet. Go back and run 'Sync theme index'... first." The empty
  state sends the person elsewhere instead of offering the next action. The page also uses a
  36 px gutter where every other shell page uses 72 px.
- Fix: opening Browse themes syncs the index when it is empty, showing progress and a count as
  it goes (lesson: "a count that can take minutes has to say it is moving"). Delete the
  separate "Sync theme index" row, and use the shared gutter token.

**M4. The App integrations empty state points at an unreachable path (droidtop)**
- Screenshot: 37.
- What is wrong: "Drop a .json file in /data/user/0/dev.droidtop.app/files/integrations". Without
  root no person can reach that directory. The empty state has no action a player can take.
- Fix: read integrations from a user-reachable folder (for example `Android/media/dev.droidtop.app/
  integrations` or a folder under the game folders), and offer "Import integration file..." via
  the system picker as the row's action.

**M5. Raw identifiers and developer text in player-facing copy (both)**
- Screenshots and examples:
  - 38: package ids ("dev.enginehost.rpgmaker.easyrpg.v1"), GitHub URLs, "covers 2000.*",
    lowercase engine ids "rpgmaker", "cmvs (ps2)".
  - 36: "core: null" and 30-item extension lists.
  - 39: "<folder>/<system>/<romFile>".
  - 44: an adb command and "CI publishes from main".
  - 20: "JSON file".
  - 51 and 52: "BUTTON_START + BUTTON_SELECT", "DPAD_UP", "Left stick horizontal: X".
  - 60: engine "rpgmaker".
  - 03, 06, 18: folder names shown as titles ("Anomalous_Coffee_Machine_2",
    "DIVINEDAWN-0.27B-PC", "LUST-ACADEMY-BOOK1").
  - 04: "v0.37 - .dv pc".
  - Double-hyphen "--" used as a dash throughout (13, 16, 23, 24, 25, 36, 37, 44).
- What is wrong: lessons "never show a directory id", "a label that is really a class or
  package name is not a label", "no jargon in what the user must read".
- Fix: display names only (engine's own name, "RPG Maker 2000"), with ids and URLs behind a
  Details action. Show the physical glyphs (Start, Select, D-pad up, left stick). Omit a null
  field. Give titles a humanised fallback (underscores and dashes to spaces, version and platform
  suffixes stripped to a subtitle). Replace "--" with a real dash or a full stop in every string
  resource.

**M6. The two apps look like unrelated products, and Enginehost's status-bar clock is invisible (both)**
- Screenshots: 01 to 46 versus 47 to 60.
- What is wrong: droidtop is black with an off-white type scale and a soft blue accent.
  Enginehost is light grey and white with a saturated Material blue, different corner radii,
  outlined buttons and stock dialogs. Launching Enginehost from droidtop flashes the whole
  screen white. Enginehost's white status-bar clock on a light background is unreadable
  (47 to 53, 59). The design language asks for one set of roles implemented per project.
- Fix: give Enginehost a dark palette that resolves from the same role names droidtop uses
  (surface, row, focus ring, accent, supporting text), and make it the default on the handheld.
  Share the type scale and the focus and hint-row idioms. Draw under no system bar (or set the
  status-bar icon colour from the surface).

**M7. Game detail is thin and has misplaced content (droidtop)**
- Screenshots: 04, 05.
- What is wrong: the page ends at "Downloads", a global queue ("Everything downloading or
  waiting") that has nothing to do with this game. SPEC 7f lists saves, controls, engine
  settings, metadata/scrape, collections and hide for the detail, and none is present. The hero
  is a blurred crop of a small icon. The hint says "A Select" where A would play.
- Fix: add the SPEC's sections, at least saves, metadata/scrape and hide/favourite, since this
  page is the game's one in-context menu. Move Downloads to the Quick Menu or the Games section.
  When art is too small, draw the plate without art (lesson: no stand-in cover). Name the hint
  after what A does on the focused element ("A Play").

**M8. The same concept has different names and numbers across screens (droidtop)**
- Screenshots and examples:
  - 02 versus 03: "IBM PC", 185 games becomes "PC", 174 games (with "in 185 folders").
  - 09 versus 21 and 24: "Scraper" versus "Artwork & metadata scraper".
  - 13 versus 15: "Standard mode" versus "Home settings".
  - 04 versus 38 and 07: "enginehost" versus "Enginehost".
  - 01 versus 07: "L" versus "R" switch section.
  - 09 and 24: Game folders is reachable from two places.
  - 10 and 39: Rescan library appears in two places.
- Fix: one name per concept and one place per setting. The count on the carousel and the grid
  must be the same number, or both must say which kind of thing each counts.

**M9. Destructive actions fire on one press (droidtop, Enginehost)**
- Screenshots: 39 ("/mnt/windows/BstSharedFolder - Activate to remove this folder from
  scanning"), 24 ("Delete orphaned media - Permanently removes..."), 49 (Uninstall on every
  card, next to Deny).
- What is wrong: the design language requires two-step confirmation. A stray A on a folder row
  drops that whole library source.
- Fix: every destructive row or button arms on the first press (its label changes to "Press
  again to remove") and fires on the second. Moving to another item disarms it. Make folder
  removal the row's in-context menu action, not its primary A.

**M10. The disabled "Approve" button is illegible, and the plugin cards are button-heavy (Enginehost)**
- Screenshot: 49.
- What is wrong: disabled Approve is white text on pale grey, well under 3:1 contrast (lesson:
  "a disabled control needs its own alpha token"). Every card carries three full-width buttons,
  one of them already inapplicable, so twenty cards read as a wall of buttons.
- Fix: show trust state as the badge ("Approved"), with one contextual action (Deny, or Approve
  when pending). Put Uninstall and Details behind the card's own screen or in-context menu.

**M11. The plugin channel can be changed in three places (Enginehost)**
- Screenshots: 48, 53, 58.
- What is wrong: the Plugins "Channel: Stable >" row, the "Switch to Testing / Switch to
  Unstable" buttons, and Settings > "Plugin releases" all change one value. The Plugins header
  also says "Up to date." while showing an empty channel next to 20+ installed plugins, which
  reads as a contradiction.
- Fix: keep the channel in Settings (it is configuration). On the Plugins page, show it as a
  read-only line ("Showing: Stable") with one "Change" affordance. Say that the installed
  plugins came from other channels.

**M12. The Quick Menu's hint row moves between tabs (droidtop)**
- Screenshots: 45, 46.
- What is wrong: on Notifications the hint row floats under "No notifications." at the top of
  the panel. On System it sits at the bottom. A "Close" text button in the corner duplicates B
  Close. The tab switch (R1/L1) is not hinted.
- Fix: dock the hint row at the panel's bottom on every tab. Add "L1/R1 Tab" to it. Drop the
  corner "Close" text, or make it the touch route to B and label it as such.

**M13. The ES-DE carousel's centre panel is empty (droidtop)**
- Screenshots: 01, 02, 06, 18.
- What is wrong: DEcaffe's large centre area shows nothing on either system. The gamelist name
  strip shows an arbitrary game ("MY DEMONIC ROMANCE") while the "All Games" system is
  selected. Whether this is missing scraped media (see H7) or a renderer gap, the first screen
  of the product is two-thirds empty.
- Fix: diff against the theme's own reference images at the same size (standing rule), and
  confirm which element the theme draws there (system art or video). Once H7 is fixed, re-check
  the screen with scraped media present.

**M14. Settings rows break the one-row-height rule and put state in subtitles (droidtop)**
- Screenshots: 09 to 13, 16, 40.
- What is wrong: rows with no subtitle are about 84 px tall, rows with one about 93 px, and rows
  with a three-line description taller still (11 "Turn on Hacker's Keyboard", 44). Some rows put
  their state in the subtitle ("Android Keyboard (AOSP) - tap to switch", "Already set up.",
  "Not granted -- needed for...", "Granted -- DND is a toggle..."). Others put it in the value
  column. "tap" is the wrong verb on a pad device. Lessons: "one row-height rule", "state
  belongs in the value column".
- Fix: fixed row height with one subtitle line (ellipsis). Move long explanations to the Y Info
  sheet, which also gives the "Y Info" hint a real job. All state goes in the value column
  ("Granted", "Not granted", "Set up").

### Low

**L1. The app detail's banner is a stretched icon (droidtop).** Screenshot 08. The banner is a
blurred, upscaled launcher icon, there is a "Back" button next to B, and no app actions are
offered (app info, hide, uninstall, add to collection). Fix: the same plate as the game detail
with the icon at native size; drop the Back button; put the actions in this screen.

**L2. Enginehost shows the generic Android robot in droidtop's Apps grid (Enginehost).**
Screenshot 07 (Enginehost tile). Fix: ship an adaptive launcher icon.

**L3. Chips on the game grid show no active state (droidtop).** Screenshot 03. "Installed /
Folder / PC Games / RPG Maker / Visual Novels" look the same, so it is not clear which filter is
on. The hint row does not say how to reach them (Y is "Stores and folders"). Fix: an on-state
fill, and a shoulder or X hint that reaches the filter row.

**L4. Software updates is walls of text with two check actions (droidtop).** Screenshot 44.
"Check for a droidtop update" and "Check and install now" are two ways to do one job. The
debug-build row is a paragraph with timings. Fix: one "Check now" row whose value says the
installed build and last check; move the explanations to Info.

**L5. Manage platforms rows carry full extension lists (droidtop).** Screenshot 36. Rows show
the id in parentheses and the whole extension list. Fix: name plus "N file types, runs with
X"; details on the platform's own page.

**L6. Enginehost's controller page layout confuses (Enginehost).** Screenshots 51, 52.
- Two full-width filled-blue settings rows (Host menu shortcut, Controller profile) look like
  primary actions.
- The selected engine is marked only by the text "(editing)".
- Engines that "handle their own controller" are listed as if selectable, with nothing to edit.
- "Reset all" is clipped at the bottom edge.
- Fix: rows, not buttons; a selection fill on the chosen engine; group the self-handling
  engines under a note; keep Reset all in view.

**L7. Enginehost setting choosers are unclear (Enginehost).**
- 54: "Save folder" mixes the one-shot action "Move old saves here" with the choices and marks
  no current choice. "Use internal storage" is ambiguous next to /storage/emulated/0.
- 55: the CMVS chooser has a single option, "Choose folder", with no way back to "Shared
  folder".
- 56: "Browse from" does not say what it affects.
- Fix: radio lists with the current value marked; a "Shared folder" option in each per-engine
  chooser; the move as its own confirmed action; explain "Browse from" (the starting folder of
  the folder picker).

**L8. Plugin source cards are labelled by group, not by the thing (Enginehost).** Screenshot 50.
Two cards are both titled "Enginehost native", the description is the same boilerplate on every
card, and raw URLs are shown. "Sources (11)" is a link that swaps the page body. Fix: title
each card with the engine it provides; drop the boilerplate; the URL goes behind Details;
Sources opens its own screen with a normal back.

**L9. The update banner looks like a text field (Enginehost).** Screenshot 47. "Enginehost
0.1.5-dev-152 is available" is drawn in an outlined input shape with no action. Fix: a banner
with an "Update" button, or move it to Settings > Updates.

**L10. Game setup's editor has blank fields and detached help (Enginehost).** Screenshot 60.
"Engine version" is an empty box with no placeholder. The help line for the focused field sits
under the Test button, far from its field. "choose the exact context" is jargon. The path
display looks like an editable text field. Fix: placeholder "Unknown (read from the game when
possible)"; help text directly under its field; say "Pick RPG Maker 2000 or 2003"; show the
path as plain supporting text.

**L11. Controller screen copy (droidtop).** Screenshot 41. The emphasis "where a button IS"
is all caps. The current choice is shown as a blue "Selected" word instead of a radio or check.
Fix: normal case; the one selection idiom.

**L12. Global settings rows have no warnings or descriptions (droidtop).** Screenshots 19, 20.
"Rerun onboarding" does not say it resets first-run state. "System settings" duplicates Settings
> Android settings. Fix: say what is reset; delete the duplicate.

---

## 2. Cross-cutting issues (fix once, apply everywhere)

1. **One owner for the system bars, in both apps.** Immersive on every resume, and on return
   from any system screen or other app. Covers H1 and M6 (the invisible Enginehost clock).
2. **One focus token per app, the same role in both.** An accent ring plus a raised fill on
   every focusable element: rows, buttons, chips, tabs, cards, dialog options. It follows pointer
   hover as well, so hover and focus are one selection. Covers M1 and H5.
3. **A hint row on every screen, generated from bound actions.** Docked at the bottom, always
   present (it is the touch route to pad buttons), with printed button names, and never listing
   an action that does not dispatch. Enginehost needs one from scratch. Covers H2, H5, H8, M12.
4. **B means back, by every route, on every screen and dialog.** Back pops exactly one level to
   the row it came from. Captures may take B only while explicitly armed. Covers H2, H3, H5.
5. **One settings row component in droidtop, and no settings outside the shell.** Fixed height,
   one-line subtitle with ellipsis, state in the value column, "‹ value ›" on focus, a chevron
   only for navigation. The launcher3 and Onboarding activities stop hosting droidtop
   configuration. Covers H4, M14.
6. **Settings are configuration only.** One-shot actions go in the in-context menus on the
   carousel, grid and detail, following ES-DE's pattern. Live device toggles belong to the Quick
   Menu alone. Covers M2, M3, L4, L12.
7. **A copy pass on both apps' string resources.**
   - No package ids, URLs, keycodes, "null", glob patterns, internal paths or adb commands in
     primary text.
   - Humanised display names with a documented fallback.
   - No "--".
   - Explanations longer than a line move to the Info sheet.
   Covers M5, L5, L8, L10.
8. **One concept, one name, one place.** Audit that each Settings row's label equals the title of
   the page it opens, that each setting exists once, and that counts agree across screens.
   Covers M8, M11.
9. **Every empty state has an in-place next action.** It never says "go back and run X", and
   never names a path the person cannot reach. Covers M3, M4, H9.
10. **Two-step confirmation for every destructive control, in both apps.** Covers M9.
11. **Shared role tokens across the two apps.** Surface, row, focus, accent, supporting text, the
    type scale and dialog/sheet shape, each app implementing them itself (no dependency). The
    handheld default is dark in both. Covers M6.
12. **No stand-in art.** Draw the plate without art rather than a blurred upscale of a tiny icon,
    on the game detail, the app detail and the grid. Covers M7, L1.

---

## 3. What already works and should be kept

- **The Quick Menu System tab (46).** A status header, a volume slider with a clear accent ring,
  large tiles with icon, name and state, and a bottom hint row with Lower/Raise/Act/Close. The
  user has called it "amazing". It is the model the other surfaces should converge on.
- **Game grid cards (03).** A clear focus ring, a two-line title and kind, and blank plates rather
  than invented covers. The header subtitle's "in 185 folders" follows the folders-versus-games
  lesson.
- **The game detail's primary action (04).** One big Play button that says where it runs
  ("Starts now on enginehost (Ren'Py)"), with the resolved runner and its reason in a "Runs with"
  row above it.
- **Value rows that show "‹ value ›" only on focus (12, 12a, 21, 22, 44).** It tells the person
  left/right changes the value, without cluttering unfocused rows.
- **Plain, user-side supporting copy on most shell rows.** Examples: "Move the shell to the other
  panel when droidtop guessed wrong" and "Detect connected screens again and re-place the
  shell".
- **The "Which button means yes" question (41).** It asks the one thing hardware cannot report,
  in physical terms, with an example of each pad family. Keep the content; fix the container
  (H2).
- **Enginehost's empty channel state (48).** It names the empty channel and what the others hold,
  exactly as the lesson asks.
- **Enginehost Settings layout (53).** Two columns that fill the landscape window, grouped cards,
  and the current value on the right. Its radio choosers show the current choice and a short
  description per option (57, 58).
- **Enginehost's trust surfacing (49).** An "Official" badge and "Trust: Approved" on every
  installed plugin, and a one-line statement of what approval means.
- **The ES-DE theme rendering (01, 02).** DEcaffe's layout, typography, logos and system
  metadata render cleanly. The shell's own chrome (top tabs, R2 chip) is restrained enough not
  to fight it once H1 is fixed.
