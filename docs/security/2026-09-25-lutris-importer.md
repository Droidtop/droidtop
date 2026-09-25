# Threat model: importing Lutris install scripts

Date: 2026-09-25. Written before the importer's code, as its design
constraint (docs/SPEC.md §7e3). Format reference:
github.com/lutris/lutris, `docs/installers.rst`.

## What is being imported

A Lutris installer is a community-submitted script for one game. Its
`script` has four parts that matter here:

- `files`: things to fetch (URLs, `N/A:` prompts for a user-supplied
  file, `$STEAM:` data);
- `installer`: an ordered list of directives, several of which run code
  or write files with the invoking user's rights: `execute` (a file or a
  `command` run by `bash`), `task` (`wineexec`, `winetricks`,
  `set_regedit`, `dosexec`, ...), `write_file`, `write_config`,
  `write_json`, `extract`, `move`, `merge`/`copy`, `chmodx`,
  `insert-disc`, `input_menu`, `gogdl_setup`;
- `game`: the executable, its arguments and working directory, the prefix
  and its architecture;
- a runner section (`wine`: version, `dxvk`, `esync`, DLL `overrides`)
  and `system` (`env`, `single_cpu`, `pulse_latency`, ...).

Lutris runs all of it. A real published Wine script for a well-known
game, read while writing this, runs `sed -i` over a shell script it
extracted and then executes it, and appends to a config file with
`printf ... >>` through `bash -c`. Consuming scripts the way Lutris does
is running an unsigned installer from whoever last edited the page.

## Who can attack, and through what

| Source | Capability |
|---|---|
| Any lutris.net account | Submit or edit an installer. Moderation exists but is not a security review; drafts and revisions are public. |
| lutris.net itself, compromised | Serve any JSON under `/api/installers/<slug>`. |
| The network | Nothing beyond TLS failure: requests are HTTPS only, cleartext is off app-wide (targetSdk 34, no network security config). |
| Another app on the device | Nothing new: the importer has no exported entry point, reads no shared file and accepts no intent extras. |

## Assets

- droidtop's own private data (credentials, the library database, store
  sessions) and its uid's rights.
- The Wine prefix, which is shared by every Windows game that has no
  prefix of its own (§5b, §7i): a bad prefix setting breaks all of them.
- The user's game folders (droidtop never copies, moves or writes a
  game's files).
- The user's attention: a preview that claims more than will happen, or
  hides what was dropped, is its own harm.

## Decisions (each is a requirement on the code)

1. **Data translation, never interpretation.** The importer reads the
   script into droidtop's own fields and nothing else. No directive is
   executed, emulated or partially run. There is no `bash`, no `sh -c`,
   no `wine` start, no `winetricks`, no registry write, no download and no
   file write performed on the script's behalf. Anything a directive
   would do that droidtop has no already-reviewed action for is listed
   to the user as **not imported, needs manual setup**, with what it
   would have done in plain words.
2. **Closed mapping tables.** The only things that reach droidtop's state
   are:
   - per game: an executable, its arguments and its working directory;
   - on the prefix: DXVK on or off, esync on or off, a single CPU, a
     Windows component from gamenative's own component set (reached from
     a known `winetricks` verb through a fixed table), a DLL override, and
     an environment variable from a fixed allowlist.
   Every other key, directive, task name or verb is not imported. An
   unknown directive is not imported by default; new mappings are code
   changes that go through review, never data.
3. **The executable must be inside this game's folder.** A script's
   `exe` is matched against the game's own folder by the file name and
   its trailing path components; the match must be an existing regular
   `.exe` file whose canonical path is inside the game folder's
   canonical path (symlinks and `..` cannot escape). An absolute path,
   anything in the prefix (`drive_c/windows/...`, `cmd.exe`) or anything
   not found is not imported. The working directory obeys the same rule
   and must be a directory.
