# Changelog

Every build's release notes, and the What's new droidtop shows after an update, are one entry per build
(`build-scripts/release_channel.py`, docs/SPEC.md 10b "Every build carries its changelog"). The entry is made from the
commits since the previous published build, and enhanced by the fragments in this folder. A build always publishes.

## The commit convention

The generator reads each commit's message (`build-scripts/changelog_commits.py`). In the message's last paragraph,
as git trailers, a commit may say:

    Changelog: Added: <what a person notices, in plain words>
    Changelog: Changed: ...
    Changelog: Fixed: ...
    Changelog: none
    Onboarding: <what a person needs to do or know after updating>

- `Changelog:` is the change's line in the build's entry, under that heading. Several make several lines. `none` marks
  a change nobody will notice (CI, tests, docs, a refactor): it folds into one "Internal changes" line.
- `Onboarding:` is a line under "After updating" in the release notes and in What's new: something a person must do
  after this update (sign in again, turn a setting on). Every build's lines between the installed version and the
  new one are shown, so an update that skips builds misses none.
- Without a `Changelog:` trailer, the commit's subject is its line: under Fixed when it says it fixes something, Added
  when it adds something, else Changed; a subject about tests, CI, docs or a fragment counts as internal. Subjects are
  written for developers, so a change people will notice should carry a trailer or a fragment.
- Commit hygiene fails a malformed trailer (`Changelog: Fixed the thing` instead of `Changelog: Fixed: the thing`).

## Fragments

A fragment is the longer form of a change's line, for when one line is not enough. It replaces the line of the commit
that added it, and of any commit without a trailer that cites the same tracker issue (a fragment named
`513-plugins-search` tells a commit that mentions `#513`).

- One file per change, named for it, for example `727-whats-new.md`. A name is never reused: it is how every
  build's history knows which entries it already carried.
- Keep a Changelog's headings, `### Added`, `### Changed` or `### Fixed`, each over `- ` lines. A line that runs on
  is indented two spaces.
- Say what a person notices, not how it was built. No tracker links, no private plugin names, no tool names.

`python3 build-scripts/release_channel.py range AFTER THROUGH` prints every build's entry between two versionCodes,
merged, with their After updating lines: what an update between them brings.

When a real version is cut, `python3 build-scripts/release_channel.py fragments` prints every fragment grouped for
`CHANGELOG.md`; it is rewritten there as that version's section and the fragments are removed by name.
