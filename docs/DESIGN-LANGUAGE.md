# Mech design language and UX lessons (global, all projects)

Purpose (user, 2026-09-11): "I want the design cues and everything we learn from one
software to transfer to others, globally." Not a brand. This file is the one place
design decisions and UX lessons land, whichever project taught them, and the one
place every project's design work starts from. Each project implements it in its own
code (droidtop: SPEC 7k tokens + 7j conventions; Enginehost: its own tokens); no
project depends on another. Every owner doing UI work reads this first and appends
what it learned last.

## Principles
- Whole-app view; one mechanism per job; delete dead code; no two ways to do one thing.
- Accuracy over deference: where a reference implementation defines a behaviour (ES-DE,
  an engine), match it exactly and cite it; "deliberate exception" only for our own chrome.
- Portrait and touch-first are primary (most users are on phones without controllers);
  controllers fully supported; one layout system driven by window size class + orientation.
- Copy is from the user's side: actions say what happens; errors say what went wrong and
  what to do; no jargon in what the user must read (e.g. "this game requires a patch",
  not the patch filename).
- Ship no unbound controls, no empty states without a next action, no status shown
  without its reason.

## Layout
- Never a single narrow centred column with large margins; content fills the window
  with a consistent gutter; panels size to the window (capped), sheets are right-edge in
  landscape and bottom in portrait.
- One spacing scale, one type scale, one colour token set per project; no raw literals
  in screens; both light and dark states resolve from the same tokens.
- 48 dp minimum touch targets; visible focus for pad navigation; the help/hint row is
  the touch route to pad buttons and must exist in every orientation.

## Interaction
- Capture-style mapping: pick the action, then press the input; explicit Unbind.
- Two-step confirm: a second activation confirms, whether pad or tap; only moving to a
  different item disarms (droidtop bug, 2026-09-11).
