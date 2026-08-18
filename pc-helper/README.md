# pc-helper

A small background service (`droidtop-helper`) that runs on the user's
gaming PC — not an Android module, a separate Go program, since it needs to
run natively on Windows/Linux where Steam and Sunshine actually live.

## What it actually does, honestly

Two capabilities, of very different maturity — see the research this was
built from, summarized in [docs/SPEC.md](../docs/SPEC.md):

1. **Auto-register a game with Sunshine** (`internal/sunshine`, called from
   `POST /v1/apps/register-stream`) — **solid, proven.** Sunshine has a real
   documented REST API (`POST /api/apps`) for exactly this. No manual
   `apps.json` editing, no web UI interaction required.
2. **Trigger a Steam install** (`internal/steam`, called from `POST
   /v1/apps/install`) — **not headless, don't present it as such.**
   `steam://install/<appid>` requires Steam already running and the user
   already logged in on this PC, and it brings the Steam client window to
   the foreground — there's no documented silent/background variant. The
   SteamCmd alternative (`TriggerInstallViaSteamCmd`, unimplemented) can be
   scripted unattended, but only after a one-time interactive Steam Guard
   login on this specific machine, and only with extra work to make the
   result show up in the normal Steam client's library (SteamCmd doesn't
   install into the GUI client's expected `steamapps/common/` layout by
   default). **There is no known way to do a true zero-touch first-time
   remote install with current tooling** — set expectations accordingly in
   the app's UI, don't imply this is instant/automatic.

## Not yet safe to expose on a network

Every endpoint here does something consequential (installs software,
registers apps with a locally-running admin API). `internal/api`'s server
has no pairing or authentication yet — it's scaffolded to listen on
`127.0.0.1` only. Before this can listen on the LAN (which it needs to, to
be useful — the whole point is the phone talking to it), it needs a pairing
flow: helper shows a short one-time code, user enters it in the DroidTop
app once, helper issues a bearer token for subsequent requests. Do not skip
this to get something "working" faster.

## Status

Scaffold only — not built or run. No Go toolchain was available in the
environment this was written in; this has not been compiled.

## Running (once buildable)

```bash
go run ./cmd/droidtop-helper \
  -sunshine-user admin \
  -sunshine-pass <sunshine-admin-password>
```