4. **Arguments are data, not a command line to a shell.** Arguments are
   split into a list once, quote-aware, and handed to the existing Wine
   launch as separate arguments (`WineLaunchPlan.guestExecutable`, which
   is exec'd without a shell). Any argument containing a `$` variable
   (`$GAMEDIR`, `$RESOLUTION`, `$INPUT_*`) or a control character is not
   imported: droidtop does not substitute Lutris variables, and a value
   half-substituted is worse than one left out.
5. **Environment allowlist, value grammar.** Variables are imported only
   when the name starts with `DXVK_`, `VKD3D_`, `MESA_`, `mesa_` or
   `__GL_`, or is one of `WINE_LARGE_ADDRESS_AWARE`,
   `STAGING_SHARED_MEMORY`, `PULSE_LATENCY_MSEC`, and only when the
   value is made of letters, digits and `_ . , : = + -`. That excludes
   every loader and path variable (`LD_PRELOAD`, `LD_LIBRARY_PATH`,
   `PATH`, `WINEPREFIX`, `WINEDLLPATH`, `BOX64_*`), every value that is a
   path (no `/` or `\`), and everything that would break the prefix's
   space-separated variable string. `WINEDLLOVERRIDES` in `env` is not
   imported; overrides come only through the `overrides` mapping.
6. **DLL overrides are validated.** Names are `[A-Za-z0-9_.-]+` with a
   trailing `.dll` dropped; values must be one of Lutris's documented
   forms (`n`, `b`, `n,b`, `b,n`, `native`, `builtin`, `disabled`, and
   the long forms of the two pairs). They are merged into the prefix's
   `WINEDLLOVERRIDES`, `;`-separated.
7. **The preview is the truth, and nothing changes before Apply.**
   Before anything is written, the person sees three lists: what will be
   set for this game, what will be set on the prefix (named, and
   stated to be shared with every other Windows game that uses it), and
   what is not imported and why. Apply writes exactly the first two;
   "This game only" writes only the first. Nothing is applied on open,
   on download, or in the background.
8. **Resource limits on the parse.** The response is read up to 2 MiB
   and refused past it; at most 500 directives and 200 files are read;
   strings longer than 4 KiB are refused. A script that exceeds a limit
   is refused as a whole rather than half-read.
9. **Where it can come from.** In this pass, only lutris.net's own
   `/api/installers/<slug>` over HTTPS, with the slug taken from a
   Lutris search the person chose from and checked against
   `[a-z0-9-]+` before it goes into a URL. No script file from shared
   storage, no intent, no URL the script names is ever fetched.
10. **Runner scope.** Only `runner: wine` scripts are importable. Other
    runners (`linux`, `dosbox`, `scummvm`, `steam`, emulators) are shown
    with their runner name and cannot be picked: droidtop's own runner
    resolution (§7i) is the authority on how a game runs, and a script
    for another runner describes a different game setup.

## What is deliberately still possible

- A script can set a prefix-wide option that breaks other games in a
  shared prefix (for example DXVK off, or a single CPU). That is the same
  power "Prefix and graphics" already gives the person; the importer only
  pre-fills it, names the shared prefix, and requires the person's Apply.
  "This game only" exists for exactly this case.
- A script can name the wrong executable among the game's own. The
  launch then runs a program from the game's folder, which the person
  already chose to trust by putting it there; the preview names the file.
- Windows components are gamenative's own DLL archives, fetched by
  gamenative's own component downloader from the URLs in its
  `assets/wincomponents_download.json` (the same path the Win Components
  tab uses). Importing one only switches it on in the prefix's component
  list; no DLL and no URL comes from the script.

## Out of scope for this pass

Local script files, `$STEAM:` data, `launch_configs` beyond the main
executable, Lutris variable substitution, registry edits, file edits
inside the game folder, and per-game prefixes (a game with no prefix of
its own shares droidtop's one prefix, §5b). Each is listed to the person
as not imported when a script uses it.
