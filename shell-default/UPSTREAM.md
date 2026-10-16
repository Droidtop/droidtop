# shell-default's upstream

shell-default is a fork of **Murine Launcher**, <https://github.com/alesimula/Murine-launcher> (Apache-2.0), itself a fork
of AOSP Launcher3. It was brought in on 2026-08-21 (droidtop `cd6b712f`, "Fork in Murine Launcher as shell-default's real
Standard shell") with its build reshaped for droidtop (one library module, the aosp + withoutQuickstep variant, no
compatLib); see that commit's message.

## Base

**Upstream `081e840`** ("VERSION - Update version to 1.1.2", 2026-07-18). Found on 2026-10-16 by comparing file
contents: of the import's 3476 files, 3448 are byte-identical to upstream at `081e840` (the most of any upstream
commit; its parent `0546c42` differs only in the version line, which the import's build rewrite does not keep). The
import's own message named no commit.

## Taken from upstream

Each upstream commit droidtop has considered, with what happened to it. `scripts/murine-upstream.sh` lists the
upstream commits since the base that are not in this table, so a new upstream commit shows up until it is decided.
"Already" means droidtop had made the same fix itself.

| Upstream | Subject | Here |
| --- | --- | --- |
| `ef9d373` | fix: handle SecurityException when loading apps for other users | Taken (LoaderTask); its manifest permission was reverted upstream (`cdf1a0b`) and is not taken |
| `cdf1a0b` | Revert INTERACT_ACROSS_USERS permission | Nothing to do: the permission was never taken |
| `4dcfc82` | WIDGETS - fix crash on pre-S devices when creating widget host views | Taken |
| `a4bd148` | BUGFIX - fix crash when the notification listener is destroyed before it's connected | Taken |
| `8ded9b2` | BUGFIX - Fix blank clock widget preview below Android 10 | Taken |
| `5ad7c53` | BUGFIX - fix rare crash in FolderAnimationManager | Taken |
| `1b06907` | Fix widget-related crash in LauncherWidgetHolder | Taken |
| `b1a2911` | BUGFIX - wallpaper - survive SecurityException from Huawei's setWallpaperOffsets | Taken |
| `ccf988f` | BUGFIX - widgets - fix NPE when the pending widget was never on the workspace | Taken |
| `7eef061` | BUGFIX - widgets - guard setOnLightBackground behind API 29 | Already |
| `49d0013` | WORK_APPS - fix enabling work profile on Android 8 / 8.1 | Taken (WorkProfileManager); the UserProfileManager and ApiWrapper guards were already here |
| `586e138` | BUGFIX - NoSuchMethodError while restoring backups on Android 8 | Already |
| `571606d` | BUGFIX - FloatingSurfaceView - don't send a stale icon position | Taken |
| `2d99e32` | BUGFIX - crash on shortcut long-press on some Android 8/9 devices (non-convex icon mask outline) | Taken |
| `4fd9b01` | BLUR - fix unnecessary heap allocation | Taken |
| `93e7abe` | HOME_LOCK - add home screen lock option | Taken (Droidtop/tracker#564); its settings, strings and the long-press menu hunks fitted by hand around droidtop's rows |
| `3346dfa` | HOME_LOCK - disable adding new icons to homescreen when locked | Taken; the settings hunk fitted by hand |
| `7f84e03` | LABELS - full hide option (both home and drawer) | Taken |
| `aa1bb09` | DRAWER - add side padding option | Taken |
| `09fc582` | LABELS - show only in home option | Taken |
| `ce7a74b` | ICONS - adaptive icons toggle | Taken (Droidtop/tracker#564) |
| `c549552` | ICONS - avoid double wrapping when adaptive icons are disabled | Taken |
| `5104187` | ICONS - add option to force legacy icons | Taken |
| `70d3436` | ICONS - gray out "Force legacy icons" if resource hack is outdated | Taken |
| `6206042` | LAUNCHER_ICON - add monochrome icon | Not for this tree: Murine's own app icon; droidtop has its own |
| `9001d72` | ICONS - add monochrome icons option for Android 13+ | Taken |
| `e0f2e25` | ICONS - resolve correct icon while dragging on Android 8-11 | Taken, after the icon options it builds on; one import fitted by hand |
| `17740a9` | DRAWER - add drawer search autofocus | Skipped on purpose (coordinator, 2026-10-16): the drawer's search field opens droidtop's one search (Droidtop/tracker#12), so focusing it on open would open that search every time |
| `e4e124c` | QSB - search within apps | Skipped on purpose (coordinator, 2026-10-16): it makes the home search bar a second app search; apps are found by droidtop's one search (`LauncherSearchActivity`), which the drawer's search field opens, and the home search bar keeps its web search |
| `72bd0fa` | QSB - long-press menu and drag icons from QSB search | Skipped with `e4e124c`, whose results it acts on |
| `831bcd2` | APP_LOCK - removed reflection and using direct calls | Waits: calls Android 17's App Lock API directly, and shell-default compiles against SDK 36 |
| `507fc48` | APP_LOCK - add protected apps tab | Waits with `831bcd2`, which it builds on |
| `50cc836` | I18N - add remaining locked_apps_label localizations | Waits with `507fc48`; only es and it are kept here |
| `9901444` | APP_LOCK - add native app lock toggle in app info bottom sheet | Waits with `831bcd2` |
| `b726053` | APP_LOCK - use "Hidden & Protected apps" label when protected apps are supported | Waits with `507fc48` |
| `2ccb47e` | APP_LOCK - do not show lock / unlock button for deep shortcuts | Waits with `9901444` |
| `f7e2f56` | REFACTOR - remove TODO to hide the "search hidden apps" toggle in the Locked tab | Waits with `507fc48` |

Not yet decided: the rest of the list `scripts/murine-upstream.sh` prints. Of those, the website (`WEBSITE - ...`),
version bumps and upstream's own build moves (AGP and Gradle upgrades, `4e670a4` moving every module under
`modules/`) are not for this tree, whose build is droidtop's.
