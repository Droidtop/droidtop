# Changelog

All notable changes to droidtop are documented in this file. The format
follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and
versions follow [Semantic Versioning](https://semver.org/) (0.x releases are
pre-release: no compatibility promises between them).

Every dev build published between releases gets its own release notes,
generated the same way as this file's entries (grouped Added / Changed /
Fixed); this file only gains a new section when a real version is cut.

## [Unreleased]

Nothing yet.

## [0.2.0] - 2026-09-28

This is droidtop's first version-history entry: there was no changelog
before this release, so this section summarizes what already exists rather
than listing every change one by one.

### Added

- A permanent, versioned history of every build's release, with notes
  describing what changed since the previous build, instead of one release
  that kept getting overwritten.
- Gaming mode: a gamepad-driven, themed launcher that renders a real
  ES-DE launcher theme, so it looks and feels like the emulation handheld
  frontends people already know.
- Desktop mode: a real Linux desktop, with its own window manager, taskbar
  and window list, running in a container on the device, for using droidtop
  like a small PC.
- Support for running Linux software in containers and Windows software
  through a Wine-based compatibility layer, side by side, without needing a
  full virtual machine.
- Engine games (visual novels and similar) run through a separate
  companion app using an install-once plugin system, so new engines and
  fixes for them can ship without an app update.
- A plugin catalog: browse, install and update plugins from a trusted
  source, with update trust tied to the same signing key a plugin was
  first installed from.
- One shared game library across every source (scanned folders, installed
  containers, engine games and other sources), with shared searching,
  sorting and filtering.
- An onboarding flow that walks through picking a mode and setting up the
  library the first time droidtop is opened.
- Second-screen support for devices with a second display.
- A gamepad-first navigation model: the pointer and the current focus are
  one selection, the d-pad steps through lists and menus, and every screen
  keeps a hint row showing what each button does.
- An in-app updater with Stable, Testing and Unstable release channels, and
  a "Check now" action that finds, downloads and installs an update right
  away.

### Changed

- Settings were reorganized so related options live in one place -- for
  example, every account and content source now lives under one screen
  instead of being spread across several.
- The game library's list and grid views now consistently use the theme's
  own frame, instead of droidtop drawing its own chrome on top of it.
- Container audio now goes through the same audio path Wine already uses,
  instead of a separate bridge.

### Fixed

- The d-pad could act twice on a single press in some menus; it now only
  acts when a direction is pressed, not again when it is released.
- Settings search was rebuilding its index on every keystroke instead of
  once.
- Several cases where gamepad focus could land on a control that was not
  meant to be reachable by the d-pad.