- Settings are for configuration only; actions live on the main screen and in
  in-context menus (ES-DE's pattern, not its bindings).
- Quick settings panel: status header, sliders, large tiles; the same control surface by
  pad and touch (droidtop Quick Menu, 2026-09-11; the user called it "amazing").
- Every "press X again" or hold gesture has a visible affordance on touch.

## System integration
- A disabled mode or feature runs no code (droidtop modes, 2026-09-11).
- Recovery paths that do not depend on any screen working (an adb/broadcast "update now").
- State from code and rigs, not from status tables; verify on an emulator or the real
  device, both orientations, and on the oldest supported Android.

## Lessons log (append; one line each, dated, project)
- 2026-09-10 enginehost: hover focuses, focus moves the cursor, A acts on the one selection; hide our ring when the game's own highlight is the selection.
- 2026-09-10 enginehost: per-engine action names from the engine's own input model; "Bypass" = raw input to the engine.
- 2026-09-11 droidtop: a LazyColumn inside a vertical scroll crashes; bound lists explicitly.
- 2026-09-11 droidtop: class-load references to newer framework types crash below their API even with version checks around calls; gate at the class boundary.
- 2026-09-11 droidtop: onboarding must skip permissions already granted, put rationale before the prompt, keep Back inside the flow, end with "what next".
- 2026-09-11 droidtop: hidden and sync-marker folders (.stfolder) are not games.
- 2026-09-11 droidtop: a fresh install's empty catalog must say which channel is empty and what the others hold.
- 2026-09-11 droidtop: the token layer is names first, values second -- screens read roles (a gap, a row title, a supporting colour), every number and hex lives in one file, so swapping the visual identity is an edit there and nowhere else (droidtop: shell-gamepad/.../DesignTokens.kt).
- 2026-09-11 droidtop: a disabled control needs its own alpha token -- Material's stock 38% is 2.3:1 on a light ground, which is not a control a person sees; hold both palettes to a contrast test, not just the one that had a bug.
- 2026-09-11 droidtop: a bottom-docked action area is the first thing a keyboard covers; a screen that docks its actions must fit its own IME insets.
- 2026-09-11 droidtop: a themed view owns its whole surface -- draw under no system bar, or the OS clock and the theme's clock disagree on one screen.
- 2026-09-11 droidtop: progress ("step 4 of 8") must be counted from the steps this run will actually present, and that same list must BE the pipeline, or the count and the flow drift apart.
- 2026-09-11 droidtop: never show a directory id or an untranslated label a data file happens to end with -- pick the label for the running language with a documented fallback, and use the thing's own display name.
- 2026-09-16 droidtop: a step's own plan must not be recomputed from state the step itself changes -- granting the permission on the permission step took that step out of the plan and ended the whole flow.
- 2026-09-16 droidtop: a folder that holds games is not a game; a container listed as an entry also hides everything inside it, so the list is both wrong and short.
- 2026-09-16 droidtop: a count that can take minutes has to say it is moving and what it has found so far; a motionless "Looking at this folder." reads as a hang.
- 2026-09-16 droidtop: changing what a cache is a cache OF (which folders are scanned) invalidates it; first run otherwise ends on an empty list with the data already on disk.
- 2026-09-16 droidtop: one concept, one name -- a Settings row called "Game folders" must not open a screen headed "ROM folders".
- 2026-09-16 droidtop: a result a person will act on must be logged; a swallowed failure and a genuinely empty result look identical from the UI.
- 2026-09-16 droidtop: a screen that declares "no back available" also hides the hint row's B, and that row is the touch route to B -- a nested settings screen then has no exit a finger can reach; and a menu must take B by every route it arrives on (the back dispatcher AND KEYCODE_BUTTON_B/Escape, which never reach it).
- 2026-09-16 droidtop: "state changed" is a subscription, not a check a screen runs when it happens to compose -- a flow whose Activity sits under another one is resumed, not recomposed, so the check never runs again.
- 2026-09-16 droidtop: never write your own answer into someone else's async preference and read it straight back; a device with previous state hides the race completely and only a fresh install shows it.
- 2026-09-16 droidtop: a summary must distinguish "still counting" from "counted nothing"; collapsing them reads as a finished, empty result.
- 2026-09-16 droidtop: logs a rig depends on need a sink the app owns -- logcat's buffer, rotation and filters belong to the device, and a missing line makes "found nothing", "never ran" and "was evicted" indistinguishable.
- 2026-09-17 droidtop: how many folders a thing ships in is not how many things there are -- show the game once and make its parts and versions reachable from its own detail; say "in N folders" only where the two counts differ and a person would otherwise think something is missing.
- 2026-09-17 droidtop: a section titled like its own first row says one thing twice, and a section for a mode/runner the thing does not use reads as a setting that applies -- show the one it uses, named for it, or none.
- 2026-09-17 droidtop: a similarity threshold tuned for SUGGESTIONS (a person accepts or rejects) silently merges unrelated things the moment nothing asks -- automatic identity needs an exact match; keep the score for "is this the same?" prompts.
- 2026-09-17 droidtop: a budget that drops a whole subtree loses real content -- bound the step, keep everything under it, and say where it was slow.
- 2026-09-17 droidtop: a count of what was skipped cannot be acted on -- name the items too (a few, then "+N more"), relative to the thing the line is about.
- 2026-09-17 cmvs/enginehost: a toolbar that lights the icon under a POINTER has no state to show on a touch screen -- the pointer sits where the last tap left it, so the bar is opaque after you press it and faint everywhere else, and no icon is ever lit before it is pressed. Porting a hover-driven bar means deciding what the touch equivalent of hover IS, not just forwarding the taps.
- 2026-09-17 cmvs/enginehost: a press that a UI element takes must be CONSUMED there; the same press falling through to what is underneath is the difference between a bar that works and a bar that also advances the story behind it.
- 2026-09-17 cmvs/enginehost: a control that answers nothing and a control that answers "no" look the same on a screenshot -- give every family that can take a press its own log line, or a rig pass cannot tell input that never arrived from a thing that saw it and declined.
- 2026-09-17 droidtop: a screen that owns its own place in a navigation tree LOSES it the moment something else is drawn instead -- hold the place (section, group, open detail) and what was focused in each outside the screens that draw them, and a screen asks where it is rather than remembering.
- 2026-09-17 droidtop: "back" is not a stack of everything that happened -- opening a sibling (another version of the same game) is a move SIDEWAYS, and back from it still means the list you came from, not the previous sibling.
- 2026-09-17 droidtop: returning to a list must land on the row you left it from; a list that reopens at the top costs a person the scroll they had already done, and on a grid of 158 cards that is the whole interaction.
- 2026-09-17 droidtop: state belongs in the value column, never inside the title -- a row reading "Network: Wi-Fi, signal 4/4" while every row beside it puts its value on the right is two layouts in one list; give the kinds with no state of their own a value field so they can use the same column.
- 2026-09-17 droidtop: name a quick setting after the setting, not after the permission behind it -- "Brightness / Needs permission" says both things; "Allow brightness control" says neither to someone looking for brightness.
- 2026-09-17 droidtop: a padding MODIFIER on a lazy list shrinks its viewport, so the last row ends exactly under the bar that covers it; the same space as CONTENT padding scrolls with the list and the last row comes clear.
- 2026-09-17 droidtop: one row-height rule -- a box that is whatever its text needs measured three different heights down one settings screen, and a list of unequal rows reads as unrelated things.
- 2026-09-17 droidtop: a label that is really a class or package name is not a label -- fall back application label, humanised package, package, and never the raw identifier; constrain it to the tile with an ellipsis, because the unreadable name is also the longest one.
- 2026-09-17 droidtop: one selection idiom per shell -- a 1px white rectangle on a card beside a brightened row in a menu beside the theme's own highlight is three answers to "what am I on".
- 2026-09-17 droidtop: a thing's own screen opens with the thing -- and when there is no artwork, the same plate with the same two lines rather than a stand-in cover; a made-up cover is a lie about the thing.
- 2026-09-17 droidtop: the primary action is a button, not another row in the list of rows; when it can do nothing it is a disabled button that says why, and nothing else on the screen repeats that sentence in different words.
- 2026-09-17 droidtop: a preview must be RENDERED by the thing it previews -- a theme's own view through the real parser and the real renderer, never a screenshot, a shipped image or a colour swatch; and render it with no content rather than with invented content standing in for the person's own.
- 2026-09-17 droidtop: ask the question hardware cannot answer -- Android reports a pad's buttons by POSITION, so which face button "confirms" is what is printed on the plastic and what the person expects; guessing it is the difference between a shell that works and one that cancels when you say yes.
- 2026-09-17 droidtop: an answer to that question has to be REAL in one place -- apply it to the MEANING of a press, and let the touch affordances and the help rows answer in physical terms, so the hint names the button that now confirms.
- 2026-09-17 droidtop: do not offer a swap to ratify when you can offer the choice -- a step that appears only once the app has already changed something, with one of its two answers hardcoded, is an apology, not a decision.
- 2026-09-17 droidtop: a preview is drawn at the SHAPE of the thing it previews -- a fixed 16:9 plate turns the tall layout you are recommending into a picture of a layout its author never wrote; and any cache of a layout resolved against the screen must be keyed by the screen, or a rotation keeps serving the first shape of the session.
- 2026-09-17 droidtop: two conditions for one row is how two rows get drawn -- when a chrome element and a themed element can both own the same strip, ONE value decides who owns it and both sides read that value; anything else has a combination where they overlap, and the one underneath is the one that looks broken.
- 2026-09-17 droidtop: a claim made by a screen belongs to that screen -- during a crossfade the screen leaving and the screen arriving are both composing, so a flag either of them may write ends up owned by whichever wrote last; key the claim to the screen and let only the one on top answer.
- 2026-09-17 droidtop: type does not scale with the long axis -- a proportion written against a wide screen's height becomes half again too large when the same fraction meets a tall screen, and the first thing that shows it is labels losing their last letters. Scale type by the short axis on a screen held upright (this is real ES-DE's own rule), and abbreviate what still does not fit instead of cutting it: a word that stops mid-letter reads as a bug, an ellipsis reads as a decision.
- 2026-09-17 droidtop: when a reimplementation and the original disagree, RENDER the original at the same size with the same content before changing anything -- and check the reference actually ran the input you gave it; ES-DE logs a theme as "loaded" and then draws its own unthemed fallback when the theme is newer than the binary.
- 2026-09-17 droidtop: "who owns this strip" needs more than two answers -- a THEME's row is decoration (it names buttons it cannot dispatch) and a SCREEN's own row is a control surface with that screen's actions in it, so a rule that overrides a theme's row on a touch screen must not override a screen's; collapsing both into one claim stacked two bars on the screen whose bar was already the right one.
- 2026-09-17 droidtop: put the one row in the place that was laid out for it -- chrome drawn as a strip BELOW a themed canvas shortens that canvas, changes the theme's own proportions between orientations, and leaves the plate the theme drew for its help row visibly empty above your bar; draw over the theme's own help position instead, as the original does.
- 2026-09-17 droidtop: suppress the element, never the art around it -- a theme's background image is not its help bar, and guessing that some <image> "was really" a bar's backing would break every theme that decorates the same strip.
- 2026-09-17 droidtop: the line under a name says what THIS one is, never the heading it sits under -- filled from the group name, eighteen tiles all read "Apps" two lines below a heading that already said it; take what the thing itself declares (an app's own category) and fall back to the singular name of its kind.
- 2026-09-17 droidtop: one way forward per step -- two forward actions side by side (a text "Skip" beside a filled "Next", a disabled "Next" beside a working "Skip for now") leave the label doing no work at all; make the one action say which it is: it IS the skip before the step is answered and Next afterwards.
- 2026-09-17 droidtop: a shape that says "pick one" appears only where one can be picked -- three words drawn as filled accent chips on a welcome screen are a selector with nothing behind it, and every tap and D-pad press proves it; prose that SAYS the same thing costs nothing and lies about nothing.
- 2026-09-17 droidtop: a touch affordance that DISPATCHES the real press is only as good as the place the handler sits -- in Compose a key event reaches the handlers between the active focus target and the root, and `clickable` brings a focus target of its own, so a handler written after it is never called; Android's own key-character-map fallback (an unhandled BUTTON_A re-sent as DPAD_CENTER) then hides it by making the pad look right while every other button, and every tap on the row, does nothing.
- 2026-09-17 droidtop: a row of hints is a promise -- it may name only actions that really dispatch, by every route it implies; a hint for a button nothing handles is worse than no hint, because the person has been told where to press.
- 2026-09-17 droidtop: an opaque strip is a strip wherever you draw it -- a bar moved ON TOP of a themed canvas still reads as chrome BELOW it while it paints its own plate over the theme's art; take the position the theme laid out AND paint nothing there, as the original does.
- 2026-09-17 droidtop: an element a theme did not declare still has a place -- the component it styles has its own default position, so "the theme declares no help row" must not mean "shorten the canvas and put ours underneath"; read the ORIGINAL's defaults rather than falling back to 0,0 or to your own layout.
- 2026-09-17 droidtop: a screen opened FROM a level is a level -- held as state inside the screen that opened it, it dies when that screen stops being drawn, and the handler one level up (which is its ancestor) answers back for it; on Android the system back key arrives as an ordinary key event BEFORE the back dispatcher, so an ancestor key handler beats every BackHandler below it.
- 2026-09-17 droidtop: a forward action is actionable or it is not drawn -- a greyed "Next" says "go on" while refusing to, and on a step whose only other content is the answer itself it leaves nothing on the screen that can be pressed; decide per step whether skipping MEANS anything, and if it does not, let the answer rows be the way on.
- 2026-09-17 droidtop: an interpolated value that is a whole sentence brings its own full stop -- concatenating it into a sentence of your own is where ".." comes from; decide once whether a message field is a phrase or a sentence.
- 2026-09-17 droidtop: absence is a state, not a deletion -- when a scan stops finding something the person put there, keep the card and say what is wrong with it ("broken - missing"), with its history and its place in their collections intact; throwing it away turns an unmounted drive into lost work, and the person can no longer tell you the new folder is the same thing.
- 2026-09-17 droidtop: say WHICH part finished, do not make the reader diff -- a worker that reports growing snapshots cannot distinguish "this part no longer holds that" from "this part has not been reached yet", and the two need opposite handling; a typed "part X is finished and holds exactly this" makes both the incremental update and the missing state fall out of one mechanism.
- 2026-09-17 droidtop: one list, offered from both ends -- "this replaces the missing one" and "find what replaced it" are the same question from two screens, so they share the candidate order and the same single operation; two orderings of one question make the person work out which screen to trust.
- 2026-09-17 droidtop: a one-way action names what it moves -- a row that folds two records together says, before it is pressed, exactly what crosses over ("its history, favourite and collections"), and the candidate rows say why each is a candidate (the same name, a similar name, and where it is) rather than showing a score.
- 2026-09-25 droidtop: a row of chips is part of the pad's map -- entering it lands on its first chip, not on whichever chip sits nearest the card below, and moving along it never falls through to the screen's "switch system"; a control reachable only by touch is not reachable.
- 2026-09-25 droidtop: a setting changed on a screen inside the thing it configures must be observed, not read once at start -- the row wrote "After 2 minutes" and the timer beside it went on reading Off.
- 2026-09-25 droidtop: an action that cannot run says so before it counts anything, with where to fix it -- "Scraped 3 systems." for a source with no key is a success message for nothing; a whole-library pass reports each part's own result and stops at the first total refusal.
- 2026-09-25 droidtop: a recovery action must never leave less on screen than it found -- rebuilding an index from records that do not cover every game dropped 168 games to 6; keep what is shown and let the next real walk decide what is gone.
- 2026-09-25 droidtop: a reference implementation's category art is also its branding -- ES-DE's `pc` is IBM PC/DOS, so a PC-games card drawn from it wore an IBM logo; pick the category that matches (a theme's `windows`) or fall through to the theme's own defaults and a plain name, never fabricate art.
- 2026-09-25 droidtop: a screen of ours that uses stock widgets reads as someone else's app -- the same card, focus ring, ground, header and hint row everywhere is what makes a second entry point recognisable; build it from the shell's pieces, not a lookalike.
