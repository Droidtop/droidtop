# Security review X1: droidtop intents, launch templates, storage, updater, credentials, root

Date: 2026-09-24. Scope: the `droidtop` repository at `main` (4480165),
excluding vendored trees. Method: reading the code; nothing here was run on
a device. Severity is for droidtop's own threat model: a single-user
handheld where other installed apps are untrusted, the network is untrusted,
and the platform-database repository and GitHub releases are trusted but
could be compromised.

| # | Finding | Severity | Status |
|---|---------|----------|--------|
| 1 | Launch templates could grant any file droidtop can read, private data included, to another app | High | Fixed |
| 2 | `{file.inject}` read files named by substituted values and outside the game folder | Medium | Fixed |
| 3 | Launch templates could target droidtop's own unexported components | Medium | Fixed |
| 4 | Onboarding and Console systems screens were exported with no need | Low | Fixed |
| 5 | Root shell scripts spliced paths and digests into single quotes | Low | Fixed |
| 6 | Update feed's `apkName` was used as a URL path unchecked | Low | Fixed |
| 7 | Root `tar -x` of a registry image into the rootfs | Medium | Fixed (rig check pending) |
| 8 | `GameLaunchActivity` is exported: any app can launch a library entry | Low | Accepted |
| 9 | `MainActivity` is exported with mode extras | Info | Accepted |
| 10 | ScreenScraper credentials in plain private SharedPreferences, password in the query string | Low | Accepted |
| 11 | Update integrity rests on the APK signature, not on the published digest | Info | By design |
| 12 | Enginehost and emulator targets are chosen by package name only | Info | Accepted |
| 13 | gamenative OAuth WebViews not reviewed | n/a | Not reviewed |

## 1. Launch templates could grant droidtop's private files (High, fixed)

`AmStartCommandToIntentConverter.toIntent` builds the Intent droidtop sends
for every emulator launch and every integration. Templates come from the
bundled players seed, from `players-database.json` downloaded from
droidtop-platforms (base URL overridable in Settings,
`PlatformDatabaseSource.kt`), from custom players, and from integration
files. Before the fix:

- `-f <int>` set the Intent's flags verbatim, including
  `FLAG_GRANT_WRITE_URI_PERMISSION`, `FLAG_GRANT_PERSISTABLE_URI_PERMISSION`
  and `FLAG_GRANT_PREFIX_URI_PERMISSION`.
- `-d <uri>` accepted any URI, including a hand-written
  `content://dev.droidtop.app.fileprovider/root/data/data/dev.droidtop.app/...`.
- The FileProvider uses `root-path` (`res/xml/file_paths.xml`), so that URI
  resolves to droidtop's own private files: ScreenScraper credentials, the
  library database, Steam/GOG/Epic sessions in the gamenative stores.

Together: a malicious or compromised players database (or a mirror a person
was persuaded to point at) could name an emulator package it controls and
receive a persistable, writable grant over droidtop's private data.

Fix: grant bits are masked out of `-f`; a `-d` URI on droidtop's own
FileProvider authority must be exactly the `{file.uri}` droidtop issued;
`{file.uri}` is refused for a file whose canonical path is inside
droidtop's credential- or device-protected data dir. `root-path` stays,
because ROMs live on removable volumes, but nothing can reach the private
part of it through the launch path any more.

## 2. `{file.inject}` read arbitrary files (Medium, fixed)

Placeholders were substituted into each token and `{file.inject:...}` was
then expanded on the result, recursively, including on injected content.
So text that is not template text could name a file to read:

- a ROM folder on shared storage named
  `{file.inject:/data/data/dev.droidtop.app/shared_prefs/<file>.xml}` (any
  app with storage access can create one, and paths are substituted into
  `{file.path}`, `{file.dir}` and `{system.folder}`);
- a `{query}` value, or the content of an injected id file.

The file's content then rode out to the target app in an extra. A template
could also inject any absolute path directly.

Fix: expansion is one pass over the template's own text; values and
injected content are copied through verbatim. `{file.inject}` reads only a
file whose canonical path is inside the game's own directory, which covers
both real uses (`{file.basename}.psvita` beside the game, and
`{file.inject:{file.path}}` for GameNative stubs). Unit tests in
`AmStartTokenizeTest` cover each case.

## 3. Templates could target droidtop itself (Medium, fixed)

Intents sent by droidtop may start droidtop's own unexported activities.
A template with `-n dev.droidtop.app/<internal activity>` plus extras was an
intent-redirection route into screens that are deliberately not exported
(store sign-in, container config). No template needs this; `-n` and `-p`
naming droidtop's own package are now refused.

## 4. Unneeded exported activities (Low, fixed)

`OnboardingActivity` and `ConsoleSystemsActivity` were `exported="true"`,
but every caller is inside the APK (`OnboardingGate`,
`GamingSettingsCatalog.launchComponent`, `SettingsMiscFragment`,
`DroidtopWideSettings`, all using the own package name). Any app could open
droidtop's setup flow (which walks the person through granting all-files
access, notification access and write-settings) on top of itself. Both are
now `exported="false"`.

## 5. Root shell quoting (Low, fixed)

