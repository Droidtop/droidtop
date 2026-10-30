# Changelog fragments

Every change that reaches `main` adds one file here saying what changed for the people using droidtop, in plain
words. Each build's release notes, and the What's new droidtop shows after an update, are the fragments that build
was the first to carry (`build-scripts/release_channel.py`, docs/SPEC.md 10b "Every build carries its changelog").
A build with no new fragment is not published.

- One file per change, named for it, for example `727-whats-new.md`. A name is never reused: it is how every
  build's history knows which entries it already carried.
- Keep a Changelog's headings, `### Added`, `### Changed` or `### Fixed`, each over `- ` lines. A line that runs on
  is indented two spaces.
- Say what a person notices, not how it was built. No tracker links, no private plugin names, no tool names.
- A change nobody will notice still says so, under `### Changed`, in a few words ("Internal cleanups in Settings;
  nothing looks different").

When a real version is cut, `python3 build-scripts/release_channel.py fragments` prints every fragment grouped for
`CHANGELOG.md`; it is rewritten there as that version's section and the fragments are removed by name.
