#!/bin/sh
# PID-1's actual child (see /sbin/init, which execs this under dumb-init --
# dumb-init needs a fixed child command, so it can't be /sbin/init itself).
#
# XDG_RUNTIME_DIR/WAYLAND_DISPLAY are already set by the time this runs --
# DroidSpacesRuntime.createContainer() (runtime-linux-root's Kotlin side)
# writes an env_file droidspaces loads into the container's init
# environment before exec'ing /sbin/init (per droidspaces' own
# Documentation/Linux-CLI.md "Configuration Files" section) -- this script
# doesn't set either var itself, unverified beyond that doc since this
# hasn't been booted against a live droidspaces container yet.
set -e

# seatd, not systemd-logind: sway needs *a* seat manager to open input/DRM
# devices via libseat, but there's no real login session here (headless,
# no real hardware) -- seatd is the lightweight option made for exactly
# this case, and avoids pulling in all of systemd (which itself has real,
# separate cgroup-delegation friction running under droidspaces' namespace
# model). Backgrounded so this script can go on to exec sway.
mkdir -p /run/seatd
seatd -g seat &

# Headless: no real Android GPU/input device is exposed into this
# container (see docs/SPEC.md §2 -- the compositor's outputs are virtual,
# host-bridge pulls frames via wlr-screencopy) -- so libinput must be told
# not to look for real evdev nodes that don't exist, rather than sway
# failing to start because it found none.
export WLR_BACKENDS=headless
export WLR_LIBINPUT_NO_DEVICES=1

exec sway