`DroidSpacesRuntime.writeInit` and `RootTarUnpacker.markComplete` built
`su -c` scripts with `'$path'` and `'$digest'` spliced in, without escaping.
Today the values are safe (container names are `droidtop-primary` or
`droidtop-sibling-<uuid>`, digests come from `crane digest`, which validates
them), but a single quote in either would have been a root shell injection.
Both now pass the values as positional parameters (`"$1"`). Verified with a
local `sh` simulation of `RootProcess`'s quoting, including a path with a
quote; not run under a real `su`.

## 6. Update feed `apkName` (Low, fixed)

`AppSelfUpdate.Info.apkUrl` appended `apkName` from `release-info.json` to
the download URL. A name containing `../` could have pointed the download
at another path on github.com. The digest check and Android's signature
check would still have rejected a foreign APK, so this was defence in
depth. The name must now be a bare `*.apk` file name.

## 7. Root extraction of registry images (Medium, fixed)

`RootTarUnpacker.extract` ran `tar -xf <image> -C <rootfs>` as root on
`crane export`'s flattened tarball. The image is an off-the-shelf OCI image
from a registry the person picks (or types, in the Containers screen), so
its member names and link targets are attacker-controlled.

What toybox does with that, read from its `toys/posix/tar.c` and run
off-device (toybox 0.8.14 built from source, extracting as root):
`dirflush()` resolves each member name, following symlinks, and refuses one
that lands outside `-C`, so `../` names and writes through a symlink
planted earlier in the archive were refused. A hard link's target is not
checked at all: `link(link_target, name)` is followed by `lchown` and
`chmod` of the new name, which is the target's inode. A member `hl` linked
to `../../../<outside>/victim` gave the victim a second link and changed its
mode from 0600 to the member's 0644. As root, that is a chmod and chown of
any file on the same filesystem as the rootfs (`/data`), including other
apps' private files. Older toybox versions, and a root manager's busybox
that `su` may find first on `PATH`, were not examined and did not need to
be (below).

Fixed by not giving root's `tar` the image at all. Images are now kept as
an OCI image layout and flattened by droidtop itself (`OciFlattener`,
docs/SPEC.md §3 "How a container is made"), as the app. It emits only
names relative to the rootfs with no `..`, nothing beneath a symlink or
other non-directory, each path once, and hard links only to a regular file
of the same layer that is in the final tree. It writes no user or group
names and no pax headers. That stream is piped into `su -c tar -xf - -C
<empty rootfs>`, so whichever `tar` runs is only ever given names and link
targets that stay inside the rootfs through real directories. Root still
writes the files (droidspaces needs the image's real ownership and setuid
bits), and root use stays in `:runtime-linux-root`. The proot backend writes
the same flattened entries in-process.

Verified off-device: the hostile layers above (a `../` name, a symlink then
a member through it, a lower layer's symlink with an upper layer's member
beneath it, a hard link out of the rootfs), flattened and extracted by
toybox as root, changed nothing outside the rootfs, and the lower symlink
became a real directory. Real images (alpine, debian:bookworm-slim,
python:3.12-slim) flattened this way matched `crane export` path for path.
Not verified yet: `su`'s standard input reaching `tar` under KernelSU,
Magisk and APatch, and a sibling container booting from the new store, on a
rooted device.

## 8. `GameLaunchActivity` exported (Low, accepted)

Exported so pinned shortcuts and `am start` work. Any app can launch any
library entry whose id it knows; ids are only ones droidtop's scan
produced, and a launch runs exactly what the person's own library would
run. Kept as designed.

## 9. `MainActivity` exported (Info, accepted)

It has no intent filter of its own (the `OpenShells` alias is the launcher
entry), but is exported, so any app can send `EXTRA_MODE` and the Gaming
start-section and rescan extras. Worst case is a mode switch or a rescan.
Kept, because the Launcher fork and system HOME paths reach it and a change
there needs a rig check; worth revisiting with the launcher owner.

## 10. ScreenScraper credentials (Low, accepted)

`ScreenScraperPrefs` stores the user's ssid/sspassword in a MODE_PRIVATE
SharedPreferences file; `allowBackup="false"` keeps it out of backups. The
API only takes credentials as GET query parameters over HTTPS
(`ScreenScraperClient`), and logs redact them (`summarizeErrorBody`). The
remaining exposure is a debug build (`run-as`), which the updater installs
only when the person opts in. Encrypting with the Keystore would add little
against an attacker who already has droidtop's uid. The droidtop developer
id/password are XOR-scrambled application identifiers, not user secrets
(documented in `ScreenScraperDevCredentials`).

## 11. Updater integrity model (Info)

`release-info.json` and the APK are served from the same GitHub release, so
the SHA-256 check guards against corruption and against a mirror or proxy
swapping bytes, not against a compromised release. What stops a foreign
APK is Android's signature continuity with the CI key. `UpdateNowReceiver`
is exported but guarded by `android.permission.DUMP`;
`AppUpdateStatusReceiver` is not exported and its PendingIntent is
explicit. Downloads are HTTPS; cleartext traffic is off (targetSdk 34, no
network security config).

## 12. Target apps chosen by package name (Info)

Emulators and Enginehost are addressed by package name. If the real app is
not installed, another app installed under that package name receives the
launch and its read grant for the one game file. That is how Android
package identity works for every launcher; the grant is read-only and for
one file.

## 13. Not reviewed

The gamenative store sign-in WebViews (`GOGOAuthActivity`,
`EpicOAuthActivity`, `AmazonOAuthActivity`) live in the `vendor/gamenative`
submodule, which was not checked out for this review. droidtop's own code
has no WebView and no `file://` use.
