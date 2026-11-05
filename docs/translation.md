# Translating and improving droidtop's words

droidtop is set up to be translated on [Weblate](https://weblate.org/) (owner, 2026-11-05, Droidtop/tracker#627:
"Weblate. Can we also ask for better english strings, since our current ones are terrible?"). No language is
translated yet: "We don't handle translations ourselves immediately. We're just putting the groundwork in place to
trivialize it". This page is how it works once the project exists, and what creating it needs.

## Where the words are

droidtop's own words are Android string resources, one file per module (the rule and its gate are in
docs/SPEC.md 7k, "Language is part of the system"):

| Module | File |
|---|---|
| app | `app/src/main/res/values/strings.xml` |
| runtime-common (Settings catalogs) | `runtime-common/src/main/res/values/strings.xml` |
| shell-gamepad (Gaming) | `shell-gamepad/src/main/res/values/strings.xml` |
| shell-desktop (Desktop) | `shell-desktop/src/main/res/values/strings.xml` |
| ui-shared | `ui-shared/src/main/res/values/strings.xml` |
| input-keyboard | `input-keyboard/src/main/res/values/strings.xml` |

Vendored trees (`vendor/`, Launcher3 in `shell-default`) keep their own translations and are not on Weblate.

## Two kinds of English

- **The developers' English** is `values/strings.xml`: what a developer writes with the code. It is Weblate's source
  language, set up as a language of its own, "English (developer)", code `en_DEVEL`.
- **The English people read** is `values-en/strings.xml`, a translation like any other, made on Weblate from the
  developers' English. Android shows it to everyone whose language is English, and falls back to the developers'
  English, string by string, for anything it does not have yet.

So anyone can improve droidtop's English on Weblate without touching code, and a string whose source is poor can be
flagged there (Weblate's "Source string needs review"). A developer changing a string's meaning changes it in
`values/strings.xml`; Weblate then marks its translations, English included, as needing an edit.

## How translations arrive

Weblate never commits to `main`. Each component pushes to a `ci/weblate-<module>` branch (Weblate's "push branch"),
and the coordinator lands it like every other `ci/` branch, after CI is green. A language that reaches the list of
shipped languages is added to `app/src/main/res/xml/locales_config.xml` in the same change: that list is what
Settings, Appearance, Language and Android 13's per-app language screen offer.

## Rules a translation keeps

- A sentence is one string with numbered placeholders (`In %1$s mode`); keep every placeholder, in any order.
- Counts are `<plurals>`; give every form the language has.
- `%%` is a literal percent sign. `\'` is an apostrophe.
- Keep to the length of the English where you can: menus are dense and a value has a fixed width.

## What creating the project needs (the owner's)

1. A Weblate project: Hosted Weblate's free plan for libre projects (apply at
   <https://hosted.weblate.org/hosting/>, naming the GPL licence and the repository), or a self-hosted Weblate.
2. Access to Droidtop/droidtop for Weblate: its SSH key added as a deploy key with write access, or the Weblate GitHub
   app, so it can push `ci/weblate-*` branches. It needs no other repository and no access to `main`.
3. A custom language "English (developer)", code `en_DEVEL`, set as the project's source language.
4. One component per row of the table above:
   - File format "Android String Resource", monolingual.
   - Monolingual base language file: the module's `values/strings.xml`.
   - File mask: `<module>/src/main/res/values-*/strings.xml`.
   - New translation template: the same base file.
   - Repository branch `main`; push branch `ci/weblate-<module>`.
   - License: GPL-3.0-or-later (as the repository's).
5. English (`en`) added as the first translation of every component, so the curated English can start.
6. Optional: Weblate's "Squash Git commits" add-on, so each push is one reviewable commit, and its "Cleanup
   translation files" add-on, so a string removed from the source leaves the translations too.
