# droidtop's plugin host — integrity and root consent, not a sandbox

(docs/SPEC.md §12a, decided 2026-09-25; supersedes the withdrawn
Enginehost-subplugin design from §12a's 2026-09-02/09-24 text)

## What this is not

The plugin host (`plugin-host` module) is **not a security sandbox**.
A `native_bundle` plugin's code runs as droidtop's own UID, in a
separate OS process (`:pluginhost`) but not a separate user, with no
`SecurityManager`, no dropped Android permission, no restricted
filesystem view. Plugin code can read and write anything droidtop's own
process can. Nothing below should be read as changing that.

What the process boundary actually buys is **crash containment**: a
plugin that throws an uncaught exception, deadlocks, or native-crashes
takes `:pluginhost` down, not `:app`. droidtop's binder
`DeathRecipient` and the per-call/per-job crash callback both catch this
and disable exactly the one plugin responsible (`PluginCrashPolicy`);
the launcher, and every other plugin, keep running. That is the whole of
the guarantee: **stability under a misbehaving plugin, not protection
from a malicious one.**

## Integrity — what makes it into `:pluginhost` at all

A plugin bundle (`*.droidplugin.tar.xz`: `manifest.json`, `manifest.sig`,
declared payload files) is refused unless every one of these holds,
checked in `PluginBundleInstaller.install`:

- **Every payload file has a declared SHA-256, and it matches the real
  bytes.** A file present in the archive but not in the manifest is a
  refused install. A manifest entry with no hash is a parse failure, not
  a skip.
- **The manifest's signature verifies** against a pinned P-256 public key
  for its declared `origin` (`BundleSignature.verifyManifest`,
  `PluginOriginKeys`). An unpinned origin, a missing signature, or a
  signature that doesn't verify is refused outright — there is no
  "unverified but installable" tier.
- **The plugin id is namespaced to its origin** (`<origin>.<name>`) and
  cannot shadow a protected id (`PluginBundleInstaller.PROTECTED_IDS`)
  or an id another origin already installed under. Namespace mismatch is
  a structural validation failure, checked before signature verification
  even runs.
- **Both ABIs (arm64-v8a and x86_64) are present for any native
  library.** A bundle shipping a `.so` for only one ABI is refused — the
  same standing rule droidtop's own engine-plugin bundles follow.
- **Re-verified before every activation, not just at install.**
  `PluginBundleInstaller.verifyInstalled` re-hashes every payload file
  and re-checks the signature each time `PluginCrashPolicy` is about to
  load a plugin. A file edited on disk after approval — however that
  happened — is a plugin that stops running, silently disabled with a
  reason, not one that keeps executing unverified bytes.
- **Approval is bound to the exact archive digest**
  (`PluginRecord.archiveDigest`, a SHA-256 over the signed manifest
  bytes) and never carries forward. An update that changes even one byte
  of the manifest starts back at `PENDING`, even for an id the user
  already approved once.
- **Nothing runs before all of the above passes.** A plugin that fails
  any check never has its code loaded — not even to ask it to describe
  itself. Its name, capabilities, and settings surface come entirely
  from the validated manifest.
- **A plugin's output is untrusted input on the way back.** Every value
  a plugin returns across the binder is size-capped
  (`PluginRunner.MAX_RESULT_BYTES`, 256 KiB), parsed against the calling
  capability's own shape, and never used directly as a filesystem path,
  a FileProvider URI, an intent target, or a launch template.

## Root consent — an enhancement, never a requirement

droidtop's launcher and handheld code do not use root, full stop — that
rule is unchanged by any of this. The one deliberate exception is
per-plugin and opt-in:

- A plugin must **declare** `requestsRoot` in its manifest up front. A
  plugin that doesn't declare it never gets root, regardless of what the
  device offers.
- Root use additionally requires the **user's explicit grant** on the
  approval screen, separate from approving the plugin itself
  (`PluginStore.setApproval(..., grantRoot = true)`,
  `PluginRecord.rootApproved`). Approving a plugin does not imply
  granting it root; the screen requires a second, named action.
- `PluginContext.hasRootApproval()` folds "this device actually has
  root" and "the user granted this plugin root" into one check, so a
  plugin's own code has exactly one place to gate the enhancement. Its
  documented contract requires a plugin's core function keep working
  when this is false — on a rootless device, or one where the grant was
  declined — because root here is explicitly an enhancement a plugin MAY
  use when present and approved, never something its basic job may
  require.
- The plugin host itself (`PluginRuntimeService`, `PluginStore`,
  `PluginBundleInstaller`) needs no root for anything it does. Only
  plugin code that both declared and was granted the request ever
  touches it.

A `boundServiceTargets` declaration (a package a plugin may hold a live
service connection to) follows the same shape: declared in the manifest,
shown on the approval screen alongside the root request, and — like
root — not something droidtop can technically enforce inside the shared
process. It exists so "this plugin talks to app X in the background" is
information the user had before approving, not a silent surprise
discovered later.

## What a compromised or malicious plugin can still do

Worth stating plainly, since "not a sandbox" is easy to read as milder
than it is: an approved plugin runs with droidtop's own storage access,
network access, and any Android permission droidtop itself holds. A
plugin the user approves is a plugin the user is trusting with
everything droidtop can already touch — the integrity checks above
establish that the code the user is trusting is exactly the code that
was reviewed/signed and hasn't been altered since, and the crash
containment above keeps one broken plugin from taking the rest of the
app down with it. Neither one limits what a plugin does once it is
running and approved. Approval is the actual control point; users should
treat it accordingly, and droidtop's own approval screen says so in
those terms rather than implying a stronger guarantee.
