# droidtop plugin API

The design of droidtop's plugin API: the model, the complete catalogue of
API groups, the permission model, how today's capabilities map onto it,
and the phased plan. `docs/SPEC.md` §12a summarises this document and
links to it; §12a stays the record of what is already built (the
manifest, signing, trust tiers, the catalog, the runners, the job shape).
This document is where the API's decisions live.

Umbrella issue: Droidtop/tracker#53 (label `plugin-api`; the roadmap in §9
names each item's issue).

**Premise (owner, 2026-09-28).** A droidtop device is a general-purpose
computer. It has three modes: Desktop (a computer), the standard Android
launcher (an Android device) and Gaming (a games console). Plugins extend
droidtop in context, in any of those modes. They hook into what droidtop
exposes and do not own screens; the only exceptions are the few places
where no other shape can work (a root or Shizuku manager's pairing flow,
for example). Python, native and Flutter plugins all sit on one API.
Access to that API is controlled by permissions. The question to answer is
"what could anything on a computer want?", so the catalogue in §3 is meant
to be exhaustive. Every group is designed now, even where nothing is built
yet, so that later additions fit one model instead of each inventing its
own.

**Everything goes over the API (owner, 2026-10-16; §1.0a).** The owner,
verbatim: "Remember that ALL plugin activity happens over the API. This
means programmatic control should be simpler, because plugins need to be
uniform. We need to ensure that plugins have absolutely zero access to
droidtop ANYTHING except through the API". This is the rule above every
other in this document:

- A plugin reaches droidtop only by being called at a point it declared
  (`handle`, jobs, events) and by calling the host APIs it was granted
  (`host.call` and its kind's equivalents, through the broker). Nothing
  else droidtop has (its classes and their state, its Android context,
  resources, preferences, databases and files, its components and binder
  services, its UID, its process memory, its environment and its open
  descriptors) is the plugin's by any other path, in any kind (`native_bundle`,
  `python`, `flutter_embed`, an application interface) and in any tier.
- **Full access means more API grants. It doesn't mean a back door into
  droidtop's internals.** The owner, verbatim, the same day: "Full access
  should ALSO mean restricted access API paths designed specifically for
  those purposes". So inside droidtop's API, full access is never the same
  API with its checks switched off: it is a set of dedicated, restricted
  points, one per full-access purpose (installing a package through
  droidtop, for example), and each one is declared in the manifest, granted
  only to a full-access plugin, shown to the person in plain words, logged,
  and shaped to its one job, never a general escape hatch.
- **The OS is a separate rule.** The owner, verbatim, correcting the scope:
  "Shizuku does need direct access to android APIs. We can allow for that,
  but our plugin sandbox needs to block it when full access isn't enabled.
  I said the only path into DROIDTOP is through teh API. I didn't say that
  about the OS". So there are two rules:
  1. **Into droidtop:** only through the API, for every plugin in every
     tier, full access included.
  2. **Into the OS** (Android's APIs, system services, Shizuku, the
     kernel): a full-access plugin may use them directly (the Shizuku
     bridge does, and keeps doing so; it is not turned into a droidtop
     point). Without full access the sandbox blocks direct OS use: a
     contained or open plugin reaches the OS only through droidtop's API
     or through the one hole a granted permission opens (§5.3, "Open
     processes").
- So everything a plugin needs from droidtop is a declared point or a host
  API, the same for every kind, and a test proves each other path closed.
  The audit of those paths (into droidtop at every tier, and into the OS
  without full access), what closed each and the test that proves it, is
  §5.6 (Droidtop/tracker#53).

**Scope.** This is droidtop's plugin API only. Enginehost has its own
plugin system, and the two share nothing but a design language (§12a,
"REDECIDED"). Engine games reach droidtop through the players database,
the same way as any other emulator. They never come through this API.

---

## Contents

1. The model
2. Plugin-provided APIs
3. API catalogue (groups A to J)
4. The permission model
5. Threat model: what is enforced, what is not, what is proposed
6. Mapping today's plugin API onto this model
7. Versioning and deprecation
8. Process, threading, timeouts and quotas
9. Roadmap (P0 / P1 / P2)
10. Questions for the owner

---

## 1. The model

### 1.1 Three directions

Every interaction between droidtop and a plugin goes one of three ways:

| Direction | Name | Who calls whom | Declared in the manifest as | Example |
| --- | --- | --- | --- | --- |
| droidtop calls **into** the plugin | **extension point** (EP) | host → plugin | `provides` | a Sources search, a status tile's value, a context action |
| the plugin calls **out** to droidtop | **host API** | plugin → host (broker) | `permissions` (what it may call) | read the library, post a notification, store a secret |
| droidtop tells the plugin something happened | **event** | host → plugin, one-way in meaning | `subscribes` | `game.exited`, `library.default_player_changed` |

A fourth direction, plugin to plugin, is always carried by the host as a
broker. §2 describes it: a plugin can **export** an API that other plugins
**require**. On the wire it is an extension point on the provider and a
brokered API call for the caller.

**Extension point.** An extension point is a named, versioned place where
droidtop asks plugins for something. Each one has:

- an id in dotted form, grouped by area (`library.sources`,
  `ui.status_tile`, `launch.hooks`);
- a major version;
- a set of **ops**, each with its own argument and reply schema, and each
  either a quick call or a **job** (long-running, reports progress:
  §8);
- a **static declaration** in the manifest. droidtop can render a menu,
  a row label or a filter from it without loading any plugin code;
- the **surfaces** it can appear on in each mode (the catalogue lists
  them);
- a **risk rating**. Providing a high-risk extension point needs the same
  consent as using a dangerous permission (§4.2).

A plugin never draws its own UI. It fills in droidtop's own UI
(`CatalogScreen`/`CatalogItem` rows, tiles, menu entries, dialogs). This
holds in every mode. It is what lets one plugin appear consistently in
Gaming, Android and Desktop, and it is also a security property: the
plugin cannot spoof droidtop's UI (§5, T6).

**Host API.** A host API is a named, versioned group of calls droidtop
offers to plugins (`net.http`, `vault`, `notify`, `library.read`, ...).
Every call goes through the **broker** (§1.4). The broker checks the
caller's grant for the permission that call needs, applies a quota and
writes an audit entry. The API is **deny-by-default**: a plugin with no
declared permission can reach only the unprivileged calls (§4.1, "always
available").

**Event.** An event is a notification droidtop fires to plugins that
subscribed to it. Events are a closed, versioned set, as they are today
(`PluginEvent`). Anything that reveals personal data carries a permission
(for example, `game.launching` needs `library.history`). An event handler
can ask droidtop to start a job in reaction, as it already can (§6). A
**hook** is an event whose reply droidtop waits for, with a short budget,
and whose reply can change what happens next (the pre-launch hook, §3 B3).

### 1.2 The manifest: what a plugin provides and what it needs

Contract version 2 adds the fields below. All v1 fields keep their
meaning, and a v1 manifest is translated into this shape by one function
(§6).

```json
{
  "id": "acme.vpn-tile",
  "origin": "acme",
  "label": "Acme VPN",
  "version": "1.4.0",
  "kind": "python",
  "contractVersion": 2,
  "minDroidtop": "0.2",

  "provides": [
    { "point": "ui.status_tile", "version": 1, "id": "state",
      "label": "VPN", "surfaces": ["gaming.quick_menu", "launcher.widget", "desktop.tray"] },
    { "point": "ui.settings", "version": 1, "id": "main", "target": "plugin" }
  ],

  "permissions": [
    { "id": "net.state" },
    { "id": "net.domains", "domains": ["api.acme.example"], "reason": "Checks whether the VPN tunnel is up" },
    { "id": "notify.post", "reason": "Tells you when the tunnel drops", "required": false }
  ],

  "subscribes": [ { "event": "net.changed", "version": 1 } ],

  "exports": [],
  "requires": [
    { "api": "priv.shell", "version": "1.0", "optional": true, "minLevel": "adb",
      "reason": "Can restart the VPN app when it hangs" }
  ]
}
```

- **`provides`** lists every extension point the plugin implements. Each
  entry carries that extension point's static fields (label, surfaces,
  targets, filters). droidtop calls only the points and ops a plugin
  declared, as it does today for capabilities. A reply to an undeclared
  op is thrown away. Since 2026-10-07 (Droidtop/tracker#316) this holds at
  the boundary, not only in the surfaces that choose whom to ask: the one
  check every host call goes through (`PluginGrants.pointRefusal`) refuses
  a point the manifest does not declare at a version this build serves,
  before the plugin is loaded, whatever its grants say.
  An entry's `label` is its own title, which droidtop shows **after** the
  point's name, never instead of it: "Status tiles and widgets: VPN" on the
  approval list and the Permissions screen (`ProvidedPoint.titled`, at most
  40 characters of the label, cut with an ellipsis; Droidtop/tracker#521
  item 3). A label the same as the point's name is dropped.
- **`permissions`** lists every host-API permission the plugin may use.
  Each entry has an optional plain-language `reason`, which is shown on
  the grant screen. `required: true` means the plugin cannot do its main
  job without that permission. The approval screen says so, and a plugin
  whose required permission is denied stays installed, shows "Needs
  <permission>" and is never called. Some permissions take parameters: a
  domain list, a package list, a folder scope.
- **`subscribes`** lists the events the plugin wants, each with a
  version. An unknown id is ignored, not refused, as today.
- **`exports`** and **`requires`** cover plugin-provided APIs (§2).
- **Categories** (decided 2026-10-16, Droidtop/tracker#179; the owner,
  verbatim: "Yeah, plugins are channeled, too. They should also have
  categories"). A plugin names one category, `category`, from this fixed
  list; the catalog index repeats it on the plugin's entry, and the
  Plugins page filters by it:

  | id | Shown as |
  | --- | --- |
  | `sources` | Sources and fetchers |
  | `emulators` | Emulators and runners |
  | `system` | System tools |
  | `sync` | Sync and backup |
  | `integrations` | Integrations |
  | `appearance` | Appearance |
  | `other` | Other |

  The ids of the first list (2026-10-15) are read as their successors:
  `tools` as `system`, `themes` as `appearance`, `social` as
  `integrations`, `games` as `sources`. A missing category is `other`. The
  release line a plugin is offered on is the catalog's business, not the
  manifest's (SPEC 12a, "Channels").
- **Presentation** (Droidtop/tracker#521 item 2; `PluginPresentation`):
  `summary` (one line, at most 80 characters, under the plugin's name on
  its page and in the list), `publisher` (at most 60, "Made by"),
  `homepage` (https only, "Its page"), `category` (one id of the fixed
  list below; anything else is `other`), `icon` (a payload path of a square PNG or
  WebP) and `screenshots` (up to six payload paths of PNG or WebP). All
  optional and never refused: text is cut, a home page that is not https
  is dropped, and an image is kept only when it is an entry of the signed
  payload, so droidtop draws only files whose hash it checked. droidtop
  draws them on the plugin's page in its own "About" group; a catalog
  index may repeat `summary` and `publisher` on its plugin entry for the
  list before install. A plugin no longer puts its maker, site or a "what
  it is" line into `description`. `icon` and `screenshots` are read and
  checked now; they are drawn once droidtop's settings screens have an
  image row.
- Unknown top-level fields are ignored, so a newer manifest still parses
  on an older build. Unknown ids *inside* `provides` and `permissions`
  are also ignored. The approval screen lists them as "Not supported by
  this version of droidtop", so the user sees what the plugin wanted and
  did not get.

### 1.3 Four kinds, one contract: one adapter per kind

`PluginKind` (`native_bundle`, `python`, `flutter_embed`, `app_interface`)
says how a plugin's code is *loaded*. It never decides what that code may
*do*.
Each kind has exactly one adapter, and the adapter's only job is to carry
the same envelope in both directions:

**Host → plugin (`handle`):**

```json
{ "contract": 2, "callId": "c-81f2", "deadlineMs": 15000,
  "point": "library.sources", "version": 1, "op": "search",
  "caller": { "kind": "host" },
  "surface": { "mode": "gaming", "place": "gamelist.options" },
  "args": { "query": "zelda", "platform": "snes" } }
```

**Reply:**

```json
{ "ok": true, "data": { ... } }
{ "ok": false, "error": { "code": "UNSUPPORTED", "message": "..." } }
```

Error codes are a closed set: `INVALID_ARGS`, `UNSUPPORTED`,
`NOT_FOUND`, `PERMISSION_DENIED`, `RATE_LIMITED`, `TIMEOUT`,
`PROVIDER_UNAVAILABLE`, `PROVIDER_CRASHED`, `CANCELLED`, `FAILED`,
`SIGN_IN_REQUIRED`, `BROWSER_CHECK_REQUIRED`. A plugin's own ordinary failure ("no network",
"nothing found") is an error reply, not a crash. This keeps today's
distinction in `PluginCrashPolicy`.

**What the person reads of an error (2026-10-14, Droidtop/tracker#485).**
An error may also carry `title` and `subtitle` (plain words, at most 60
and 140 characters), `permission` and `action`:
`{"code": "PERMISSION_DENIED", "message": "...", "title": "Not allowed",
"permission": "net.any"}`. droidtop draws an error as a caution notice
(§1.6, Notices) with the code and message in Info. `PERMISSION_DENIED`
naming a permission the plugin **declared** opens that plugin's
Permissions; naming one it did not declare is drawn as a plain notice and
logged as a plugin message. `SIGN_IN_REQUIRED` offers its `action` (a view
action) as "Sign in". The helpers write it (`gg.error(code, title, ...)`,
`PluginReply.error(code, title, subtitle, permission, action)`).

**A failure is one plain sentence (2026-10-15, Droidtop/tracker#544 item 8).**
An error's `message` is one plain sentence for the person: what happened, in
their words, with no HTTP status, no exception or stack trace, no markup and
no site's page text ("The site did not answer" is a message; "f95zone
answered HTTP 403" and "JavaScript is disabled. For a better experience..."
are not). What the code saw goes in the optional `detail` field (plain text,
at most 400 characters are kept, markup removed), which droidtop shows only
behind "Technical details". droidtop still guards a message that breaks the
rule (a source's search shows droidtop's own sentence instead), and the
conformance script (Droidtop/tracker#71) flags messages with `HTTP`, a stack
trace or markup.

**Sign-in and browser checks are droidtop's to draw (2026-10-15,
Droidtop/tracker#544 item 1).** A plugin whose site needs the person signed
in answers `SIGN_IN_REQUIRED`; one whose site shows a browser check first
("Checking your browser") answers `BROWSER_CHECK_REQUIRED`. Neither
needs a title, a sentence about how to sign in, or a made-up result
row: droidtop says it in its own words and offers **Sign in** or **Open the
site** itself, in a source's search (under the source's name, searching
again after) and on any view page (as the notice's action, reading the page
again after). It opens the plugin's `web.session` in droidtop's web view: the
`signIn` page the plugin declared (G3) for a sign-in, that site's home page
for a browser check. This needs `web.session` with a site; for a plugin
without one, a `SIGN_IN_REQUIRED`'s own `action` is still offered as before.

**droidtop finds the browser check itself (2026-10-23, Droidtop/tracker#600).**
The owner: "f95 has gotten more aggressive with cloudflare filtering.
F95checker has gotten better at allowing users to solve the captcha. we need
to do the same." A plugin no longer has to recognise a check:
- **Found by droidtop.** Every `net.request` and `web.session fetch` answer
  is looked at. A 403, 429 or 503 carrying a challenge (Cloudflare's
  `window._cf_chl_opt`, `<title>Just a moment...</title>` or
  `/cdn-cgi/challenge-platform/`, DDoS-Guard's page), or any answer with
  Cloudflare's `cf-mitigated: challenge` header, reaches the plugin as the
  error `BROWSER_CHECK_REQUIRED` with one plain sentence ("<host> checks the
  browser first; open it in droidtop to continue"). It never gets the page.
- **Read by droidtop's web view after that (2026-10-24, Droidtop/tracker#600).**
  Cloudflare refuses droidtop's HTTP client even with the clearance the
  person earned. So once a host has shown the plugin a check (met and not
  passed, or a clearance kept for it), a `GET` to it through `net.http` or
  `web.session fetch` is loaded by an off-screen web view with the same
  cookies, and the plugin gets an ordinary answer: `status`, the final `url`,
  the document's own `content-type`, and as `body` an HTML page's markup or the
  text of anything else (a JSON reply as JSON, not the page the view wraps it
  in). A check the
  view passes by itself within about 25 seconds is waited for; one it does not
  pass is `BROWSER_CHECK_REQUIRED` as before. Other methods still go through
  droidtop's client. A page the site shows signed out (a guest page with no
  download links) is still the site's page. A plugin that needs the account
  should answer `SIGN_IN_REQUIRED`, not a parse failure, so droidtop offers
  Sign in.
- **Passed by the person.** droidtop remembers the address the check was met
  at, and its own surfaces (a source's search, a view page, the update page
  of a game) offer **Open the site**, which opens that site in droidtop's
  web view. This works for a plugin without `web.session` too. The view
  closes by itself once the site hands it a new clearance.
- **Cleared after.** The clearance the view earned is kept for that plugin:
  the site's clearance cookies only (`cf_clearance`, `__cf*`, `cf_chl*`,
  `__ddg*`, never an account's cookie), with the web view's own user agent,
  which the clearance is bound to. droidtop adds both to every `net.request`
  and `web.session fetch` to that site and its subdomains after; a cookie
  the plugin sends keeps its own value. A sign-in or an opened link that
  passes a check keeps its clearance the same way. Kept in the plugin's
  vault under a host key it cannot name (`@web.clearance`), deleted with it.
- **Retried.** The surface that offered the check asks again after it.
  Nothing is retried in a loop: a background call that meets a check fails
  with the error, and the check waits for the person.

**Plugin → host (the broker):**

```json
{ "api": "notify", "version": 1, "op": "post",
  "args": { "title": "...", "text": "..." } }
```

The reply uses the same `{ok, data|error}` shape.

| Kind | Host → plugin | Plugin → host | Jobs |
| --- | --- | --- | --- |
| `native_bundle` | `DroidtopPlugin.handle(call: PluginCall): PluginReply` (v2); v1 `invoke(capability, args)` still served via the legacy translation | `PluginContext.call(api, version, op, args)` on the context handed to `onLoad`; `PluginContext.openFile(...)` for an op that hands over a file (built 2026-10-08) | `startJob(jobId, call, progress)` / `cancelJob` (as today) |
| `python` | module-level `handle(call_json) -> reply_json` (JSON text both ways, like `invoke`; called for a contract 2 manifest, built 2026-10-01); v1 `invoke(payload_json)` still served | `droidtop.host.call(api, op, args=None, version=1) -> dict`: the modules `droidtop` and `droidtop.host`, which the bootstrap puts in `sys.modules` before `plugin.py` is imported (built 2026-10-07); the dict is the parsed broker reply, `{"ok": true, "data": ...}` or `{"ok": false, "error": {...}}`, and a call never raises for a refusal; `droidtop.host.open(api, op, args=None, version=1)` returns the same dict with the descriptor number under `fd` when a file was handed over (built 2026-10-08) | `start_job(job_id, call, progress)` / `cancel_job(job_id)`: the bridge registers the job, runs it on a Java thread droidtop starts (not a Python thread: a thread Python makes is attached to the JVM around every host call, and one died of the sandbox's own trap, Droidtop/tracker#558), forwards progress live, and calls `cancel_job(job_id)` cooperatively |
| `flutter_embed` | the plugin's `MethodChannel("dev.droidtop.pluginhost/<plugin id>")`, method `handle`, envelope and reply as JSON strings (called for a contract 2 manifest, built 2026-10-01) | the same channel's `hostCall`, with `{api, version, op, args}` as JSON text and the broker reply as JSON text; no `open` yet: a Flutter plugin runs contained like every kind (§5.3, built 2026-10-09); its own folder is reached by path through the file broker (§5.3 "The plugin's own folder by path", 2026-10-14), so `path_provider`, `shared_preferences` and `sqflite` find real folders, and anything else goes through `data` over `hostCall` | the same channel's `startJob`/`cancelJob` plus progress messages (the job support built 2026-09-26) |

**What a `native_bundle` plugin links against.** Its dex is loaded with
droidtop's own class loader as parent, so the plugin API
(`dev.droidtop.pluginhost`) and the Kotlin standard library (`kotlin.*`, the
version in droidtop's `gradle/libs.versions.toml`) come from droidtop and the
release build keeps both whole (`app/proguard-rules.pro`). Anything else a
plugin uses (coroutines, other libraries) it bundles in its own
`classes.jar`.

The rules that make this "no kind-specific features":

1. **An adapter serialises the envelope and does nothing else.** It
   never adds an op, a field or a shortcut that the envelope does not
   carry. Kind-internal plumbing (the Flutter readiness handshake, the
   Python module namespacing) is invisible above the adapter.
2. **Parity is tested, not claimed.** One conformance script (the fake
   host, §3 I2) drives the same calls against a test fixture of each kind, and a
   kind that fails a case is a bug in that kind's adapter. The known gaps
   (audit 2026-10-01): event delivery
   (`onEvent`) for `python` and `flutter_embed`, which both still answer
   every event with the default no-op. `hostCall` for Flutter is
   exposed, and `droidtop.host.call` for python has been since
   2026-10-07 (the docs said so before it was true; the bootstrap injected
   no module until then). `handle` reaches all three kinds since 2026-10-01
   (§1.6).
3. **A new kind is a new adapter plus a pass of the same conformance
   script,** and nothing else. This is what `PluginKind`'s own doc
   comment already asks for ("add a case and a matching runner").

**`app_interface`: an installed app as a plugin (Droidtop/tracker#547;
contract 2026-10-23).** The owner: "This is a third plugin type,
"application interface". It's a plugin that's also another app. It has to
go through the same approvals and everything as a normal plugin, and is
therefore granted permissions the same way. The API is the gate for
everything", and "Applications interface plugins don't get a special
signing certificate. They use the android signature. This also means
using root to bypass the signature check will STILL trip our trust
store". Its adapter is the app itself:

- **The manifest** is an APK asset, `assets/droidtop-plugin.json`: a
  contract 3 manifest with `"kind": "app_interface"` and
  `"app": {"package", "service"}` (the APK's own package and its service
  class), and no `payload` or `entryClass`. Its `id` follows Android's
  package-name rules, not a bundle's lowercase `<origin>.<name>`: two or
  more segments of letters, digits and underscores, each starting with a
  letter, in any case (`dev.enginehost.app`; the owner, 2026-10-24:
  "droidtop refuses to add enginehost as an application interface plugin.
  It apparently requires everything be lowercase, and rejects appnames
  with more than two segments"). It is the app's own package or a name
  under it (`dev.enginehost.app`, `dev.enginehost.app.saves`), so an app
  can never take another plugin's id. What droidtop checks for this kind
  is the app's package and its signature. `host.full_trust` and
  `gpu.render` are refused for this kind: the app never runs in a droidtop
  process. Its `version` moves whenever the asset changes, and droidtop
  treats a changed asset as an update (new points and permissions wait for
  approval).
- **The service** is exported with the action
  `dev.droidtop.plugin.APP_INTERFACE` and returns an `IPluginRuntime`
  binder built from droidtop's own AIDL, copied unchanged:
  `plugin-host/src/main/aidl/dev/droidtop/pluginhost/IPluginRuntime.aidl`,
  `IPluginHostBroker.aidl` and `IPluginRuntimeCallback.aidl` (package
  `dev.droidtop.pluginhost`; methods are only ever appended). droidtop
  binds it and calls `loadContained(pluginId, manifestJson, [], [],
  broker)` with the manifest droidtop verified itself, then `handle`,
  `startJob`, `cancelJob`, `registerCallback` and `unloadPlugin` as for any
  plugin; `attachScreen`, `invoke` and `attachRelay` are never called. The
  service answers only droidtop (`Binder.getCallingUid()` is
  `dev.droidtop.app` with droidtop's certificate).
- **Trust is the APK's own signature, checked by droidtop itself.**
  droidtop opens `sourceDir` and every split once each, verifies their APK
  Signature Scheme v2 or v3 blocks in process, requires one signer for all
  of them and the same signer PackageManager reports, and reads the
  manifest asset from the same open file. A pinned official app
  certificate gives "Official"; any other signer is trusted only through
  "Keys you trust" (an entry with `app: {package, certSha256}`) and shows
  "Unofficial". A disagreement with PackageManager (a device with its
  signature checks patched out), a v1-only APK or a failed check refuses
  the plugin: "Its app's signature could not be checked, so droidtop won't
  use it". It is checked again on every package update; a v3 key rotation
  keeps trust only through a verified rotation lineage.
- **The broker is the only way in.** Everything the app does with
  droidtop is a broker call (declared, granted, rate-limited, logged).
  droidtop exports one more thing for this kind, the **door**: a service
  with the action `dev.droidtop.plugin.APP_INTERFACE_DOOR` whose binder has
  one method, `boolean knock()` (`IAppInterfaceDoor.aidl`). An installed,
  approved, verified app_interface plugin's app calls it when it needs
  droidtop and has no broker (it started a game on its own); droidtop
  checks the calling uid against that plugin, binds the app's service and
  hands it its broker in `loadContained`. Any other caller gets `false`.
  The door carries no data.
- **An app announces itself (2026-10-23).** The owner: "every app with a
  droidtop Application Interface plugin layer make a call to announce its
  presence to droidtop, self-registering". So an app with the plugin layer
  knocks once when it starts, added or not:
  - An app not added yet still gets `false`. droidtop looks at the
    packages of the calling uid (never anything the app says), at most once
    a minute each.
  - Android starts droidtop to answer the door, so this works whether
    droidtop runs or not.
  - droidtop also finds apps by itself: installs and updates while it runs,
    and on start what changed while it did not (Android's changed-package
    sequence; everything after a reboot or the first time).
  - Either way droidtop checks the app's signature itself, and tells the
    person once per app version: a notification, "<app> can work with
    droidtop", opening Plugins, when notifications are allowed. An
    announcement never adds or trusts anything; the person adds the app
    under "In your apps".
- **Where it shows:** under Plugins, "In your apps", found with
  PackageManager (apps whose service answers the action and whose APK
  carries the asset), installed through the same approval list, grants,
  first-use sheet, Permissions screen and Activity as any plugin. Its
  page says "Runs in its own app, outside droidtop; it reaches droidtop
  only through droidtop's checks". Built 2026-10-23:
  - **"In your apps"** lists each app found, with droidtop's verdict.
    - **Official** (a pinned certificate) or **trusted by you**: **Add**
      installs it as a record waiting for approval in Installed.
    - **Unofficial**, a key nobody trusted yet: its fingerprint, and
      **Trust and add**, which asks first, keeps the key for that app alone
      ("Apps you trust", under Keys you trust, with Stop trusting) and
      installs it.
    - **Refused**: one sentence saying why (an old-style signature, two
      signers, a signer Android reports but the APK does not carry, a
      manifest that is not this app's).
    - **A plugin this droidtop cannot speak** says which side needs
      updating, read from the manifest's own `contractVersion`
      (Droidtop/tracker#854, Enginehost missing from Plugins): "<App>
      needs an update to work with this droidtop" (older than contract 2,
      or a kind or shape droidtop cannot read), or "<App> is made for a
      newer droidtop" (newer than this build's contract).
    - **An installed plugin whose record no longer reads** is listed in
      Installed with "Needs an update to work with this droidtop" and
      Remove, never left out of the list.
    - An app already added is in Installed, not here. It shows here again
      when the app changed, as **Review the new version**. The same key
      keeps the approval; the new version's new items wait as any update's.
  - **Its card** has no Unofficial chip only when signed by a pinned
    official certificate, and its page says whose key signs it.
  - **Uninstalling it** removes the plugin, not the app, and lets droidtop's
    binding to the app go.

### 1.4 The broker

Every plugin → host call and every plugin → plugin call goes through one
broker in `:app`. It is reached over a new binder interface,
`IPluginHostBroker`, which `:app` hands to `:pluginhost` when it loads
each plugin:

- **One broker binder object per loaded plugin.** The broker knows who is
  calling from *which object was called*, never from an id the caller
  claims. (§5 explains why this only holds for real once each plugin has
  its own process.)
- **The order of checks on each call:**
  1. The plugin is runnable (approved, enabled, signature still
     verifies).
  2. The API and version are supported.
  3. The permission the op needs is declared in the manifest **and**
     granted (§4.3).
  4. The parameters fit the declared scope (domain, package, folder).
  5. The quota is not exceeded (§8).
  6. Then the host executes the call itself, or forwards it to a provider
     plugin (§2).
  7. An audit entry is written (§4.6).
- **The host executes with its own code.** A plugin hands over data,
  never an object: no `Context`, `Intent`, `ContentResolver`, database
  handle or binder. The host builds any intent, path, URI or request
  itself, from validated data. This is the rule `PluginContext` already
  follows (`launchAppWithExtras` takes strings; droidtop builds the
  `Intent`), made universal.
- **Files cross as descriptors.** `IPluginHostBroker.open(request)` runs the
  same checks for an op that hands the plugin a file (`data.open`,
  `files.open`, `files.shared.open`) and returns the descriptor with the
  reply. A contained plugin can open nothing else (§5.3).
- **`PluginContext` becomes a thin client.** The existing methods
  (`hasShizukuAccess`, `isAppInstalled`, `launchApp`,
  `launchAppWithExtras`, `libraryFolderPath`, `privateDataDir`,
  `hasRootApproval`) are reimplemented as broker calls. They are kept as
  deprecated wrappers for contract 1 plugins (§6).

### 1.5 Lifecycle

| Stage | What happens | Existing code |
| --- | --- | --- |
| **Install** | Picker or catalog download → `PluginBundleInstaller` checks: `manifest.jws`, whose certificate chain must end at droidtop's signing root (official) or at the root the person trusts for the origin, with a plugin-signing leaf that names the origin (and, when it names plugins, this id), nothing in it revoked, every payload hash, the manifest schema, the namespace, the ABIs, the contract version. **v2 adds:** every `provides`/`permissions`/`exports`/`requires` entry is checked against the host registries, and a `requires` on `priv.*` must be `optional` (§2.7). The record lands PENDING. No plugin code runs before approval, not even to describe itself. | `PluginBundleInstaller`, `PluginStore` |
| **Approve** | The approval screen shows, in plain language: what the plugin adds and where (grouped by mode), what it can access (normal permissions as a short list; each dangerous permission and high-risk extension point as its own line), what it needs from other plugins, and its trust badge (Official / Added by you). Every item is a tick box and **the plugin runs with the ticked subset** (decided 2026-10-01, §4.3 "Approval is a list"). Unticked dangerous permissions stay at `ask` (§4.3). | the approval screen in `AppSettingsCatalogs.pluginsScreen` |
| **Enable** | An approved plugin is enabled by default. Disabling stops every call to it and hides its contributions everywhere, because `PluginStore.runnableFor` is the only iterator (checklist point 6). | `PluginStore.setEnabled` |
| **Resolve** | On every install, approve, enable, disable, uninstall or crash, the `requires` graph is recomputed (§2.3). A plugin whose *required* API has no runnable provider is **Waiting**. It is not disabled, and it resumes by itself when a provider appears. | new: `PluginApiResolver` |
| **Start** | droidtop starts first and needs nothing from plugins; then droidtop starts the plugin host, which starts the plugins (docs/SPEC.md 12a "Start order", Droidtop/tracker#775). droidtop's start loads no plugin and starts no plugin process, and a plugin process runs none of droidtop's start. The host's start, after droidtop's first screen, brings up what runs without a call: retries, background services and schedules, Standard's entries, the apps that carry a plugin. | `PluginHostStart`, `IsolatedProcess` |
| **Run** | Loading is lazy: a plugin is loaded on its first call, and its process is let go 60 s after the last use (built 2026-10-08, `PluginProcesses`); a job or a running background service (§3 E8) keeps it in use. A crash, an uncaught exception, a native crash, a timeout or process death disables that plugin with a reason, and the user re-enables it by hand (12a checklist point 6). **Kept as is:** crash containment is the one rule `PluginCrashPolicy` exists for. | `PluginCrashPolicy`, `NativePluginRunner` |
| **Update** | Same key: approval carries over (12a "Trust over updates"). **v2 adds a permission diff.** An update that adds any permission, extension point or `exports` entry keeps running with its *old* grants; the new items wait at `ask`, and the plugin's page asks about those items only, on the same list as at approval ("Wants new access", §4.3). An update never gains dangerous access silently. A different key, or a previously DENIED plugin, goes back to PENDING (unchanged). | `PluginStore`, `PluginRecord.approvedKeySha256` |
| **Uninstall** | `onUnload` runs, and the payload, data directory, vault entries, grants and scheduled work are deleted. The audit log for the plugin is kept for 7 days (labelled "removed plugin") so that "what did it do" can still be answered. Dependents are re-resolved (§2.5). | `PluginStore.uninstall` |

**Signing a plugin (keys and certificates; docs/SPEC.md 12a,
Droidtop/tracker#550).** Every bundle carries `manifest.jws`: a detached
JWS (RFC 7515) over the exact `manifest.json` bytes, its protected header
`{"alg": "ES256" | "RS256", "typ": "droidtop-plugin+jws", "x5c": [leaf, ...]}`
with standard-base64 DER certificates, leaf first, the anchor left out. The
signing input is `BASE64URL(header) "." BASE64URL(manifest bytes)`, ES256
signatures are raw R||S, and the file is `header..signature` (the payload
part empty). Certificate dates are never checked; only a signed revocation
list stops a signature.

The keys form a hierarchy, and droidtop trusts exactly one level of it per
origin:

| Level | Key | Who holds it | What droidtop does with it |
| --- | --- | --- | --- |
| 1 | Droidtop Core | the owner's CA; only ever derived from | nothing: never pinned, never read |
| 2 | Per-app roots, each derived from Core separately: droidtop's signing root, Enginehost's, gamegrab's | offline, with the owner (a third party's root with that publisher) | droidtop pins its own root (`DroidtopSigningRoot`); any other root is an anchor the person chose in Keys you trust |
| 3 | Leaves issued by a root: one per plugin repository (EKU `...946.1`), per catalog (`.2`) and for a revocation list (`.3`) | the repository's CI secrets (`PLUGIN_SIGNING_KEY` and `PLUGIN_SIGNING_CERT`; `CATALOG_SIGNING_KEY` and `CATALOG_SIGNING_CERT`) | the chain from the leaf must end at the origin's root |

A plugin-signing leaf is CA:FALSE with keyUsage digitalSignature, the EKU
`2.25.230248497294207031301727532441854392946.1`, and subjectAltName URIs
`urn:droidtop:origin:<origin>` (the plain origin id, matching the
manifest's `origin`) plus, to bind a repository to its own plugins,
`urn:droidtop:plugin:<id>` or `urn:droidtop:plugin:<prefix>.*`. Only the
`droidtop` origin under droidtop's signing root is Official.

**A third-party publisher** (any origin but `droidtop`) sets up its own:
1. **A root:** a P-256 or RSA (2048 bits or more) key and a self-signed CA
   certificate (CA:TRUE, keyUsage keyCertSign), optionally naming
   `urn:droidtop:origin:<origin>`. Keep the private key offline.
2. **A signing leaf** issued by that root with the profile above, its key
   and certificate in the repository's CI secrets.
3. **Publish the root certificate** as `droidtop-plugin-key.json` in the
   repository or as a release asset: `{"origin": "<origin>",
   "certificate": "<root PEM>"}`. A person who adds the repository (or a
   catalog listing it) confirms that root once; droidtop stores the root's
   own key, never the key of whoever issued it, and refuses a later
   different key unless the person replaces it.
4. **Sign each bundle** into `manifest.jws` with the leaf in `x5c`.
5. **Optionally, a catalog of your own:** a `.2` leaf naming
   `urn:droidtop:catalog:<owner>/<name>` signs `index.json` into
   `index.json.jws`, and the index's `catalog.key` names the root
   (`algorithm` `droidtop-chain-v1`, `publicKeySpki`, `keySha256`). A `.3`
   leaf naming the same catalog signs its `revocations.jws`.

A single self-signed signing certificate (root and leaf in one) is also
accepted for a small publisher. gamegrab follows these steps with a root
the owner derived from Core; that derivation gives it no special standing
in droidtop.

### 1.6 UI extensions: the view schema

*Decided 2026-10-01 (Droidtop/tracker#164). Owner: "we've gotta make sure
the plugin API is full featured ... also need plugins to expose their own
UI extensions for it."*

A plugin contributes UI as **data**: a *view* is a JSON document drawn from
a closed set of node types, and droidtop renders it with its own
components. A plugin never draws a native view, a WebView, a Flutter
surface or a Compose tree into droidtop. This keeps every plugin screen
themed like the rest of droidtop, D-pad navigable through the one input
pipeline (`Modifier.onPad` / `PadGate`, #152), touch-equal, and unable to
imitate droidtop's own screens (§5, T6). The same document renders in all
three modes and is produced the same way by every kind, because it is just
the `data` of an ordinary `handle` reply (§1.3).

**One renderer.** droidtop already has one renderer-agnostic UI model that
every mode draws: the settings catalog (`CatalogScreen` / `CatalogGroup` /
`CatalogItem`, `runtime-common`). Gaming draws it with `CatalogNavigator`
(pad and touch through `onPad`), Standard with the Preference surface, and
Desktop with the same Settings app. A view is translated into that model by
one host mechanism (`PluginViews`, `library-core`), so a plugin screen is a
catalog screen: no second renderer per mode, and every improvement to the
catalog renderers reaches plugin screens for free.

**Notices (2026-10-14, Droidtop/tracker#485).** A `notice` node is a
condition the person should know about, drawn as droidtop's own notice
card (docs/SPEC.md 7k, "Notices") and named "from <plugin>":

```json
{ "type": "notice", "id": "signin", "tone": "caution", "placement": "section",
  "title": "Sign in to download", "subtitle": "Downloads are for members.",
  "details": "The site lists its download links only to signed-in members.",
  "action": { "kind": "view", "op": "signin", "title": "Sign in" } }
```

- `tone` is `info`, `caution` or `danger` (danger since 2026-10-15,
  Droidtop/tracker#521 item 1: the owner wants each notice to carry its
  real weight, "We need to surface appropriate contextual stuff"). Danger
  is for what the person is about to lose or risk now (a save that will be
  overwritten, an account that will be signed out everywhere); a
  plugin's danger notice is drawn in droidtop's danger colours and still
  says "from <plugin>". Any other value is drawn as info and logged.
- `placement` is `section` (the default) or `banner`, at most one banner
  a page; a second becomes a section notice and is logged.
- Lengths (title 60, subtitle 140, details 500, the action's label 20) and
  counts (two a section and only of different tones, three a view) are in
  `docs/view-limits/plugin-view-limits.json`, the one file the host,
  `gg.lint_view` and the Kotlin `PluginViewLint` read. Over a length the
  host cuts; over a count it drops; a notice is moved first in its section.
- A plugin notice may not use one of droidtop's own notice titles (the
  `reservedTitles` of the limits file, exact, ignoring case); such a node
  is drawn as a plain row and logged.
- A reply's `data.notice` (the same object, `type` optional) is drawn
  instead of `data.message` until the page's next action; a refetch never
  clears it. Send both, so an older droidtop shows the message.
- Every call envelope carries `host.viewFeatures` (`["notice",
  "confirm"]` today): a plugin in any language tests it and keeps the old
  `info` row for a droidtop without notices.
- What the host changed (a cut, a drop, a move, an unknown tone, a reserved
  title, an undeclared permission) is a **plugin message**: logged once per
  plugin and view under the logcat tag `PluginMessages`, and the last 50
  per plugin are kept for the plugin's page and the Plugin messages page in
  developer options.

**The document** (`docs/plugin-view.schema.json` is normative):

```json
{
  "view": 1,
  "title": "Filters",
  "subtitle": "Narrow the search",
  "sections": [
    { "id": "main", "title": null, "items": [
      { "type": "text", "id": "query", "title": "Search", "value": "" },
      { "type": "choice", "id": "region", "title": "Region",
        "options": [ { "value": "", "label": "Any" }, { "value": "eu", "label": "Europe" } ],
        "value": "" },
      { "type": "row", "id": "r1", "title": "Result title",
        "columns": ["System", "1.2 GB"], "badges": ["Verified"],
        "action": { "kind": "view", "op": "detail", "args": { "ref": "r1" } } },
      { "type": "button", "id": "dl", "title": "Download",
        "action": { "kind": "job", "op": "acquire", "title": "Downloading Result title" } }
    ] }
  ]
}
```

**Node types** (closed set; an unknown `type` is skipped, so a newer plugin
still renders on an older droidtop):

| `type` | Fields | Rendered as |
| --- | --- | --- |
| `info` | `title`, `subtitle?`, `value?` | a read-only row |
| `row` | `title`, `subtitle?`, `columns?[]`, `badges?[]`, `value?`, `action?` | a row; `columns` join the subtitle with " · ", `badges` fill the value column; with an `action` it is pressable (a `view` action makes it open a page) |
| `button` | `title`, `subtitle?`, `value?`, `confirm?`, `action` | an action row; `confirm` asks the two-step confirm every destructive row uses |
| `toggle` | `title`, `subtitle?`, `value: bool`, `action?` | a toggle |
| `choice` | `title`, `subtitle?`, `options[{value,label}]`, `value`, `action?` | a pick-one (the version, region and format pickers of a download) |
| `slider` | `title`, `subtitle?`, `min`, `max`, `value`, `action?` | an integer stepper |
| `text` | `title`, `subtitle?`, `value`, `action?` | a text field (controller and touch text entry). **Never secret**: there is no password field; secrets wait for the vault (G1, #69) |
| `progress` | `title`, `value` (0 to 100, or -1 for unknown), `subtitle?` | a read-only row with the host's job bar (gliding; -1 is the checking shimmer), never a percent in text (Droidtop/tracker#644) |
| `notice` | `title`, `subtitle?`, `details?`, `tone` (`info`, `caution`, `danger`), `placement?` (`section`, `banner`), `action?` | droidtop's notice card, "from <plugin>" (see "Notices" above) |

**Typed values (Droidtop/tracker#521 item 7).** An `info` or `row`
`value` may be an object instead of a string, and droidtop writes it the
way it writes its own values: `{"bool": true}` is "On" / "Off";
`{"number": n, "unit": "bytes"}` is a size ("1.5 GB"), `"seconds"` a
duration ("2 h 5 min"), `"percent"` (0 to 100) "42%", no unit a number
with separators ("1,500"), and any other unit is written after the number
("60 fps", at most 12 characters); `{"enum": "eu", "options":
[{"value": "eu", "label": "Europe"}]}` is the option's label. An object
droidtop cannot read shows no value. A plugin sends the number, not its
own "1.5 GB".

**`needs` (Droidtop/tracker#521 item 5).** Any node may name one of the
plugin's declared permissions with `needs`, or `"app"` for the app an
`apps.bridge` plugin manages (F1, #520: while that app is not installed,
the node is inert with "Needs <app>, which isn't installed"). While the person has that
permission turned off, droidtop draws the node as an inert row with the
node's title and "Off: turn on <permission> in its Permissions to use
this", opening the plugin's Permissions; the rest of the page is drawn as
usual. A permission still unanswered is asked when the node is used, as
any call is; a permission the plugin did not declare is ignored. A page
whose whole point is refused still gets "Not allowed to show this" (4.3).

Text is plain (no markup, no links, no colours, no icons, no images yet).
Limits: 12 sections, 200 items, 100 options per choice, 200 characters per
title, 500 per subtitle, 16 KiB of `args` per action; anything over is
cut, never refused, and the whole view is still subject to the 256 KiB
reply cap.

**Actions.** An `action` is `{kind, op, args?, title?}`. The host calls
`op` on the **same extension point** that produced the view, through
`handle`, with `args` plus the view's current form values:

- `view`: the reply's `data` is another view, opened as a page on top
  (Back returns).
- `call`: a quick call (15 s); the reply's `data.message`, if any, is shown
  on the row, then the current view is fetched again.
- `job`: a long-running job (§8, J1) with live progress on the row and in
  Jobs; when it ends the current view is fetched again.

On an input node, `action` fires when the value is committed, with the new
value already in `values`; an input without one only keeps its value for
the next action. Every call carries:

```json
"args": { "...": "the action's own args",
          "values": { "<input id>": "<value>" },
          "context": { "...": "what the extension point adds (below)" } }
```

`values` are strings (`"true"`/`"false"` for a toggle, the decimal for a
slider). `context` is filled by droidtop only and the plugin cannot change
it: a source's `destination`, a settings page's `target`, a context
action's `target`.

**Jobs in contract 2.** `startJob` keeps its signature
(`jobId, capability, args`); for a v2 job the capability is the contract 1
capability the point replaced (`library.sources` → `acquire_content`,
`ui.settings` → `settings_rows`, `ui.context_action` → `library_action`)
and `args` is one entry, `call`, holding the whole v2 envelope as JSON
text, so the plugin reads a job exactly like a `handle` call. A point with
no contract 1 capability (`ui.panel`, `ui.game_section`, `ui.quick_tile`) runs
jobs too (2026-10-07): the capability handed to `startJob` is `settings_rows`,
a label only, and the plugin reads the point and op from the envelope. The
host checks the grant of the envelope's point, never the label's. Progress
and completion are the existing `jobProgress`/`jobComplete` shape; a
completion's `values` may carry `message`, and `notice` (a notice node as
JSON text, drawn like a reply's `data.notice`). A job that finishes with no
`notice` gets droidtop's own Info notice "Finished" on the page that
started it, with the job's `message` as its body unless it only says
"Done" (Droidtop/tracker#521 item 9); a plugin does not draw its own "it
worked" row.

For a resumable native job, `PluginJobProgress.checkpoint(percent,
statusLine, resumePayload)` reports an opaque checkpoint of at most 4096
characters along with progress. The host retains the last checkpoint.
Pause calls `cancelJob(jobId)`; the plugin must stop cooperatively after
its current unit of work. Resume calls `startJob` again with the same job
id and original arguments, plus `droidtop.resume_payload` containing that
checkpoint. Jobs that have not reported a checkpoint cannot be paused from
the host UI.

**The plugin is named on every page.** A rendered page's subtitle is the
view's own `subtitle` or "From <plugin label>", and its title is the view's
title or the plugin's label. A view cannot set droidtop's title bar, hint
row, colours or buttons.

**Errors.** A view call that fails (the plugin's own FAILED, a timeout, a
crash under the usual crash policy) renders as droidtop's caution notice
"Couldn't show this page" with the plugin's sentence and its code in Info, plus a way out where the host knows one (a source's "Open <plugin>
settings" when it provides `ui.settings`). droidtop never shows a blank
page for a plugin.

**Which extension points use views.**

| Point | Op that returns a view | Notes |
| --- | --- | --- |
| `ui.settings@1` (C1) | `view {context:{target}}` | the plugin's own page under Accounts and sources → Plugins → <plugin> → Settings (`target` `plugin`). Replaces read-only `settings_rows` for contract 2 plugins (contract 1 rows still render). |
| `library.sources@1` (A2) | `form`, `detail` | below |
| `ui.context_action@1` (C4) | `run` may reply `{view}` | the view opens as a page over the screen the action ran from |
| `ui.quick_tile@1` (C2) | `action` may reply `{view}` | the view opens over the Quick Menu |
| `ui.panel@1` (C17) | `panel {context:{surface, game?}}` | the plugin's Quick Menu panel, under its tiles (1.8) |
| `ui.game_section@1` (C18) | `section {context:{target, sectionId}}` | rows on a game's page; the whole section opens as a page for its inputs (1.8) |

**Which systems a source serves (`systems`, Droidtop/tracker#543).** A
`library.sources` entry in `provides` may declare the systems it can give
games for, next to `point` and `id`:

```json
{ "point": "library.sources", "version": 1, "id": "f95",
  "label": "F95zone", "systems": ["windows", "linux"] }
```

`systems` is a list of droidtop system ids (the ES-DE short names, as in
`system.id` below), or `"*"` (alone or in the list) for every system. droidtop
decides from the manifest alone, without loading the plugin:

- a system's search (its "Search SNES" and its Get games entry) asks the source only if it names that system;
- the PC library (PC Games, search scoped to `pc`) asks only the sources that
  name a PC system (`pc`, `windows`, `windows3x`, `windows9x`, `dos`, `linux`,
  `mac`, `macos`); a source naming `pc` serves all of them, and a source that
  names only consoles is not asked by a PC search;
- a search with no system of its own (the launcher's) asks every source;
- Game sources opens a source that serves only PC systems straight on the PC
  search, and asks which system for any other.

An entry **without** `systems`, or with an empty or malformed one, keeps the
behaviour every source had before the field: listed under every system and
asked by every search. Adding `systems` is not a breaking change and needs no
contract bump; a build that does not know the field ignores it. Several
`library.sources` entries in one plugin serve the union of their `systems`
(one entry without it makes the plugin undeclared).

**`library.sources` with views (getting games through the one search).** There is no Get games screen (owner,
2026-10-16, Droidtop/tracker#218; docs/SPEC.md 13 "There is no Get games sheet"): a source's `search` answers the
one search, and its rows sit in the ranked list beside the device's games and apps. Every call on this
point carries `context: {system: {id, name}, destination}`; `system.id` is
droidtop's system id (the ES-DE short name, `snes`), `destination` the
system's own games folder, resolved by droidtop (absent when a search has
no destination, such as the PC library). Ops:

- `form {context}` → a view whose input nodes are the search field and the
  filters (region, format, a system list, anything the source has). Every
  committed input runs `search` again with all `values`. Optional: a source
  that answers UNSUPPORTED (or a contract 1 source) gets droidtop's default
  form, one `text` node `query`.
- `search {query, values, limit?, context}` → `{results: [result]}`, where a result
  is `{id, title, subtitle?, columns?[], badges?[], platform?, ref?, version?, size?, get?}` (the last three:
  "Fetchers answer in the one search" below, with the 10 s budget).
  `limit` is how many results the host will show (ten per source in the one
  search): send at most that many, a page and not the whole answer; the host
  cuts a longer list to it. A failing search answers `ok: false` with one
  plain `message` and, optionally, a `detail` (§1.3); the host shows its own
  sentence when the message is not a plain one ("It could not reach the
  site"), and keeps the `detail` (or the message), with markup removed, behind
  "Technical details", so a site's page text is never a good message. A
  source that needs a sign-in or a browser check answers `SIGN_IN_REQUIRED` or
  `BROWSER_CHECK_REQUIRED` (§1.3) and returns no result row about it: the
  host shows "Sign in to search here" or "The site checks the browser first"
  with its own Sign in / Open the site under the source's name.
  `ref` is any JSON object; droidtop hands it back unread in `detail` and
  `acquire` and never parses it (12a checklist point 5). droidtop renders a
  result as a `row` that opens `detail`. At most 200 results are shown (on the source's own page under Game
  sources, which asks without a `limit`).
- `featured {platform?, limit, context}` → `{results: [result]}`, the same results as `search`: what the source
  features now (a site's latest updates, its new or popular games), best first, scoped to `platform` (droidtop's
  system id) when given (proposed and approved 2026-10-23, Droidtop/tracker#612). Optional and additive (§7): a
  source with no such list answers UNSUPPORTED, or never declares it, and features nothing; a contract 1 source is
  not asked. Send at most `limit`. The same 10 s budget as `search`, and the same errors. Its rows join Get new
  games' page under the source's name (docs/SPEC.md 13).
- **A result's facets (contract 3).** A `search` or `featured` result may carry `facets: [...]` (§1.10): its
  pictures (`media` or `image`, by role), its `metadata`, its badges. Its box art is the first picture by role
  `cover`, then `wide`, `banner`, `hero`; a `screenshot`, `video`, `logo` or `icon` is never the box art. Put the
  box art first. A result without facets is read as before.
- `detail {ref, context}` → a view: the result's own sections (description
  rows, its files), its pickers (`choice` nodes for version, region,
  format, mirror) and its actions, at least one `job` action with op
  `acquire`. Optional: droidtop's default detail shows the result's title
  and a Download job. Contract 3 (2026-10-29, Droidtop/tracker#737): the
  reply may instead be the result's facets (`{facets: [...]}`, or `items:
  [{id, facets}]` with the result's `id`): its `item.page` bundles
  (`metadata`, `description`, `image`, `download`, `links`) and `link`s are
  drawn as droidtop draws a record (docs/SPEC.md 7g, "Facets in place"), and
  its other facets as any view. A `download` entry with a `url` (or a part
  with one) opens in droidtop's web view; an entry or part with only a
  `link` is fetched by `acquire {ref, values: {link, version?}, context}`,
  the token in `values.link` and the item's `metadata.version` in
  `values.version`, answered as any `acquire` is. A release in `parts` is
  gathered part by part and placed as one job.
- `acquire {ref, values, context}` → a **job**. It writes only into
  `context.destination`. Results become games only when droidtop's own
  detection finds the files (12a, A10), and droidtop looks at exactly the
  files the job names instead of rescanning the library: a job that wrote
  the file itself returns `placed` (a JSON string holding a list of
  paths, absolute or relative to `context.destination`) or reports them with
  `library.files` `changed` (A2), and a job that returns `download` has its
  file reported by droidtop when it is placed. A job that does neither
  still gets a background rescan, the only case left that does. Additive reply: a successful job may return `download` as a JSON
  string containing `{url, headers?, fileName, unpack?, sha256?, sha1?, md5?, size?}` instead of
  downloading the file itself. `url` is HTTP(S); `fileName` is the name the
  file is placed under, kept as given (see "File names" below); `sha256` is 64, `sha1` 40 and `md5` 32 hexadecimal characters (a
  source gives whichever it publishes; when several are given droidtop checks
  the strongest, and a mismatch deletes the file and fails the job) and
  `size` is a positive byte count used as a size cap. droidtop's download engine (aria2) fetches it
  into its own downloads area, then places it in `context.destination` and
  indexes that file, from the download job, so the game appears even if the
  page that started it was closed or droidtop restarted meanwhile. Credential headers (Authorization, cookies, token and key
  headers) remain in memory only. A plugin may omit `download` and continue
  doing its own transfer as before. **Engine hint** (2026-10-09,
  Droidtop/tracker#380, #355): with `download`, a job may return `engine`,
  an engines-database id (`renpy`, `unity`, `html`, ...: the ids
  `EngineRegistryParser.ENGINE_IDS` knows) naming the engine the source says
  the game uses. droidtop pins the placed file, and the folder an archive of
  that name unpacks to (its name less the archive extension, beside it), to
  that engine and so its Enginehost player (`EngineOverridePrefs`, the same
  pin as the game's Engine row) before the library indexes it; a pin the
  person already set is kept, and they change or clear it on the Engine row
  like any pin. An id droidtop does not know is ignored; the download goes
  ahead either way.

  **System hint, file names, unpacking and redirects** (2026-10-09,
  Droidtop/tracker#417). With `download`, a job may return `system`, droidtop's
  id for the game's platform (`dos`, `windows`, `snes`, ...). When the search is
  not already that system's own (a search from the PC library or from
  global search), the download is placed in that system's games folder, so the
  system's player finds it. A system droidtop knows by its folder that has no
  games folder yet gets one, named by the system's id (`dos`), beside the
  person's other system folders (the folder most of them are in, else the first
  games root), and the job says so ("Made a dos folder in Roms",
  Droidtop/tracker#846). Only a system droidtop does not know fails the job,
  with a sentence saying so, and the game is never dropped in another system's
  folder.
  **File names:** `fileName` is kept as given, because the library reads
  titles and regions from it: spaces, brackets and accents stay. It must not
  hold `/` or `\`, start with a dot, or exceed 200 bytes of UTF-8; characters
  no file system takes (`: * ? " < > |` and control characters) are replaced
  by `_`. **Unpacking:** `unpack: "archive"` unpacks a zip, 7z or rar into a
  new folder named like the file less its extension (`Prince of Persia.zip`
  becomes the folder `Prince of Persia`) and deletes the archive, as the last
  step of the same Downloads job, so it survives the page closing or a restart.
  droidtop's own extractor proves every entry stays inside that folder, creates
  no symlink, and refuses encrypted entries and archive bombs. The folder is
  filled under a hidden name and renamed when whole, an existing folder is never
  touched, and a failed unpack keeps the download and fails the job with a
  sentence. An engine hint pins the unpacked folder like it pins a placed file.
  **BIOS files:** a single file that is a BIOS file of the screen's system goes
  to the games folder's `bios` folder under droidtop's BIOS database's name for
  it, created when missing, not among the games: its md5 is one the database
  lists for the system, or, from a plugin that also provides `emulator.bios`,
  its name is the database's (`AcquireBios`, Droidtop/tracker#558). The page
  then says what the download did ("Added <file>"), not only "Finished".
  **Several files:** instead of `download` the reply may return `downloads`,
  a JSON string holding a list of 1 to 16 descriptors (each as above, with its
  own `fileName`, digests and headers; both `download` and `downloads`, or an
  invalid descriptor in the list, fail the job). droidtop shows them as one
  entry in Downloads, fetches and checks them in turn (a restart carries on
  with the file it was at), and places them together in the destination, all
  or nothing: if any name exists, or two share a name, nothing is placed. This
  is for a release that is several files (a cue sheet and its tracks). Parts
  of one split archive are handled as in the next paragraph.
  **Torrents and magnets** (2026-10-22, Droidtop/tracker#577). The owner:
  "magnet or torrents. Ideally the data itself, but a URL will be downloaded
  sandboxed, checked, then added". A `download` descriptor may name one of
  three things:
  - a magnet link as its `url` (`magnet:?xt=urn:btih:...` or `btmh`, at most
    8,192 characters);
  - a `.torrent` by its address, `"kind": "torrent"` with an HTTP(S) `url`. It
    is fetched like any download, with the descriptor's headers, its redirects
    walked, at most 4 MiB, and checked to be a torrent before the engine is
    given it;
  - the `.torrent` itself, `"torrent": "<base64>"` with no `url`, checked the
    same way when the reply arrives.

  Either way the download is a whole folder, not a file. The engine fetches
  it into a folder of droidtop's downloads area, and the job's post step is
  given that folder. In a games folder it is placed as one entry named
  `fileName` ("Name (2)" beside one of that name). As an update
  (`library.updates` `download`) it is the new version folder. Either way,
  the one top folder a torrent makes is unwrapped. The person's update
  manager, the library or a later step reads the folder as they read any
  game folder, so a source that fetches a torrent elsewhere and hands over
  its files (a debrid service) can produce the same thing.

  **Debrid is droidtop's (2026-10-24, Droidtop/tracker#638).** Every torrent
  or magnet a reply names may be served by the person's debrid accounts
  (TorBox, Real-Debrid) before any peer is contacted: a service that has it
  whole hands over its files as direct links into the same folder. A source
  names the torrent and does nothing else for it: it never asks a debrid
  service itself and never sees the person's keys.

  **File hosts are droidtop's too (2026-10-24, Droidtop/tracker#638 part 5).**
  A link a source gives on a host droidtop has an API for (PixelDrain, Mega)
  is served by the person's own account on the host, then by a debrid
  account, then by the host's anonymous API, before droidtop's web view
  opens the page (docs/SPEC.md 12a "Host resolvers"). For a plugin it is
  the same as a page's download: `web.session open_in_session` replies with
  `captured`, `download` and `downloads` and a token for each, and droidtop
  adds the headers (the person's key among them) by the token, in memory. A
  plugin never sees the key. A host that serves a file encrypted (Mega) has
  the key to decrypt it with held the same way, and droidtop decrypts and
  checks the file itself; the key is never sent to the host or shown to a
  plugin.

  A torrent or magnet carries no digests (its pieces are checked), no
  `unpack`, no web-session token and no `auth`, and a magnet carries no
  headers. It is a download of its own, never one of `downloads`. Seeding
  ends when the download does (`seed-time` 0). A download of a whole folder
  is not offered to the app-catalog or BIOS replies, which take one file.
  **Split releases** (Droidtop/tracker#419): with `downloads` and
  `unpack: "archive"` on every descriptor, the files must be the parts of one
  split archive, numbered 1 to N without a gap, in any order; otherwise the
  job is refused before anything downloads. Byte-split parts (`X.7z.001`,
  `X.zip.001`, `X.rar.001`, `.002`, ...) are joined in part order once the last
  has arrived and been checked, the parts deleted, and the joined archive
  unpacked into the folder `X` like a single archive. RAR volumes
  (`X.part1.rar`, `X.part2.rar`, ...) are unpacked together into the folder
  `X`: libarchive reads them in part order as one stream, so a file spanning
  two volumes comes out whole, and the volumes are deleted once the folder is
  in place. When they cannot be unpacked (a damaged or encrypted set), they
  are placed together as they are and the job says why. One
  `open_in_session` with `max` captures every part a page offers in one
  visit (#419), and its `downloads` is the reply's `values.downloads` as is.
  **Headers across redirects:** `headers` (cookies, Referer, Origin and the
  credential headers) are sent on every hop that stays on the same host over
  the same or a stronger scheme, so a link that answers 302 to a signed address
  on the same site still sees the session; a redirect to another host, or from
  https to http, drops them.

This reply extension is part of the additive 1.6 acquire contract; it does
not change the view document's `view: 1` or the manifest's
`contractVersion: 2`. The JSON shape is also recorded in
`docs/plugin-view.schema.json` under `$defs.acquireDownload` for plugin
authors and tooling.

A source that fails `search` with a setup problem answers FAILED with a
plain sentence ("The game index is not downloaded yet"); droidtop shows it
under the source's name and, when the source provides `ui.settings`, an
"Open <plugin> settings" row right there.

**Fetchers answer in the one search (2026-10-16, Droidtop/tracker#218).**
The owner, 2026-10-16: "The Get Games sheet shouldn't really be a sheet.
Get Games results should be injected into the search by fetcher plugins".
And the same day: "Remember that ALL plugin activity happens over the
API" (§1.0a): a fetcher's results reach the search only through this
point, as every other answer of a plugin does.

- **No new point.** The one search (SPEC 12a "One search") asks every
  granted `library.sources` source with `search` as above, `limit` 10.
  Where Get games used to open its own sheet, droidtop opens the one search
  scoped to that system (`context.system`, the sources that serve it); the
  surface is the UI lane's (#218). `form`, `detail` and `acquire` keep
  their meaning.
- **Result fields added** (each optional; an older droidtop ignores them,
  and none needs a contract bump):
  - `version`: the release's version as the site gives it (`"1.0.4"`,
    `"v0.12 Final"`), cut to a title's length; droidtop shows it as the
    first column.
  - `size`: the download's size in bytes, a positive integer; droidtop
    writes it in its own units ("1.2 GB"). A source that only has text for
    it keeps that in `columns`.
  - `get`: `{values?, title?}`, where `values` is an object of strings. It
    says the result can be fetched without its detail page: droidtop's
    row offers Get, which starts `acquire {ref, values: get.values,
    context}` as a job, exactly as a `job` action on the detail page does
    (the destination by SPEC 12a "Download destination"; the job is titled
    `get.title`, else "Getting <title>"). Without `get`, Get opens `detail`
    as a page, for a result that needs a version, a mirror or a sign-in
    picked first.
  - The source's name on a row is droidtop's: the `label` of the
    plugin's `library.sources` entry, else the plugin's own. Nothing in a
    result names a source, so a plugin cannot pass its rows off as
    another's.
- **Budget: 10 s, not a crash.** Every `search` (the one search's and a
  source page's) carries `deadlineMs` 10000 (§8,
  `PluginGameSource.SEARCH_TIMEOUT_MS`). A source that misses it shows
  under its name as "didn't answer in time" while every other row stands,
  and the miss is not a crash: it never counts toward disabling the plugin,
  because a slow site is slow, not broken. droidtop's own backstop for a
  call that never returns at all is a second longer
  (`PluginSearchAggregator.PER_SOURCE_TIMEOUT_MS`, 11 s).
- **Cancellation: one call per source, the newest query wins.** droidtop
  waits for typing to pause (350 ms) before it asks. A source has at most
  one `search` from the one search in flight: while it runs, droidtop
  sends that source nothing more; when it answers, its reply is dropped if
  the query has moved on, and only the newest query is sent then (the ones
  in between are never sent). Leaving the search drops whatever is in
  flight. There is no cancel message, because no kind can interrupt a
  call: a source keeps its work within `deadlineMs`, and the rule above
  keeps it to one search at a time.
- **Permissions** are any declared point's: `provide:library.sources` (a
  source the person unticked is never asked, and one waiting for approval
  is droidtop's "waiting for approval" row, never its results), the
  source's own `net` grants for its site, and `library.folders.write` for
  the destination when it acquires. Every granted source sees the query
  text, and the point's line on the approval list says so: "Lets it see
  what you search for, search a source with it and download games into
  your game folders."
- **As built (2026-10-16), the plugin half:** `SourceResultProtocol`
  reads `version`, `size` and `get` (`SourceGet`; `PluginViewTest`);
  `PluginGameSource` puts the version first in the row's columns and the
  size in droidtop's units (`SourceSize`), and answers `canGet` and `get`,
  which runs the same `PluginViews.acquire` as the detail page's job
  action; the search budget is `SEARCH_TIMEOUT_MS`; the one search asks
  each source through `SourceSearchFlight` (one call in flight, the newest
  next; `SourceSearchFlightTest`). The rows' Get and the sheet's
  replacement are the UI lane's (#218).

**Pad and hint row (#179).** A plugin never binds a button. On every
plugin row, tile and menu entry, droidtop's input pipeline (SPEC 6e,
`onPad`) gives A to the node's one action, B to Back and Y to droidtop's
own Info sheet, exactly as on droidtop's own rows; X, Select and the
shoulder buttons are never given to plugin content. A node that needs a
second action shows it as a second row or `button`, never as another
button on the same row. So the hint row (SPEC 7j: a hint promises only
what dispatches) is built by the host from the node type alone, before
and without loading the plugin, and an inert node (`info`, `progress`, a
`row` with no action) shows no A hint. The same holds for static entries
in `provides` (tiles, context actions): A runs them, nothing else. A
`buttons` field in `provides` is reserved for a later version and is
ignored today.

**Empty and error states (#179).** One rule for every surface that shows
plugin content (C1 settings pages, A2 sources, C2/C3 tiles, C4 context
actions, C11 rows): when a plugin's op times out, fails or crashes, the
host draws its standard empty state with the plugin's name and the
one-line reason (the shape `SourceOutcome` already uses for search,
2026-09-30), never a blank area and never a spinner that does not end.
When an op succeeds with nothing to show (no results, an empty view),
the host draws its own Info notice "Nothing here yet", from the plugin,
with Refresh (Droidtop/tracker#521 item 6). A view may give the one
sentence that notice says under its title with the top-level `empty`
("Saves you make appear here"; at most the notice subtitle length, cut
beyond); without it droidtop's words stand alone. A plugin does not add
its own "No items" row. A tile that
misses its budget keeps its last value and says it is stale. Each
surface's budget is §8's table.

**Gaming rows over a themed view (#179).** ES-DE themes have no slot for
host content, and the one precedent, an overlay over the frame-only
render (SPEC 7i), overlapped the theme on the PC tab. So C11 rows, and
any plugin content in Gaming, appear only on droidtop's own surfaces
(the Quick Menu, a game's page, the options menus, Settings), never
inside a themed system or gamelist view, until the frame-only render
declares a region for host content.

**Consent.** Approval is a list the user can cut down (§4.3 "Approval is
a list"). Every extension point a plugin lists under Adds is a tick box,
high-risk ones included, so a plugin whose only point the user unticked is
shown as not allowed instead of silently never working. The high-risk
points start unticked, as §4.2 and §4.1 already held them back; everything
else starts ticked. A call to a point that is not granted is never made:
`PluginGrants.pointRefusal` answers `PERMISSION_DENIED` before the plugin
is loaded, and a plugin view for such a point is the standard error state
saying it was not allowed, with a row that opens that plugin's
Permissions screen. (This replaces the 2026-10-01 rule that approval
granted every listed point.)

**All kinds.** A view is the `data` of a `handle` reply, so every kind
produces it the same way: `native_bundle` from `DroidtopPlugin.handle`,
`python` from a module-level `handle(call_json) -> reply_json`, and
`flutter_embed` from the channel method `handle` (envelope in, reply out,
both JSON text). A contract 2 plugin of any kind must implement `handle`;
a contract 1 plugin keeps the legacy translation.

**As built (2026-10-01, the renderer core).**

- `PluginView` (`plugin-host`) reads a view into typed nodes with the
  limits above, drops what it cannot draw, and reads a source's
  contract 2 `results` (`SourceResultProtocol`); `PluginViewCall` writes
  `values` and `context` after the plugin's own args so they cannot be
  spoofed. Unit-tested in `PluginViewTest`.
- `PluginViews` (`library-core`) is the one renderer: view to
  `CatalogScreen`, actions through `PluginCrashPolicy.handle`, jobs
  through `PluginJobsCenter`, input actions off the main thread, and no
  plugin page in the settings search index.
- `ui.settings`: a contract 2 plugin's Settings row on its plugin page
  opens its view (`PluginSettingsRows.screenFor`); contract 1 rows are
  unchanged.
- Reply views for `ui.context_action` (`run`) and `ui.quick_tile`
  (`action`): both may reply `{view}`; droidtop opens it with
  `PluginViews.screenFor`, carries the point's `target` or `tileId`
  as `hostContext`, and the message stays on the status line.
- `handle` for contract 2 `python` and `flutter_embed` plugins, and the
  approval grant of every provided point.
- `library.sources`: `PluginGameSource` calls contract 2 `search` for the
  one search (`UnifiedSearch`) and opens source `detail` views, with contract 1
  droidtop defaults. A source's own page under Game sources (`SourceScreens`)
  opens its `form` and adds results after committed inputs. There is no Get
  games screen (2026-10-16, Droidtop/tracker#218). A successful acquire job's files are indexed (A2), not rescanned.
- **Not built yet** (queued): reply views for `ui.context_action` and
  `ui.quick_tile`, and samples that use views.

---

### 1.7 A plugin's own full-screen UI: `ui.main`

*Decided 2026-10-01 (Droidtop/tracker#187). Owner: "we need a 'view plugin
interface' menu in settings that'll launch the plugin's main UI to let us
configure stuff or etc."*

The views of 1.6 are how a plugin adds rows and pages to droidtop's own
screens, and they stay that. A plugin that is also a whole app with a user
interface of its own (a browser for its content, a configuration screen
with more than inputs and buttons) declares the optional extension point
`ui.main@1`, and droidtop opens that UI **full-screen, in the plugin's own
process**, the one its runner already lives in. It is in addition to 1.6,
never instead of it: a plugin with `ui.main` still draws its settings page
and rows through views.

```json
{ "point": "ui.main", "version": 1, "entrypoint": "mainUi" }
```

- **`entrypoint`** (required): the name of the plugin's own start function.
  **`library`** (optional): the library URI that function lives in, when it
  is not the root library of the plugin's build target.
- **Kinds.** Only `flutter_embed` can host one. droidtop starts a
  `FlutterActivity` (`PluginMainActivity`, in `:pluginhost`) over a second
  `FlutterEngine` built from the plugin's own `libapp.so` and runs
  `entrypoint` on it; the function must be annotated
  `@pragma('vm:entry-point')` so the AOT build keeps it, and it normally
  just calls `runApp`. `native_bundle` cannot: its code is loaded from dex
  and droidtop's manifest cannot name a plugin's activity, so a hosted
  Android activity is not offered. `python` has no UI toolkit. A manifest of
  either kind that declares `ui.main` is refused at install with that
  reason, so no row ever does nothing.
- **Input.** The plugin UI is the foreground activity, so the keyboard and
  controller keys reach it natively through Flutter's own handling; droidtop
  does not translate them. Back pops the plugin's own routes and, with none
  left, closes it and returns to droidtop.
- **Approval.** `ui.main` is one more approvable item (4.2, #164): it is
  listed under Adds with its own box, and a plugin whose box is unticked
  gets the sentence "has not been allowed to open its own screen" instead
  of a launch. The launch also needs the plugin approved and on, its
  runtime installed, and its payload to pass the same signature and hash
  check every call does. The UI's own host calls are made through the same
  broker and grants as the headless engine's.
- **Where it appears.** Every plugin's page under Accounts and sources
  gets an "Open <plugin name>" row when it declares `ui.main`, in all three
  modes (the settings catalog is the one renderer, 1.6). In Gaming the same
  row is in the "Get more" group under each download source that has one
  (Gaming shows plugins only on droidtop's own surfaces, never in a themed
  view).
- **Process.** The main UI shares `:pluginhost`, and so its fate, with the
  plugin's headless engine: a crash of either is a crash of the plugin
  (`PluginCrashPolicy`).

### 1.8 Plugin UI, Decky-style (decided 2026-10-07, Droidtop/tracker#316)

*Owner: "we probably want to use a decky loader style system for our
plugins, at least from the UI" ... "Plugins don't ONLY appear there. That's
their control and configuration point. plugins can modify the entire UI.
We're just securing it better than they do by forcing it through a plugin
API."*

Decky Loader puts every plugin's panel in Steam's Quick Access Menu and
lets a plugin patch any part of Steam's UI by injecting React code. droidtop
takes the shape and refuses the mechanism:

- **Each plugin's panel is its control and configuration point.** The
  Quick Menu's Plugins section is a list of plugins; A opens one's panel
  and B returns to the list, as in Decky. A panel is drawn by droidtop:
  the plugin's quick and status tiles first (C2, C3), then the view it
  returns for `ui.panel` (C17), then rows into its settings (C1), its own
  app (C16) and the app it manages (`apps.bridge`). A plugin that declares
  no `ui.panel` but has tiles gets a panel of its tiles, so plugins built
  before panels appear without a change. The last row of the list leads
  to the Plugins place (get, update, permissions), which is Decky's store
  and settings buttons. The same panel is a row on the plugin's own page
  under Settings, so Standard and Desktop, which have no Quick Menu, reach
  it too.
- **A plugin changes the rest of the UI only through declared extension
  points.** Each is a place droidtop draws plugin content in its own
  components (a game page's rows, a shelf on Home, a context action, a
  settings page, a tile): never injected code, never a plugin's own
  widgets. The points that exist are the catalogue's C group; the ones
  built on 2026-10-07 are C17 (panel), C18 (game page rows) and C11 (Home
  shelves), with C6a (toasts) as the first UI host API; on 2026-10-08 C19
  (friends and chat, with its `social.changed` host API).
- **Everything is visible and controllable.** Every point a plugin
  provides is an item on its approval list and on its Permissions screen
  (4.3), and a point it does not declare is never called (1.2). Turning a
  point off removes that plugin's content from that place only.
- **Full screens.** A plugin's own pages are views opened as pages (a
  `view` action), drawn from the same schema wherever they open (in the
  Quick Menu sheet, over a game's page, in Settings). `ui.main` (1.7)
  stays the one way to show a UI the plugin draws itself, for
  `flutter_embed` only.
- **Not taken from Decky:** arbitrary JavaScript and CSS into the shell
  (`executeInTab`, `injectCssIntoTab`), React tree patching, and plugin
  backends that run as root by a manifest flag. A plugin reaches root only
  through a provider plugin the user approved (2.7).

### 1.9 Plugins in every mode (decided 2026-10-08, Droidtop/tracker#53)

*Owner: "they need to be able to act on all the UI modes if they want to. I
feel like we've been focusing exclusively on gaming mode until now", then
"Still focus on gaming mode for now, just wire it in OUT from there".*

A droidtop device has three modes, Gaming, Standard (the Android home
screen) and Desktop, and a plugin reaches all of them through the same
declared points. Gaming's surfaces come first; Standard and Desktop draw the
**same contributions** where they already have a natural home, with
Gaming's components (the one catalog renderer, `PluginViews`), not a family
of surfaces of their own.

- **`modes` on an entry.** Any `provides` entry may say where it wants to
  be: `"modes": ["gaming", "desktop"]` (`android` is read as `standard`).
  An entry without `modes` is in every mode where its point has a place;
  one that names only modes this build does not have is nowhere.
- **The person decides too.** The plugin's Permissions screen has "Where it
  appears": one switch per mode the plugin has a place in (grant key
  `mode:<mode>`, on unless switched off). Off removes every contribution of
  that plugin from that mode and nowhere else; the point switches still
  work per point. A same-key update keeps these switches.
- **The mode a plugin is drawn in** is `host.info().mode` (`gaming`,
  `standard` or `desktop`; it said `android` for Standard before
  2026-10-08), `host.info().modes` lists the modes that are on, and every
  call's `context.surface` names the place (`gaming.quick_menu`,
  `standard.home`, `desktop.taskbar`, `gaming.home`, `desktop.start_menu`,
  `gaming.companion` and `gaming.companion_game` with each mode's own prefix
  (C15), `settings`, and `android.widget` for a droidtop widget on any
  launcher's page, C10, which belongs to no mode).
- **Where each point is drawn** (`PluginModes.HOMES`):

| Point | Gaming | Standard | Desktop |
| --- | --- | --- | --- |
| `ui.panel` and tiles (C17, C2, C3) | Quick Menu Plugins section; the companion (C15) | home screen's long-press menu: "Plugins", the same list, in droidtop's plugin window; the companion | taskbar "Plugins", the same list; the companion |
| `gaming.rows` (C11) | Home shelves | — (no shelf of games) | Start menu sections of the same entries |
| `ui.game_section` (C18) | game pages | — (no game page) | — |
| `ui.context_action` (C4) | a game's or app's detail screen, the PC game page | an app's long-press menu: "Plugin actions", a page of the actions in droidtop's plugin window (`standard.app_menu`) | a Start menu row's menu (Y or a long press): "Plugin actions", the same page (`desktop.start_menu`) |
| `social.provider` (C19) | Social place, Quick Menu tile, companion | Settings → Social | Settings → Social |
| `ui.settings`, `ui.main` (C1, C16) | Settings | Settings | Settings |
| `jobs.service`, `jobs.schedule` (E8, E9) | run in every mode, shown in Jobs | same | same |
| `ui.widget` and tiles that allow it (C10, C2, C3) | droidtop's Android app widgets, on any launcher's pages, whatever mode droidtop is in (`android.widget`) | same | same |

- **droidtop's plugin window** (`PluginHubActivity`, opened through
  `PluginHub`): where a panel opened from Standard or Desktop is drawn,
  with the same navigator, hint row and look as the Containers screen.
- **Later, not built:** Desktop taskbar items with a menu (C8 `ui.tray`),
  Start menu and home-screen actions (C12 `launcher.actions`), context
  actions on a window, and file associations (E4).
  Each is a catalogue row already; they come after
  Gaming's surfaces are complete.

### 1.10 Facets: small, typed pieces of UI any plugin puts where droidtop has a place (proposed 2026-10-22, approved 2026-10-23, contract 3, Droidtop/tracker#602)

**Status: approved by the owner (2026-10-23: "Facets is really useful"); being built in pieces.** Built: the model and
its guards (`Facets`, `FacetGuards` in `:plugin-host`: reading a reply, templates, the target list with the kinds and
subjects each takes, the per-target caps from the limits file's `facets`, the store and media guards, the `ui.facets`
point and `host.info().facets`); the renderer (facets become the view model the one renderer draws, `FacetViews`: a
group is a section, a list's entries are rows, a `page` call opens a page whose reply is facets, and an input's value
goes back as `args.value`, typed); and the Quick Menu's plugin section (a plugin whose `ui.facets` entry declares
`quickmenu` gets its panel from `get {target: "quickmenu"}`, through `FacetGuards.admit`, in place of its old
`ui.panel`); and the plugin's settings (`settings.page`: the Settings row on its page, its Quick Menu panel and
its game-source page all open `get {target: "settings.page"}` when it declares that target, in place of its old
`ui.settings` view, `PluginSettingsRows.has` being the one test); and the game page (a plugin whose `ui.facets` entry
declares `game.page` takes a section's place on every game's page in Gaming, asked `get {target: "game.page", subject}`
with the game as its subject only when it holds `library.read`, its rows drawn as a section's are); the plugin's own page (`plugin.page`: a
"Status and actions" row first on the plugin's page opens `get {target: "plugin.page"}`); and "Where it appears" (a
switch per declared target beside the mode switches, kept in the grant file under `facets:<target>` across updates;
a target switched off is not asked for; the plugin's own page and settings have none). And the item page (`item.page`): a
source's record page is drawn from the item's bundles (`metadata`, `description`, `image`, `download`, `links`)
through the guards, from `ui.facets get {target: "item.page", subject: {type: "item", source, id}}` when the source
declares that target, or from facets on its `record` reply (`items: [{id, facets}]`, which counts as declaring the item
page); a contract 2 `record` is read as before (`ItemPageFacets`). A source result's page is the item page too
(Droidtop/tracker#737): a `library.sources` `detail` reply of facets about the result is drawn from its bundles the
same way, through the same guards (`SourceItemPage`). Hiding one facet is a switch per facet under the
plugin's "What it shows", beside "Where it appears": droidtop remembers what each plugin last showed on each target
(`PluginFacets.Seen`), a facet switched off is not drawn there (`FacetGuards.unhidden`, kept under
`facet:<target>/<id>`), and it is listed there to show again. Not built yet: "Hide this", "Move up" and "Move down" on
Select of a facet itself, and the person's order of plugins per target.

**Facets first (the owner, 2026-10-23: "While plugins CAN present a whole UI, we really want them to use Facets.
Small snippets to mix their functions into droidtop").** Facets are the default way a plugin shows anything. A
plugin's own full UI (a `ui.settings` page, a view of its own, a Flutter screen) stays allowed, but only for what
facets cannot express, and a plugin's catalog listing says which of its surfaces are its own. When a plugin needs
something no facet kind or target can do, that need is droidtop's work first: it is raised on Droidtop/tracker#602
as a candidate kind or target before the plugin falls back to a full UI. Every gamegrab-sources plugin moves its
bespoke UI onto facets wherever they cover it, and its old path is deleted, not kept beside the facets.

The owner, 2026-10-23, the rule of thumb for every plugin: "they should use facets whenever it's practiceable. Full
UIs are available, but they'll rarely be something the user actually uses. facets turn the plugins from 'little bubble
the user has to do work to use' into 'part of the application'". In every plugin review, a feature reachable only
through a plugin's own UI is a finding; the one exception is a feature no facet kind can express, and then the fix is
the facet kind to propose here (Droidtop/tracker#602).

**Facets go where they fit (the owner, 2026-10-24: "Facets go where they fit. No clunky redirects, no weird pathing.
That's one of the main reasons I designed the facets system, so the app can surface information where it makes
sense").** A surface that exists to act on an item shows that item's facets in place, inline or as a sheet over the
current screen; it never navigates to another screen just to reach a facet (first applied on Droidtop/tracker#639: an
Updates row presents its download facet). In a review, a redirect to reach a facet is a finding.

**What facets are (the owner, 2026-10-29: "Facets are 'how a plugin presents information and integrates itself'.
While a plugin CAN have a full UI, that's unideal, and likely a fallback").** A plugin's own full UI is the fallback for
what no facet can express, not a second way to do what facets do. With that, the owner approved the six additions
below (Droidtop/tracker#602, the facets-first audit): form groups, `toggle` on `item.page`, the `grant` action, the
`secret` kind, `parts` on a `download` entry and the `search.filter` target.

*The owner: "The facets system would benefit from being standardized as a system for presenting small surfaces to
the droidtop frontend to provide specific information."; "We can provide a set of 'facets' a plugin can surface for
things (download, metadata, store, description, etc), which lets us surface and attach data to."; "Rather than a
plugin having to draw and provide large surfaces, it gives it a granular method to provide sets of information.";
and on the first draft: "Facets is meant to provide a comprehensive system for providing UI bits without providing a
whole UI."*

**The idea in one paragraph.** Every plugin, of any kind (sources, metadata, stores, app interfaces and bridges,
social, device and system, widgets, catalogs), gives droidtop **facets**: small, typed pieces of UI made of data and
actions, never of drawing. droidtop owns every surface. It has **targets**, the places where a plugin may appear
(a game page's section, the Quick Menu, a Home shelf, a companion card, a Settings page, a notification, a widget),
and it draws each facet kind one way on all of them, with its own components, theme, focus, pad, touch and TalkBack.
A facet is **about** something, its **subject**: an item (a game, a thread, a record, an app), the plugin itself,
or the device. Facets about items are kept by droidtop and attached to the library, so information is fetched once
and shown everywhere. This one mechanism replaces the view schema (§1.6), the panels, the tiles and the one-off
reply fields; contract 2 plugins keep working through a mapping (below).

#### The envelope

Every facet is one JSON object:

```json
{ "id": "vpn.state", "kind": "status", "v": 1,
  "subject": { "type": "plugin" },
  "targets": ["quickmenu", "companion.card", "status.area"],
  "priority": "normal", "ttl": 0, "needs": "net.vpn",
  "label": "VPN", "state": "on", "tone": "positive", "value": "Connected to Home" }
```

- `id`: the plugin's own stable name for the facet, unique within the plugin. A later facet with the same `id`
  replaces it in place: that is how a plugin updates what is on screen.
- `kind` and `v`: one of the kinds below, at that kind's version (each starts at 1; an additive field does not move
  it). A kind droidtop does not know is skipped, as an unknown field is (§7).
- `subject`: what it is about. `{type: "item", source, id}` (an item of one of the plugin's `provides` entries: a
  forum thread, a store product, a release feed, an app in a catalog), `{type: "game", gameId}` (a library game
  droidtop asked the plugin about, by droidtop's id), `{type: "plugin"}` (the plugin itself; the default) or
  `{type: "device"}`.
- `targets`: where the plugin would like it (see Targets). droidtop takes only targets the plugin declared and that
  take this kind; it may show a facet on fewer targets than asked, never more.
- `priority`: `low`, `normal` or `high`, the plugin's own order among its facets on one target. It never moves a
  facet above droidtop's own content or another plugin's.
- `ttl`: seconds the facet stays true without asking again (see Caching). `0` means droidtop asks each time the
  target is shown, or the plugin pushes changes.
- `needs`: one of the plugin's declared permissions, or `"app"` (§1.6's `needs`, unchanged). While it is off, the
  facet is drawn inert and says so.
- Texts are plain unless a kind says otherwise; lengths and counts are in `docs/view-limits/plugin-view-limits.json`,
  as for views today. Over a limit droidtop cuts or drops and logs a plugin message (§1.6, "Notices").

#### The kinds: UI building blocks

| Kind | Fields | Drawn as |
|---|---|---|
| `info` | `label`, `value` (a string, or a typed value as in §1.6: bool, number with unit, enum), `sub?`, `filterable?`, `sortable?` | A label and value. With `filterable` or `sortable`, an item's info becomes a filter facet or a sort of the lists it is in (the label names it; values group by equality, numbers by range) |
| `status` | `label`, `state` (`on`, `off`, `busy`, `ok`, `warning`, `error`, `unknown`), `tone?` (`neutral`, `positive`, `caution`, `danger`), `value?` | Its words in the value column and the host's status glyph beside the label: `busy` a turning ring, `warning` the caution circled "!", `error` the danger octagon; `ok`, `on` and `off` show no glyph (Droidtop/tracker#644) |
| `badge` | `text` (at most 16 characters), `tone?` | A small chip on a row, a capsule or a tile ("Update", "Beta", "3 new") |
| `progress` | `label`, `value` (0 to 100, or -1 for unknown), `sub?`, `cancel?` (an action) | A bar with its words; a job's progress is a job's, in Downloads and installs (§8) |
| `meter` | `label`, `value`, `max`, `unit?`, `warnAt?`, `dangerAt?` | The host's bar, filled to `value` of `max`, with "120 of 512 MB" under it (storage, battery, a quota; Droidtop/tracker#644) |
| `chart` | `label`, `style` (`line` or `bars`), `points` (at most 60 numbers; for `bars`, at most 12 `{label, value}`), `unit?` | A sparkline (a speed, a temperature over the last minutes) or a small bar chart (storage by folder). Nothing larger: no axes the plugin draws, no colours of its own |
| `action` | `label`, `sub?`, `call` (an action, below), `confirm?` (`none`, `confirm`, `danger`), `icon?` (from droidtop's icon set) | A button, a menu entry or a row, as the target draws actions. `confirm` is droidtop's two-step confirm; `danger` adds danger colours and names what is lost (`sub`) |
| `toggle` | `label`, `value: bool`, `sub?`, `call` | A switch; the change is sent at once |
| `choice` | `label`, `options: [{value, label}]` (at most 100), `value`, `sub?`, `call` | A pick-one |
| `slider` | `label`, `min`, `max`, `step?`, `value`, `unit?`, `call` | A stepper or slider |
| `input` | `label`, `value`, `hint?`, `inputKind?` (`text`, `number`, `url`, `search`; 2026-10-29: `kind` is the envelope's, so the field type has its own key), `call`, `optional?` | A text field (controller and touch entry). Never secret: secrets stay with G1/G3. In a form group it has no `call`, and `optional: true` lets the form be sent with it empty; any other empty input holds the submit back ("Fill in Device ID first"), and nothing reaches the plugin |
| `list` | `title?`, `entries: [{id, title, sub?, value?, badges?, media?, call?}]` (at most 200), `more?` (an action that asks for the next page) | Rows (a search's results, a watch list, a friends list, a download's mirrors) |
| `collection` | `title?`, `entries: [{id, title, media, badges?, call?}]` (at most 60), `shape?` (`capsule`, `wide`, `square`) | Tiles or capsules (a Home shelf, a store's sale) |
| `media` | `role` (`cover`, `wide`, `hero`, `logo`, `icon`, `screenshot`, `banner`, `video`), `url`, `poster?` (a video's still), `width?`, `height?`, `alt` | A picture by role (Droidtop/tracker#585), fetched and checked by droidtop; a video plays only on the person's press, muted until unmuted |
| `text` | `body` (at most 20,000 characters), `format?` (`plain`, or `basic`: paragraphs, headings, bold, italic, lists, and links drawn by droidtop) | A text block (an About, a changelog, terms) |
| `link` | `label`, `url`, `kind?` (`source`, `homepage`, `store`, `forum`, `wiki`, `discord`, `support`, `other`) | A link row; opened on the person's press in droidtop's web view or another app, never fetched |
| `guide` | `title`, `from` (who it is from, as the person reads it: "F95zone thread"), `format` (`web`, `pdf`, `text`), exactly one of `url` (https) or `document` (a file name in the plugin's own data folder, as `data.write` wrote it), `updated?` (epoch ms) | A walkthrough or guide for the item (2026-10-30, Droidtop/tracker#817, agreed with the UI lane): droidtop's one guide reader opens it, by pad, on the game page, the companion beside the running game and the overlay. Only guides that exist, at most 32 per item; `id` stable per plugin and item, as the reader keeps its place under it. A `url` is opened or fetched only when the person picks it: `web` in droidtop's web view without the plugin's session, `pdf` and `text` fetched by droidtop (any https host, at most 64 MiB, the type checked). A `document` that leaves the folder or is missing is dropped. Any plugin may give guides for an item, not only its source. droidtop never takes guides from `download` sections; it adds the game folder's own `walkthrough*`, `guide*` and `readme*` documents itself |
| `notice` | §1.6's notice, unchanged: `title`, `sub?`, `details?`, `tone`, `placement?`, `call?` | droidtop's notice card, "from <plugin>" |
| `account` | `site`, `signedIn`, `name?` | droidtop's own account row (docs/SPEC.md "A plugin's site account"); its Sign in and Sign out are droidtop's, never the plugin's |
| `group` | `title?`, `facets: [facet]` (one level deep), `form?: true`, `submit?` (an action, with `form`) | Facets kept together, in order: a section of a page, a card of the companion. **A form** (2026-10-29): with `form: true` its inputs do not send their own `call`s; droidtop holds what the person enters and sends it all with `submit` when it is pressed, as `args.values` (`{facetId: value}`), so "Add a device" (an ID, a name, a trust choice, then Add) is one call. A form's inputs need no `call` of their own; `submit` is drawn as the group's last button and is pressed only once every input that is not optional has a value |
| `secret` | `label`, `sub?`, `call` (asks the plugin for the value: the reply's `value`) | A row with Show (2026-10-29). Nothing is sent until the person presses Show; then droidtop asks the plugin and shows the value in a sheet of its own with Copy (a secure window: no screenshot, recording or Recents thumbnail holds it; the clip is marked sensitive), for as long as the sheet is open. Show is a quick `call` (never a job or a page) whose reply carries `value`, at most 200 characters. droidtop never keeps it, logs it, puts it in a notice, a message, the activity log or a backup, or speaks it unless TalkBack is on and the sheet is open. For a value the person must read or copy elsewhere (an encryption password), never for one droidtop could use itself (that is G1/G3) |

A facet's text is the plugin's; its look, size, focus order, pad and touch behaviour and its TalkBack wording
("VPN, on, Connected to Home, switch") are droidtop's alone.

#### Content bundles: standard sets for items

Information about an item uses fixed bundles of the kinds above. Each has its own `kind` name, so droidtop knows what
the information means and can use it beyond drawing (filters, updates, linking, Downloads):

| Bundle | Fields | Built from, and used for |
|---|---|---|
| `metadata` | `title?`, `version?`, `developer?`, `publisher?`, `engine?` (an engines-database id), `status?` (`ongoing`, `completed`, `onhold`, `abandoned`), `released?` (`YYYY-MM-DD`), `updated?` (epoch ms), `rating?` (0 to 1), `votes?`, `tags?` (at most 64 of 40 characters), `genres?` (at most 16) | `info` and `badge` facets on the game page, list rows and capsules; the PC Games filters and sorts (Release status, Tags, Rating, Developer, Genre); `version` is what an update is compared with (docs/SPEC.md 7g) |
| `description` | `overview?`, `changelog?` | `text` facets: the game page's About, the record page's Overview and Changelog |
| `image` | as `media` | Filed by role; only for a role the game has no picture for, unless the person chose this source's art |
| `download` | `entries: [{section, host, label, url?, link?, size?, version?, kind?, systems?, parts?}]`, at most 200. `parts` (2026-10-29): `[{label, url?, link?, size?}]`, at most 100, the pieces of one split release on one host; the entry is one row ("4 parts, 12 GB"), and picking it fetches every part in one Downloads job and joins and unpacks them as droidtop's split-set path does (multi-volume RAR, numbered 7-Zip and ZIP parts), never one row per part. `version` lets one item offer several versions; an entry without one is the item's `metadata.version`. `kind`: `page` (default: opened in droidtop's web view, where the person starts the download), `file`, `torrent`, `magnet`. `link` is a token only the plugin's `download` job understands. `systems` (2026-10-23, Droidtop/tracker#617): the systems the link is for, of `windows`, `linux`, `mac`, `android`; without it droidtop reads them from `section` ("Win/Linux" is Windows and Linux), and an entry naming none is for any system. `role` (2026-10-30, Droidtop/tracker#838): `build` (default), `patch` or `extra`; `official` (default true) is false for an item the thread marks unofficial (`*`). `build` (2026-10-31, Droidtop/tracker#845): the build a game's link is for, as the source names it ("Steam Build"), absent for the default build; without it droidtop reads the build from `section` ("Steam Build · Win/Linux"), and `sources.parse_thread` gives it for every link under a build's heading. Patches and extras are offered, never taken by droidtop's own pick. A mirror is one entry however many systems it covers: droidtop keeps one entry per address (or per section, host and `link` without one), holding every system any copy named (Droidtop/tracker#838) | A `list` of `action` entries on the record page's Downloads tab: one row per `section` of builds (a build and its platform, "Steam Build · Win/Linux"), its hosts listed under it and tried in turn; a section with one link is that link's row; the copy's build first when there are several (docs/SPEC.md 7g, Droidtop/tracker#838). When the links differ by system, a System row comes first, offering the thread's own download groupings ("Windows/Linux", "Android"; the one last picked for that source, else the one with Windows, then Linux), and only the picked choice's links show; the ones for any system follow under "Patches", "Extras" (a heading naming a walkthrough, a patch, a save file and the like) or "Other", each with its `section` as its description, so a plugin puts what a link is ("Walkthrough") in `section`; Android picked, droidtop says "This will not be trackable by us. Windows or Linux is recommended." The picker and the notice are droidtop's, drawn from `systems`: no facet filters another, and no plugin words droidtop's notice. Picking one goes through droidtop's Downloads and the download engine and places the result (a new game, or a new version folder, docs/SPEC.md 7g). Kept, so nothing is fetched again to download (Droidtop/tracker#599) |
| `links` | `entries: [link]`, at most 32 | `link` facets: the game page's Links, Open the source's page |
| `store` | `store`, `url`, `price?` (a decimal string), `currency?` (ISO 4217), `owned?`, `free?`, `onSale?` | An `info` and a `link`: where to get the game, the Wishlist shelf. Only store and source plugins may give it, under the guards in "Store information" below |
| `update` | `version?`, `url?`, `gone?` | A `badge` and an `info`: the Update available badge and shelf, Updates, the record page's version line |
| `identity` | `ids: {key: id}` | Not drawn: linking. Only ids of the plugin's own `library.updates` keys are taken without asking; others are offered as matches |

#### Store information: who may give it, and its guards

The owner: "Any store OR SOURCE plugin can provide that information. There has to be some guards on that."
- **Who.** A **store plugin** (one providing a store point; droidtop's own stores are built in today) or a **source
  plugin** (one providing `library.sources` or `library.updates`). A `store` facet from any other plugin is dropped
  and logged as a plugin message.
- **About what.** Only an item the plugin itself knows: its own item, or a game linked to its own item. A plugin
  cannot say where some other game is sold.
- **Where it points.** `url` is https on the plugin's own `net.domains`: a source points at its own site, and a store
  plugin at its store. It opens only on the person's press, in droidtop's web view or the store's own app. A `store`
  facet carries no other action, droidtop never buys anything, and no payment detail ever passes through droidtop.
- **What it may claim.** `price` is a decimal of at most 1,000,000 with an ISO 4217 `currency`; `onSale` counts
  only with a price; `free` and a price together drop the price. droidtop writes the price itself, labelled "from
  <plugin>" with when it was fetched ("$4.99 at itch.io, 2 days ago"), and never sums, converts or compares
  plugins' prices as if they were droidtop's own.
- **Ownership.** `owned` is taken only from a store plugin whose account is signed in (G3's account row), and only
  for items in that store; from a source plugin it is ignored. It never changes droidtop's own ownership facts (the
  Ownership facet, 7g's store rows); it shows as "In your <plugin> library".
- **How much.** One `store` facet per plugin per item, refreshed by its TTL (7 days by default) like any bundle.
- **The person's say.** Each plugin's store information is its own switch under the plugin's "Where it appears", and
  a store or source the person hides is hidden on every game.

#### Targets: where a facet can appear

droidtop has a fixed list of targets. Each takes some kinds, a number of facets per plugin, and either a subject of
its own (a game, an item) or none. A plugin declares the targets it uses on a `provides` entry of the point
`ui.facets@1` (`"targets": ["quickmenu", "game.page"]`), and each is a permission row of its own (4.1).

| Target | Where | Kinds | Subject |
|---|---|---|---|
| `game.page` (sections `overview`, `versions`, `extras`, `details`) | A game's page, under the tab the facet names | all but `collection` | the game, or the items it is linked to |
| `game.row`, `game.tile` | A game's list row and its capsule | `badge`, `status`, `progress` (one per plugin) | the game |
| `item.page` | The record page of an item (docs/SPEC.md 7g, "Getting an update") | the bundles, `action`, `notice`, `link`, `toggle` (2026-10-29: watch or unwatch a thread, follow a release), `guide` (2026-10-30) | the item |
| `library.filter`, `library.sort` | The PC Games filter and sort sheets | from `info` with `filterable`/`sortable`, and the bundles' fields | items attached to games |
| `search.result` | The one search's results (C5, Droidtop/tracker#218) | `list` entries, `collection` entries | the query |
| `search.filter` | A source's own options in the one search, under its name (2026-10-29) | `choice`, `toggle`, `input`, `notice`, `action` | the plugin. The values travel with the query on `library.sources` `search` as `args.filters` (`{facetId: value}`) and are kept per source, as the System pick is; a `notice` with an `action` says why the source cannot search yet and fixes it in place ("No site is on" with the switches, "Download the game index" as a job). A source's §1.6 `form` is deleted once it gives these |
| `home.shelf`, `home.card` | Home's shelves and cards (Droidtop/tracker#586) | `collection` (a shelf), `info`, `media`, `action`, `progress` (a card) | none |
| `quickmenu` | The Quick Menu's plugin entries, which take over the left plugin menu (#586) | `status`, `toggle`, `action`, `progress`, `meter`, `chart`, `choice`, `group` | the device, the running game |
| `companion.tab`, `companion.card` | The companion's tabs and cards (C15) | all but `collection` in a card; a tab is a `group` | the device, the running game |
| `settings.page` | The plugin's settings (C1) | all inputs, `info`, `action`, `notice`, `account`, `group` | the plugin |
| `notification` | droidtop's notifications (C6) | `notice`, up to three `action`s, `progress` | any |
| `status.area` | The status bar and the floating status cluster | `status` (the dot and label only), `badge`, `meter` | the device |
| `widget` | Android home-screen widgets (C10) | `info`, `status`, `progress`, `meter`, `media`, `list` (5 rows), `action` (2) | the plugin, the device |
| `plugin.page` | The plugin's own page under Plugins | everything | the plugin |
| `desktop.tray` | The desktop's taskbar tray (C8) | `status`, `badge`, `action` (a menu of up to 8), `toggle`, `meter` | the device |
| `files.action` | The file manager's actions and "open with" (C9) | `action` | a file or folder the person picked (droidtop passes a document token, D4, never a path) |
| `launcher.drawer` | The app drawer's and home screen's actions (C12) | `action`, `badge` | an app |
| `onboarding.step` | A step of onboarding (C13) | `info`, `text`, `media`, inputs, `action`, `notice` | the device. Risk high: offered only by plugins from the person's trusted catalogs, and shown only after droidtop's own steps |
| `<plugin>:<slot>` | A slot another plugin opens in its own page or card (below) | what the slot's owner lists | the slot owner's subject |

**Extending: slots and templates.** The system is meant to grow, and each way it grows is guarded:
- **Slots.** A plugin may open a named slot in one of its own groups (`{"kind": "group", "slot": "addons"}`), and
  another plugin may target `<owner>:<slot>`. The owner declares the slot on its `provides` entry with the kinds it
  takes and a cap; the guest needs the target granted like any other, and its facets are drawn as droidtop draws all
  facets, labelled "from <guest>". The owner never sees the guest's data or calls: droidtop routes each call to
  the plugin that gave the facet.
- **Templates.** A plugin may define a named arrangement of built-in kinds once (`{"kind": "template", "name":
  "player", "facets": [...]}` with `{"$": "field"}` placeholders) and give many facets of it (`{"kind": "use",
  "template": "player", "fields": {...}}`). A template is only ever built-in kinds: it adds no drawing, and it
  is checked against the target like the facets it expands to.
- **New kinds and targets** are droidtop's to add, in a droidtop release, behind a `v` and `host.info().facets`. A
  plugin cannot define a kind that draws something droidtop does not draw, and a kind it does not know is skipped.
- **Guards that hold for every extension:** no plugin code or markup runs in droidtop's process; text is text; media
  is fetched by droidtop on the plugin's sites and checked by type and size; every target, slot and call has its
  own permission row and per-call check (4.1); every count and length has a cap in the limits file; a facet is
  always labelled with the plugin it came from wherever two plugins could be confused; and the person can hide
  anything.

**Place and order.** droidtop decides both. Within a target its own content comes first, then plugins in the
person's order (by default the order they were installed), and within a plugin by `priority`, then the order the
plugin gave. Per target there is a cap per plugin (one badge on a row, four Quick Menu entries, two widget actions,
and the rest in the limits file); what is over the cap is dropped and logged. A facet whose target is not on screen
is not asked for.

**The person arranges them.** Every plugin facet has, on Select (or a long press), "Hide this", "Hide everything from
<plugin> here", "Move up" and "Move down". Settings > Plugins > <plugin> > "Where it appears" (§1.9) lists each target
the plugin uses, with a switch each and the hidden facets to show again. A hidden facet is not asked for. A target's
order of plugins is the person's, kept per target.

#### Interaction: actions, values and live updates

- **An action** (`call`) is `{op, args?, kind?}` on the point that gave the facet: `kind` `call` (a quick call, 15 s,
  the default), `job` (§8, with its progress where the facet is) or `page` (the reply is a `group` of facets, opened
  as a page on top; Back returns, and the plugin's own page is that, never a screen of its own).
- **A grant action** (2026-10-29) is `{kind: "grant", permission}`, naming one of the plugin's declared permissions:
  pressing it opens droidtop's own grant prompt for that permission, in place, with the reason the manifest gives,
  and the facet is asked again after the person answers. The plugin is never told more than the new state, and it
  cannot word the prompt. It replaces "turn it on in this plugin's Permissions" (a redirect).
- **A value** (a toggle, a choice, a slider, an input) is sent with its `call` when the person commits it, as
  `args.value`, with droidtop's `context` (the subject, the target, the running game). droidtop shows the new value at
  once and puts the old one back if the call fails, with the plugin's sentence as a notice.
- **The reply is facets.** Any reply may carry `facets: [...]`: each replaces the facet of the same `id` wherever it is
  shown. A plugin that changes on its own (a VPN that dropped, a download that finished on its side) pushes facets
  with the host op `ui.facets.update {facets}` (rate-limited to one a second per facet), or marks subjects stale with
  `ui.facets.changed {subjects}`, and droidtop asks again when they are next on screen.
- **Asking:** droidtop asks with `ui.facets` `get {target, subject?, context}` → `{facets: [...]}` when a target opens
  (15 s; on a miss the target shows what it had, or nothing). Item facets are also asked by the update round and the
  scrape pass (below).
- **Focus, pad, touch and TalkBack are droidtop's.** Each kind has one focus behaviour, one hint row, one touch
  target size and one spoken form, the same on every target and in every mode (§1.9). A plugin cannot take focus,
  hold a key or draw a hint.

#### Permissions, per call type

Enforcement is droidtop's, at the API; a manifest only asks (docs/SPEC.md 12a):
- `get` for a target needs `provide:ui.facets` with that target granted. Each target is its own row in the plugin's
  Permissions, at the risk the C point it replaces had (game page medium, Quick Menu and settings low).
- A `call` goes back to the plugin; whatever it then asks of droidtop is checked per host API call as today.
- droidtop fetches `media` only on the plugin's `net.domains`; `link` facets open only on the person's press and never
  carry the plugin's session; a `download` entry of `kind` `page` opens with the plugin's session only when
  `web.session` is allowed; `file`, `torrent` and `magnet` downloads run in the download engine under the plugin's
  `net.domains`, and the engine refuses connections to other hosts.
- `notification` needs `notify`, `status.area` needs `ui.status_tile`'s permission, `account` needs `web.session`.
- A refused call is `PERMISSION_DENIED`, and the facet stays as droidtop last had it.

#### Caching and refresh

- droidtop keeps item and game facets per subject and kind, with when they were fetched, in its own store (the
  library's database, beside `source_answers`). They are library facts: never written into a game's file, kept
  across the plugin's updates, deleted with the plugin, and deleted with an item no game links to that has not been
  looked at for 30 days. Plugin and device facets are kept in memory only.
- A facet is fresh for its `ttl` (bounded to between 1 hour and 30 days for items). Defaults: `update` 6 hours,
  `download` 1 day, `metadata` and `store` 7 days, `description`, `image`, `links` and `identity` 30 days; a UI facet
  without a `ttl` is asked for each time its target opens.
- **Stale while asking:** a target shows what droidtop has at once, saying when it was fetched where that matters,
  and the plugin is asked in the background.
- **Never fetched to act:** a download, a link or a version comparison uses the kept facet. A `download` entry whose
  page no longer works opens the item's `source` link instead (Droidtop/tracker#599).
- **Several plugins about one game:** each bundle has its own source order (Settings, "Metadata sources", one list
  per bundle, all starting from one default order: the game's own linked update source, then metadata plugins in
  install order). `tags` and `genres` are joined, images are per role, and `download` and `store` list each source's
  entries under its name.

#### One mechanism: today's schema, panels and tiles become facets

The view schema (§1.6), the settings view, panels and tiles are folded in. droidtop keeps reading contract 2 by
mapping it one-to-one, so no current plugin changes:

| Today | As facets |
|---|---|
| A view (§1.6): `sections` of nodes | a `group` per section, opened as a page (`call` `kind` `page`) |
| `info`, `row` (with `columns`, `badges`, `action`) | `info`; a `list` entry with `badges` and a `call` |
| `button` (with `confirm`) | `action` (with `confirm`) |
| `toggle`, `choice`, `slider`, `text` | `toggle`, `choice`, `slider`, `input` |
| `progress`, `notice` | `progress`, `notice` |
| `ui.settings` `view` (C1) | `settings.page` |
| `ui.panel` (C17) and companion panels (C15) | `quickmenu` and `companion.tab` groups |
| `ui.quick_tile` (C2), `ui.status_tile` (C3) | `quickmenu` `status`/`toggle`/`action`; `status.area` |
| `ui.widget` snapshot (C10) | `widget` |
| `gaming.rows` (C11), `ui.game_section` (C18) | `game.page` and `home.shelf` |
| `ui.search` (C5), `library.sources` `search`/`detail` | `search.result`; `item.page` |
| `library.updates` `record` and `check`'s `facts` (A6) | `item.page` bundles: `description`, `metadata`, `image`, `download`, `links`; `check` answers become `update` |
| `library.metadata` `fetch` fields (A3): `description`, `developer`, `publisher`, `genre`, `releaseDate`, `rating`, `cover`, `sourceLinks` | `description`, `metadata`, `image` (`cover`), `identity` |

The renderer that draws views today (`PluginViews` into the settings catalog) becomes the facet renderer, so the
improvement reaches every target at once. Contract 2 is retired under §7, never before the plugins droidtop ships
have moved.

#### Versioning

Facets are contract 3 (§7). Each kind and bundle has its own `v`: an additive field does not move it, and a removed
or retyped field is `v: 2`, served beside `v: 1` under §7's deprecation rules. Each target is versioned the same way.
droidtop advertises what it reads in `host.info().facets` (`{kinds: {status: [1], ...}, targets: {quickmenu: [1],
...}}`), and a plugin offers older kinds to an older droidtop.

#### Examples

A VPN bridge plugin in the Quick Menu, the companion and the status area, with live updates:

```json
{"facets": [
  {"id": "vpn.state", "kind": "status", "v": 1, "targets": ["quickmenu", "companion.card", "status.area"],
   "label": "VPN", "state": "on", "tone": "positive", "value": "Connected to Home"},
  {"id": "vpn.switch", "kind": "toggle", "v": 1, "targets": ["quickmenu", "companion.card"],
   "label": "Use the VPN", "value": true, "call": {"op": "setVpn"}},
  {"id": "vpn.speed", "kind": "chart", "v": 1, "targets": ["companion.card"], "label": "Download speed",
   "points": [1.2, 3.4, 2.9, 4.0], "unit": "MB/s"}
]}
```

An achievements plugin on a game's page and its capsule:

```json
{"facets": [
  {"id": "ach.badge", "kind": "badge", "v": 1, "subject": {"type": "game", "gameId": "pc:steam:440"},
   "targets": ["game.tile"], "text": "12/50"},
  {"id": "ach.progress", "kind": "progress", "v": 1, "subject": {"type": "game", "gameId": "pc:steam:440"},
   "targets": ["game.page"], "label": "Achievements", "value": 24, "sub": "12 of 50 unlocked"}
]}
```

The F95zone plugin's record (0.4.9), as contract 3 (`item.page`, kept per item):

```json
{"items": [{"source": "f95zone", "id": "93340", "ttl": 86400, "facets": [
  {"id": "meta", "kind": "metadata", "v": 1, "title": "Eternum", "version": "0.9.5 Public", "developer": "Caribdis",
   "engine": "renpy", "status": "ongoing", "rating": 0.96, "votes": 310, "tags": ["3dcg", "male protagonist"],
   "updated": 1700000000000},
  {"id": "about", "kind": "description", "v": 1, "overview": "You are moving with your best friend to the city of Kredon.",
   "changelog": "v0.9.5: ..."},
  {"id": "cover", "kind": "image", "v": 1, "role": "cover", "url": "https://attachments.f95zone.to/2023/10/3018543_f95zone_banner.png", "alt": "Eternum"},
  {"id": "dl", "kind": "download", "v": 1, "entries": [
    {"section": "Win/Linux", "host": "DATANODES", "label": "DATANODES", "url": "https://datanodes.to/f/2", "kind": "page"},
    {"section": "Win/Linux", "host": "AKIRABOX", "label": "AKIRABOX", "link": "0:AKIRABOX", "systems": ["windows", "linux"]}]},
  {"id": "links", "kind": "links", "v": 1, "entries": [{"kind": "source", "label": "F95zone thread", "url": "https://f95zone.to/threads/93340/"}]}
]}]}
```

#### Decided by the owner (2026-10-22)

The owner, on this proposal's questions: "the system should be as extensible as possible, but remember to guard it."
So:
1. **Store and source plugins may give `store`** (the owner: "Any store OR SOURCE plugin can provide that
   information. There has to be some guards on that."), under the guards in "Store information": only about the
   plugin's own items, on its own sites, never a purchase, prices labelled and dated, and `owned` only from a
   signed-in store plugin.
2. **A source order per bundle**, all starting from one default order.
3. **`download` entries carry their own `version` and `size`**, so one item may offer several versions.
4. **The desktop tray, file-manager actions, the app drawer and onboarding are targets now**, each with its own
   permission and risk (onboarding high, offered only from trusted catalogs).
5. **`chart` has a small bar style beside the sparkline**, capped at 12 bars.
6. **Extension points are slots and templates**, and new kinds and targets come only in droidtop releases (see
   "Extending").

---

### 1.11 Sources behave the same: match, links, states (approved 2026-10-30, Droidtop/tracker#784)

The owner, 2026-10-29: "We need to normalize the other plugins as much as possible. We want users to be able to
interact with them consistently", after F95's Match showed a correct link as "thread 214581" and could not be changed
(#784), and its downloads opened stale links and pages the person had nothing to do on (#783). This section is what
every source plugin (`library.metadata`, `library.updates`, `library.sources`) can count on droidtop doing, and the
little it must do itself. Where droidtop can do a thing once, it does, so no plugin builds its own.

**Status: approved** (the owner, 2026-10-30: "Plugin rules look good"). It is built in this order, each a slice of #784:
names through `fetch`, the one rematch path, the record-link path, the shared states, then the audit of every source
plugin. The parts marked *(built)* have landed.

1. **A candidate has a name, or says it has none.**
   - A `match` candidate carries `title`, the name the source's record goes by.
   - A source that cannot name it leaves `title` out. It never puts in a stand-in such as `"Thread 214581"`.
   - droidtop shows an untitled candidate as "<source> <ids> (name not given)" *(built, #784)*. Before the person
     picks one, droidtop asks `fetch {ids}` for its `title`, so a name the source can read still shows.
   - The conformance check (the fake host in `plugin-host-testing`, #71) fails a candidate whose title is only its
     id.
2. **One rematch path, droidtop's.**
   - The automatic scrape is unchanged: a linked game's `match` gets the link in `storeIds` and `sourceLinks`, and
     the source answers by it without searching (A3).
   - A Match the person starts (`facts.asked: "person"`) never sends the source's own link. The source searches by
     `title` and `folder.name`, as for a game it has never seen.
   - droidtop then puts the current link first, named by `fetch {ids}` and marked "Current link", followed by the
     search's candidates without it. A source does not build this list itself.
   - Picking another candidate replaces the link under that source's key. Picking the current one changes nothing.
3. **One link path: the record.**
   - A source gives download links only in its record (`record` / `item.page` downloads, A6 and §1.10).
   - droidtop keeps the record's links, opens a link out of sight first, and asks for the record again when the page
     carries the source's declared `expiredText` *(built, #783; G3)*. A served host (a debrid or host API) takes the
     link with no page shown. The visible web view opens only when the person has something to do.
   - **One thread parser** *(built, #838 slice 1)*: `sources.parse_thread {html}` (no permission; it reads only the
     text it is given) reads a forum thread's first post into `download` entries: a build heading is the `section`
     ("Steam Build · Win/Linux"), a platform label is one entry per host covering every system it names ("Win/Linux"
     is one download for Windows and Linux), hosts keep the thread's order, "Patches" and "Extras" items carry `role`,
     and a `*` makes one `official: false`. It answers `{version?, entries, problem?, message?}`; with no entries,
     `problem` says why: `browser_check`, `members_only`, `no_downloads` or `layout`. A source builds its record's
     downloads from it rather than its own scraping.
   - A source that knows its links expire declares `expiredText`. No source resolves, retries or opens links on its
     own, and there is no second "resolve" op: asking for the record again is that op.
   - A source's `acquire` job that opens a page itself (`web.session open_in_session`) goes the same way, and answers
     `{captured: false, expired: true}` for the plugin to ask its site again *(built, #783)*.
4. **One set of states, droidtop's words.**
   - A source answers the shared codes (§1.3): `SIGN_IN_REQUIRED`, `BROWSER_CHECK_REQUIRED`, `RATE_LIMITED`,
     `NOT_FOUND` (the record is gone) and `FAILED` (anything else, the site being down included).
   - Its `message` is one plain sentence about its own site, never instructions for droidtop's screens.
   - droidtop draws every one of them the same way for every source: "Sign in", "Open the site" and "Try again", with
     the same sentence shape ("<source> could not ...: <message>").
   - A source draws no sign-in, retry or error row of its own.
   - *(built, #784)* The sentence is droidtop's for every shared code (`SourceFailures.forCode`): a sign-in, a check,
     "The site asked for a pause" for `RATE_LIMITED`, "It is no longer on the site" for `NOT_FOUND`. Only a `FAILED`
     message may stand as written, and only when it is one short plain sentence with no link or address in it.
     Anything else gets droidtop's sentence, with the source's words under Technical details.
   - *(built, #784; the owner: "Parse and sanitize it against any injection attacks and stuff first")* Every word a
     plugin sends is cleaned where it enters droidtop (`PluginText`, applied in `PluginReply.parse`, `PluginReply.error`
     and `PluginResult`), before any screen, job line, notice or TalkBack gets it:
     - markup and HTML entities are removed;
     - control characters become spaces;
     - invisible format characters are dropped: the bidirectional overrides and isolates that can show text out of
       order, zero-width characters, the byte-order mark;
     - lone surrogates are dropped;
     - the text is bounded (a message 200 characters, a title 80, a detail 400).

     An error's `permission` must look like a permission id, or it is ignored. droidtop shows the result only as text:
     never parsed as HTML, a format string, a link or an intent. A job's `summary` and `message` are cleaned the same
     way; its other values are data and are kept as given.
5. **What each source is checked against.** The audit on #784 goes through every source plugin and lists, per point
   above, what it does now and what changes:
   - gamegrab-sources: F95, the ROM catalog plugin, LewdCorner, Repacks, Nyaa, Abandonware, BIOS, the F95 adapter in
     droidtop-agent;
   - Droidtop: Old versions, and any other plugin that matches or acquires.

## 2. Plugin-provided APIs

*Added at the owner's direction on 2026-09-28: "the shizuku and magisk
plugins are ALSO plugins. They consume these APIs, and can also expose API
context/new calls/etc that other plugins can use."*

### 2.1 What this changes

- **Root and Shizuku are not host features for plugins.** The host has
  no root API and offers no Shizuku API to a plugin. (droidtop itself
  also reads the system Shizuku API for its own `PrivilegedShell`
  backend, docs/SPEC.md "The task manager"; that is not exposed to
  plugins.) Privileged operations come from **provider
  plugins**: the official `droidtop-plugin-shizuku` (ADB-level privilege
  through Shizuku) and the official `droidtop-plugin-mmrl` (root, and
  Magisk/KernelSU/APatch module management), plus any others. What the
  host keeps is the *broker*: the manifest fields, resolution,
  permission checks, audit, timeouts and the grant UI. That is the same
  job it does for every other API.
  - The desktop container stack's own root backend (`runtime-linux-root`,
    §3) is host-internal Desktop-mode code and is **not** a plugin API.
    Plugins reach containers only through the containers API (§3 E1),
    whatever backend is running.
- **Any plugin can be a provider.** A plugin can export an API and other
  plugins can use it. This is how plugins talk to each other (§3 J3);
  there is no other inter-plugin channel.

### 2.2 Declaring it

The **provider** declares, in `exports`:

```json
"exports": [
  { "api": "priv.shell", "version": "1.2",
    "attributes": { "level": "adb" },
    "ops": [
      { "op": "exec", "permission": "priv.shell.adb", "job": false },
      { "op": "exec_stream", "permission": "priv.shell.adb", "job": false },
      { "op": "stream_read", "permission": "priv.shell.adb", "job": false },
      { "op": "stream_write", "permission": "priv.shell.adb", "job": false },
      { "op": "stream_kill", "permission": "priv.shell.adb", "job": false }
    ] },
  { "api": "droidtop.shizuku.status", "version": "1.0",
    "permissions": [
      { "id": "droidtop.shizuku.status.read", "risk": "low",
        "label": "See whether Shizuku is running and paired" } ],
    "ops": [ { "op": "get", "permission": "droidtop.shizuku.status.read" } ] }
]
```

The **caller** declares, in `requires`, *and* lists the permission in its
own `permissions`:

```json
"requires": [ { "api": "priv.shell", "version": "1.1", "optional": true, "minLevel": "adb",
                "reason": "Clears the emulator's shader cache" } ],
"permissions": [ { "id": "priv.shell.adb", "reason": "Clears the emulator's shader cache" } ]
```

There are two kinds of API ids.

- **Standard interfaces**, specified by droidtop in this document. Any
  plugin may implement one, and droidtop defines the op schemas, the
  permissions, their risk and their plain-language labels. Initial set:
  - `priv.shell` (with attribute `level` = `adb` or `root`);
  - `priv.packages` (install, uninstall, grant a runtime permission, set
    an appop, force-stop);
  - `priv.settings` (read/write `secure` and `global` settings);
  - `root.modules` (list, install, enable or remove a
    Magisk/KernelSU/APatch module);
  - `input.remap` (apply a key-mapping profile, the Key Mapper bridge in
    §3 F1);
  - `sync.folders` (Syncthing-style folder sync status and trigger).

  Standard ids have no origin prefix, and the host keeps their registry.
- **Provider-specific APIs**, which are namespaced
  `<origin>.<plugin>.<name>` (the example `droidtop.shizuku.status`
  above). Only that plugin can export one. Its permissions are declared
  by the provider, inside its own namespace, with a `risk` and a `label`.

Rules:

- **A version is `major.minor`.** A major is a breaking change and a
  minor is additive. A caller's `"version": "1.1"` means major 1 with
  minor at least 1. A provider may export several majors at once.
- **One op, one permission.** Every exported op names exactly one
  permission. A provider cannot offer an op that needs no permission,
  because a permission is how the caller's grant is checked (§2.6).

### 2.3 Discovery and resolution

- **The resolver.** `PluginApiResolver` (host) keeps a graph of `exports`
  against `requires` over runnable plugins. It recomputes the graph on
  every lifecycle change (§1.5) and is never computed per call.
- **One provider per interface.** For a standard interface with several
  runnable providers (say `priv.shell` from both the Shizuku and the root
  plugin), the user chooses once per interface in Accounts and sources →
  Plugins → "Provided by". It works like a system's default player, and
  it is never ranked automatically beyond the first default. When a
  caller's `minLevel` excludes a provider (it wants `root`, only `adb`
  exists), that provider is not offered for that caller.
- **What a caller sees.** A caller can ask
  `host.call("plugins", "available", {api})` to learn whether it is
  resolved, which version, and the provider's attributes. It never learns
  the provider's identity beyond its label.
- **A missing required API.**
  - The plugin shows "Needs <interface label>" and is Waiting.
  - If a catalog plugin exports that interface, the row offers "Get
    <provider>", so the user reaches the catalog's own install path. The
    host never installs a provider on its own.
  - A missing *optional* API only makes calls to it fail with
    `PROVIDER_UNAVAILABLE`.

### 2.4 Brokering: droidtop mediates every call

A plugin never talks to another plugin directly. There is no binder
handoff, no shared file convention and no intent between them. For
`caller.host.call("priv.shell", "exec", args)` the broker:

1. **Checks the caller.**
   - It is runnable.
   - It declared `requires priv.shell`.
   - It holds a **grant for `priv.shell.adb` of its own** (§2.6).
   - The resolved provider satisfies the version and `minLevel`.
2. **Checks the provider.** It is runnable, and its own export is still
   granted (§2.8).
3. **Forwards the call** to the provider's `handle` as an ordinary
   extension-point call, with `point: "api:priv.shell"`. The `caller`
   block names the calling plugin's id, origin, trust tier and **only
   the grants that apply to this API**, plus a `via` chain (§2.6). The
   provider can apply its own policy on top ("this caller may only run
   `pm clear` on packages it manages"), but it can never widen what the
   host already refused.
4. **Applies timeouts.**
   - The provider's deadline is the caller's remaining deadline minus a
     1 s margin, capped at 15 s for a quick op.
   - A job op becomes a host-tracked job in `PluginJobsCenter`. It is
     owned by the *caller* and shown under the caller's name, with the
     provider named as "via".
   - A provider timeout counts as a provider crash, as today.
5. **Caps the result and returns it.**
   - The reply is capped at 256 KiB and schema-checked against the
     interface's reply schema (for standard interfaces). A
     provider-specific API's reply is checked for size and JSON shape.
   - The result goes back to the caller as JSON data only.
6. **Audits both sides.** The caller's log says "used priv.shell.exec
   via <provider>". The provider's log says "served priv.shell.exec for
   <caller>".

### 2.5 When the provider goes away

| Event | In-flight calls | Dependents with a required dependency | Dependents with an optional dependency |
| --- | --- | --- | --- |
| Provider disabled or uninstalled | fail with `PROVIDER_UNAVAILABLE`; jobs are cancelled and marked "provider removed" | become Waiting (not disabled, not crashed) and resume by themselves if a provider returns | get the event `plugins.api_changed {api, available:false}` |
| Provider crashes | fail with `PROVIDER_CRASHED`; the provider is disabled under the crash policy | become Waiting | get `plugins.api_changed` |
| Provider updated (same key) | calls finish on the old code; new calls go to the new code after the swap | if the new version drops a major the dependent needs, it becomes Waiting | `plugins.api_changed` with the new version |
| User switches the provider of a standard interface | calls finish on the old provider | a dangerous interface **asks the user again, once, per dependent**: "<Caller> will now use root through <new provider>" | the same one-time question for dangerous interfaces |

A caller's crash never disables its provider, and a provider's crash
never disables its callers.

### 2.6 Permissions for plugin-provided APIs, and confused deputies

- **Every caller needs its own grant.** Plugin A reaching root through
  plugin B needs a user grant of `priv.shell.root` **to A**. The fact
  that B holds root is never enough. The host checks A's grant before
  B's code even sees the call.
- **The grant UI is the same as for host permissions.**
  - Standard-interface permissions have host-written labels and fixed
    risks.
  - Provider-specific permissions show the provider's label, prefixed
    by the provider's name ("Acme Sync: see your sync folders") and
    marked with the provider's trust badge.
  - Grants are revocable in the same place (§4.4).
- **A risk floor stops providers laundering access.**
  - A provider-declared permission's effective risk is
    `max(declared risk, the highest risk of any dangerous grant the
    provider itself holds)`.
  - So a plugin that holds `files.shared.write` cannot export a "low"
    API through which a caller writes shared files without the user
    seeing a high-risk grant.
  - Official providers may declare a lower risk explicitly, with a
    written justification in the provider's own repo. That exception is
    reviewed as part of official origin signing.
- **The chain is attributed and limited.**
  - A provider serving a call may itself call other APIs, using its own
    grants. The broker carries `via: [A, B]` into every nested call and
    its audit entry.
  - The depth is capped at 3 (A → B → C) and a cycle is refused with
    `INVALID_ARGS`.
  - A provider **must not** use a nested call to give a caller a result
    the caller could not have obtained with its own grants. That is a
    provider bug, and it is the one confused-deputy case the host cannot
    detect mechanically (§5, T4).
  - For standard interfaces the op schemas are narrow on purpose, for
    example `priv.packages.clear_data(package)` rather than a free
    shell. Narrow schemas keep what a grant means close to what the
    label says.

### 2.7 Root and Shizuku, restated under this model

1. **Root and ADB-level privilege are optional enhancements and never
   required.** This is unchanged, and the installer now enforces it: a
   `requires` entry on any `priv.*` or `root.*` interface must be
   `"optional": true`, or the install is refused. A plugin keeps its
   core function with no provider installed, the provider present but
   the device not rooted, or the grant declined.
2. **Only providers touch privilege.** The Shizuku provider holds the
   Shizuku binder; root comes only from Shizuku running as root, Sui, or a
   Magisk-module provider (owner rule: droidtop never runs `su`). Every
   other plugin reaches them only through `priv.*`/`root.*` calls with its
   own grant.
   - **Levels (built 2026-10-09, plugin-enforce-5).** A provider may export
     the same API at two levels, `{"level":"adb"}` and `{"level":"root"}`.
     Each export is its own grant on the provider's approval list, in plain
     words from the op's permission ("Other plugins may: Run system
     commands", "Other plugins may: Full control of the device"); the
     root one is kept under the key `priv.shell@root`, the adb one under the
     API's plain name. Callers declare `priv.shell.adb` or `priv.shell.root`,
     each its own critical grant.
   - **A root export is offered only while root is held.** The provider
     reports what it holds right now with the host op
     `plugins.report_level {api, level}` (`none`, `adb` or `root`, and only
     a level it exports). droidtop keeps the last report
     (`ProviderLevels`, kept across restarts) and offers the root export to
     a `minLevel: "root"` caller only while it says `root`; a manifest can
     never claim root by itself. The provider checks again before every
     command, so a stale report only means a refused call. This is what
     `PluginContext.hasRootApproval()` and `plugins.available` with
     `minLevel: "root"` answer from.
   - **The level travels with the call.** The broker puts the level of
     the export that served the call in `caller.level` (and, for a plugin
     caller, the op's permission in `caller.grants`), so a provider runs it
     at that level. droidtop's own calls (`HostApiCaller`) ask for a level
     with `minLevel` (the rooted desktop stack asks for `root`) and are
     otherwise served by the lowest export (least privilege). A provider
     that cannot run a plugin's call at its granted level refuses it
     rather than run it higher: Shizuku running as root cannot drop to the
     shell user without `su`, so it refuses an adb-level plugin call then
     and says to allow "Full control of the device".
   - **Stream sessions (`exec_stream`, built 2026-10-09,
     plugin-enforce-5).** A command that outlives one call or takes input
     (droidtop's `PrivilegedShell.spawn`, the rooted desktop stack,
     Droidtop/tracker#394). Four quick ops on `priv.shell`, each gated by
     the same per-level permission as `exec`:
     - `exec_stream {argv, session}`: starts `argv` directly (no shell
       unless `argv` names one). `session` is a token the caller chooses
       (a random UUID), so it exists before the reply. A provider keeps a
       session per caller (a plugin id, or droidtop) and at most 8 per
       caller.
     - `stream_read {session, waitMs ≤ 5000, maxBytes ≤ 65536}`: waits
       up to `waitMs` for output and returns `{stdout, stderr}` (base64)
       and, once both streams are drained after the process ended,
       `{exited: true, exit}`. The provider stops reading a stream whose
       unread output reaches 1 MiB, so a caller that does not read
       blocks the command, as a pipe would.
     - `stream_write {session, data, close?}`: `data` (base64, at most
       64 KiB) to stdin; `close: true` closes stdin.
     - `stream_kill {session}`: ends the command and the session.
     They are quick ops, not a job: droidtop's own calls cannot run job ops,
     a brokered job is one blocking call capped at 30 minutes, and a job's
     id never reaches the provider, so it could not address stdin and
     stdout. `exec_stream` and `stream_kill` are logged like any privileged
     call; the reads and writes in between are not (fixed by droidtop for
     this interface, `BrokerCore.isSessionPlumbing`), or a running session
     would push everything else out of the activity log. A session ends
     with its process; if the provider's process dies, Shizuku ends the
     commands it started. `ProviderProcess` (plugin-host) is the
     `java.lang.Process` over these ops that droidtop's `spawn` returns.
3. **Providers run in the full-trust tier.** Shizuku grants its
   permission to droidtop's UID, and root managers grant `su` per UID, so
   an isolated process can use neither (§5.3). This is the reason the
   full-trust tier survives the containment proposal.
   - A **user-trusted** (third-party) provider of a `priv.*` interface
     needs the user to grant it `plugins.export_privileged` (critical)
     as well as its approval. The grant screen says in words: "This
     plugin, from a source you added and droidtop has not checked, will
     be able to give other plugins root access."
4. **The legacy fields are shimmed.**
   - `requestsRoot`, `PluginContext.hasRootApproval()` and
     `hasShizukuAccess()` keep working for contract 1 plugins through a
     host shim (§6).
   - The shim stops being offered to contract 2 manifests, and is
     removed together with contract 1 support.

### 2.8 Trust interaction for providers

- **Official providers.** An official provider's exported dangerous
  interface is offered like any other.
- **Providers from a user-trusted origin.**
  - The export itself is a grant the *provider* needs:
    `plugins.export_privileged` for `priv.*`/`root.*`, and
    `plugins.export` (normal) for everything else. So "this plugin makes
    something available to other plugins" is always visible.
  - Callers see "via <provider> (Added by you)" on their grant line.
- **Removing a key.** Removing a user-trusted key (12a "Removal is
  real") stops the provider, which makes its dependents Waiting at the
  same moment.

### 2.9 All three kinds

Exporting is `handle` with `point: "api:<id>"`, and calling is
`host.call`. Both exist in every adapter (§1.3), so a Python or Flutter
plugin can be a provider exactly as a native one can. The limits come
from the execution tier (§5.3), not from the kind: a provider that needs
the Shizuku binder must run full-trust.

---

## 3. API catalogue

**How to read an entry:**

- **Type**: EP (the plugin provides; droidtop calls in), API (the plugin
  calls out through the broker), Event, or Hook.
- **Surfaces** are listed for each mode.
  - **G** = Gaming (the `:shell-gamepad` shell, including its Quick
    Menu and the companion screen).
  - **A** = Android: the standard launcher (`:shell-default`) plus
    droidtop's settings surfaces (the Preference renderer).
  - **D** = Desktop: droidtop's desktop surface and the container-side
    taskbar reached over the host bridge (§2a).
  - "—" means the group does not appear in that mode.
- **Permission** gives the permission ids from §4.1. "—" means always
  available, and `provide:` means providing a high-risk extension point
  (§4.2).
- **Risk**: low, medium, high or critical (§4.1).
- **Status**: built, partial or not built. It is derived from code, and
  §6 cites the code.

The group ids (A1, B3, ...) are stable references for issues and code
comments.

### Naming (owner, 2026-10-10, Droidtop/tracker#459)

"It probably makes sense to make API call names simple and descriptive. We
should be able to parse the api call behavior by what it does, and the name
should match it." A call is `<area>.<verb>` or `<area>.<verb>_<object>`: the
area is what it acts on, the verb says what happens, lower-case words joined
by `_`. A read-only call starts with a read verb (`get`, `list`, `check`,
`read`, `find`, `search`, `count`); a call that changes something uses the
verb of that change (`add`, `remove`, `trust`, `install`, `open`, `send`,
`set`, `report`, `cancel`, `post`, `write`, `delete`, `move`). No bare nouns
(`status`, `info`), no event-style past tenses (`changed`). The same rule
holds for a plugin's own ops (`exports`, `linkOps`). droidtop cannot enforce it
on a plugin, so the bundle workflow warns on names that break it
(`HostOpNames.check`, the rule as code) and the action-link review never relies
on a plugin op's name (docs/SPEC.md 12a "Action links").

**Contract 2.1 renames** (`host.get_info` reports `contractMinor: 1` and the
map as `deprecatedOps`). Each old name stays a deprecated alias that answers as
the new one until contract 2.2 (§7):

| Before 2.1 | From 2.1 |
| --- | --- |
| `net.state` | `net.get_state` |
| `net.http` | `net.request` |
| `host.info` | `host.get_info` |
| `plugins.available` | `plugins.list_available` |
| `plugins.job_status` | `job.get_status` |
| `plugins.job_cancel` | `job.cancel` |
| `plugins.report_level` | `provider.report_level` |
| `data.usage` | `data.get_usage` |
| `data.path` | `data.get_path` |
| `storage.volumes` | `storage.list_volumes` |
| `vault.keys` | `vault.list_keys` |
| `web.session.status` | `web.session.get_status` |
| `apps.check` | `apps.check_installed` |
| `apps.intent` | `apps.send_intent` |
| `apps.view` | `link.open` (permission still `apps.view`) |
| `social.changed` | `social.report_changed` |
| `library.files.changed` | `library.files.report_changed` |
| `library.read.systems` | `library.read.list_systems` |
| `companion.recording` | `companion.set_recording` |
| `retroarch.status` | `retroarch.get_status` |
| `retroarch.command` | `retroarch.send_command` |

The `apps.catalog` point's ops follow it from the start (`list_sources`,
`get_app`, `list_latest`, `search`, `add_source`, `refresh`, `acquire`).
Names elsewhere in this document that predate 2.1 mean the op in this table.

### A. Library and content

**A1 Library read.** API `library.read@1`. Risk medium.
- **For:** letting a plugin see what is in the library (games, apps,
  systems, folders, entry metadata), so it can offer something relevant.
- **Ops:**
  - `systems()`, built (2026-10-07): `{ready, systems: [{id, name, games,
    choice, playerId, playerName, playerPackage, core}]}`, one row per
    system that holds at least one game that is there, by name. `choice`
    is where the emulator came from: `system` (chosen for that system),
    `global` (the person's default emulator), `automatic` (the first
    installed one) or `none` (nothing installed runs it; the player fields
    are empty strings, never null, as in `library.default_player_changed`).
    `playerId`/`playerName`/`playerPackage`/`core` are what a launch with no
    per-game setting would use, so the answer and a launch agree. `ready`
    is false, with no systems, only when no surface has published the
    library yet and the first load did not finish within 5 s: ask again
    later. A call over a `host.call` (native `context.call`, Python
    `droidtop.host.call`) is made as `library.read@1` op `systems` with no
    args. It is the whole of what this op tells a plugin: no titles, paths
    or entry ids;
  - `entries(filter, page)`, `entry(id)`, `folders()` (not built);
  - `history(entryId)`, which needs `library.history`: last played,
    playtime, session count (not built).
- **Events:**
  - `library.entry_added`, `library.entry_removed`,
    `library.scan_finished {counts}`;
  - `library.default_player_changed` (exists as `default_player_changed`).
- **Surfaces:** none; it is data.
- **Permission:** `library.read` (normal) and `library.history`
  (dangerous).
- **Status:** `systems` and the one event are built; the other ops are not.
- **Rules:** paged (at most 500 per page); read from the index, never a
  disk walk. `systems` reads the library's in-memory index
  (`PluginLibraryRead`) and the emulator resolution a launch uses, on the
  broker's binder thread, never the main thread; a call without the
  `library.read` grant is refused by the broker like any host call
  (declared, granted, quota, 8). A plugin never gets a database handle
  (12a).

**A2 Sources.** EP `library.sources@1`. Risk high.
- **For:** "where can I get this": search a source, look up a known
  title, list the choices one result offers, and acquire it into a
  configured library folder. This is **`pluginsearch`'s Sources API**
  (`GameSourceProvider`, `library-core`). It is folded in here unchanged:
  a plugin source is one implementation, and built-in stores are others.
- **Manifest:** `systems` on the `provides` entry (1.6, "Which systems a
  source serves"): which systems it is listed under and which searches ask it.
- **Ops:**
  - `search {query, platform}`;
  - `lookup {title, platform, ids}` (defaults to `search`);
  - `options {result}`;
  - `acquire {result, choice, destination}`, a **job**.
  - Host call `library.files` op `changed` (version 1): see the end of this
    entry. A source that put a file in a game folder says so and droidtop
    indexes that file.
- **Surfaces:**
  - every mode: the one search (Gaming's search and Get games entries, Standard's search, Desktop's
    Start menu search, the companion's search), and the source's own page under Game sources.
- **Permission:**
  - `provide:library.sources`;
  - `library.folders.write`, scoped to the destination the host passes;
  - `net.domains` or `net.any`.
- **Status:** built as `acquire_content` (the wire contract in 12a, "The
  wire contract this UI defines"); the interface is being built by
  `pluginsearch`.
- **Rules:**
  - Results become games only when droidtop's own detection finds the files.
    Plugins contribute no library entries (12a).
  - `destination` is a host-resolved folder. A plugin-returned path is
    never used to write; it is only ever a name for a file already written
    there.
  - **`library.files` `changed {added[], removed[], changed[]}`** (host
    call, version 1; Droidtop/tracker#354, docs/SPEC.md 7g "Targeted
    indexing"). Absolute paths, 1 to 200 per call. Droidtop classifies and
    adds, updates or removes exactly those files and folders in its index
    with the rules a scan uses, looking at a new game folder's own contents
    and walking nothing else, off the plugin's thread. Permission:
    `library.folders.write`, so it is declared in the manifest and shown on
    the permission screen with "Add files to your game folders". Every path
    must be inside one of the person's game folders and strictly below it (a
    game folder itself is a rescan); a call naming any path outside them is
    refused whole with `PERMISSION_DENIED` and indexes nothing. Reply
    `{accepted: true}`. From a Python plugin it is
    `droidtop.host.call("library.files", "changed", {"added": [path]})`; native and
    Flutter plugins use the same host call. Report a game's folder rather
    than a file deep inside a folder droidtop has never seen.

**A3 Metadata providers and scrapers.** EP `library.metadata@1`. Risk
medium. *(Proposed 2026-10-22: `fetch`'s fields become facets in contract 3,
§1.10; they are read as facets meanwhile.)* The owner, 2026-10-16: "can we add an automatic scraping system?
We have LOTS of games with no metadata" (Droidtop/tracker#568), and on
the same issue: "That's fine for plugins, but our built-in scrapers don't
talk over the API".
- **For:** what a plugin knows about a game, offered next to droidtop's
  built-in sources (§7h's honesty rules apply).
- **Plugins only.** droidtop's built-in sources stay inside the scrape
  job, in droidtop's own process, and never go through this point:
  ScreenScraper, TheGamesDB, the libretro database, store data, the PC
  sources, and Steam's `appdetails` (`SteamStoreClient`). This point is
  how a plugin adds to them.
- **Ops:**
  - `match {facts} → {candidates: [{ids, confidence 0..1, title?}]}`.
    - Facts:
      - `title`, `fileName`, `systemId`, `systemName`;
      - `version` (the folder or archive's version text);
      - `folder` (`name`, `exe`, `engine`, `sizeBytes`);
      - `storeIds` (`{steam, vndb, f95, dlsite, ...}`: the game's links
        and the ids read from its folder);
      - `sourceLinks`, kept for plugins written before `storeIds`.
      - `folder` carries at least `name`, the game's folder name, also
        sent as `fileName` (2026-10-17, Droidtop/tracker#575).
    - A source with no link for the game searches by `title` and by
      `folder.name`, and answers only exact matches, as droidtop's
      built-in sources match.
    - The host takes the one most confident candidate at or above 0.5 (a
      candidate without a confidence counts as 0.5). When two or more
      share the top confidence, it takes none: the game goes to "Choose
      matches", where the person picks among them (SPEC 7h, "A source's
      own match"). A candidate's `title` names it there and in
      "Rescrape with…".
  - `fetch {ids} → fields`:
    - `title`;
    - `description`, `developer`, `publisher`, `genre`, `tags`;
    - `releaseDate` (YYYY-MM-DD, YYYY-MM or YYYY);
    - `players`, `rating` (0..1);
    - `cover` and `screenshots` (https URLs);
    - `links` (`[{label, url}]`);
    - `storeIds`, so a later scrape can skip `match`.
    - `cover` (2026-10-17): the https address of the game's cover. droidtop
      fetches it itself for a game with no cover: the address and every
      redirect must be on the plugin's `net.domains`, and the answer a PNG,
      JPEG, WebP or GIF of at most 10 MiB (SPEC 7h). `screenshots` are not
      taken yet. It is filed by its real shape (SPEC 7h, "Artwork roles",
      2026-10-29, #585): box art is the cover; a landscape picture (a
      thread's banner) goes to the game's landscape slot when that is empty,
      and is never squashed into the cover. A plugin with landscape art only
      may still send it as `cover`.
    - `sourceLinks` (`{<key>: <id>}`), the record the source matched:
      droidtop links the game under that key (one of the plugin's own
      `library.updates` entry ids; any other key is ignored), unless it
      is already linked there. The next `match` gets it in
      `sourceLinks` and answers by it without searching.
    Every field is optional.
  - `cancel {callId}`: the scrape stopped. Answering is optional; the
    host does not wait.
- **Surfaces:**
  - G/A/D: the Scraper screen lists it as a source; the game's detail
    screen offers "Rescrape with…".
  - The automatic scrape (#568) asks it for every game still missing
    fields, as a background job.
- **Permission:** `provide:library.metadata`, `net.domains`.
- **Status:** match and fetch are called by the scrape job
  (`PluginMetadataSources`). Built (#568, 2026-10-29): the facts
  `storeIds` (the store row's own id, the ids it names, a store marker in
  the folder, and the game's source links, which win) and `version` (the
  installed build, when one is known); the pace below, enforced per
  source by the pass (`PluginMetadataSources.Session`,
  `PluginMetadataProtocol.perMinute`/`waitMs`), with a `RATE_LIMITED`
  source resting for the rest of the pass and saying so in the scan log.
  The pace covers every call to the source, the picker's `match` and the
  `fetch` that names an unnamed candidate (1.11) included. `title` is read
  only to name a picker's candidate (1.11). Not built yet: `folder.exe`,
  `folder.engine`, `folder.sizeBytes`; the fetch fields `title` as a field
  the scrape fills, `tags`, `screenshots`, `links`, `storeIds`; and
  `cancel` (a cancelled scrape stops waiting on the call, but the plugin
  is not told).
- **Rules:**
  - The host merges, stores and attributes the result.
  - A plugin fills only fields still empty after the built-in sources,
    never overwrites another source's value, and is recorded as the
    source of each field it fills.
  - The plugin never writes to the database or the media folder. It
    returns image URLs, and the host downloads them through its download
    manager, checks their type and size, and stores them (A4's rules). A
    URL's host must be in the plugin's `net.domains`.
  - The host enforces the pace; the plugin is not trusted to:
    - one call in flight per source;
    - calls spaced to the `rate.perMinute` on the manifest's `provides`
      entry (default 30, at most 120);
    - a `RATE_LIMITED` reply rests that source for the rest of the pass;
    - each call's deadline is 15 s;
    - cancelling the scrape cancels the call in flight.
  - It runs in the scrape job, never in list rendering. The plugin sees
    only the facts above, one game at a time.

**A4 Artwork and media providers.** EP `library.artwork@1`. Risk medium.
- **For:** box art, logos, heroes, screenshots, videos and manuals (the
  SteamGridDB shape, and theme media slots).
- **Ops:**
  - `list {entry ids, kinds} → [{kind, url, w, h, lang}]`;
  - `fetch`, which runs through the host's download manager into
    `downloaded_media`.
- **Surfaces:** G/A/D: the artwork picker on a game, and scrape
  settings.
- **Permission:** `provide:library.artwork`, `net.domains`.
- **Status:** not built.
- **Rules:** the host downloads, validates the image type and size, and
  stores the file. A plugin never writes the media folder.

**A5 Recommendations.** Closed to plugins: **not an extension point**.
Risk —.
- **For:** "games you might like". This is `pluginsearch`'s
  `RecommendationProvider`, droidtop's own built-in feature.
- **Why closed:** by owner directive it is "never plugin-fed, never
  plugin-named". Plugins neither provide nor read recommendations.
- **Where it touches the API:** only through A2 `lookup`, where a
  recommendation's "where to get it" list comes from sources.
- **Surfaces:** G: Get more; A/D: the library views.
- **Status:** interface built (empty candidate pool; see its doc).

**A6 Update checks.** EP `library.updates@1`. Risk medium.
- **For:** "is there a newer version of this game", for a game the person
  linked to a record of the source: a web-sourced game (#9, the F95zone
  plugin, tracker#380), a GitHub-released homebrew, or an Obtainium bridge
  (F1).
- **Declaring it:** one `provides` entry per source, whose `id` is the
  source key droidtop stores the person's links under (keep it stable
  across versions: a changed key orphans every link) and whose `label` is
  the row title on a game's page ("F95zone thread"). An optional
  `linkHint` in the entry says what to paste ("Thread link or number").
  Two plugins declaring one key: the first one installed is asked.
- **Ops** (every call's args also carry `source`, the entry id):
  - `check {ids: [string]}` → `{answers: [{id, version?, url?, gone?}]}`.
    Run in the host's update round, never from UI: at most 50 ids per call,
    each id at most every 6 hours (once a minute for a person's "Check
    now"), 2-minute budget, a miss is not a crash. The plugin paces its own
    site requests inside the call. An id left out of `answers` keeps its
    last answer; `gone: true` says the record is private, moved or deleted.
    The HOST compares `version` with the game's own versions (docs/SPEC.md
    7g, "When it is an update"); a plugin never says "update available".
  - `resolve {text}` → `{found: {id, title?, version?, url?, note?}}` or
    `{}`: the record a person's pasted link or id names (15 s).
  - `match {title, versions}` → `{candidates: [{id, title?, version?,
    url?, note?}]}`: records that may be the game, best first, at most 20
    shown (15 s). `note` says why one is offered ("on your watch list").
  - `check`'s answers may also carry `facts {status?, tags?, rating?,
    votes?}` (2026-10-17): what the PC Games filter offers for linked games
    (docs/SPEC.md 7i, "What a linked source knows about a game"). `status`
    is one of `ongoing`, `completed`, `onhold`, `abandoned`; `tags` at most
    64 strings of at most 40 characters; `rating` 0 to 1; `votes` a count.
    Kept with the answer; a later answer without `facts` keeps the last.
  - `record {id}` → `{record: {id, title, url?, version?, developer?,
    status?, rating?, votes?, tags?, updated?, cover?, overview?,
    changelog?, downloads: [{section, host, label, link}]}}` (15 s,
    2026-10-17): the record's page, which droidtop draws (docs/SPEC.md 7g,
    "Getting an update": Overview, Changelog, Downloads, Info). `updated`
    is epoch milliseconds; `cover` an https address on the plugin's
    `net.domains`; `overview` and `changelog` plain text (at most 20,000
    characters each, cut beyond). A download's `section` is the source's
    own heading ("Win/Linux"), `host` the file host's name as the source
    words it ("MEGA"), `label` what the link says, and `link` an opaque
    string at most 2,048 characters long that droidtop only hands back to
    `download`; at most 200 downloads. Asked when the page opens, never
    in the update round. A download may instead, or as well, carry `url`
    (2026-10-22, Droidtop/tracker#599): the https address of the link
    itself, as the source's page has it. droidtop keeps the record, and
    picking that download opens `url` in droidtop's web view with the
    plugin's session (G3): the person starts the download there, droidtop
    takes over what the page downloads (up to 16 files) and places it as
    `download`'s result is placed. Nothing is fetched again to download. A
    link that starts nothing opens the record's own `url` next. A download
    with only `link` still goes to the `download` job.
  - `download {id, link, version?}`, a **job** (the person picked a link on
    the record page): its result is an A2 `acquire` reply (`download`,
    `downloads`, `unpack`, `engine`; a captured `download.session` from
    `web.session open_in_session`). droidtop places what it downloads as a
    new version folder of the linked game, `<name> v<version>`, beside
    its newest one, and copies saves kept in the game's own folder into
    it (docs/SPEC.md 7g, "Getting an update"); the job's `args` name no
    folder, and the plugin writes none. `version` is the record's, as
    `record` gave it. Replaces the earlier `apply` (never built).
- **Surfaces:** G/A/D: one row per source under a folder game's Versions
  and in its options menu, a sheet that links it (the `match` candidates,
  a paste field for `resolve`, Unlink), and "Check for update"; the answer
  feeds the "Update available" badge, the Updates shelf and filter, and
  Settings > Updates (docs/SPEC.md 7g "One wording"). A notification (C6)
  is not built.
  Tapping an update, or a linked source's row, opens droidtop's record
  page for that source (`record`); a source that does not answer `record`
  (`UNSUPPORTED`) gets a record page that says it has no update page, with
  Change link under it (Droidtop/tracker#619).
- **Permission:** `provide:library.updates`, `net.domains`.
- **Facets (proposed 2026-10-22, §1.10):** in contract 3, `record` and
  `check`'s `facts` become `item.page` facets (`ui.facets` `get`, or
  facets on `check` answers); contract 2's replies are read as facets
  meanwhile.
- **Status:** built 2026-10-08 (`PluginUpdateSources`,
  `SourceUpdateCheck`, `SourceLinkRows.kt`); replaces droidtop's built-in
  F95zone update check and F95Checker import.
- **Rules:** the host schedules every `check`; it is never polled from UI,
  and the plugin stores no library facts: the link and the last answer are
  droidtop's (`source_links`, `source_answers`).

**A7 Save management and sync.** EP `saves.sync@1` plus API
`saves.locate@1`. Risk high.
- **For:** backing up and syncing save data between devices or to a
  service.
- **Constraint:** the standing save policy holds: droidtop never changes
  save logic, it only changes where system locations point.
- **Ops:**
  - API: `locate {entryId} → [{path scope, kind}]`, which needs
    `saves.read`;
  - EP: `push {entryId, snapshot fd}` (a **job**), `pull {entryId}` (a
    **job**), `status {entryId}`.
- **Hooks:** B3 `pre_launch` (pull) and `post_exit` (push).
- **Surfaces:**
  - G: the game's Quick Menu "Saves" and detail screen;
  - A: settings;
  - D: file manager actions on a save folder.
- **Permission:**
  - `provide:saves.sync`;
  - `saves.read` and `saves.write`, both dangerous and scoped per entry;
  - `net.domains`.
- **Status:** not built.
- **Rules:** the host hands the plugin read or write file descriptors
  for the save paths it resolved itself. The plugin never picks a path.

**A7a Where saves are, both ways (Droidtop/tracker#547, contract
2026-10-23).** EP `saves.roots@1`. Risk medium. Host API `saves`
(permission `saves.exchange`, normal; only an `app_interface` plugin may
call it, any other gets `PERMISSION_DENIED`). The owner: "The two applications
should detect and work with each other over the plugin API so no matter
what, saves go where they're needed". Which app launched a game makes no
difference:
- **droidtop asks the plugin (EP `saves.roots@1`, Droidtop/tracker#546
  design v3 section 8, checks C10 and D12):** one point per job, so this
  is #546's point; the first contract post's `saves.locations@1` is
  dropped.
  - `roots {games: [{entryId, folder, engine, context?}]}` →
    `{default, families: {<family>: path}, games: [{entryId, folder?}]}`.
    `folder` is the game's folder as droidtop's launch hands it,
    `engine` and `context` Enginehost's engine name and engine context
    (as in `list_engine_games` and the launch config). `default` is the
    app's save root; `families` the root per engine family where it
    differs; `games[].folder` the game's own folder inside its family's
    root, only for families whose folder the host names (HTML,
    Flash/AIR, CatSystem2, web RPG Maker MV/MZ), never for an engine
    that names its own (Ren'Py's `config.save_directory`).
  - Roots, never files: a plugin names folders, droidtop finds the saves
    in them by the game's save rule.
  - Each path is used only when (C10) its canonical path is a directory
    droidtop can read on a mounted shared-storage volume, not under
    `Android/data` or `Android/obb`; (D12) it ends in the folder name
    `saves` (Enginehost's `SaveLocationStore.saveRootFor` always appends
    it) and is either the default `<volume>/Enginehost/saves` or one the
    person confirmed once: "Enginehost keeps game saves in <folder>. Use
    the same folder for these games here?"; and a game's folder is inside
    its family's root. Anything else is ignored and droidtop keeps
    Enginehost's default root. The person's own override (Settings >
    Saves > "Save folder for <engine>") still wins for droidtop's
    runners.
  - Asked when the save model needs an answer, kept for one hour.
    Facts for the save model: droidtop writes nothing because of them
    alone.
- **The plugin asks droidtop (host API `saves`, permission
  `saves.exchange`):**
  - `saves where {path, engine?, title?}` → `{known: true, gameId,
    saveRoot, synced}` or `{known: false}`: the folder droidtop keeps (and
    syncs) a game's saves in, the one it passes in its own launch, for the
    game at `path`, asked before the game starts, whoever starts it.
    `saveRoot: null` means the plugin's own default. droidtop answers only
    about the folder the plugin names and never lists its library. For a
    synced game droidtop syncs the saves down first, as before its own
    launch (it may ask the person about a conflict), so the call can take
    seconds. `{known: false, ready: false}` while the library is loading.
  - `saves report_changed {path, saveFolder, reason?, endedAtMs?}` →
    `{accepted, synced, engines: [{engine, line}], message}` (named
    `changed` in the first contract post; an op is named for what it
    does): the game at `path` wrote saves in `saveFolder`. droidtop keeps
    where each game last saved, for every game, and hands the report at
    once to every save sync engine that covers the game
    (`SaveSyncEngines`): a store's cloud adopts the saves into droidtop's
    folder for the game and uploads them, as after a game droidtop
    launched; paired computers and handhelds take it once they sync from
    a folder outside a Wine prefix (Droidtop/tracker#546), a cloud or
    network share once droidtop has one (Droidtop/tracker#573), and a
    `saves.sync` plugin (Droidtop/tracker#284). `synced` is whether any
    engine covers the game, `engines` what each did. With none, nothing is
    copied and that is no failure (`accepted: true, synced: false`).
    `saveFolder` must be a folder in shared storage or on a card, whose
    real path is not droidtop's own: anything else is `accepted: false`
    with the reason. Always in the activity log. Idempotent: the app sends
    it for every game it ran, droidtop's launches included (alongside the
    `GAME_ENDED` broadcast while that stays); a repeat copies only files
    newer than droidtop's and joins the upload already running.
- **droidtop offers its engine games (host API `library.read`, op
  `list_engine_games`, permission `library.read`):** the owner: "droidtop
  should also present games for enginehost to register, but not
  necessarily their whole configs. Remember, we want configs to be created
  by the USER, not necessarily saved into the game folder".
  `list_engine_games {engines?}` → `{games: [{gameId, title, path, engine,
  engineVersion?, updatedAtMs}], complete}`: the library's engine games
  only, the whole list each time (at most 2,000; `complete: false` when
  cut), for the app to register what is new and drop what is gone.
  `path` is the folder droidtop's launch hands Enginehost, `engine`
  Enginehost's own engine name, `updatedAtMs` when the folder last
  changed; `{ready: false}` while the library is loading. No
  configs travel and droidtop writes nothing into a game's folder: a
  game's configs are the person's, made in the app. The app asks again
  when droidtop connects, after a knock, and on `library.scan_finished`.
- **Without a broker yet:** the plugin's app knocks on droidtop's door
  (§1.3, `app_interface`), gets its broker within seconds, and asks or
  tells then.
- **Status:** `saves where`, `saves report_changed` and
  `list_engine_games` built (AppInterfaceSaves); `saves.roots@1`
  registered, asked by the save model next, and the reported folder shown
  on the game's Saves row.

**A7b Engine cores (Droidtop/tracker#752, 2026-10-29).** EP
`engines.cores@1`. Risk low. Only an `app_interface` plugin is asked.
Host API `engines` (no permission; only an `app_interface` plugin that
provides `engines.cores`, with the point on, may call it, any other gets
`PERMISSION_DENIED`). The owner: "we also can use the application
interface plugin code to present an engine core list that droidtop can
use". It decides a game's runner without droidtop knowing which cores
exist:
- **droidtop asks the plugin:** `list_cores {}` → `{cores: [{id, engine,
  label, contexts?, engineVersions: {series, any}, rows, installed,
  installedVersion?, availableVersion?, updateAvailable}],
  catalogCheckedAtMs}`. One entry per core line, installed or one the
  app's catalog can install. `id` is the bundle id, stable across
  versions. `rows` are the ids of the engines-database rows whose
  `enginehost` mapping the core serves, so droidtop matches its own
  detection to a core. `engineVersions` gives the game versions it runs
  (a series such as `8.3`, or `any`). An installable core has
  `installed: false` and its `availableVersion`. Reads only: answering
  never fetches anything, and `catalogCheckedAtMs` (0: never) says how
  old the catalog part is. droidtop keeps at most 200 cores per app.
- **When:** at droidtop's start, when the app connects through the door,
  after each `engines report_changed`, and when the app is installed or
  updated (droidtop's package watch). Never on a timer. The last answer
  is kept in droidtop's files, so a decision made before the app connects
  still has it. An app that cannot be asked now (waiting for approval,
  turned off, needing an update) keeps its last answer; it is dropped
  only when the plugin is removed or no longer provides the point
  (Droidtop/tracker#854: a dropped list made a game page offer "Get …
  support" for a core that was installed).
- **The plugin tells droidtop:** `engines report_changed {}` →
  `{accepted}`: a core was installed, removed or updated, or the catalog
  changed. droidtop asks `list_cores` again, off the caller's thread.
- **What droidtop does with it** (docs/SPEC.md 7i): for a game whose
  engines-database row a core serves, at the game's engine version, an
  installed core makes Enginehost Ready, and an installable one makes
  its setup step "Get <core label> support". When a checked catalog has
  no core for the row, Enginehost is not a runner for that game. When no
  list decides (none read, or the catalog never checked), droidtop keeps
  Enginehost's capabilities provider's answer, which stays advisory.
  The plugin's page lists the cores, installed first, then the ones that
  can be installed.
- **Status:** built (`EngineCores.kt`, `PcRunnerOptions`); Enginehost
  answers since its manifest version 3.

**A8 Achievements.** EP `achievements.provider@1`. Risk medium.
- **For:** achievement sets and progress, for example RetroAchievements
  (#14) or a store's own achievements.
- **Ops:**
  - `set {entry} → {achievements: [{id, title, desc?, points?, badgeUrl?, hidden?}]}`;
  - `progress {entry} → {unlocked: [{id, unlockedAt}]}`, `unlockedAt` in epoch milliseconds or an ISO date;
  - `entry` is `{title, system?, store?: {id, gameId}}`: what the game is called, droidtop's system id, and the
    store and its id for a store's game. Never a folder or a file on the device;
  - `account`, handled through G5.
- **Events:** `achievements.unlocked` (host → plugins, for an overlay
  plugin).
- **Surfaces:**
  - G: the game detail screen's Achievements, and the Quick Menu in game;
  - A: the detail screen;
  - D: the library object view.
- **Permission:** `provide:achievements.provider`, `net.domains`, and
  `library.history` if the provider reads play sessions.
- **Status (2026-10-24, Droidtop/tracker#143):** `set` and `progress` built (`PluginAchievements`): when a game's
  page opens and neither RetroAchievements nor the game's store has achievements for it, each provider is asked in
  turn (10 s each) and the first with any fills the page's Achievements row and its page, the same row and page as
  RetroAchievements' and Steam's. An `https` badge is kept, anything else dropped; 1000 at most. The event and the
  Quick Menu surface are not built.

**A9 Media and files library.** API `media.read@1`. Risk high.
- **For:** a general-computer need: music, video, pictures and
  documents, for a now-playing, gallery, backup or media-server plugin.
- **Ops:** `query {kind, page}` through MediaStore, run by the host, and
  `open {id} → fd`, which is read-only.
- **Surfaces:** G: the companion screen now-playing; A/D: file manager
  views.
- **Permission:** `media.read` (dangerous).
- **Status:** not built.
- **Rules:** the host queries MediaStore. A contained plugin (§5.3) has
  no filesystem of its own.

**A10 Library sources of entries.** Closed by decision (12a): a plugin
never registers a `LibraryProvider` or contributes an entry directly. It
writes real files that droidtop's own scan finds (A2).
- **Open question (§10, Q1):** store and web catalogues whose games are
  not files, such as cloud or streaming services, may need this
  reopened.

**A11 BIOS providers.** EP `emulator.bios@1` (Droidtop/tracker#415, decided
2026-10-09). Risk medium; no `provide:` consent (medium), it appears as an
"Adds" tick box like the other medium points.
- **For:** a plugin that can supply the BIOS files an emulated system needs,
  so the Emulator setup helper (SPEC "Emulator setup helper") can offer "Get
  the BIOS for this system" without the person hunting for files.
- **Ops:**
  - `list {system}` → `{files: [{name, md5?[], size?, source?}]}`: the files
    the plugin can supply for droidtop's system id (the ES-DE short name, as
    in `bios-database.json`), not what the system needs; the needs stay
    droidtop's. At most 500 are read; `source` is a label of at most 60
    characters that the confirmation shows ("Internet Archive").
  - `acquire {system, name, md5[]}` → a **job** whose reply is the acquire
    reply's single `download` descriptor (1.6; `md5`, `sha1` or `sha256`
    verified by the Downloads job). A descriptor with `session`, or several
    files, is refused.
- **What droidtop does with it:** it matches each file of its own BIOS
  database that the emulator's BIOS folder lacks to one of the plugin's files
  (a shared md5 decides, then the same file name), so the plugin never names
  where anything goes. The row "Get the BIOS for this system" appears only
  while a provider is installed, running and not switched off, and only where
  the helper can write the folder (itself, or through the privileged helper);
  otherwise the helper stays "Add a BIOS file" (the person's own file). Each
  file is a row "Get <file>" that first asks, naming the provider, its source
  and the exact path (and, through the helper, whose folder). The download
  runs as a Downloads job (`place_bios` post step): the file's md5 must be one
  the database lists for that entry, and it is then written by the helper's one
  write path, `EmulatorSetup.write`, which is the Risky actions gate
  (`OTHER_APP_FILES`) for another app's folder. It is never placed in a games
  folder. A download that fails its digest, or a placement that fails, keeps
  the file in droidtop's downloads folder and the job says where.
- **Permission:** `net.domains` for the source, as for `library.sources`; no
  folder permission, because droidtop writes the file.
- **Status:** built 2026-10-09 (`PluginBios`).

**A12 Android app catalogs.** EP `apps.catalog@1`. Risk high.
- **For:** a catalog of Android apps: an F-Droid repository client, a tracker
  of release pages (Obtainium-style). The plugin lists; droidtop downloads,
  checks, installs and decides what is an update (docs/SPEC.md 10b
  "Installing apps", Droidtop/tracker#261).
- **Ops** (every call also carries `device {sdk, abis, locale}`, so the plugin
  answers for this device):
  - `list_sources {}` → `{sources: [{id, name, address, enabled, updated?, apps?,
    fingerprint?, note?}], known: [{name, address, about?}]}`: the sources the
    person added, and the ones the plugin lists to add (F-Droid's own lists).
  - `open_link {link}` → `{review: {token, name, address, fingerprint?,
    fingerprintFromLink, description?, apps?, existing, note?}}`: fetches and
    verifies the source a link, an address or a QR code names; nothing is
    stored. Also how a declared link pattern reaches it (F3).
  - `add_source {token}`, a **job**: adds the reviewed source and fetches its
    index (the person's Accept).
  - `remove_source {source}`, `set_source_enabled {source, enabled}` →
    `{message}`.
  - `set_source_options {source, options: {name: value}}` → `{message}`: one of
    the source's own settings changed on its page. A source lists its settings
    in `list_sources` as `options: [{name, label, kind, value, description?,
    choices?}]` (`kind` `bool`, `text` or `choice` with `choices: [{value,
    label}]`, at most 16); droidtop draws them on the source's page and sends
    each change; what a setting means is the plugin's (a tracked page's
    pre-releases, its file-name pattern).
  - A source a plugin lists to add (`known`) may carry an address that is not a
    URL, such as `obtainium:import`; droidtop passes it to `open_link` unchanged,
    as a call the person started, so the plugin may ask for a file there
    (`files.pick`) within that call's three minutes.
  - `refresh {source?}`, a **job**: fetches and verifies the indexes again.
  - `search {query, limit}` → `{apps: [{id, name, summary?, version?,
    versionCode?, source?, antiFeatures: [key]}]}` (an empty query lists recent
    apps; at most 200).
  - `get_app {id}` → `{app: {id, name, summary?, description?, license?, website?,
    sourceCode?, author?, source?, versions: [{version, versionCode, size?,
    signers: [sha256], minSdk?, added?, antiFeatures: [{key, label, reason?}],
    whatsNew?}]}}` (at most 20 versions, compatible with `device`).
  - `list_latest {after?, limit}` → `{apps: [{id, name, version, versionCode,
    signers, source?}], next?}`: the newest compatible version of every app,
    paged by package name. droidtop compares; the plugin is never told what is
    installed.
  - `acquire {id, versionCode}`, a **job** whose reply is the acquire reply's
    single `download` descriptor (1.6) plus `packageName`, `versionCode` and
    `signers` (comma-separated certificate SHA-256s). No `session`, no unpack.
    The descriptor may say `"auth": "github"` (a private repository's release
    asset): droidtop then adds the person's GitHub token where GitHubAuth allows it
    (resolving the asset's redirect itself), held by Downloads in memory only and
    never shown to the plugin, and only for a plugin granted `github.api`.
- **What droidtop does with it:** the download is a Downloads job whose post
  step installs only the package and version named, signed by one of the keys
  named and by the installed app's key (docs/SPEC.md 10b).
- **Permission:** `provide:apps.catalog`, `net.domains` or `net.any` for the
  sources (an F-Droid client reads repositories on any host the person adds,
  so `net.any`); `intents.in` to receive the links it declares.
- **Status:** host side built 2026-10-10 (`AppCatalogs`, `AppCatalogScreen`,
  `ApkInstaller`); the F-Droid plugin is Droidtop/droidtop-plugin-fdroid.

### B. Launch and runtime

**A13 Stores.** EP `library.stores@1` (Droidtop/tracker#220; owner,
2026-10-01: "we need store/source and downloader integration APIs",
downloading belonging to sources). Risk high. Built 2026-10-28, in part
(rows and sign-in; installing and uninstalling).
- **What it is:** a store droidtop shows beside its own (Steam, GOG,
  Epic, Amazon, itch). A plugin's store is a `StoreLibrary` like theirs
  (`PluginStoreLibrary`, registered in `StoreLibraries` by
  `PluginStores`), so its games join the PC library, the automatic sync
  and every place that reads the stores. This answers A10's open
  question for stores: the rows are a store's rows, not entries a plugin
  adds to the library.
- **Manifest:** a `provides` entry
  `{"point": "library.stores", "version": 1, "id": "<store>", "label": "<name>", "site": "https://..."}`.
  `id` is the store half of every library id (`humble:123`): 2 to 24
  lower-case letters and digits, starting with a letter, and not a store
  droidtop already has. If two plugins name the same id, the first one
  read has it.
- **The account is the plugin's site account** (G3 `web.session`, with a
  declared `signIn` page). The store's Sign in opens it in droidtop's
  web view, and droidtop draws who is signed in. The plugin never sees a
  password, and Sign out clears that session and droidtop's copy of the
  rows.
- **Ops:**
  - `library {store} -> {games: [{id, title, size?, art?, externalIds?, holding?}]}`:
    the account's games. `id` is the store's own id (at most 128
    characters, one line). `art` is taken only over https.
    `externalIds` names the same game in other stores (`{"steam":
    "261570"}`), the strongest evidence that two rows are one game.
    `holding` is `owned` (the default), `family`, `free` or `wishlist`.
    Each id is taken once, and a row without an id or a title is
    dropped. droidtop keeps the answer as its own copy and lists from
    it with no call; Sync and the automatic sync ask again (60 s budget).
  - `install {store, id} -> {download}` or `{downloads}`: a job (1.6)
    answered exactly as a source's `acquire` is: one descriptor or a
    list, with `unpack`, digests, headers, a web-session token or a
    torrent. droidtop makes the game's folder under the store's folder
    in the person's game folder (`<game folder>/<store label>/<title>`,
    "Title (2)" beside a folder that is not droidtop's), fetches the
    files there with Downloads and records the game as installed at
    that folder. The plugin never names a path. A Cancel removes the
    unfinished folder; Uninstall deletes the folder droidtop made and
    nothing else, with no call to the plugin. droidtop's record of what
    it installed survives Sync and Sign out.
- **In the Stores place:** a running plugin's store is listed after
  droidtop's five, by its label, with the same row and page as theirs:
  sign in (the plugin's site account in droidtop's web view), sign out,
  Sync, its counts and its installs. It is also signed in from its
  plugin's page (the account row) and read there ("<store>: read your
  games"). It leaves the place when its plugin stops.
- **Launching** is droidtop's: the store gives no launch answer
  (`StoreLibrary.launch` is null), so droidtop finds the executable in
  the installed folder, or the engine that runs it, as it does for a
  built-in store's game without one. There is no `launch` op. A store
  whose games need the plugin to start them is B1's (launch providers).
- **Not built yet:** update (the plugin says nothing about versions, so
  an installed game is uninstalled to be installed again).
- **Permission:** `provide:library.stores` (high), `web.session`,
  `net.domains`.

**B1 Launch providers.** EP `launch.provider@1`. Risk high.
- **For:** a runner, emulator frontend or engine host whose launch needs
  code rather than a players-database `am start` template. Examples: a
  runner that must prepare a prefix, or unpack or mount an image before
  launching.
- **Relation to other mechanisms:** the players database stays the one
  mechanism for data-describable launches (§7e2). **Enginehost is not a
  plugin here**; it is a players-database emulator (§7d).
- **Ops:**
  - `can_launch {entry facts} → {ok, label}`, answered from the
    manifest's static `systems` / `extensions` filter first and called
    only on selection;
  - `prepare`, a **job**;
  - `launch {entry} → {intent spec}`, which the host validates and
    fires;
  - `cleanup`.
- **Surfaces:**
  - G/A/D: appears as a player choice in a system's Player screen and in
    a game's per-game runner choice (§7m), next to database entries.
- **Permission:** `provide:launch.provider`, plus `apps.intents.out`
  scoped to declared packages.
- **Status:** not built.
- **Rules:**
  - The host builds the `Intent`.
  - A provider never claims the game file for `open_with` (12a rule 1);
    it is a *player*, chosen where players are chosen.

**B2 Per-game settings.** EP `launch.game_settings@1`. Risk low.
- **For:** rows on a game's own settings page (resolution scale, a patch
  toggle, a per-game core option). This is the per-entry form of C1.
- **Ops:** `rows {entry} → CatalogItem[]` and `set {entry, key, value}`.
- **Storage:** values live in the host's per-entry store under the
  plugin's namespace, so they survive the plugin's own data being
  cleared.
- **Surfaces:** G: the game's settings in the detail screen; A: the
  same; D: the object properties.
- **Permission:** none beyond approval.
- **Status:** not built (`settings_rows` has a `target` arg that is
  reserved for this).

**B3 Launch hooks.** Hook `launch.hooks@1`. Risk medium.
- **For:** acting around a session: pull saves before a game, set a
  performance profile, pause a sync daemon, switch the audio route,
  record playtime elsewhere.
- **Hooks:**
  - `pre_launch {entry, player}` → `{proceed | veto(reason) |
    job(capability,args)}`, with a 2 s budget.
    - A timeout means proceed.
    - A job reply shows a blocking progress sheet with **Skip**, so the
      user can always launch.
  - `post_exit {entry, player, durationS, exitReason}`, with no budget
    because it is not awaited; a returned job runs in the background.
- **Events:** `game.launching` and `game.exited`, the observe-only
  forms.
- **Surfaces:** G: the pre-launch sheet; A/D: the same sheet on
  `GameLaunchActivity`.
- **Permission:** `provide:launch.hooks` and `library.history`.
- **Status:** not built.
- **Rules:**
  - A veto shows the plugin's name and reason.
  - The user can always choose "Launch anyway".

**B4 In-game overlays.** EP `runtime.overlay@1`. Risk medium.
- **For:** information and actions during play, such as achievement
  toasts, a performance HUD value or a chat line.
- **What the plugin supplies:** data only (text, value, icon id,
  priority). droidtop draws it in its own overlay window and on the
  companion screen (§4d). A plugin never gets a window.
- **Ops:** `snapshot {session} → items[]`, pulled on droidtop's schedule
  (at most 1 Hz), and `action {id}`.
- **Surfaces:**
  - G: the Quick Menu in-game panel, the toast lane and the companion
    screen;
  - A: —;
  - D: the taskbar tooltip for a running game.
- **Permission:** `provide:runtime.overlay`, and `overlay.toast`
  (normal, rate-limited).
- **Status:** not built.

**B5 Controller mapping.** EP `input.mapping@1` plus the standard
interface `input.remap`. Risk medium.
- **For:** per-game and per-player controller profiles: provide
  profiles, and apply them through a bridge such as Key Mapper (F1).
- **Ops:**
  - EP: `profiles {entry, device} → [{id, label}]`;
  - `apply {profileId}`, which goes to the resolved `input.remap`
    provider.
- **Surfaces:** G: Quick Menu → Controls, and the game settings; A:
  settings; D: the input settings for a container app.
- **Permission:** `provide:input.mapping`, `input.devices`, and
  `input.remap.apply` (a standard-interface permission).
- **Status:** not built.
- **Rules:** the controls model follows each player's own input model
  (the standing directive), and plugins supply maps only.

**B6 Performance stats.** API `perf.read@1` plus EP `perf.source@1`.
Risk low.
- **For:** frame rate, CPU, GPU, temperature, battery draw and memory,
  for a HUD (B4), a logger or a governor plugin.
- **Ops:**
  - API: `sample() → {cpu, gpuFreq, tempC, batteryMw, mem, fps?}`, with
    fps only where droidtop can know it (a windowcast or container
    session);
  - EP: `sample()` for plugins that can read more (a root provider
    through `priv.shell`).
- **Surfaces:** G: the Quick Menu performance panel; D: a tray item
  (C8).
- **Permission:** `perf.read` (normal).
- **Status:** not built.

**B7 Performance profiles.** API `perf.profile@1`. Risk high.
- **For:** setting CPU/GPU governors or a fan curve.
- **How:** it needs privilege, so it is always routed through
  `priv.shell` providers. The host defines the profile schema so that
  the UI stays one.
- **Surfaces:** G: Quick Menu → Performance.
- **Permission:** `perf.profile.set` (dangerous), plus the caller's own
  `priv.*` grant.
- **Status:** not built.

**B8 RetroArch's network commands.** API `retroarch.command@1`. Risk low.
- **For:** controlling the RetroArch that runs the game: save and load
  states, the slot, fast-forward, shaders, RetroArch's own FPS line (the
  RetroArch manager's rows on the companion's Game tab, C15).
- **Ops:** `command {command}` sends one of RetroArch's own hotkey commands
  (command.h `map[]`; droidtop's list in `RetroArchCommands.ALLOWED`: no
  QUIT, which is droidtop's own Quit, and no memory reads) as a UDP line to
  port 55355 on the loopback address, then asks `GET_STATUS` and waits half
  a second. Reply `{sent, answered, state?, system?, content?}`; `answered`
  false means RetroArch did not reply (its network commands are off, or it
  is not running). RetroArch cannot be asked for its state slot, so a
  plugin keeps count of the slot changes it made. `status {}` asks
  `GET_STATUS` alone: `{answered, state?, system?, content?}`, for a panel
  deciding what to show.
- **Why droidtop sends it:** a contained plugin has no network (5.3), and
  this needs no more than the loopback address and one port.
- **Permission:** `retroarch.commands` (normal).
- **Status:** built (2026-10-09).

### C. UI extension (always in context, always drawn by droidtop)

**C1 Settings rows and pages.** EP `ui.settings@1`. Risk low.
- **For:** a plugin's configuration, rendered as droidtop settings rows.
- **Targets:**
  - `plugin`: the plugin's own page under Accounts and sources →
    Plugins → <plugin>;
  - `system:<id>`;
  - `entry:<id>`, which is B2;
  - `section:<registry id>`: a row inside an existing droidtop screen,
    for example Network.
- **Ops:**
  - `rows {target} → CatalogItem[]`: toggle, choice, text, action, link
    to a sub-page, read-only info;
  - `set {target, key, value}` and `action {target, key}`.
- **Surfaces:**
  - G: `GamingSettingsCatalog`;
  - A: the Preference renderer;
  - D: the Settings app in the desktop.
  All three are the same catalog.
- **Permission:** none.
- **Status:** built as `settings_rows` (read-only rows, `target=global`),
  in `PluginSettingsRows`.
- **Rules:**
  - Every row carries the plugin's name chip.
  - A plugin cannot create a password field; secrets go through G1 and
    G2.

**C2 Quick Menu tiles.** EP `ui.quick_tile@1`. Risk low.
- **For:** a toggle or a value in Gaming's Quick Menu and in Android's
  quick-settings-like surfaces: "VPN on", "Sync now", "Fan: quiet".
- **Ops:** `state → {label, value, on?, icon}` and `toggle` or `action`. The label is cut to 32 characters and the value to
  16 (`tileLabel`, `tileValue` in docs/view-limits/plugin-view-limits.json), with an ellipsis: a tile is a host row
  of fixed height (Droidtop/tracker#644).
- **Surfaces:**
  - G: the plugin's panel in the Quick Menu (C17), first rows;
  - A: an `android.service.quicksettings.TileService` slot that droidtop
    owns (P2);
  - D: the tray (C8).
- **Permission:** none.
- **While a game runs (2026-10-15, Droidtop/tracker#544 item 6).** A tile
  entry (`ui.quick_tile` or `ui.status_tile`) may declare
  `"whileGame": true`: it is shown only while a game is running. With
  `"gamePackages": ["com.retroarch", ...]` (the `ui.panel` entry's field) it
  is shown only while one of those apps runs the game. A tile with neither
  is shown whatever runs. RetroArch's Save state and Fast-forward are the
  case: `{"point": "ui.quick_tile", "id": "save_state", "gamePackages":
  ["com.retroarch", "com.retroarch.aarch64"]}`.
- **On a widget (2026-10-16, Droidtop/tracker#563).** A tile whose
  `surfaces` is empty or names `android.widget` can be put on droidtop's
  widgets (C10). Its state is asked with the same `state` op and budget,
  at most once every 30 s, and a press on the widget is the same `toggle`
  or `action`, with `context.surface` `android.widget`, the 15 s budget,
  and no UI: the reply's `message` is shown as a short note naming the
  plugin, and a reply view is not opened from a launcher. A tile with
  `whileGame` or `gamePackages` is never offered for a widget.
- **Status:** built in the Quick Menu; since 2026-10-07 its rows are in
  the plugin's panel. A `status_tile` counts as a read-only quick tile
  (§6). Widget calls built 2026-10-16 (`PluginWidgets`).

**C3 Status tiles and home widgets.** EP `ui.status_tile@1`. Risk low.
- **For:** ambient readings (network or VPN state, a sync status).
- **Ops:** `state → {label, value, severity}`.
- **Refresh:** droidtop decides when (widget update, Quick Menu open) and
  the plugin never runs its own loop.
- **Surfaces:**
  - G: the plugin's panel in the Quick Menu (C17), and its value on the
    plugin's row of the Plugins list; the companion screen;
  - A: droidtop's widgets (C10); `PluginStatusWidgetProvider` until it
    moves onto them;
  - D: the tray.
- **Permission:** none.
- **Status:** built (`status_tile`: the widget and the panel; the
  settings test row is gone, 2026-10-07).

**C4 Context actions.** EP `ui.context_action@1`. Risk medium.
- **For:** "do X with this" on a game, an app, a file or folder, a
  system, a container or a window.
- **Static filter** (in the manifest): `targets: [game|app|file|folder|
  system|container|window]`, plus `systems`, `mimeTypes`, `packages`.
  droidtop decides visibility without loading the plugin; that is the
  performance rule.
- **Ops:**
  - `enabled {target}`, called only when the menu opens, with a 500 ms
    budget; on a timeout the action is shown enabled;
  - `run {target}`, a quick call or a **job**.
- **Surfaces:**
  - G: the game's detail screen and Select/Options menu;
  - A: the launcher's long-press app menu (the `launcher3` popup,
    hooked, never rewritten: the vendored-tree rule) and the detail
    screen;
  - D: file manager and taskbar window menus.
- **Permission:**
  - `provide:ui.context_action`;
  - the target's data needs its own grant: a file target hands a
    read-only fd scoped to that file, and a game target needs
    `library.read`.
- **Status:** built on the game's or app's detail screen, the PC game page,
  the PC game menu (Y) and an app's Select menu in Gaming, Standard's
  long-press app menu and Desktop's Start menu (section 6). The gamelist's
  own options (Select on a list) act on the list, not one game, so they
  carry none.

**C5 Search providers.** EP `ui.search@1`. Risk medium.
- **For:** results in droidtop's global and per-section search: games
  (A2 covers acquire), web answers, container apps, files, settings.
- **Ops:**
  - `query {text, section} → [{title, subtitle, icon, action}]`, with a
    1.5 s budget, debounced;
  - `run {resultId}`.
- **Surfaces:**
  - G: global search and section search (#12);
  - A: the launcher QSB results;
  - D: desktop search (Start menu).
- **Permission:** `provide:ui.search`. The query text is personal, so
  the grant screen says "sees what you type into search".
- **Status:** not built (#12 covers plugin results from Sources).

**C6 Notifications.** API `notify@1`. Risk low.
- **For:** telling the user something outside the moment: a download
  finished, an update is available, a sync failed.
- **Ops:**
  - `post {title, text, actions[≤2], progress?}` and `cancel {id}`;
  - an action tap comes back as `handle` with `point: "notify.action"`.
- **Surfaces:**
  - G: the in-shell toast lane and droidtop's notification list;
  - A: an Android notification on droidtop's per-plugin channel (the user
    can mute each plugin in Android settings);
  - D: a container-side notification through the host bridge.
- **Permission:** `notify.post` (normal), rate-limited (§8). droidtop holds
  Android's `POST_NOTIFICATIONS` for it (§4.1, "Android permissions").
- **Status:** built in part (2026-10-08, Droidtop/tracker#378): `post {title,
  text}` → `{posted}` as an Android notification in the plugin's own
  channel, at most 5 an hour; droidtop asks Android for its permission on
  first use during a call the person started. `actions`, `progress`,
  `cancel` and the G and D surfaces are not built.
- **Rules:**
  - Every notification is attributed to the plugin.
  - There is no full-screen intent and no heads-up by default.

**C6a Toasts.** API `ui.toast@1`. Risk low.
- **For:** a short message in the moment, Decky's `toaster.toast`: "Synced",
  "Tunnel up".
- **Ops:** `show {text}` returns `{shown}`. Text is cut to 200 characters.
- **Surfaces:** droidtop's corner flash (docs/SPEC.md 7k), always led by
  the plugin's name so it is never mistaken for droidtop's own; about 80
  characters are drawn and the whole text is kept in Recent (the Quick
  Menu's Notifications tab). While no droidtop surface is on screen (a
  game) it is a platform toast, still led by the name.
- **Permission:** `overlay.toast` (normal), rate-limited like every
  broker call (§8).
- **Status:** built (2026-10-07).

**C6b Confirm.** API `ui.prompt@1`, op `confirm`. Risk low.
- **For:** a yes-or-no question before a plugin does something the person
  should agree to.
- **Ops:** `confirm {title, subtitle?, details?, tone: info|caution|danger,
  proceed, cancel?}` returns `{confirmed}`. Drawn on droidtop's confirm
  card (the danger dialog's shape with no danger: Cancel first and
  focused), named after the plugin, with no second press to arm.
- **Rules:** only during a call the person started; a title that is one of
  droidtop's own notice titles is refused (`INVALID_ARGS`); limits as the
  notice node's. The `text` op and its `REFUSED`/`BUSY` answers come with
  the text-entry work (Droidtop/tracker#485, slice 9).
- **Status:** built (2026-10-14).

**C7 Themes and theme assets.** EP `theme.pack@1`. Risk medium.
- **For:** ES-DE themes, icon packs, sound packs, wallpapers,
  controller-glyph sets and fonts.
- **Ops:** `list → [{kind, id, label}]` and `files {id} → fd list`.
- **Surfaces:**
  - G: Theme settings (the real ES-DE engine, §7f);
  - A: the launcher icon pack and wallpaper pickers;
  - D: the desktop theme or wallpaper.
- **Permission:** `provide:theme.pack`.
- **Status:** not built. Themes today come from the theme downloader.
- **Rules:**
  - Theme XML and images are untrusted input. They are parsed by the
    same engine as downloaded themes, with its limits.
  - A theme can never run code.

**C8 Desktop taskbar and tray items.** EP `ui.tray@1`. Risk low.
- **For:** a persistent indicator with a menu in Desktop mode.
- **Ops:** `state → {icon, tooltip, menu[]}` and `menu_action {id}`.
- **How it renders:** droidtop publishes each item container-side as a
  StatusNotifierItem through host-bridge, so it appears in the
  container's own taskbar (§2a: the taskbar is container-side).
- **Surfaces:** G: —; A: —; D: the taskbar tray.
- **Permission:** none.
- **Status:** not built.

**C9 File-manager actions and "open with" handlers.** EP
`files.handler@1`. Risk high.
- **For:** opening, previewing or converting a file type droidtop
  otherwise cannot handle (§12's `open_with` rules extended to plugin
  code). Examples: a PDF manual viewer, a patch applier for
  `.ips`/`.bps`, an archive browser.
- **Ops:**
  - `open {file fd, mime}`, which returns a host-renderable result
    (text, an image list, a page list) or a **job**;
  - `convert {fd} → job`, which writes output only to a host-chosen
    folder.
- **Surfaces:**
  - G: the chips on a game's detail screen;
  - A: droidtop's file actions;
  - D: the file manager's "Open with" menu and default-app associations
    (E4).
- **Permission:** `provide:files.handler`, and `files.shared.write` only
  if it writes beyond the host-chosen output folder.
- **Status:** not built (JSON `open_with` is built).
- **Rules:** never the game file itself (12a rule 1); nothing is ranked
  or auto-picked (rule 3).

**C10 Widgets.** EP `ui.widget@1`. Risk low.
- **For:** a small live panel on a launcher's page, the desktop surface
  or the companion screen: a clock, a system monitor, now playing, a sync
  status (Droidtop/tracker#563: a plugin's integration on Niagara's or any
  launcher's pages).
- **What the plugin supplies:** data only, in one of a closed set of
  templates that droidtop draws. Every template maps to views an Android
  app widget can hold, so no plugin code runs in a launcher's process and
  there are no free-form views.
- **Ops:**
  - `snapshot {size: {widthDp, heightDp}}` → a template. droidtop asks at
    most once every 30 s for one widget size (sooner only right after a
    press on it), with a 5 s budget; a miss, a failure or a template
    droidtop cannot draw keeps the last snapshot and is not a crash.
    `context.surface` says where it is drawn (`android.widget` on a
    launcher).
  - `action {id}`: a tap. Only an id the last snapshot offered is sent;
    15 s; the reply's `message` is shown as a short note naming the
    plugin, and a reply view is not opened from a launcher. The widget
    is drawn again afterwards.
- **Templates** (`data.template`; text is cut at 60 characters for a
  title, 140 for a subtitle or `empty`, 40 for a value or label):
  - `value {title, value, subtitle?, severity?}`; `value` may be a typed
    value (1.6, "Typed values").
  - `list {title?, items[{id, title, subtitle?, value?, action?}], empty?}`:
    at most 20 items, each `id` once; `empty` is the plugin's own line for
    no items.
  - `gauge {title, percent, label?}`: `percent` is 0 to 100, or -1 for
    unknown.
  - `media {title, subtitle?, image?, buttons?[{id, label}]}`: at most 3
    buttons.
  - `image {image, title?}`.
  - Any template may carry `action {id}`, a tap on the whole widget; a
    list item's `action` and a media button's `id` are the others. An id
    is 1 to 64 of `A-Z a-z 0-9 . _ : -`.
  - An `image` is `{data, type}`: base64 PNG, JPEG or WebP, at most
    96 KiB. droidtop checks that the bytes are what `type` says and
    decodes them once, at most 512 px on the longer side. A widget never
    fetches a URL.
- **Which entries can be on a widget:** a `ui.widget` entry, and C2 and
  C3 tiles, whose `surfaces` is empty or names `android.widget` (the
  filter every tile surface reads). The widget maker lists them from
  manifests without loading a plugin.
- **Surfaces:**
  - A: droidtop's widgets on any launcher's pages (Droidtop/tracker#563),
    through the one widget renderer;
  - G: companion screen panels (§4d), not built;
  - D: the desktop surface, not built.
- **Permission:** none.
- **Status:** built for Android app widgets (2026-10-16,
  `PluginWidgetProtocol`, `PluginWidgets`, `PluginWidgetActionReceiver`);
  the widget renderer and maker are the UI lane's (#563).

**C11 Gaming-shell sections and rows.** EP `gaming.rows@1`. Risk medium.
- **For:** a row on Gaming's home or a section, such as "New on
  <source>", "Continue on <other device>" or "Friends playing".
- **Ops:** `rows {section} → [{title, items[{title, art, action}]}]`,
  cached by the host and refreshed at most every 15 minutes or on user
  refresh.
- **Surfaces:** G: droidtop's own surfaces only (Quick Menu, a game's
  page, options menus), never inside a themed view, until the
  frame-only render declares a region for host content (§1.6, #179);
  A: —; D: the Start menu, one section per shelf, asked with
  `context.surface` `desktop.start_menu` (1.9).
- **Permission:** `provide:gaming.rows`, plus `net.domains` for the art,
  which the host fetches.
- **Status:** built for shelves of library entries (2026-10-07,
  Droidtop/tracker#316):
  - op `rows {context:{surface:"gaming.home", library?}}` returns
    `{shelves:[{id, title, entries:["<entry id>"]}]}`
    (`docs/plugin-view.schema.json` `$defs.shelvesReply`);
  - `context.library` lists the entries Home can show (id, title, kind,
    system, favourite; at most 500, most recent first) only when the
    plugin holds `library.read`, and play times only with
    `library.history`;
  - droidtop keeps at most 2 shelves of 24 per plugin, only ids of real
    entries, and draws them after Home's own shelves, titled
    "<title>, from <plugin>";
  - the answer is kept for 15 minutes, so a library change re-reads it
    and never waits on the plugin; a plugin that fails has no shelf;
  - items that are not entries (actions with art) are not built.
- **Rules:**
  - Items that are library entries must be real entries (A10).
  - Other items are actions, never fake games.

**C12 Launcher app-drawer and home actions.** EP `launcher.actions@1`.
Risk medium.
- **For:** Android-mode additions: a drawer folder source such as "Games
  by system", a long-press action on any app (C4 with target `app`), a
  home-screen gesture action.
- **Ops:** `drawer_groups → [{label, packages[]}]` and `gesture_action
  {gesture}`.
- **Surfaces:** A: the drawer, long-press and gesture bindings, hooked
  into the fork's existing extension points; G: —; D: —.
- **Permission:** `provide:launcher.actions`, and `apps.list` for any
  group that needs the package list.
- **Status:** not built (12a notes the drawer long-press is not wired).

**C13 Onboarding steps.** EP `onboarding.step@1`. Risk high.
- **For:** an official or approved plugin adding one step to first-run or
  "set up again", for example "pair Shizuku" or "choose your sync
  folder".
- **Ops:** `step → {title, body, rows (C1 items), skippable: true}` and
  `done?`.
- **Surfaces:** onboarding (§7b), in all modes.
- **Permission:** `provide:onboarding.step`, allowed for official
  origins only. Onboarding is where users trust most, so a third-party
  step would be a spoofing vector.
- **Status:** not built.
- **Rules:** every step is skippable.

**C14 Dialogs, prompts and forms.** API `ui.prompt@1`. Risk low.
- **For:** a plugin asking the user something *in response to a user
  action*: confirm, choose, enter text, show progress, show a QR code
  or pairing code.
- **Ops:**
  - `confirm`, `choose`, `text {hint, secret:false}`, `progress`,
    `code {text, qr}`;
  - every op is only allowed while a user-initiated call is in flight
    (the host tracks this).
- **Surfaces:** a host-drawn, gamepad-navigable sheet with a hint row,
  in G, A and D alike.
- **Permission:** none. A prompt outside a user-initiated call is
  refused.
- **Status:** not built (`app_status` text-entry jobs are one special
  case of this).
- **Rules:** there is never a secret text field; those go through G1 and
  G2.

**C15 Companion screen panels.** No point of its own: a plugin's `ui.panel`
(C17) is its companion panel too (decided 2026-10-09, Droidtop/tracker#414
slice C9; the `ui.companion@1` point once listed here was never built and is
dropped, one panel per plugin).
- **For:** content and controls for the companion screen (SPEC "The
  companion's tabs"): a stream's controls, save and load for an emulator, a
  chat, a performance card.
- **Where:** the companion's Plugins tab lists every panel with its abilities
  on one line; a person can put one panel on the companion's bar as a tab of
  its own; the panel's tiles (C2, C3) can be pinned on the companion's Home.
  The call's `context.surface` is `<mode>.companion` (`gaming.companion`,
  `standard.companion`, `desktop.companion`); `context.game` is the running
  game under the `library.read` rule, as in the Quick Menu.
- **On facets (Droidtop/tracker#602).** A plugin whose `ui.facets` entry
  declares `companion.tab` is drawn on the companion from those facets, never
  from its `ui.panel` view: `get {target: "companion.tab", subject}` with the
  device as the subject on its tab and the Plugins tab, and with the running
  game (under the `library.read` rule) for its rows on the Game tab. Its
  abilities and `gamePackages` go on that `ui.facets` entry, as below. A
  plugin that declares only `quickmenu` is drawn on the companion from its
  `quickmenu` facets, as before.
- **Abilities**, declared on the `ui.panel` entry (or the `ui.facets` entry
  that declares `companion.tab`) as `"companion": [...]` and
  shown on the panel's line before it is opened (unknown names are ignored):
  - `game`: the panel has rows for the running game. The companion's Game tab
    asks the panel with `context.surface` `<mode>.companion_game` and draws
    the rows under its own (not in Kid or Kiosk). With `"gamePackages":
    [...]` on the entry it asks only while one of those apps runs the game.
  - `keep_on`: the companion screen stays on while the panel shows.
  - `recording`: the plugin may raise droidtop's Recording state through the
    host API below.
- **Asking first:** a `button` with `confirm` asks through droidtop's one
  rule (SPEC "The companion's tabs", Game): while "Ask before load and
  overwrite" is on (the default), and always in Kid and Kiosk. A plugin
  flags Load state and a save over a filled slot this way.
- **Host API** `companion.recording@1 {on, sinceMs?}`: a recorder says it
  started (`sinceMs`, when it began; never later than now) or stopped. The
  companion's status line then shows "Recording 1:05" and speaks it once,
  with the plugin's name. Refused unless the caller's `ui.panel` declares
  `recording` and that point is on; cleared when the plugin is turned off,
  disabled or removed. droidtop records nothing itself.
- **Expected companion plugins:** the RetroArch manager (Game rows: save
  state, load state, slot up and down, fast-forward, shader on/off, next and
  previous; tiles to pin) and windowcast (stream rows such as bitrate and
  latency, send Ctrl+Alt+Del, a Performance card, and the Recording state
  while it records locally).
- **Permission:** none beyond the approval of `ui.panel`.
- **Status:** built (2026-10-09, `PluginPanels.surfaceCompanion`,
  `CompanionAbilities`, `PluginRecording`).

**C16 A plugin's own full-screen UI.** EP `ui.main@1`. Risk low.
- **For:** a plugin that has a complete UI of its own to configure and use
  (1.7).
- **What the plugin supplies:** `entrypoint` (and optionally `library`),
  the Dart function started on the hosted engine.
- **Surfaces:** G, S, D: an "Open <plugin name>" row on the plugin's page;
  G: also on the download source's row in the Gaming search.
- **Kinds:** `flutter_embed` only (1.7).
- **Permission:** none beyond the approval of the point itself.
- **Status:** built (2026-10-01).

**C17 Quick Menu panel.** EP `ui.panel@1`. Risk low.
- **For:** the plugin's control and configuration point (1.8): what Decky
  calls a plugin's QAM panel.
- **Static fields:** `label` (the row's name in the Plugins list; the
  plugin's label when absent).
- **Ops:** `panel {context:{surface, game?}}` returns a view (1.6); the
  view's actions come back to this point. `surface` is
  `gaming.quick_menu`, `standard.home`, `desktop.taskbar`, `<mode>.companion`, `<mode>.companion_game` (C15) or `settings`; `game` is the running game's
  `target` under the `library.read` rule (C4).
- **Surfaces:** G: the Quick Menu's Plugins section; A: "Plugins" on the
  home screen's long-press menu; D: "Plugins" on the taskbar (1.9); G, S, D: a "Panel"
  row on the plugin's page under Settings, and the companion screen (C15).
- **Permission:** none beyond the approval of the point.
- **Status:** built (2026-10-07, `PluginPanels`).

**C18 Rows on a game's page.** EP `ui.game_section@1`. Risk medium.
- **For:** facts and actions a plugin has about one game: a save-sync
  state, a patch's status, a thread's last version.
- **Static fields:** `id`, `label`, `tab` (`overview`, `versions`,
  `extras`, `details`; Extras when absent) and the C4 filter (`targets`,
  `systems`, `packages`), decided without loading the plugin.
- **Ops:** `section {context:{target, sectionId}}` returns a view, asked
  once when the page opens (5 s budget) and again after a row ran
  something.
- **Surfaces:**
  - G: the PC game page draws every node as a row of its own under the
    tab named (a fact takes no A; a call or job runs in place with its
    status in the value column; a `view` node, or an input, opens the
    section as a page). Every row's tooltip names the plugin. A section
    that fails is one row saying so. Context actions (C4) on the same
    game are rows under Extras there too.
  - G: the console game screen shows each section as a chip that opens
    it as a page.
- **Permission:** `provide:ui.game_section`; the game's identity is in
  `target` only with `library.read`, as for C4.
- **Status:** built (2026-10-07, `PluginGameSections`).

**C19 Friends and chat.** EP `social.provider@1`. Risk medium.
- **For:** a service's friends, presence and 1:1 chat (Discord, Matrix,
  another store) beside Steam's, in droidtop's one Social place
  (docs/SPEC.md "Social", Droidtop/tracker#327).
- **Static fields:** `id` (one account; `default` when absent; a plugin
  with two accounts declares two entries) and `label` (the service's
  name, shown as the friends' badge; the plugin's label when absent).
- **Ops** (each a quick call, 5 s budget; a miss or an error reply keeps
  what droidtop last had and is not a crash; every call's `args` carry
  `service`, the entry's `id`):
  - `account` returns `{link: online|connecting|reconnecting|offline,
    name?, presence?: online|invisible|offline}`; no `presence` means the
    service has no standing to set, and no status choice is drawn;
  - `friends` returns `{friends: [{id, name, state: online|away|busy|
    in_game|offline, activity?, unread?, lastMessageMs?}]}` (at most 2000;
    the first of a repeated id wins; an unknown state reads as offline);
  - `conversation {friendId, read}` returns `{messages: [{key, mine, text,
    timeMs}]}` (the newest 200 are kept, ordered and de-duplicated by
    droidtop); `read: true` means the person opened it, so the plugin marks
    it read with its service;
  - `send {friendId, text}` returns `{message?}`; an error reply's message
    is shown on a "Not sent" row;
  - `presence {presence}` sets the person's standing.
- **Host API** `social.changed@1 {service?, friendId?, message?: {key,
  text, timeMs}}` returns `{accepted}`: a plugin with a live connection
  says a friend or a conversation changed, so droidtop never polls it.
  Refused (PERMISSION_DENIED) unless the caller declares `social.provider`
  and the person has not switched that point off, the same
  `PluginGrants.pointRefusal` every host call to a plugin passes. droidtop
  then asks `account` and `friends` again, and `conversation` when that one
  is open. A `message` for a conversation that is not open becomes a
  notification only when the plugin holds `notify.post`; without it the
  unread count still moves.
- **Surfaces:** G: the Social place (a left-menu place), the Quick Menu's
  Social tile (the unread count over every provider), the companion's
  Social tab; the notifications. Every row is droidtop's own: the plugin
  never draws one.
- **Permission:** the approval of the point ("Friends and chat"), and
  `notify.post` for notifications. Staying connected in the background
  needs `jobs.service` (E8).
- **Status:** built (2026-10-08, `PluginSocialProviders`, `SocialHub`).

### D. System and device

Every host API in this group is run by droidtop's own code with
droidtop's own Android permissions, gated per plugin by the broker.

| Id | Group | For | Ops and events | Surfaces | Permission | Risk | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| D1 | Network state | adapt to connectivity; VPN/hotspot tiles | `net.state()` → {type, metered, vpn, ssid?}; event `net.changed` | data | `net.state` (normal; SSID needs `net.wifi_details`, dangerous: location-grade) | low | built (2026-10-08, `net` `state` → {online, type, metered, vpn}); SSID and `net.changed` not built |
| D2 | Network access | talk to the internet | `net.http {method, url, headers, body \| bodyBase64, timeoutMs, as}` → {status, url, headers, body \| bodyBase64, truncated} (each body ≤ 128 KiB: it travels inside the reply cap); `net.download {url, name}` → {jobId}, a job into the plugin's own data (H1), read with `plugins.job_status` (`done`, `status`, `percent`) and stopped with `plugins.job_cancel` | data | `net.domains` (declared list, normal) / `net.any` (dangerous) / `net.local` (LAN, dangerous) | medium to high | built (2026-10-08, `HostNetApis`): the URL alone picks the permission before any name is looked up; https only outside the local network; redirects followed by droidtop with every hop checked; a declared name that resolves to the local network needs `net.local`; every request logged with its host |
| D3 | Storage and volumes | free space, removable media, where a library folder lives | `storage.volumes()` → [{id, label, removable, freeBytes}]; events `storage.mounted` / `unmounted` | data | `storage.volumes` (normal) | low | built (2026-10-08, `storage` `volumes`, with `primary`, `state`, `totalBytes`); the mount events are not built |
| D4 | File picker (SAF) | let the user choose a file or folder for the plugin | `files.pick {mode, mime}` → a grant token; `files.open {token}` → fd | the system picker | `files.picker` (normal; the user's pick is the consent) | low | built (2026-10-08): `files.pick {mode: open \| create, mime, name}` → {picked, token, name, writable}, only during a call the person started; `files.open {token, mode}` → fd; `files.forget {token}`; folders are not offered |
| D5 | Shared files | broad access to shared storage | `files.shared` `list`, `read`, `write`, `mkdir`, `delete`, `open` `{path, volume?}`, `path` inside one of the folders the permission entry declares (`paths`, `"*"` for all) | data | `files.shared.read` / `files.shared.write` (dangerous; droidtop holds MANAGE_EXTERNAL_STORAGE) | high | built (2026-10-08): never `Android/data` or `Android/obb`, checked again after links are followed, and only while droidtop itself has all-files access |
| D6 | Clipboard | copy a code or link; read for paste-to-search | `clipboard.write {text}`; `clipboard.read()` only during a user-initiated call | data | `clipboard.write` (normal) / `clipboard.read` (dangerous) | low / high | not built |
| D7 | Power and battery | defer work on low battery; power profiles | `power.get_state()` → {level (0 to 100, null without a battery), charging, saver}; `power.keep_awake {≤10 min}` (only while a job runs); events `power.low`, `power.charging` | data | `power.state` (normal) / `power.keep_awake` (normal, quota) | low | `power.get_state` built (2026-10-16, Droidtop/tracker#553: read from the battery broadcast and Battery Saver; a plugin reads it instead of `/sys/class/power_supply`); `keep_awake` and the events not built |
| D8 | Display | know the displays (internal, companion, external) to place content | `display.list()` → [{id, role, size, density, hdr}]; events `display.added` / `removed`; `display.set {brightness}` | data; C15 renders | `display.info` (normal) / `display.control` (dangerous) | low / medium | not built |
| D9 | Audio routing | route or duck audio per session | `audio.routes()`, `audio.set_route {id}`, `audio.volume {stream, level}`; event `audio.route_changed` | G Quick Menu | `audio.control` (normal); **no microphone API** (P2 decision: `audio.record` would be critical and needs a real use) | medium | not built |
| D12 | Network links | a plugin with its own sockets (`net.sockets`, §5.3 "Open processes") binds, announces and resolves names itself | `net.get_links()` → {links: [{name, type, active, metered, addresses: [{address, prefixLength}], dns: [...], searchDomains: [...]}], dns: [...]}; the active link first, top-level `dns` its servers | data | `net.sockets` (dangerous) | low | built (2026-10-15, Droidtop/tracker#553; `NetLinks`, read from Android's `LinkProperties` of every network up) |
| D10 | Media session | now playing / transport control (Spotify-style, §7e) | `media.sessions()`, `media.control {id, action}`; event `media.changed`; EP `media.source` for a plugin that *is* a player | G companion; A widget | `media.sessions` (dangerous: sees what other apps play) | medium | built-in only (§7e) |
| D11 | Input devices | controllers, keyboards, mice: which are connected, their layout | `input.devices()` → [{id, name, vendor, kind}]; events `input.connected` / `disconnected` | data | `input.devices` (normal) | low | not built |
| D12 | Input injection | synthetic input (macros, remapping without Key Mapper) | `input.inject {events}`: **not offered by the host**; only through a `priv.*` provider's `input.remap` | — | provider permission (critical) | critical | not built, by design |
| D13 | Bluetooth | list and connect controllers or headsets | `bt.devices()`, `bt.connect {address}`; events | A/G settings rows | `bt.devices` (dangerous: Android treats it as nearby-device access) | high | not built |
| D14 | USB | talk to a USB device (flash cart, adapter, dongle) | `usb.devices()`, `usb.open {device}` → the host asks Android's own per-device permission dialog, then hands over an fd | data | `usb.devices` (dangerous) | high | not built |
| D15 | Sensors | tilt controls, ambient light | `sensors.list()`, `sensors.subscribe {type, rateHz≤60}` → a stream delivered as job progress | data | `sensors.read` (normal) | low | not built |
| D16 | Vibration | haptic feedback for a plugin action | `vibrate {pattern ≤2 s}` | — | `vibrate` (normal) | low | not built |
| D17 | Locale and time | format for the user; plan scheduled work | `locale()`, `time()` → {tz, 24h}; event `locale.changed` | data | — | low | not built |
| D18 | Accessibility bridge | read the screen or act for the user (e.g. an auto-skipper, a TTS reader of game text) | `a11y.*`: **host only exposes the droidtop-owned service's events for droidtop's own windows** | — | `a11y.bridge` (critical, official origins only) | critical | not built; §10 Q3 |
| D19 | Location | region-aware stores, time zones | not offered (droidtop holds no location permission; a plugin that needs it is the wrong shape) | — | — | — | not offered |
| D20 | Camera | QR pairing | not offered by the host; QR *display* is C14; scanning goes to an app bridge | — | — | — | not offered |
| D21 | Paired devices | a plugin that keeps the person's devices in step with its own counterparts on them (the Syncthing plugin accepts exactly the devices droidtop paired, so its own pairing is never shown) | `devices.list_paired()` → {devices: [{id, name, kind: "computer"\|"handheld", pairedAt, online, announced}]}, `announced` being what the same plugin id announced on that device or null; `devices.announce {value}`: an object of at most 4 KiB (null withdraws it) sent to every paired device, kept there per device, answered at once while droidtop sends it; event `devices.changed` (a device paired or forgotten, or a value it announced changed) | data | `devices.paired` (dangerous) | medium | built (2026-10-16, Droidtop/tracker#556, #553; `PairedDevices` over droidtop-agent build-67's `announce`, `serve_start` and `serve_stop`) |

### E. Desktop and computer

| Id | Group | For | Ops and events | Surfaces | Permission | Risk | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| E1 | Containers and lifecycle | see and drive the Linux/Wine containers (§3) | `containers.list()`, `containers.state {id}`; `start`, `stop`, `create {image ref}` (**jobs**); events `container.started` / `stopped` | D: Containers screen, taskbar; G: PC section | `containers.read` (normal) / `containers.manage` (dangerous) | medium / high | not built |
| E2 | Container exec | run a command inside a container as the container user | `containers.exec {id, argv, env, cwd}` → a job with streamed stdout/stderr | data | `containers.exec` (critical: full access to the user's container home) | critical | not built |
| E3 | Terminal | open a terminal for the user at a place (a container, a game's folder) | `terminal.open {container, cwd}`: the host opens its own terminal; the plugin never reads it | D: terminal window; A/G: a context action (C4) | `terminal.open` (normal: the user sees it and types) | low | not built |
| E4 | File associations | declare that a container app or a plugin handles a MIME type | manifest static `associations: [{mime, ext, label}]` + EP C9 | D: the file manager's defaults; A: droidtop's file actions | `provide:files.handler` | medium | not built |
| E5 | Windowing (through windowcast) | list, focus, move or close app windows; know which window a game runs in | `windows.list()` → [{id, app, title}]; `windows.focus/close {id}`; events `window.opened` / `closed`. **Streaming, capture and remote display are never in droidtop**: they are windowcast's (standing rule), and this API only manages windows droidtop already knows | D: taskbar menus (C8) | `windows.read` (dangerous: window titles leak content) / `windows.control` (dangerous) | high | not built |
| E6 | Printing bridge | print from a plugin, or add a printer (the CUPS bridge, §4b) | `print.submit {fd, mime, options}` → the host shows its own print dialog; `print.printers()` | D/A: the print dialog | `print.submit` (normal: the dialog is the consent) / `print.admin` (dangerous) | low / high | not built |
| E7 | Container package bridge | an "app store" for containers: search and install packages (apt, pacman, Flathub-style) | EP `containers.packages@1`: `search`, `install` (**job**), `remove` (**job**), `updates`; the host runs the package manager through E2 on the provider's behalf | D: the app store or Start menu; G: the PC section's "Get apps" | `provide:containers.packages` + `containers.exec` | critical | not built |
| E8 | Background services and daemons | a long-running plugin task (a sync daemon, a server) | EP `jobs.service@1` entries `{id, label, restart: never\|on_failure}` (default `on_failure`); droidtop starts each as a job (`run {serviceId}`) while the point is allowed and the entry's own switch is on, under one silent foreground notification, and restarts a failed one after 30 s, 2, 10 and 30 min, then leaves it stopped. **One row (2026-10-30, Droidtop/tracker#790):** a failing service keeps one row in Downloads, its last failed run, with Retry, which starts it at once with no failures counted; each new run takes that row's place. A plugin job's failure line is one plain sentence: a message that is a runtime's own words (a class name, an exception, a stack trace, over 200 characters or several lines) becomes "<plugin> couldn't load its native code on this device" or "<plugin> ran into a problem it didn't explain", and its words are the job's Details (`JobFailureLine`); a plugin's own plain sentence stays the line | all modes: Jobs, the notification; switches on the plugin's Permissions screen | `provide:jobs.service` (dangerous: battery and network while you are away; starts unticked) | high | built (2026-10-08, `PluginBackground`, `PluginServicesService`) |
| E9 | Scheduled jobs | periodic work (update checks, a backup) | EP `jobs.schedule@1` entries `{id, label, every: "15m".."30d", charging?, unmetered?}`; a periodic system job (only while one is on) starts each due one as a job (`run {scheduleId}`) when its constraints hold | all modes: Jobs; switches on the plugin's Permissions screen | `provide:jobs.schedule` (normal) | low | built (2026-10-08, `PluginScheduleJobService`) |
| E10 | Inter-plugin IPC | plugins calling each other | **§2**: plugin-provided APIs through the broker are the only channel | — | per exported permission | varies | not built |

### F. Integration with other apps

| Id | Group | For | Ops and events | Surfaces | Permission | Risk | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| F1 | App bridges | a plugin that represents another installed app (Syncthing, Key Mapper, Obtainium, Discord, #15): status, actions, and **droidtop's own fallback when the app is absent** | EP `apps.bridge@1` (today's `app_status`): `status` → `{package, installed?, status?, version?}` (2026-10-15, Droidtop/tracker#544 item 5, #521 item 4): `package` is the managed app, whose name, installed state and version droidtop reads from Android itself; `installed` only when the plugin knows better than Android; `status` one plain sentence about the app's state; `version` when Android does not know it. droidtop draws the app's row (its name, Installed / Not installed, the status sentence, the version, Launch); any other value is never shown as "Key: value", only behind "Technical details"; `action {id}` (quick call or **job**). The entry may name the app (2026-10-16, Droidtop/tracker#520): `{"point": "apps.bridge", "app": "RetroArch", "packages": [...], "get": "https://..."}` (`packages` defaults to the `apps.check` packages). While none is installed, droidtop puts one "<app> isn't installed" notice, with Get, at the top of the plugin's panel, and draws rows and tiles marked `"needs": "app"` inert with the reason; the plugin draws no "not installed" rows of its own | A/G/D: the app's row wherever droidtop shows that app (the Player screen today), Accounts and sources, C3 tiles | `provide:apps.bridge` + `apps.check` (declared packages) + `apps.bind` (for live connections) | medium | built as `app_status` (`PluginAppStatus`) |
| F2 | Intents out | start another app, share to it, hand it a link or a file | `apps.launch {package}` (exists); `apps.intent {package, action, extras(strings), data?}` → the host builds and fires it; `apps.view {uri, title?}` → `{opened, reason?}`: droidtop builds `ACTION_VIEW` for the link and shows Android's own chooser (a magnet to a torrent app, a web page to a browser); the allowed schemes are `https`, `http` and `magnet` only (no `file`, `content`, `intent`, `javascript` or droidtop's own), at most 8192 characters, and only during a call the person started (a button's job); when no app takes the link the answer is `{opened: false, reason: "no app opens magnet links", copied}`, not an error, and **droidtop shows its own fallback** (2026-10-15, Droidtop/tracker#544 item 3): the link is put on the clipboard and droidtop says why and that it is ready to paste; when the person has turned "Open links in other apps" off, droidtop says so (and that it can be allowed in the plugin's Permissions) and copies the link the same way, and the plugin gets the usual `PERMISSION_DENIED`. A plugin only calls `apps.view`: it builds no copy page and no sentence about a refused permission; every use is in the activity log (the kind of link and a web page's host, never a query or a magnet's contents); `apps.share {text \| file token}` → the system share sheet | — | `apps.launch` (normal) / `apps.view` (normal: "Open links in other apps") / `apps.intents.out` (normal for declared packages; dangerous for any package) | medium | partial (`launchApp`, `launchAppWithExtras`; `apps.view` built 2026-10-09, `AppLinks`, Droidtop/tracker#418) |
| F3 | Intents in | let links reach a plugin: a third-party scheme (fdroidrepos://), a site (`f95zone.to`), a path on any host (`/fdroid/repo`), share *to* droidtop; and ops callable from droidtop's own action links (`droidtop://call?v=2&...`, docs/SPEC.md 12a "Links", "Action links") | a `provides` entry's `links: [{scheme, host?, pathPrefix?, pathSuffix?, query?}]` → droidtop's one router (`LinkRouter`) calls `open_link {link}` on that point; `linkOps: [{op, label, description, risk, args}]` → that op, called literally on that point after the person approved the call | "Open this link with" when several handlers match (remembered per kind of link); an action link's call-by-call review, showing the plugin's description and the permissions the call could use | `intents.in` (dangerous: other apps can trigger it) | high | built 2026-10-10 (Droidtop/tracker#459); droidtop declares the scheme aliases, the links-helper APK for other schemes is not built |
| F4 | Installed apps | know what is installed (to find emulators, detect a bridge target) | `apps.check {packages[] declared}`; `apps.list()` → all packages | data | `apps.check` (normal, declared list) / `apps.list` (dangerous: the full list is personal) | low / high | partial (`isAppInstalled`) |
| F5 | Install apps | install or update an APK (an Obtainium-style updater) | `apps.install {file token}` → Android's own installer UI | Android's confirmation | `apps.install` (dangerous) | high | not built |
| F6 | Bound connections | a live service or binder connection to another app | manifest `boundServiceTargets` (exists); the host binds, the plugin exchanges data through the broker (`apps.bound.call {package, method, args}` for AIDL surfaces the host knows) | — | `apps.bind` (dangerous, per package) | high | declared only |
| F7 | Privileged operations | Shizuku / root | **plugin-provided** `priv.*`, `root.*` (§2.7) | — | `priv.shell.adb`, `priv.shell.root`, `priv.packages`, `priv.settings`, `root.modules` (all critical) | critical | host shim only |
| F8 | Context sync | a plugin's supporting state kept in step, both ways, with a program on the person's paired computers (F95Checker's watch list for the F95 plugin is the first) | `context.open {context, fields: [{name, direction, rule}], presence}` → `{records, conflicts}`; protocol 2 (syncable data): `context.publish {context, records}` → `{published, generation, lines, conflicts}`, `context.copy {context}` → `{generation, records}`, event `context.received {context, generation}`; protocol 1: `context.get {context, after?, limit ≤ 200}` → `{records, next, conflicts}`; `context.put {context, key, fields}`; `context.remove {context, key}`; `context.sync {context}` → `{lines, conflicts}`; `context.resolve {context, key, field, keep: device\|computer}` | the plugin's own UI; the computer under Settings → Accounts and sources → Computers | `context.sync` (dangerous: the data travels to your computers and changes an app's data there) | high | built (protocol 1 2026-10-08; protocol 2 2026-10-30, Droidtop/tracker#794; `PluginContexts`, `HostContextApis`; see "F8 Context sync" below) |
| F9 | Apps that start games | another Android app (a frontend, a launcher) asking droidtop to start a library entry; not a plugin, but held to this section's grant model (Droidtop/tracker#124, docs/SPEC.md "Other apps that start games") | the exported `dev.droidtop.app.action.LAUNCH_GAME` intent with `dev.droidtop.app.extra.ENTRY_ID`; the caller is named by `getCallingPackage` or Android 14's shared identity, never the referrer | the first-use sheet (4.3); Accounts and sources > Plugins > Apps that start games (4.4) | `launch.games`, per app (dangerous: asked on first use, Allow / Not now / Never) | medium | built 2026-10-28 (`ExternalGameLaunch`) |

#### F8 Context sync

Owner, 2026-10-08 (Droidtop/tracker#380): "It's context SYNC. It's meant to
allow you to do things on the computer and have them synced." A context is a
plugin's supporting state, not library data. It syncs both ways between the
device and the person's paired computers (docs/SPEC.md 7o "Computers"), so
something done in either place shows up in the other. Example: watch a
thread or mark a version installed in F95Checker on the PC, and the F95
plugin on the device shows it. Do the same in the plugin, and F95Checker's
database on the PC follows.

- **Contexts droidtop can carry.** Each context needs an adapter on the
  computer: a program that reads and writes the other app's own store
  there (droidtop-agent docs/DESIGN.md section 8, adapter protocol 1). The
  plugin publishes it and declares it in its signed manifest, and droidtop
  accepts only the context ids a plugin declares that way.
  **EP `computers.context_adapter@1`. Risk medium.** One `provides` entry
  per context:
  `{"point": "computers.context_adapter", "version": 1, "id": "<context>",
  "label": "<shown name>", "programs": {"windows-x86_64": {"url": "https://...",
  "sha256": "<hex>"}, "linux-x86_64": {...}, "macos-aarch64": {...}}}`
  (system keys are Rust's OS and arch names). `context.sync` sends that offer
  with each sync. A computer without an adapter for the context keeps the
  offer and installs nothing until the person runs `droidtop-agent contexts
  approve <context>` there; it then fetches the program, checks the SHA-256
  and that the program serves that context. Later versions from the same
  plugin follow without asking again. The F95 plugin's adapter is
  gamegrab-sources/droidtop-agent-f95-adapter, context `f95checker`:
  F95Checker's `db.sqlite3`, `games` table, one record per watched thread,
  keyed by thread id. It never reads the `cookies` table or the settings'
  passwords and tokens.
- **Syncable data: the plugin syncs a copy, never its live data (protocol 2,
  2026-10-30, Droidtop/tracker#794; droidtop-agent docs/DESIGN.md section 8).**
  The owner: a syncable context "is an explicit NON-USABLE context ... the
  point is that the sync is atomic, and it's not the live dataset", and "we
  need atomic syncs to prevent corruption".
  - **`context.publish {context, records}`** is the plugin's `updated`. The
    records (`{key: {field: value}}`, at most 5000) become the context's copy
    whole: droidtop writes it beside the current one and renames it over it,
    so nothing ever reads half a copy. That is the next `generation`. The
    context then syncs with every paired computer, and the reply is
    `context.sync`'s plus `published`, the generation published.
  - **Sync by copies.** For each computer droidtop runs the agent's
    `sync_context_copy`: it reads the computer's copy, merges it three ways
    against that computer's baseline (the rules below), and puts the merged
    copy back in one step. A computer whose copy changed in between refuses
    it (`stale`); it is read, merged and put again, three times at most, then
    left for the next sync. The merged records become this device's copy in
    one rename, then the baseline does. A computer that fails, is out of
    reach or is stale changes nothing here or there.
  - **`context.copy {context}`** → `{generation, records}`: the current copy,
    `generation` null before the first publish. Nothing runs from it.
  - **Event `context.received {context, generation}`** (subscribe to it): a
    merged copy that differs from what the plugin last published was made
    current here. The plugin reads it with `context.copy` and brings it into
    its own data when that is safe for it. Only the plugin that owns the
    context hears it.
  - **The plugin's live data never syncs.** droidtop reads only the
    published copy; cookies, tokens and the plugin's own database stay in
    its data folder.
  - On the computer the adapter speaks protocol 2: the agent makes a copy
    current in one rename and runs the adapter's `received`, which brings it
    in when the app there is closed (`deferred` until then; the copy is
    current anyway). A context the plugin has never published keeps
    protocol 1 below, which a protocol-1 adapter still needs.
- **The plugin declares the context** with `context.open`. A record is a map
  of fields. Each field has:
  - a `direction`: `both`, `to_device` (computer to device) or
    `to_computer`;
  - a `rule` for when both sides changed it: `device`, `computer` or `ask`.

  `presence` says which way adding and removing whole records travels. Only
  declared fields travel. A field the computer's adapter cannot write stays
  as it is there. For F95Checker the adapter writes `installed`, `finished`,
  `archived`, `rating` and `notes`, and reads `name`, `url`, `version`,
  `developer`, `status`, `type` and `last_updated` as well.
- **droidtop keeps the records** in `plugins/<id>/contexts/`, outside the
  plugin's own data folder, so every change goes through droidtop's ops and
  the next sync sees it: the copy in `<context>.copy.json`, protocol 1's
  records in `<context>.json`, a baseline per computer. Each is replaced
  whole, written beside it and renamed over it.
- **Protocol 1** (`context.put`, `context.remove`, `context.get`): records
  changed one at a time and merged with the computer's live store through a
  protocol-1 adapter. It stays until every adapter speaks protocol 2; a new
  plugin uses `context.publish`.
- **Change tracking is a three-way merge per field**, against the baseline
  droidtop keeps for each computer and context (the agent core's
  `context::merge`, the same code the computer runs):
  - if only one side changed a field, that change wins;
  - if both changed it differently, the field's `rule` decides;
  - `ask` leaves both sides as they are, and the field is listed under
    `conflicts` until the plugin calls `context.resolve`.

  Records added or removed on one side travel when `presence` allows.
- **A program that keeps its data in memory** (F95Checker does) is written
  only while it is closed. While it runs, the computer answers that the
  change waits. droidtop keeps that field's baseline at the computer's value,
  so the next sync sends the change again.
- **When it runs:** only when the plugin calls `context.sync` (from its own
  UI, during a call the person started) or the person presses "Sync now" for
  a computer. Nothing polls.
- **How droidtop reaches the computer** is droidtop-agent's channel
  (Droidtop/droidtop-agent, docs/DESIGN.md): LAN direct, a direct WireGuard
  tunnel, or the person's own cloud share. The plugin never sees an address,
  a key or the computer's files.

### G. Identity and accounts

| Id | Group | For | Ops and events | Surfaces | Permission | Risk | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| G1 | Secure vault | per-plugin secrets (API keys, refresh tokens) | `vault.put {key, value}` → `{stored}`, `vault.get {key}` → `{value \| null}`, `vault.delete {key}` → `{deleted}`, `vault.keys` → `{keys}`; keys 1 to 128 of `[A-Za-z0-9._-]`, values up to 16 KiB, 256 per plugin; sealed with droidtop's one at-rest cipher (`KeystoreSecretCipher`, AES-GCM) under a Keystore key per plugin; in `noBackupFilesDir`, so **never in a backup**; file and key deleted on uninstall; a value whose key is gone reads as null | — | `vault.own` (normal; own namespace only) | low | built (2026-10-08, `PluginVault`) |
| G2 | OAuth helper | sign in to a service without the plugin handling a password | `auth.oauth {authUrl, tokenUrl, clientId, scopes, pkce: true}` → the host opens a Custom Tab, catches the redirect on its own scheme, exchanges the code and stores the tokens in G1 → returns a vault key | A/G/D: a host sign-in sheet | `auth.oauth` (normal: the user sees and does the sign-in) | medium | not built |
| G3 | Web session | sources that need a real browser session: a site sign-in, a "click to download" page, a protected link (#9, Droidtop/tracker#380) | Declared as `web.session` with the plugin's sites and, optionally, the site's sign-in page: `{"id": "web.session", "domains": ["f95zone.to"], "signIn": "https://f95zone.to/login/"}` (each domain covers its subdomains; https only; `signIn` must be on a declared site). **droidtop draws the account (2026-10-15, Droidtop/tracker#544 items 1 and 2):** the plugin's page carries "<site> account: Signed in / Not signed in" with Sign in (the `signIn` page) or Sign out, from the session droidtop keeps, and says that the plugin never sees the password; `SIGN_IN_REQUIRED` and `BROWSER_CHECK_REQUIRED` get droidtop's own Sign in / Open the site (§1.3). A plugin draws no account row, no "never sees your password" line and no sign-out of its own. Pressing droidtop's Sign in is the person allowing `web.session`, so an unanswered grant becomes allowed when a session is kept; one the person turned off stays off. `web.session sign_in {url, doneCookie?}` → `{signedIn}`: droidtop's own web view (`WebSessionActivity`, the one view droidtop shows signed-in pages in, also a store's own pages, SPEC 7g) opens `url` (a declared site) during a call the person started; the person signs in on the site's own page and presses Done, or the site setting `doneCookie` ends it; the cookies of the declared sites are sealed in the plugin's vault under a host key the plugin can neither read nor list (`@web.session`). `get_status` → `{signedIn, account}`; `clear` → `{cleared}`. `set_account {name}` → `{saved}` (2026-10-17): the name of the account the session is signed in to, as the plugin read it from the site (one line, 1 to 64 characters), shown as the value of droidtop's account row; `saved` is false when no one is signed in, and a new sign-in forgets it. A `doneCookie` on the `web.session` entry (`{"id": "web.session", "domains": [...], "signIn": "...", "doneCookie": "xf_user"}`) ends droidtop's own sign-in from the account row the way `sign_in`'s argument does. An `expiredText` on the entry (2026-10-30, Droidtop/tracker#783; up to 8 sentences, each one line of at most 200 characters, `{"id": "web.session", ..., "expiredText": ["This link has expired"]}`) is the words the site's page shows for a download link that has expired: droidtop opens a download link out of sight first (SPEC 12a), and a page carrying one of them, ignoring case and spacing, is an expired link. For a link from a source's record droidtop then asks the source for the record again and opens the same mirror's current link; droidtop never guesses at a site's page, so a plugin that declares none has no link found expired. The event `web.session.signed_in` (no args) tells a subscribed plugin that the person signed in through droidtop: the row, Switch account, or a source's Sign in. **The row is the one way in (SPEC 12a "A plugin's site account"):** signed out, pressing it opens the sign-in at once; signed in, it names the account and offers Sign out, and Switch account where `signIn` is declared. `fetch {url, method?, headers?, body?, as?}` → the `net.http` answer plus `signedIn`: one request to a declared site with the session's cookies and the web view's user agent added by droidtop (they replace any the plugin sent), refused if it would be redirected off the declared sites; it also needs `net.domains` for the site. `open_in_session {url, max?}` → `{captured, download: {url, fileName, size?, mimeType?, session}, downloads: [...]}` (`max` 1 to 16, default 1, 2026-10-16, Droidtop/tracker#419: above 1 the view stays open, counts what it took and closes at `max` or on Done; `downloads` lists every capture in order, each with its own token, and is an acquire reply's `values.downloads` as it is; `download` is the first): any https link (a thread on a declared site, or a file host it links to; the session's cookies still go only to the declared sites) opens in the web view with the session; the link opens out of sight first (2026-10-30, Droidtop/tracker#783, SPEC 12a): a page that leads to a host droidtop serves or starts a download needs no view, a page carrying the entry's `expiredText` answers `{captured: false, expired: true}` for the plugin to ask its site for a current link, and only a page the person must act on is shown; the first download the page starts is captured (address, the cookies it needs, user agent, referrer) and the view closes; the plugin returns that `download` as its `acquire` reply's `values.download`, and droidtop adds the captured headers by the one-use `session` token (its own plugin, the same address, within 30 minutes) and runs it through the Downloads job like any acquire download, with the cookies held in memory only. The session's cookies are in droidtop's shared cookie store only while the web view is open. **Download sites (2026-10-17, docs/SPEC.md 12a "Download sites"):** droidtop's own signed-in file hosts (Mega, Gofile, Pixeldrain and the ones the person added) have their cookies put into the view for their domains too, and a capture from one carries them by the token as above; no op reads, lists or reports them, and `fetch` sends them only to a plugin that declares that site and keeps no sign-in of its own for it (2026-10-28, Droidtop/tracker#648: its `fetch`, `open_in_session` and `get_status` then use droidtop's sign-in for that site, droidtop adding the cookies as for the plugin's own session; the person can turn it off in the plugin's Permissions, "Use your download-site sign-ins"; renewed cookies go back to droidtop, never into the plugin's vault). Deleted with the plugin's vault on uninstall. | droidtop's web view, headed with the plugin's name and the site the page is on, with Done (sign-in) and Close; B goes back a page | `web.session` (dangerous: authenticated access to your accounts on those sites) | high | built (2026-10-09, `HostWebApis`, `WebSessions`, `WebSessionActivity`) |
| G4 | GitHub token | higher API limits and private repositories for sources on GitHub (#16) | `github.request {path, headers?, as?, timeoutMs?}` → the `net.request` answer: a GET to `https://api.github.com<path>` made by the host with the person's token (set once in Accounts and sources; unauthenticated when there is none). The token never reaches the plugin; GitHubAuth decides on every redirect hop whether it may go there. The plugin's headers (a `Range`, `If-None-Match`) go on every hop. A download descriptor's `"auth": "github"` (A12) asks the same for a file. `github.token()` (the raw token) is not offered | Accounts and sources | `github.api` (normal, through the host request) / `github.token.read` (dangerous: the raw token, not built) | medium / high | `github.request` built 2026-10-10 (Droidtop/tracker#261) |
| G5 | Accounts registry | a plugin's account shown as a row in Accounts and sources (signed in or not, sign out) | EP `accounts.provider@1`: `status → {signedIn, name}`; `sign_in` (runs G2/G3), `sign_out` | A/G/D: Accounts and sources | `provide:accounts.provider` | low | not built |
| G6 | Identity of the device user | "who is this" | not offered: plugins get no Android account list, no email, no device ids. `host.info()` gives a **per-plugin random install id** only | — | — | — | by design |
| G7 | Theme tokens | UI a plugin draws itself (a Flutter plugin's pages, its own screen) in droidtop's colours, light or dark (Droidtop/tracker#559) | `ui.get_theme()` → {theme: "light"\|"dark", tokens: {ground, surface, surfaceVariant, onSurface, onSurfaceMuted, primary, onPrimary, caution, danger, focus, focusInner}}, each `#RRGGBB`, read from the palette in use (docs/SPEC.md 7k "Light and dark": the Gaming theme's while Gaming shows one, droidtop's own light or dark otherwise); `host.get_info` carries `theme`; event `host.theme_changed` {theme} when Light or dark, Android's night mode or the Gaming theme changes the set. A plugin draws no colour of its own for these roles. A Flutter plugin reads the same op through its host calls | — | — (no permission: the colours say nothing about the person) | low | built (2026-10-16, `RoleTokens`) |

### H. Data

| Id | Group | For | Ops and events | Surfaces | Permission | Risk | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| H1 | Per-plugin storage | the plugin's own files and preferences | `privateDataDir()` (exists; a path in every tier, in a plugin process the private-folder path its broker answers with the plugin's own folder, 2026-10-29); `preferences(name)` (droidtop's store, §5.3 "Preferences and SQLite are droidtop's", 2026-10-29); `prefs.get/set` (small key/value, host-stored, shown in the export); quota §8 | — | — (own data) | low | built: `privateDataDir` with full access; contained, the `data` API on the same folder (2026-10-08): `write {name, text \| base64, append}`, `read {name, offset, length ≤ 48 KiB, as}` (2026-10-15, Droidtop/tracker#544 item 4: the reply crosses the broker as UTF-16 text and base64 adds a third, so 120 KiB came back as a 327808-byte binder reply; a longer `length` is answered with 48 KiB and `eof: false`, so a reader that loops until `eof` is unchanged; a large file is opened instead, `open` → fd; writes stay at 128 KiB), `list {prefix}`, `delete`, `move {from, to}`, `usage`, `open {name, mode}` → fd, `path {name}` (droidtop's path of the file, only to hand to a provider plugin), names of `[A-Za-z0-9._-]` segments, writes stop at 512 MiB; `prefs` not built |
| H2 | Shared databases | data other plugins or droidtop may read: droidtop's library index (A1), and a plugin's own published dataset | droidtop's own databases: only through A1 APIs, never a handle. A plugin publishing data does it as a plugin-provided API (§2), e.g. `acme.playlog.query` | — | per API | varies | by design |
| H3 | Import and export | move a plugin's settings or data between devices | EP `data.export@1`: `export → job writing into a host-chosen fd`; `import {fd}` | A/G/D: Accounts and sources → Plugins → <plugin> → Export / Import | none (user-initiated) | low | not built |
| H4 | Backup and restore | droidtop's own backup includes plugin data | manifest `backup: {include: ["prefs", "data/<subpath>"]}`; vault entries never included; restore re-verifies the plugin and asks before re-granting dangerous permissions | Settings → Backup | none | medium | not built (#46 is the credentials-in-backup issue) |
| H5 | Logs and diagnostics | debugging a plugin; including it in "Share diagnostics" (#48) | `log.write {level, msg}` → a per-plugin ring buffer (256 KiB); the host scrubs tokens, emails, IPs, paths under the user's home and anything in the vault before any export; `diagnostics.read` would let a plugin read **droidtop's** logs | Accounts and sources → Plugins → <plugin> → Log; Share diagnostics | `log.write` (—) / `diagnostics.read` (dangerous, official only) | low / high | not built |
| H6 | Telemetry | a plugin's own usage or crash reporting to its author | `telemetry.send {endpoint declared, payload ≤ 16 KiB}`: **off until the user turns it on per plugin**; the host shows the exact payload on the first send; nothing leaves the device without the user | Accounts and sources → Plugins → <plugin> → Share usage data (off) | `telemetry.send` (dangerous, opt-in) | high | not built |

### I. Developer

| Id | Group | For | What | Status |
| --- | --- | --- | --- | --- |
| I1 | Debug surface | plugin authors and bug reports | Accounts and sources → Plugins → <plugin> → Developer (shown when developer options are on): the manifest as parsed; its grants; the last 50 calls with durations and error codes; the last 50 broker calls; the log (H5); "Call op…" with a JSON argument box (replaces today's status-tile "Call …" debug row); "Force crash" (today's `force-crash` query) | the debug row exists, status_tile only |
| I2 | Fake host / test harness | test a plugin without a device | a JVM library `plugin-host-testing` (and a Python `droidtop_fake_host.py`) that loads a plugin, serves the host API from in-memory fakes with configurable grants, and runs the **conformance script** (§1.3) that a test fixture of every kind must pass in CI | built 2026-10-16 (Droidtop/tracker#71): `:plugin-host-testing` holds `FakeEnv` (droidtop in memory), `FakeHost` (a `native_bundle` plugin's class in the JVM, its `host.call` through the real `BrokerCore`) and `Conformance`; the cases are one file, `conformance.json` in that module, which `plugin-host-testing/python/droidtop_fake_host.py` reads too. CI runs the native fixture (`ConformanceTest`) and the python fixture (android-checks.yml). The cases: a declared point answers, a granted host call succeeds, a turned-off permission is `PERMISSION_DENIED`, a failure is one plain sentence, a job reports and completes. Not covered: `flutter_embed` (its Dart code needs the engine; a rig check) |
| I3 | Manifest schema | validation for authors and CI | `docs/plugin-manifest.schema.json` (JSON Schema for contract 2, with v1 accepted), used by `PluginManifest` tests | not built |
| I4 | Test fixtures | a provider/caller pair per kind for the conformance script | droidtop ships no sample plugins (owner, 2026-10-14); fixtures live under the test directories and are never packaged | built for the JVM (2026-10-16): one conformance fixture each for `native_bundle` and `python` (Droidtop/tracker#71), and a `native_bundle` provider/caller pair through the real broker (`ProviderCallerTest`, Droidtop/tracker#129: the answer, `PROVIDER_UNAVAILABLE` while the provider is off or gone, the answer again when it is back; `FakeHost.provide`). The same steps on a device run with real plugins: Shizuku's `priv.shell` and the RetroArch manager calling it (coordinator, 2026-10-16: "Keep the rig part, run with real plugins ... Use disable where possible, since the console is production.") |

### J. Plugin platform (cross-cutting)

| Id | Group | For | What | Status |
| --- | --- | --- | --- | --- |
| J1 | Jobs | long-running work with progress and cancel | `startJob`/`cancelJob`, `PluginJobsCenter` (one registry, one Jobs screen); any EP op may be declared `job: true`; a job belongs to the plugin that started it (or to the caller, for a brokered provider job) | built |
| J2 | Event bus | host → plugin notifications | `PluginEventBus`; events are namespaced `<area>.<name>`, each with its own version (§7); v1 `default_player_changed` is kept as an alias of `library.default_player_changed@2` | partial (registered; only default-player delivery implemented) |
| J3 | Plugin-provided API broker | plugin → plugin | §2 | not built |
| J4 | Host info | what this host supports | `host.info()` → {droidtopVersion, contract, points, apis, mode (`gaming`/`standard`/`desktop`), modes (the ones on), abis, installId}; `plugins.available {api}` | built |

**The count:** ten areas (A to J) and **94 numbered entries**:

- A: 10
- B: 7
- C: 20 (C1 to C19, and C6a)
- D: 20
- E: 10
- F: 7
- G: 6
- H: 6
- I: 4
- J: 4

E10 and J3 are the same broker seen from two areas, so there are 93
distinct API groups. Groups closed on purpose (A5, A10, D12, D19, D20,
G6) are counted, because deciding "not offered" is part of an
exhaustive list.

---

## 4. The permission model

### 4.1 Permissions, grouped

Tiers:

- **normal**: granted when the user approves the plugin. The approval
  screen lists these as a short list.
- **dangerous**: never granted silently. Each one is a tick box on the
  approval screen that starts unticked, or the user answers a prompt on
  first use. A dangerous
  permission can be revoked at any time.
- **critical**: dangerous, plus a written warning. Some critical
  permissions are restricted to official origins (marked †).

The plain-language label is what the grant screen shows. It is the
wording the host uses, so it is identical in every mode.

| Permission | Tier | Plain-language label | Groups |
| --- | --- | --- | --- |
| `library.read` | normal | See your library: games, apps and systems | A1, C4 |
| `library.history` | dangerous | See what you play and for how long | A1, B3, A8 |
| `library.folders.write` | dangerous | Add files to your game folders | A2 |
| `saves.read` | dangerous | Read save data of your games | A7 |
| `saves.write` | dangerous | Replace save data of your games | A7 |
| `saves.exchange` | normal | Ask where your games' saves go, and tell droidtop when it saved | A7a |
| `media.read` | dangerous | See your music, videos and pictures | A9 |
| `perf.read` | normal | See performance readings (CPU, temperature, battery) | B6 |
| `perf.profile.set` | dangerous | Change performance and fan settings | B7 |
| `overlay.toast` | normal | Show short messages during games | B4 |
| `retroarch.commands` | normal | Send commands to RetroArch while you play: save and load states, slots, shaders, fast-forward | B8 |
| `net.sockets` | dangerous | Make its own connections to other devices and the internet | §5.3 ("Open processes") |
| `devices.paired` | dangerous | See and reach the devices you paired with droidtop | D21 |
| `notify.post` | normal | Send you notifications | C6 |
| `net.state` | normal | See whether you are online | D1 |
| `net.wifi_details` | dangerous | See the name of your Wi-Fi network | D1 |
| `net.domains` | normal | Connect to: *listed domains* | D2 and every EP that fetches |
| `net.any` | dangerous | Connect to any site on the internet | D2 |
| `net.local` | dangerous | Find and connect to devices on your local network | D2 |
| `storage.volumes` | normal | See your storage devices and free space | D3 |
| `files.picker` | normal | Ask you to choose files or folders | D4 |
| `files.shared.read` | dangerous | Read all files on shared storage | D5 |
| `files.shared.write` | dangerous | Change and delete files on shared storage | D5, C9 |
| `clipboard.write` | normal | Copy to the clipboard | D6 |
| `clipboard.read` | dangerous | Read the clipboard | D6 |
| `power.state` | normal | See battery and charging state | D7 |
| `power.keep_awake` | normal | Keep the device awake while it works | D7 |
| `display.info` | normal | See your displays | D8 |
| `display.control` | dangerous | Change brightness and display settings | D8 |
| `audio.control` | normal | Change volume and audio output | D9 |
| `media.sessions` | dangerous | See and control what other apps are playing | D10 |
| `input.devices` | normal | See connected controllers and keyboards | D11, B5 |
| `bt.devices` | dangerous | Find and connect Bluetooth devices | D13 |
| `usb.devices` | dangerous | Use USB devices you connect | D14 |
| `sensors.read` | normal | Use motion and light sensors | D15 |
| `vibrate` | normal | Vibrate | D16 |
| `a11y.bridge` † | critical | Read the screen and act for you | D18 |
| `containers.read` | normal | See your Linux and Windows containers | E1 |
| `containers.manage` | dangerous | Start, stop and create containers | E1 |
| `containers.exec` | critical | Run programs inside your containers, with access to everything in them | E2, E7 |
| `terminal.open` | normal | Open a terminal for you | E3 |
| `windows.read` | dangerous | See your open windows and their titles | E5 |
| `windows.control` | dangerous | Focus, move and close your windows | E5 |
| `print.submit` | normal | Ask to print | E6 |
| `print.admin` | dangerous | Add and change printers | E6 |
| `apps.check` | normal | Check whether *listed apps* are installed | F4, F1 |
| `apps.list` | dangerous | See all apps installed on this device | F4, C12 |
| `apps.launch` | normal | Open other apps | F2 |
| `apps.view` | normal | Open links in other apps | F2 |
| `apps.intents.out` | normal (declared packages) / dangerous (any) | Send information to other apps | F2, B1 |
| `intents.in` | dangerous | Be opened by other apps and links | F3 |
| `apps.install` | dangerous | Install and update apps (Android asks each time) | F5 |
| `apps.bind` | dangerous | Stay connected to other apps | F6, F1 |
| `vault.own` | normal | Keep its own saved passwords | G1 |
| `context.sync` | dangerous | Keep its data in step with apps on your paired computers | F8 |
| `auth.oauth` | normal | Ask you to sign in to a service | G2 |
| `web.session` | dangerous | Use your sign-in on its sites | G3 |
| `github.api` | normal | Use your GitHub account for GitHub requests | G4 |
| `github.token.read` | dangerous | See your GitHub sign-in | G4 |
| `diagnostics.read` † | dangerous | Read droidtop's logs | H5 |
| `telemetry.send` | dangerous (opt-in) | Send usage data | H6 |
| `plugins.export` | normal | Offer features to other plugins | §2.8 |
| `plugins.export_privileged` | critical | Give other plugins full control of the device | §2.7 |
| `host.full_trust` | critical | Full access | §5.3 |
| `os.shizuku` | critical | Use system access through droidtop | §5.6 (full access only: the Shizuku relay) |
| `priv.shell.adb` | critical | Run system commands | F7, §2 |
| `priv.shell.root` | critical | Full control of the device | F7, §2 |
| `priv.packages` | critical | Install and remove apps without asking | F7 |
| `priv.settings` | critical | Change protected settings | F7 |
| `root.modules` | critical | Install system add-ons | F7 |
| `input.remap.apply` | dangerous | Change your controller mapping | B5 |

That is 66 permissions. Those marked † are restricted to official
origins.

**Android permissions (decided 2026-10-08, Droidtop/tracker#378).** A
plugin never holds an Android permission: a contained process has none,
and Android grants them to droidtop's package, not to a plugin. What a
plugin declares is which of droidtop's Android-backed operations it needs;
droidtop runs them under its own permission, gated per plugin like every
other permission here.

- Each registry row names the Android permission droidtop needs to do that
  operation for a plugin, and from which Android version: `notify.post`
  needs `POST_NOTIFICATIONS` (API 33+), `net.state`
  `ACCESS_NETWORK_STATE`, `net.any` `INTERNET` (`PluginPermission.android`).
- A plugin may declare the Android name (`{"id":
  "android.permission.POST_NOTIFICATIONS", "reason": ...}`) instead of the
  host id. The manifest reader takes it as that row's permission and keeps
  the name it was written under, so the approval list, the grant and the
  broker check are one. An Android name and its host id are one permission.
- An Android permission droidtop's own manifest does not request is
  **refused at install** with the reason ("it asks for the Android
  permission CAMERA, which droidtop itself does not have, so no plugin can
  use it through droidtop"). One droidtop requests but no operation uses is
  listed as "Not supported by this version of droidtop".
- At call time the broker checks the plugin's own grant first, then
  whether droidtop holds the Android permission. If it does not, Android's
  own prompt is shown, but only during a call the person started; from the
  background the call fails with `PERMISSION_DENIED` and droidtop asks next
  time. The approval line says "Android asks you too, the first time".
- Camera (D20), location (D19) and Bluetooth (D13) have no droidtop
  operation, and droidtop requests none of those permissions, so a plugin
  naming them is refused at install. Each becomes possible only when its
  operation is built and droidtop's manifest gains the permission, which is
  then a decision about droidtop itself.

**Always available, with no permission:** `host.info`, `plugins.available`,
own `prefs`, own `privateDataDir`, `log.write`, `locale`/`time`, the
`ui.prompt` sheet during a user-initiated call, and job progress.

### 4.2 Providing sensitive extension points

Providing an extension point rated high or critical is itself a consent
item, shown as `provide:<point>`, because providing is what gives the
plugin power there. These points are:

- `library.sources` (writes into game folders);
- `library.stores` (adds the games an account holds to your library, A13);
- `apps.catalog` (offers Android apps that droidtop installs);
- `saves.sync`;
- `launch.provider` (decides what runs your game);
- `files.handler` (receives your files);
- `onboarding.step` (official only);
- `intents.in`;
- `containers.packages`;
- `jobs.service` (keeps running while you are away);
- every `exports` entry.

Every point, high-risk or not, is listed on the approval screen under
"Adds", grouped by mode, as its own tick box with one plain sentence on
what it lets the plugin do; the high-risk ones are marked and start
unticked, the others start ticked.

### 4.3 Grant states, and when the user is asked

Each (plugin, permission) pair is in one of three states:

- **granted**;
- **denied**, which is what an unticked normal or optional permission,
  extension point or export becomes at approval;
- **ask**, which is what a dangerous or critical permission the user did
  not tick at approval becomes when the plugin declares it `required`
  (the list says "Needed" and that droidtop asks when it is first used),
  and what an update's new items start as. An optional one left unticked is
  denied: the tick box really declines it (Droidtop/tracker#512).

When they are asked:

- **At approval ("Approval is a list").** One screen, "What you're
  allowing", reached from the row under the plugin page's header (the
  page itself leads with the header and Approve on its bar,
  Droidtop/tracker#512); what is ticked there is what Approve allows.
  Every line below with a tick box can be unticked before approving,
  high-risk ones included:
  - "Adds": each extension point, with the mode it shows in (Gaming / Android / Desktop) beside it and nothing beside a point that has no particular place,
    with one plain sentence on what it lets the plugin do (high-risk ones
    marked "high risk");
  - "Can": normal permissions, each with its plain-language line;
  - "Asks for": each dangerous or critical item, with its tick box, the
    plugin's own `reason`, and "Needed" if `required`;
  - "Offers to other plugins": each `exports` entry;
  - "Uses from other plugins": each `requires`, with its provider and
    that provider's badge (information, not a tick box);
  - the trust badge.

  Items start ticked except what this section already holds back: a
  dangerous or critical permission and a high-risk extension point start
  unticked. The plugin then runs with exactly the ticked subset: an
  extension point that is not granted is never called, a denied host API
  returns `PERMISSION_DENIED` ("turned off for this plugin") to the
  plugin, and a plugin view for a denied point shows the standard error
  state saying it was not allowed, with a route to its Permissions screen.
  The same list serves the three modes through the settings catalog
  (Gaming through the input pipeline, SPEC 6e; Standard; Desktop).
- **On first use.** A call that needs a permission in `ask` state, made
  **during a user-initiated call**, shows a host sheet: "<Plugin> wants
  to <label>", with the reason and *Allow* / *Not now* / *Never*. The
  sheet is gamepad-navigable, has a hint row, and B means Not now.
  - A call in `ask` state from a background context (an event, a
    schedule, a service) fails with `PERMISSION_DENIED` and never shows
    a prompt. The plugin row shows "Wants <label>", and the user can
    grant it there. Plugins cannot pop prompts on their own.
  - **Not now lasts this run of droidtop** (2026-10-16,
    Droidtop/tracker#518): the same permission is not asked again until
    droidtop restarts or the person chooses on the plugin's Permissions
    page; until then the call fails with `PERMISSION_DENIED` and a whole
    sentence saying so.
  - **A refused permission degrades only what needs it.** A page whose
    call failed because droidtop refused one of the plugin's declared
    permissions, which the plugin passed on without naming, is drawn as
    a notice about that permission that opens its grant, never as "Not
    allowed to show this" (that is for a refused point). A plugin keeps
    the rest of its page by marking the nodes that need it with `needs`
    (1.6) and not making the call while it builds the page.
- **Revocable.** Accounts and sources → Plugins → <plugin> → Permissions
  lists every declared permission with its state and when it was last
  used (from the audit log). Changing a state takes effect on the next
  call. A revoked `jobs.service` point, or a service's own switch turned
  off, stops the service.
- **Updates.** Every item an update adds (any permission, extension point
  or export) arrives in `ask` (§1.5), and the plugin's page asks about
  those items only, as the same list with the same defaults ("Wants new
  access"); what the user allowed before is untouched.
- **After a denial.** A denied `required` permission leaves the plugin
  "Needs <label>" and it is not called. A denied optional permission
  makes only that call fail with `PERMISSION_DENIED`, and the plugin
  must degrade.

### 4.4 Where grants are managed

Everything is under Accounts and sources → Plugins, the one settings
area, which reaches the same catalog screens from every mode:

- per plugin: Permissions, Activity (audit), Developer;
- per interface: "Provided by" (§2.3).

There is no second place.

**Decided (Droidtop/tracker#830 R0; SPEC "Permissions").** The
grant UI follows Android's permission UI as a whole:
- the moment-of-use sheet asks "Allow <Plugin> to <do this>?" with the plugin's
  quoted reason and the risk, choices Allow and Don't allow, a separate
  "Don't ask again" checkbox that makes Don't allow stick, B for Not now;
- a plugin's Permissions page is sorted into Allowed and Not allowed (Not allowed
  holds both "Ask every time" and "Don't allow"), plus a group for what it adds,
  each row with its last use;
- the one Permissions page's Allowed tab sorts By plugin or By permission (Android's
  Permission manager: every holder of a group, "2 of 5 plugins allowed");
- its Recent use tab is the last 7 days of dangerous and critical use from the
  audit (§4.6), Android's privacy dashboard;
- a plugin not called for the set time (30 days by default) has its dangerous and critical grants set back
  to "Ask every time", quietly, listed under Removed while unused. This is on by
  default and can be turned off for all plugins and per plugin.

These are views of the same grants; there is still no second place where a
grant is kept.

### 4.5 How trust tiers and permissions interact

| | Official origin | User-trusted origin ("Added by you") | Unknown origin |
| --- | --- | --- | --- |
| Install | yes | yes | refused (as today) |
| Normal permissions | granted on approval | granted on approval | — |
| Dangerous | ticked or asked | ticked or asked; the grant sheet adds "from a source droidtop has not checked" | — |
| Critical | ticked, with a warning | ticked, with a warning **plus** a second confirmation; † permissions not grantable at all | — |
| `host.full_trust` | allowed only with the critical grant; contained is the default (§5.3, built) | the same | — |
| Export privileged APIs | allowed | only with `plugins.export_privileged` | — |
| Onboarding steps | allowed | never | — |

Trust decides *who may ask*. Permissions decide *what is granted*. An
official plugin still gets no dangerous permission without the user's
tick.

### 4.6 Auditing: what a plugin used, and when

- **What is logged.** Every broker call that needs a dangerous or
  critical permission writes an entry: plugin, permission, op, a
  one-line target summary (a domain, a package, a folder name, never
  contents), `via` chain, result code and timestamp. Network calls
  (`net.http`, `net.download`, with the host), shared-file calls and
  picked-file calls are logged whatever their tier (built 2026-10-08).
  Other normal-permission calls, and ops that need no permission, are
  counted per day, not logged one by one (built 2026-10-28,
  Droidtop/tracker#61): a count per day and per permission, or per
  `api op` when there is none, kept 30 days beside the ring and deleted
  with it (`PluginCallCounts`). Counts gather in memory and are written
  at most every 30 s, when the day changes, and before they are read.
- **Storage.** In droidtop's private storage, per plugin: a ring of 2,000
  entries or 30 days. It is kept 7 days after uninstall.
- **Where it shows.** Activity per plugin ("Used your GitHub token 3 times
  today, last 14:02"), and "last used" on each permission row. Built
  2026-10-08: Accounts and sources → Plugins → <plugin> → Activity lists
  the last 200 entries, newest first, under one line that says what the
  list covers for that plugin's tier. Below them, "Everyday calls, counted by day" shows
  the last 7 days' counts, busiest first.
- **Export.** It is included in Share diagnostics only with the user's
  say-so, and scrubbed (H5).
- **What the audit cannot see.** Anything a full-trust plugin does
  directly with droidtop's UID, bypassing the broker (§5). The Activity
  screen says so on full-trust plugins: "This plugin runs with full
  access; only what it asks droidtop to do is listed." A contained plugin
  has no way around the broker.

---

## 5. Threat model

### 5.1 What is enforced, and what is not

Since 2026-10-08 (Droidtop/tracker#378) a plugin runs in one of two tiers
(§5.3), and what is enforced depends on which:

| | Contained (the default for contract 2) | Open process (`gpu.render`, `net.sockets`: each grant one hole) | Full trust (`host.full_trust`, and contract 1) |
| --- | --- | --- | --- |
| Process | an isolated process of its own: a random UID, no permissions, the `isolated_app` SELinux domain | a process of its own (one of eight slots) under droidtop's UID, **not** isolated, so it may open the GPU | a process of its own (one of eight slots) under droidtop's UID |
| Graphics chip | no: an isolated process may not open `gpu_device` (sepolicy), so Flutter draws in software | yes: hardware rendering into a droidtop-owned surface | yes |
| Network | none: not in the `inet` group, so `socket()` is refused; `net.http`/`net.download` only, checked against `net.domains`/`net.any`/`net.local` per hop and logged | none of its own unless `net.sockets` is granted: droidtop's system-call filter refuses every socket but a local datagram one (§5.3), so `net.http`/`net.download` only, as contained; with `net.sockets`, internet sockets of its own and no other socket; **but** its own code could ask Android's services (the system download manager, say) to fetch something as droidtop, unseen | droidtop's `INTERNET`: any socket, unseen by droidtop |
| droidtop's files (databases, preferences, sign-in tokens, other plugins' data) | none | none by path: every call that names a path goes to its file broker in :app, whose rules hold none of them (§5.3, since 2026-10-10); **but** its own code could still ask Android's services for files as droidtop over binder | all of them |
| Its own files | the `data` API on its own folder only | the `data` API on its own folder only (same binder caveat) | its own folder by path, and everything else droidtop's UID reaches |
| Shared storage | `files.shared` within its declared folders, `files.pick` documents, logged | the same as contained through the broker (same binder caveat) | all of it (`MANAGE_EXTERNAL_STORAGE`) |
| Installed apps, usage stats, secure settings, logs | through declared host APIs only | through declared host APIs only | whatever droidtop's UID holds (`QUERY_ALL_PACKAGES`, `PACKAGE_USAGE_STATS`, `WRITE_SECURE_SETTINGS` and `READ_LOGS` if granted) |
| Shizuku, `su` | none (no binder, no permission; it can reach privilege only through a `priv.*` provider, with its own grant) | none through the broker, and the filter refuses the stream socket a root manager's `su` talks over; **but** as droidtop's UID its own code could ask Shizuku for a binder if droidtop was allowed it | Shizuku's binder if droidtop was allowed, and `su` if the root manager granted droidtop's UID |
| Other plugins | only through the broker (§2) | only through the broker | other full-trust plugins' files (same UID); not their objects (separate processes) |
| The broker | the caller is the binder object; grant, scope, quota and audit on every call | the same | the same for what it asks droidtop to do |

**Enforced for every plugin:**

- **Install integrity.** Signature (`manifest.jws`, a JWS carrying the
  signing certificate chain), per-repository keys certified by droidtop's
  pinned signing root (with revocation), the root the person trusts for
  any other origin, the trust tiers,
  every payload hash, re-verification before every activation, id
  namespacing and ABI coverage. Official bundles are signed by CI
  (repo secrets `PLUGIN_SIGNING_KEY` and `PLUGIN_SIGNING_CERT`); see
  docs/SPEC.md 12a, "Signatures: certificate chains to droidtop's signing
  root", and "Signing a plugin" in §1.5.
- **Consent.** Nothing runs before approval. Approval binds to the digest
  and key. A plugin that cannot run contained must declare
  `host.full_trust`, or it is refused at install, and it is not called
  until the person allows it. An Android permission droidtop does not hold
  is refused at install (§4.1).
- **Routing.** droidtop calls only declared and allowed points and
  delivers only subscribed events. A disabled plugin is never called
  (`runnableFor`).
- **What droidtop hands over.** Intents are built by droidtop from strings.
  No database handle is passed. A contained plugin gets files only as
  descriptors from the broker.
- **Replies.** Results are capped at 256 KiB and treated as untrusted
  input (never used as a path, URI or intent target without validation).
- **Stability.** Watchdogs, and a crash, timeout or death of its process
  during a call disables the plugin.

**Not enforced:** anything a full-trust plugin does directly. Its
permissions are declarative consent plus host-side gating of what droidtop
does for it; they do not bound what it can do on its own, and the Activity
screen says so (§4.6). That is why full trust is a critical grant, needed
only by what needs UID-level privilege (`apps.bind`, the `priv.*` and
`root.*` providers) and by contract 1 plugins. Every kind of
plugin runs contained, Flutter and native libraries included (§5.3,
"Guarded hooks"). Where a plugin needs one risky capability and nothing
more, it declares that one permission and the person ticks it on the usual
list; granting it opens that one hole, never full access. The first such
permission was `gpu.render` (§5.3, "The graphics tier"), and the second is
`net.sockets`; both open their hole in the one open process type (§5.3,
"Open processes: one hole per permission"): one permission, one hole,
following the rule the owner set on 2026-10-09 ("Granting a single risky
permission does NOT automatically grant everything").
Whether the contained tier holds on a given device is what the Containment
check (§5.3) shows.

### 5.2 Threats and mitigations

| # | Threat | Today | Mitigation in this design | What remains |
| --- | --- | --- | --- | --- |
| T1 | **Malicious plugin** (bad author, or a good author's key stolen) exfiltrates droidtop's credentials, the library, play history or shared files | mitigated for contained plugins (built 2026-10-08): no direct network, no files, no UID permissions | contained tier (§5.3): everything through the broker, with domain allowlists and an audit | full-trust plugins stay able to do anything; only by the critical `host.full_trust` grant, needed only by what cannot run contained |
| T2 | **Silent escalation on update** (same key, new code wants more) | approval carries over; nothing re-asks | permission diff (§1.5): new dangerous items wait in `ask`; new exports need a grant | a full-trust plugin's new *code* can still misuse existing direct access |
| T3 | **Confused deputy through the host**: a plugin gets droidtop to act with droidtop's power (write outside a folder, send an intent to an arbitrary package, open a FileProvider URI) | ad hoc: the host builds intents, validates results | the broker checks the **caller's** grant, never droidtop's; every path comes from the host (tokens and fds), never from the plugin; intents only to declared packages unless `apps.intents.out` is dangerous-granted | host bugs in validation: each API gets a unit test that a plugin-supplied path, URI or package outside scope is refused |
| T4 | **Confused deputy through a provider plugin**: A reaches root through B without its own grant | not possible yet (no plugin→plugin) | A's own grant is required (§2.6); risk floor; `via` chain audited; depth ≤ 3, no cycles; narrow standard-interface schemas | a buggy provider that exposes more than its label: reviewed for official providers; third-party exporters need `plugins.export_privileged` |
| T5 | **Data exfiltration** by network, intents, clipboard, share, logs, telemetry or crafted result URLs | mitigated for contained plugins: the network is `net.http`/`net.download` only, every hop checked and logged | `net.domains` allowlist, audited; `apps.intents.out` scoped; `clipboard.read` dangerous and user-initiated only; logs scrubbed; telemetry opt-in with payload shown; host renders no plugin-supplied URL as a clickable link without showing the domain | covert channels inside allowed domains; a contained plugin can still send what it can read to an allowed domain, so keep what it can read small |
| T6 | **UI spoofing / phishing**: a plugin renders "Enter your Steam password" or a fake permission sheet | partially: plugins draw no UI | plugin text always carries its name chip; no secret text fields (C14); passwords only through G2/G3 host flows; permission sheets are host-only and visually distinct; onboarding steps official only | a misleading but honest-looking label; mitigated by attribution and review of official plugins |
| T7 | **Denial of service**: hang, crash loop, battery drain, notification spam | watchdog; crash disables | quotas (§8); background only with a grant; per-plugin notification channel and rate limit | a plugin that behaves badly but never crashes: the Activity and Jobs screens make it visible |
| T8 | **Cross-plugin interference** in a shared plugin process | mitigated (built 2026-10-08): one process per plugin; contained ones isolated | contained tier: one isolated process per plugin; full-trust plugins in their own non-isolated process (shared only beyond eight full-trust plugins in use at once) | full-trust plugins share droidtop's UID and can still read each other's files |
| T9 | **Supply chain**: TOFU key fetch, catalog index tampering, runtime download tampering | mitigated (https only, fingerprint shown, changed key stops dead, index untrusted, pinned runtime hashes) | unchanged; add a "key changed" entry to the audit log | a user who confirms a TOFU key for a malicious source |
| T10 | **Privacy of the user's play history and library** | mitigated for contained plugins: the library database is unreachable, only A1 calls | `library.history` and `apps.list` are dangerous; A1 read is paged and index-only; G6: no identity APIs; per-plugin random install id | a plugin granted history can keep it |
| T11 | **Root or Shizuku abuse** | contained plugins: no `su`, no Shizuku binder; full-trust plugins: same-UID code can still call `su` or Shizuku without the host knowing | host holds no privilege; providers only; caller grants; `priv.*` requires optional-only (§2.7) | a full-trust plugin still shares droidtop's UID, so it can reach Shizuku or `su` directly if the root manager granted the UID; hence §5.3's "each full-trust plugin its own process" and the plan for a provider-owned UID (§10 Q2) |
| T12 | **Malformed data into droidtop**: theme files, metadata, result JSON, notification text | size caps, the untrusted-input rule | schema check per op reply; theme input goes through the same hardened parser as downloads; text length caps | parser bugs in droidtop itself |

### 5.3 The contained execution tier (built 2026-10-08)

To make the permission model hold for real, plugins must stop sharing
droidtop's UID. The design, as built (Droidtop/tracker#378):

- **Contained, the default for contract 2 plugins.** Each plugin runs in
  its own **isolated process**: `android:isolatedProcess="true"` on the
  runtime service. `Context.bindIsolatedService` (API 29+) gives one
  process per plugin from a single declaration; on API 28 and older a
  fixed pool of declared slots (`PluginSandboxSlot0..7`) is used, one
  plugin each, the same pattern browsers use for renderers.
  - **What an isolated process has.** Its own random UID, **no
    permissions at all**, no network (it is not in the inet group), no
    access to droidtop's files or shared storage, and almost no system
    services.
  - **What it gets instead.** Everything comes through the broker: code
    and payload as file descriptors, network through `net.http`, files
    as fds from `files.pick` or host-resolved paths, and storage through
    a host-backed data API.
  - **How the code loads:** see "Spike results" below.

**Spike results (P2-1, Droidtop/tracker#378, 2026-10-08).** The loaders are
built, and the same code is what runs a contained plugin; there is no
separate spike build. Whether each one works on a device is measured by
the plugin page's **Containment check** (Advanced): it loads the plugin
exactly as a call would and reports which process it runs in and its UID,
whether a socket opens (an isolated UID is refused at `socket()`, before
any address, so the check sends nothing), whether droidtop's files and
shared storage list (shared storage by its fixed path: asking
`Environment` for it is a binder call to the `mount` service, which an
isolated process may not look up, and that threw inside the check, which
then read "did not answer" on both rigs, plugin-enforce-7), and, for python, how every library got in. The
answers below are from the code and from Android's documented rules; the
device answers come from the rig (the "Needs a rig check" steps of the
commit that built this) and are written here when they arrive.

| Kind | How it loads in the isolated process | Contained? |
| --- | --- | --- |
| `native_bundle`, dex only | `:app` opens `classes.jar` and hands the descriptor over; the sandbox reads its `classes*.dex` into memory and loads them with `InMemoryDexClassLoader`, parented to droidtop's own loader so the plugin sees the plugin API. API 27+ takes any number of dex files, API 26 one. | **Yes.** Needs no path at all. |
| `native_bundle` with `.so` | Its libraries for the device's ABI reach the process as descriptors and get virtual paths (`/droidtop-sandbox/plugin/lib/<abi>/...`); the class loader's library path names that folder (the `InMemoryDexClassLoader` constructor from API 29, the loader's own `addNativePath` on API 28). The plugin's own `System.loadLibrary` then runs the ordinary way: libcore's `findLibrary` check and ART's `android_dlopen_ext` are answered by the guarded hooks, the library is mapped from a private memfd copy, and ART registers it with the plugin's class loader, so JNI binds by symbol name or `RegisterNatives` as usual. | **Built 2026-10-09 (plugin-enforce-3); the rig decides.** Needs Android 9 or later. A library whose `DT_NEEDED` names another of the plugin's own libraries loads only after that one (the linker resolves its dependencies by name, which the hooks do not see); load them in order. |
| `python` | `:app` hands over libpython, the runtime's other libraries (OpenSSL, SQLite), the standard library as one zip (`python-stdlib.zip`, built once beside the runtime: pure Python, stored, no tests, IDLE or Tk) and every `lib-dynload` module. `libdroidtoppy.so` maps each library with `android_dlopen_ext` and `ANDROID_DLEXT_USE_LIBRARY_FD` from a private `memfd` copy of its descriptor, never from the descriptor itself (plugin-enforce-7: trying droidtop's own file first logged an `avc: denied { execute }` per library on the Android 14 rig, because `isolated_app` may never execute `app_data_file`). Each extension module is registered as a built-in (`PyImport_AppendInittab`) before the interpreter starts, because the import system loads an extension only by path. The zip goes on `sys.path` as `/proc/self/fd/<n>`; `zipimport` stats that path and reads it through an open-code hook (`PyFile_SetOpenCodeHook`) that answers with the descriptor itself (later with `pread`, so threads importing at once never share a file offset), so nothing is opened by path. `plugin.py` is executed from its text. One interpreter per plugin process. | **Built; the rig decides.** AOSP's policy (below) answers the first question: an isolated process may never map code from droidtop's files, so the memfd copy is the only path any library takes, and the report says so line by line ("memfd"). It may `getattr` a file handed to it, so the zip is expected on `sys.path` through its own descriptor. Whether a device's kernel and policy match AOSP's is what the report shows; if contained Python fails on one, the report and logcat's `avc:` lines say exactly what was refused. |
| `flutter_embed` | A headless `FlutterEngine` in the isolated service. `:app` hands over the downloaded engine, the plugin's `libapp.so`, its `flutter_assets` as one zip (built once per installed bundle in droidtop's cache) and its registrant dex. `System.load` of the engine is answered by the guarded hooks; once the engine is in, its own imports are hooked too, so its `dlopen` of `libapp.so` maps the plugin's. **Flutter assets** (plugin-enforce-7): AssetManager opens an asset path itself, inside libandroidfw and libziparchive, and the hooks did not reach that open on the Android 13 rig ("Unable to open '/droidtop-sandbox/plugin/flutter_assets.zip'", `AssetManager.addAssetPath` refused). From API 30 the sandbox unpacks the zip descriptor once into a memfd of its own and adds a `ResourcesLoader` with an `AssetsProvider` (`ResourcesProvider.empty`) to its Resources before the engine is built; each asset is answered as an `AssetFileDescriptor` range of that memfd, so the engine's `AAssetManager_open` finds `flutter_assets/...` with nothing opened by path (`ContainedAssets`). On Android 8 to 10 there is no such API and the virtual path is the only route, so a contained Flutter plugin there depends on the hooks reaching the asset manager. **Engine arguments** (plugin-enforce-7, emulator-5560): FlutterLoader rejects an `--aot-shared-library-name` outside `getFilesDir()` ("External path ... rejected") and drops `--enable-software-rendering` in a release build, so a contained engine gets both, with `--enable-impeller=false` (Impeller refuses software rendering: "Check failed: !settings.enable_impeller"), at `FlutterJNI.init`, which passes its arguments to the engine unchanged. A library mapped from a memfd is known to the linker as `/memfd:<name> (deleted)`, so the hooks match that form too; before, the engine itself was never hooked and Dart found no snapshot. The engine draws in software (`--enable-software-rendering`): an isolated process may not open the GPU (grant `gpu.render` and it runs in a non-isolated droidtop-owned process that draws with hardware, "The graphics tier"). `ui.main`: an activity cannot run in an isolated process and the window manager is out of its reach, so droidtop's `PluginScreenActivity` owns a `SurfaceView`; the plugin's main-UI engine draws each frame into shared memory droidtop allocated and droidtop paints it into that surface ("ui.main in the sandbox" below: an isolated process can use neither vsync nor a Surface's buffers), and droidtop forwards touch, packed by droidtop itself (`FlutterPointerPacket`: Flutter's `AndroidTouchProcessor` asks the input service about each pointer's device, which an isolated process cannot reach, Droidtop/tracker#511), and keys (`KeyEventChannel`); Back pops the plugin's route and its last route (`SystemNavigator.pop`) closes the screen. | **Built 2026-10-09 (plugin-enforce-3); the rig decides.** Plugin packages that need files (`path_provider`, `sqflite`) had none when contained until 2026-10-14; since then the file broker maps droidtop's private folder to the plugin's own ("The plugin's own folder by path" below). An isolated process cannot use a surface's gralloc buffers on either rig ("ui.main in the sandbox"), so a contained `ui.main` draws through droidtop's frames: seen drawing on emulator-5560 and BlueStacks (plugin-enforce-8). |

What every contained process is, by Android's rules: a random UID from the
isolated range, no permissions, not in the `inet` group (no sockets), the
`isolated_app` SELinux domain, which may use descriptors handed to it but
may not open droidtop's files (`app_data_file`) or shared storage, and no
content providers. droidtop's application object checks for an isolated
process first and starts nothing in it.

**What the platform allows an isolated process, from AOSP's own policy**
(system/sepolicy, main branch, read 2026-10-09), and what the design does
with it:

- `private/isolated_app_all.te`: it may `read write getattr lock map` an
  `app_data_file` handed to it, but `neverallow isolated_app_all
  app_data_file_type:file open`: so files arrive as descriptors and are never
  reopened by path (the hooks answer a virtual path with a duplicate of the
  descriptor, and Python's open-code hook does the same for its zip).
- `private/app.te`: `neverallow { ... isolated_app_all ... } { data_file_type
  -apex_art_data_file -dalvikcache_data_file -system_data_file
  -apk_data_file }:file no_x_file_perms`: an isolated process may never map
  code from droidtop's files, whatever the descriptor. So every library a
  plugin brings (and libpython, the Flutter engine, `libapp.so`) is copied
  into a memfd the process makes itself before it is mapped: the
  `app_domain()` macro (`public/te_macros`) labels an app's own tmpfs files
  `appdomain_tmpfs` and allows it `execute getattr map read write` on them
  (not `open`, which is why the memfd is mapped through its descriptor and
  never reopened through `/proc/self/fd`). Chrome's and WebView's isolated
  renderers live under the same rules: their code comes from an APK
  (`apk_data_file`, executable) or anonymous memory, never from app data.
- `isolated_app_all.te`: `neverallow { isolated_app_all
  -isolated_compute_app } service_manager_type:service_manager find` except
  `activity_service`, `display_service` and `webviewupdate_service`: no
  window manager, so no window and no `SurfaceControlViewHost` of its own;
  a surface has to come from droidtop. `neverallow { isolated_app_all
  -isolated_compute_app } gpu_device:chr_file { rw_file_perms execute }`: no
  GPU, so Flutter draws in software. Sockets other than `AF_UNIX` may not
  be created at all.

**The rigs (plugin-enforce-7, v0.2.0-dev.1649 and the fixes after it).**
- **The system-call filter in every plugin process.** The isolated sandbox
  now takes the same seccomp filter as a `gpu.render` process (sockets only
  as local datagrams, no programs, no io_uring, no ptrace;
  `PluginSyscallFilter`). The
  BlueStacks rig runs with SELinux disabled (`getenforce`: Disabled) and its
  isolated UID could open a socket; with the filter the check reads
  "Network: refused" there as on stock Android. Since 2026-10-14 the filter
  also answers the plugin's own files (see "Guarded hooks"), so a process
  that cannot take it does not run the plugin.
- **The 10 s "Waiting for permission service" on BlueStacks (Android 13).**
  Timed step by step: it is the first load of an app library into the
  isolated process (droidtop's own `libdroidtoppy.so`, which links only
  libdl and liblog), and every step after it takes 0 to 2 ms. libbinder logs
  that line from `checkPermission` when the `permission` service cannot be
  found, which an isolated process may not look up. BlueStacks loads every
  app library through its own native bridge (`ro.dalvik.vm.native.bridge` =
  `libnb.so`, which picks Houdini or ndk_translation per app); the stock
  emulator (`libndk_translation.so` directly) shows no wait. droidtop makes
  no such call; the load completes after the wait, inside the 15 s budget.
  A device without that bridge (the Retroid's arm64) is expected not to wait;
  its rig run says.
  Every plugin process paid it, and the processes restart after a minute
  idle, so the first search after a start ran out of its 10 s for several
  sources at once (rig, release 0.2.0-dev.2070, Droidtop/tracker#218). An app
  zygote that preloads the library (Android 10 and later) was tried and
  turned off the same day: on BlueStacks the preload left libbinder's
  ProcessState in the zygote, and every plugin process forked from it
  aborted with "libbinder ProcessState can not be used after fork" (rig,
  release 0.2.0-dev.2097, Droidtop/tracker#218). The wait per plugin
  process stands until a start path that avoids it is found;
  `PluginStartRigTest` measures it, and `PythonPluginStartRigTest` measures
  a python plugin's start step by step (Droidtop/tracker#848); both run on
  the CI emulator, reported only, and by hand on the rig.

**ui.main in the sandbox (plugin-enforce-8, emulator-5560 and BlueStacks).**
A contained plugin's own screen stayed black for two reasons, both rules of
the platform rather than bugs:
- **No vsync.** Choreographer and AChoreographer need a display-event
  connection to SurfaceFlinger, which an isolated process may not look up
  ("avc: denied { find } ... SurfaceFlingerAIDL"; "AChoreographer: Failed
  to initialize"), so the engine never got a frame tick. The guarded hooks
  hide `AChoreographer_getInstance` from the engine's `dlsym`, so it asks
  for vsync through FlutterJNI, and droidtop answers on a 60 Hz clock
  (`IsolatedVsync`).
- **No buffers.** A Surface's buffers come from the graphics allocator HAL,
  whose fds only its clients may use, and an isolated process is never one
  ("avc: denied { use } ... hal_graphics_allocator_default ... tclass=fd",
  then "dequeueBuffer: IGraphicBufferProducer::requestBuffer failed"); AOSP
  grants that fd use only to `hal_graphics_allocator_client` domains
  (`private/hal_graphics_allocator.te`). So no isolated process can present
  into any Surface. The closest workable shape, and the one the owner named
  ("rendering through a droidtop-owned renderer/surface where the plugin's
  draw calls are mediated"): droidtop allocates two frames of shared memory
  (`ASharedMemory_create`, an isolated process may map `appdomain_tmpfs`),
  the guarded hooks give the engine a stand-in window whose `lock` hands out
  a frame and whose `unlockAndPost` reports it (`screen_bridge.c`,
  `ScreenBridge`), and `PluginScreenActivity` paints each finished frame
  into its own surface on a render thread, dropping frames rather than
  queueing them. The cost is one copy per frame in droidtop's process; a
  `gpu.render` plugin is not isolated and draws into the surface directly.

With both in place the screen was still black in every tier, `gpu.render`
included, for two reasons in how droidtop drove the engine:
- **Zero-size layout.** `FlutterRenderer.ViewportMetrics` carries minimum and
  maximum sizes that default to 0; FlutterView sets them equal to the size,
  droidtop did not, so the framework laid the screen out at zero size and
  every frame was empty although Dart reported its first frame built.
  `FlutterDroidtopPlugin.viewport` now sets them.
- **Frames disabled.** The `resumed` sent with the entrypoint reaches the
  engine before the framework's binding listens, so `framesEnabled` stayed
  false. droidtop sends `inactive` then `resumed` again once the framework
  is up (`LifecycleChannel` drops a repeat of the same state).

**Guarded hooks (decided 2026-10-09, owner: "If those plugins can't run
locked down, we have to fix how WE handle them, maybe guarded hooks").**
`SandboxFiles` and `native/src/sandbox_hooks.c`: every file a contained
plugin's code loads by path gets a virtual path under `/droidtop-sandbox/`
for a descriptor `:app` handed over. Since 2026-10-14 (Droidtop/tracker#470,
"merge, not replace") each one is a local file of the sandbox library
(`sbx_local_add`): the process's lockdown traps every call that names a
path, and an `open`, `stat` or `access` of a virtual path is answered from
its descriptor (read only) at the system-call level, whichever library
makes it. Before, only the import slots of the libraries droidtop knew
about were rewritten (`libjavacore`, `libopenjdk`, `libandroidfw`,
`libziparchive`), so a library they missed could not see the files. What
stays a hook (PLT/GOT, the bytehook technique) is code: `dlopen` and
`android_dlopen_ext` in `libnativeloader` and in the Flutter engine once
loaded map a virtual path from the memfd copy with
`ANDROID_DLEXT_USE_LIBRARY_FD` added to the caller's own flags (so a library
ART loads keeps its class-loader namespace and its JNI binding), and the
engine's `dlsym` and window hooks. A virtual path nobody registered is
`ENOENT`. The Containment check reports which libraries were hooked and how
many import slots.

**The plugin's own folder by path (2026-10-14, Droidtop/tracker#470).**
Every plugin process loaded from descriptors, contained or open,
gets a file broker in :app (`SandboxBroker`, `native/src/plugin_broker.c`),
and its lockdown sends droidtop's private folder to it under every name
code uses for it (`/data/user/<n>/<package>`, `/data/data/<package>`, the
device-encrypted `/data/user_de/<n>/<package>`). The broker maps those
names to the plugin's own folder (`plugins/<id>/data/app-data` and
`app-data-de`, beside its data store, removed with it), read and written.
So `getFilesDir`, `getCacheDir`, `getDatabasePath`, Flutter's
`path_provider` and `app_flutter` and SQLite find real folders, and
droidtop's own files are not among them under any name. One limit in the
contained tier (emulator-5560, 2026-10-14): SELinux lets an isolated
process use a file the app hands it but not list a folder it hands it
(`avc: denied { read } ... app-data/code_cache ... isolated_app`), so code
that lists its folder fails there (ART then skips its vdex cache); a
open process, under droidtop's UID, lists them. A contained process locks down in passthrough
(`SBX_LOCK_PASSTHROUGH`): only those names go to the broker, and every
other path is made by the handler with the kernel deciding, where the
isolated UID and SELinux keep droidtop's files out as before. The
Containment check's "droidtop's files" line looks for droidtop's
`files/plugins` folder by its own name, which in a plugin process is the
plugin's folder, where there is none.

**Preferences and SQLite are droidtop's (decided 2026-10-29,
Droidtop/tracker#741).** The first emulator run of `ContainedFilesRigTest`
(Android 13 and 14) found Android's own Java paths to preferences and SQLite
failing in an isolated process before any file is touched:
`getSharedPreferences` first asks the user service whether storage is unlocked
(an isolated process cannot look it up), and opening a `SQLiteDatabase` first
reads a global setting through the settings provider (an isolated process may
call no content provider). The owner: "Android updates can't be allowed to
affect this", and "Plugins don't have to be given access to ANY android
internals". So:

- **A plugin depends on the plugin API only**, never on Android's services or
  on which Android it runs on. Where a plugin reads an Android version, it is
  a value droidtop's plugin runtime defines (the API level droidtop is
  prepared to handle, in host info and in the runtime's environment), not the
  device's, and nothing is intercepted to make it so.
- **Preferences.** droidtop's own store (`ContainedPreferences`): a map in
  memory and one file per name at `<private folder>/shared_prefs/<name>.xml`,
  in Android's own XML format (so a plugin's settings read the same in either
  tier, after a move between them, and from a file Android wrote, its `.bak`
  of an unfinished write taken first), written by a temporary file renamed
  over the old one, `commit` and `apply` in order on one writer thread,
  listeners on the main thread (a `clear` heard as one null key). A Flutter
  plugin's engine is built in a plugin process with droidtop's
  `PluginProcessContext`, which is its own application context and whose
  `getSharedPreferences` is this store, so `shared_preferences`' Android half
  gets it unchanged (its DataStore-based API writes plain files and needs
  nothing). A Kotlin plugin gets no Android `Context`: it has
  `PluginContext.preferences(name)`, and `privateDataDir()` is its own folder
  by path in every tier. Both tiers, contained and open, get the same store.
- **SQLite** is a bundled SQLite at a pinned version, never Android's
  framework SQLite: the amalgamation named by `plugin-host/sqlite.pin`
  (3.54.1, checked against the SHA3-256 sqlite.org lists by
  `build-scripts/fetch-sqlite.sh`), built into droidtop's native library with
  its symbols hidden (FTS3/4/5, R*Tree and the math functions on; temporary
  tables in memory, since a plugin process has no temporary folder). A
  Kotlin plugin opens one with `PluginContext.database(name)`
  (`<folder>/databases/<name>`; `execute`, `insert`, `update`, `query`,
  `cursor`). In a Flutter plugin's engine droidtop answers `sqflite`'s
  channel (`com.tekartik.sqflite`) itself, after the plugin's registrant, so
  the package's Dart half is unchanged (`SqfliteCalls`): all sixteen methods
  of its Android half (`sqflite_android`, read at tekartik/sqflite 52dbf98),
  with its replies, its error codes and data, its transaction ids and paged
  cursors, and SQLite's errors worded as Android words them ("... (code 2067
  SQLITE_CONSTRAINT_UNIQUE)"), which the Dart half reads. Where it differs
  from the Android half:
  - `getPlatformVersion` answers droidtop's own Android ("Android 14", its
    target SDK), never the device's.
  - `androidSetLocale` is accepted and not honoured: SQLite's own collations
    only, no ICU.
  - A `BEGIN` inside an open transaction fails as SQLite fails it ("cannot
    start a transaction within a transaction"); Android's session layer
    turned it into a savepoint. `sqflite`'s own `transaction()` never nests.
  - No `android_metadata` table is created in a new database.
  - One worker thread for every database (`androidThreadCount` and
    `androidThreadPriority` are accepted and not used), and the
    `com.tekartik.sqflite.wal_enabled` manifest flag is not read: a plugin
    turns WAL on with `PRAGMA journal_mode=WAL`.
  - `options`' `logLevel` is kept for `debug`, and nothing is logged.
- **The guard** is the emulator job: `ContainedFilesRigTest` runs on Android
  13 and 14 and must answer the same on both. Its probe uses only what
  droidtop gives a plugin (its `PluginContext`), never Android's context or a
  hidden API.

**What a contained plugin's own folder holds (design 2026-10-16,
Droidtop/tracker#375).** Decided by the coordinator, 2026-10-16:
"Contained Flutter plugins get shared_preferences, path_provider and `open`
through droidtop, so standard Flutter packages just work while contained.
It keeps contained-first, and it keeps the machinery invisible to plugin
authors and users." On listing: "don't leave listing as a permanent limit if
the broker can answer it." The folder above is the mechanism; what it serves:

- **Packages that keep files.** `path_provider` (Flutter's `app_flutter`,
  `getFilesDir`, `getCacheDir`), `shared_preferences` (droidtop's own
  preferences store since 2026-10-29, "Preferences and SQLite are droidtop's"
  below; its files under `shared_prefs`, written by a temporary file and a
  rename, both brokered) and `sqflite` (the database under
  `databases`, with its `-journal`, `-wal` and `-shm` files: each is created,
  opened, renamed and unlinked by path, all brokered; its locks are `fcntl`
  on the descriptor, which never leaves the process). The WAL's `-shm` is
  mapped from a brokered descriptor; if SELinux refuses an isolated process
  that mapping, SQLite reports it on opening in WAL mode, and `sqflite`'s
  default (a rollback journal) needs no mapping. Nothing here is new to a
  plugin author: the packages use the paths Android gives them.
- **Listing a folder.** `getdents64` names no path, so it never reached the
  broker, and SELinux refuses an isolated process a read of a folder :app
  opened (`avc: denied { read } ... isolated_app`). The lockdown now traps
  `getdents64` in passthrough. The handler first lists the folder itself (its
  own call is let through by a mark in the upper half of the count argument,
  which the kernel ignores); when that is refused it sends the descriptor
  to the broker (`SBX_LIST`), which lists it only if it resolves under a rule
  the process may read, with the same open file description, so the
  position moves for both and `rewinddir` still works. One reply carries at
  most 32 KiB of records; a larger read is answered in pieces, as the
  kernel may. So `Directory.list`, `listFiles` and SQLite's own folder checks
  work in the contained tier.
- **`chmod` and `utimes`.** In passthrough these went to the kernel, which
  answers `ENOENT` for an aliased name (Android's `FileUtils.setPermissions`,
  called by `getDir` and `SharedPreferences`, logged "Failed to chmod"). The
  lockdown now traps `fchmodat` and a path `utimensat` (and their old forms)
  like the other path calls, and a claimed path goes to the broker
  (`SBX_CHMOD`, `SBX_UTIMENS`), which applies them to the real file under a
  write rule (the mode limited to the owner's bits and below `0777`). Any
  other path still goes to the kernel.
- **The quota.** The folder is inside the plugin's data store, so the
  store's limit (`PluginDataStore.LIMIT_BYTES`, 512 MiB) covers both. The
  broker's decider in :app (its rules on the folder carry `SBX_DECIDE`)
  checks every new file (an open with `O_CREAT` of a name not there yet)
  and every `mkdir` in the folder: at or over the limit it answers
  `ENOSPC` ("No space left on device": Dart's `FileSystemException`,
  SQLite's `SQLITE_FULL`), never a crash. Opening a file that exists, for
  reading or writing, and renames stay allowed, so a full plugin can still
  open its database and free space. Usage is the folder's size, measured at
  most once a second and kept between. A write on a file already open never
  reaches the broker (a descriptor's writes stay in the process), so a
  contained process also runs with `RLIMIT_FSIZE` at the limit and
  `SIGXFSZ` ignored: one file can never pass the whole quota (that write
  gets `EFBIG`), and the next new file after the folder passes it gets
  `ENOSPC`. An open process gets no such cap: it may write shared storage
  it was granted, where a sync engine's files can be larger.
- **`open` over Flutter's `hostCall`** (kind parity with
  `PluginContext.openFile` and `droidtop.host.open`). The channel method
  `hostOpen` takes the same `{api, version, op, args}`; droidtop makes the
  call through `openFile`, gives the descriptor a path of its own,
  `/droidtop-sandbox/open/<n>`, as a local file of the sandbox library
  (`sbx_local_add_mode`: read, and written when the descriptor was opened
  for writing), and answers the broker's reply with that `path` added to
  its `data` (`FlutterOpenFiles`). Dart opens it with `dart:io` like any
  file; every open of that path is the same descriptor, so they share one
  position, and `O_TRUNC` empties it. `hostClose {path}` lets it go (the
  path then names an empty file), and a plugin keeps at most 64 at a time
  (`RATE_LIMITED` past that). A plugin with full access, where nothing
  stands between it and the kernel, gets `/proc/self/fd/<n>` of the
  descriptor itself.
- **How it is checked.** The library's own tests (sandbox 45e476d) cover
  each trap with a broker in one process. The SELinux refusal and the
  isolated UID exist only on a device, so the rig check is an
  instrumentation test that loads a probe plugin contained, with droidtop's
  own runner, and does each item above the way Android's own classes do
  (`ContainedFilesRigTest`, run by hand: `adb shell am instrument -w -e
  class dev.droidtop.app.ContainedFilesRigTest
  dev.droidtop.app.test/androidx.test.runner.AndroidJUnitRunner`). It
  reports each check by name, so a refusal (the WAL's `-shm` mapping
  above, say) names itself.

**Open processes: one hole per permission (2026-10-15,
Droidtop/tracker#553).** The graphics tier below became the first of a
family. The owner's decision for Syncthing (a plugin that dials peers and
listens for them itself): one non-isolated plugin process type, where each
granted permission opens only its own hole, never a separate process family
per permission. So `PluginTier.OPEN` is a process of droidtop's UID that is
**not** isolated (`PluginOpenService`, slots `:plugin_open0..7`), loaded
exactly as a contained plugin is, and its holes are the hole permissions
the plugin declared and the person granted (`PluginTiers.holes`):

| Hole permission | What it opens | What stays shut |
| --- | --- | --- |
| `net.sockets` | internet sockets. From Android 11 (API 30) the process is isolated, under a UID of its own, and the sockets are made, connected, bound and accepted by droidtop's broker (`SBX_LOCK_NET_BROKERED`, 5.6), with name lookups through it; below API 30, a process of droidtop's UID with the sandbox library's `SBX_LOCK_INET` (`AF_INET` and `AF_INET6` stream and datagram sockets, `bind`, `connect`) until the broker's byte relay is built | the graphics devices; every other socket family; netlink |

`gpu.render`, the graphics-chip hole, is **retired** (2026-10-16, 5.6):
plugins never get the GPU. A manifest that still declares it is told the
permission is not supported by this version of droidtop and runs contained.

- **Each hole is its own grant.** `net.sockets` is the one hole left; it
  opens no graphics device. A hole the plugin did not declare is never
  opened. Full access and a
  plugin that cannot run contained get none (`PluginTiers.holes`).
- **Who decides.** :app writes the granted holes into the load request
  (`_droidtopHoles`, replacing anything a plugin's own manifest says) and
  the process builds its filter from them (`PluginSyscallFilter.install`).
- **One process, one plugin, for its whole life.** A seccomp filter can
  never be lifted, so an open slot is never shared: the process serves the
  plugin it started for and ends when :app lets it go (`PluginOpenService`
  ends itself when unbound; `PluginProcesses.endOpenLocked`), and its slot
  is not used again until that process's death has been seen. A changed
  grant, an update or an idle minute therefore starts a fresh process with
  the holes granted then.
- **What the network hole cannot tell apart.** seccomp cannot read the
  address `connect` or `bind` is given, so a plugin with `net.sockets`
  reaches this device's own services on loopback as well as the internet;
  the permission's warning says so ("also to other apps on this device").
  Android's own resolver and bionic's `connect` talk to netd over Unix
  stream sockets, which stay refused, so a networked plugin resolves names
  itself against the DNS servers of the active network (Go's pure resolver)
  and makes its system calls directly, as Go does. Interface listing
  through netlink stays refused, so droidtop hands the plugin both:
  `net.get_links` (D12) lists each network's interface, addresses, DNS
  servers and search domains from Android's own view of it, and the
  plugin resolves names against those servers (Go's pure resolver, its
  dialer pointed at them) and binds and announces on those addresses.
- **Shared folders by path (2026-10-16, Droidtop/tracker#553).** An open
  process also reaches, by path, the shared-storage folders its granted
  `files.shared.read` and `files.shared.write` entries declare (D5), on
  every mounted volume, while droidtop has all-files access: the file
  broker's rules hold them, read or read and write as granted
  (`SharedFiles.brokerRules`). It is the same scope the `files.shared` ops
  apply: never `Android/data` or `Android/obb`, nothing that climbs out
  with `..`, links resolved before the rule decides; a whole volume (`"*"`)
  keeps `Android` out in every spelling of its case, because shared storage
  matches names without case and the broker's rules match them exactly. A
  sync engine (the Syncthing plugin) works on games, ROMs and saves in
  place. A changed `files.shared` grant retires the process, as a changed
  hole does, so a refused folder is gone at once. A contained process has
  no such rule: its isolated UID cannot reach shared storage, and the D5
  ops are its way there.
- **The Containment check** names the holes ("A process of its own with
  graphics access and its own internet connections ...") and its network
  line shows what the filter reported.

**The graphics tier (`gpu.render`, built plugin-enforce-4, 2026-10-09;
narrowed plugin-enforce-6, 2026-10-09).**
Some plugins need the GPU and nothing else: a Flutter screen that janks in
software, a small game engine. An isolated process may never open the GPU
(the `gpu_device` neverallow above), so these would otherwise be pushed to
full access, which the owner rejected: "We can grant GPU access if needed.
We don't have to full isolate. Granting a single risky permission does NOT
automatically grant everything." So `gpu.render` is a dangerous permission
the plugin declares and the person ticks on the usual approval list, and it
opens that one hole:

- **The platform shape.** Granting it runs the plugin in a droidtop-owned
  open process (`PluginOpenService`, slots `:plugin_open0..7`, until
  2026-10-15 `PluginGpuService` on `:plugin_gpu0..7`) that is **not**
  `android:isolatedProcess`. That is the only process an app may run that
  can open the GPU (see "Narrowing it further" below for every alternative
  looked at). droidtop's application classes start nothing in it
  (`IsolatedProcess.runsPluginCode`): no crash reporter, no database, no
  sign-in is held in memory beside the plugin's code.
- **What is still locked down.** The plugin's code is loaded exactly as a
  contained plugin's is: from descriptors through `InMemoryDexClassLoader`
  and the guarded hooks, with a broker-only `PluginContext` (no root, no
  Shizuku; droidtop's private folder is the plugin's own, as in the
  contained tier). Every host API it uses goes through the
  broker with the same grant, scope, quota and audit. It draws into a
  droidtop-owned `Surface` (`PluginScreenActivity`), with hardware instead
  of `--enable-software-rendering`.
- **The system-call filter (plugin-enforce-6; since 2026-10-10 the sandbox
  library's, Droidtop/tracker#470).** Before any of the plugin's code
  loads, the process installs a seccomp-bpf filter on every thread
  (`native/src/plugin_filter.c`, `PluginSyscallFilter`). It is the
  lockdown of the sandbox library droidtop shares with Enginehost
  (`vendor/sandbox`, bi0shacker001/sandbox, GPL-3.0), brokered (every path
  call traps; see "The plugin's own folder by path"), with
  `SECCOMP_FILTER_FLAG_TSYNC` after `PR_SET_NO_NEW_PRIVS`, so nothing in the
  process can lift it. It refuses `socket()` for every family but
  `AF_UNIX`, and `AF_UNIX` for everything but datagrams (liblog's socket to
  logd, which `connect` may still reach), with `EACCES` as an isolated
  process gets: no internet socket and no DNS lookup (netd's resolver
  socket is a stream socket), and no stream socket to a root manager's
  daemon or to a relay droidtop runs. It also refuses `bind`, `execve`,
  `io_uring` (its operations open files and sockets without passing through
  the filter), `ptrace` and `process_vm_readv`/`writev`, `bpf` and
  `perf_event_open` (`EACCES`); new namespaces and mounts, the kernel
  keyring, pidfds and a signal to any process but its own (`EPERM`); and any
  foreign system-call ABI (x86_64's x32 numbers). The library's own test
  runs every one of these as an escape attempt, first unconfined as a
  control, on Linux and on the Android emulator at API 28 and 34. A process
  that cannot take the filter does not run the plugin. The Containment check (Advanced)
  shows "System-call filter: on: ..." and the network line reads "refused".
- **Files by path go to a broker (since 2026-10-10, Droidtop/tracker#470).**
  seccomp sees system-call numbers and integer arguments only ("BPF
  programs may not dereference pointers", kernel
  Documentation/userspace-api/seccomp_filter.rst), so it cannot tell an
  `openat` of the GPU's device node from one of droidtop's database. So
  every call that names a path traps instead, after Chromium's syscall
  broker: the sandbox library's handler sends it over a socket :app handed
  the process with its descriptors (`ContainedFiles.BROKER`) to a broker
  thread in :app (`SandboxBroker`, `native/src/plugin_broker.c`), which
  answers from rules holding the system's read-only code and data,
  droidtop's APK and the device nodes a graphics driver opens, and nothing
  of droidtop's private data; every refusal is logged under
  `droidtop.sandbox` with the plugin's id. The broker resolves with
  `readlink` alone and makes only the call asked for, and acts from a
  re-checked parent directory, so a symlink swapped in cannot lead it out.
  Device files the kernel ties to their opener (hwbinder, kgsl, Mali, DRM
  render nodes) are opened by the process just before the lockdown and
  duplicated afterwards, and a stat or access of their path is answered
  from that descriptor (libhidl checks `/dev/hwbinder` with `access` before
  it opens it; refused, it finds no gralloc mapper and the process aborts,
  emulator-5560, 2026-10-14); the process is made dumpable so the broker, under
  the same UID, can answer for its own `/proc` entries (ptrace stays
  refused). The plugin's own files keep arriving as descriptors through
  the guarded hooks.
- **What it cannot stop, stated plainly.** The process still has droidtop's
  UID, and a filter cannot look inside a binder call. The plugin's own
  native code (and a Flutter plugin's Dart, which is machine code in
  `libapp.so` with `dart:ffi`) could still ask Android's services for
  things as droidtop (the system download manager, media store, starting
  activities, droidtop's own components, Shizuku if droidtop's UID was
  allowed it), below the broker and unseen by it.
- **So the person is told, in plain words** (owner, 2026-10-09: "no
  sandboxing is perfectly effective, and as with any third party code, users
  are cautioned to be careful what they install ... if it's not mitigatable,
  make the warning more explicit"). The approval line reads "Use the
  graphics chip to draw its screen", with the detail "A plugin with this
  could reach past droidtop's checks, so only allow it for a plugin from a
  source you trust" (`PluginPermission.caution`). While it is granted, the
  plugin's page carries the row "Could reach past droidtop's checks" with
  the same sentence (`PluginTiers.caution`), its Permissions screen shows the
  sentence on the permission's own line (where it can be blocked again), and
  its Activity screen says that what it did past those checks would not be
  listed. No tier name appears anywhere a person reads.
- **The general rule.** One permission opens one hole. Full trust stays
  reserved for UID-level privilege that no per-permission process can give:
  Shizuku, root and `apps.bind`.

**Narrowing it further: what was looked at (Droidtop/tracker#378,
2026-10-09; reference material only, no probes).**

| Idea | What the platform allows | Outcome |
| --- | --- | --- |
| seccomp-bpf installed by the app itself | Allowed. Chrome adds its own seccomp-bpf filter to its Android renderers from inside the app process ("Chrome applies a layer-two sandbox in the form of a Seccomp-BPF system call filter", Chromium docs/security/android-sandbox.md; `sandbox/linux/seccomp-bpf-helpers/seccomp_starter_android.cc` starts it `MULTI_THREADED`, i.e. with TSYNC). Android's own app filter (bionic `libc/seccomp/seccomp_policy.cpp`) stays underneath: "seccomp-bpf policies stack and can only be made more restrictive" (same doc). The earlier note here that "Android gives apps no seccomp" was wrong. | **Built** (above). Filters numbers and integer arguments only, so it closes the network, `io_uring`, `ptrace` and root-daemon sockets, not paths or binder. |
| An app zygote (`android:useAppZygote`, `ZygotePreload`) to set the process up before it runs | Only for an `isolatedProcess` service ("An `isolatedProcess <service>` can be declared with `android:useAppZygote`", android-sandbox.md, "Zygote"), and the `app_zygote` domain is "allowed to fork into `isolated_app`" only, which may not open the GPU. | Not usable for a GPU process, and not needed: the filter is installed in the process itself before the plugin loads. |
| A restricted class loader plus an allowlist of native libraries | The class loader already gives the plugin nothing but its broker. A class loader cannot stop JNI or reflection inside the same process, and a Flutter plugin's Dart is native code (`libapp.so`, `dart:ffi`). An allowlist of library hashes would admit only libraries droidtop has reviewed, which is origin trust (Official), not a sandbox. | Not done. |
| A file broker: trap `open` and let droidtop open only allowed paths | What Chrome's Linux GPU process does: a broker process with per-GPU-vendor file lists, and the driver loaded and warmed up before the sandbox starts (`content/common/gpu_pre_sandbox_hook_linux.cc`, e.g. `AddArmMaliGpuPermissions`; `sandbox/linux/syscall_broker/broker_process.h`). On Android Chrome does not do this: its GPU process runs "under untrusted_app and the same UID as the browser process ... no privilege separation" (android-sandbox.md). Each GPU vendor's driver opens its own device nodes, firmware and caches, so the lists are per driver and only a device shows them; and binder would stay open regardless. | Not done: driver-specific, untestable off-device, and it would not close the binder path, so the warning stays either way. Recorded as the next step if a plugin ever needs more than the warning. |
| Mediated rendering: the plugin stays isolated and sends GL/Vulkan commands to a droidtop-owned renderer | The design that closes the hole: Chrome's renderers never touch the GPU and send commands through the GPU process's command buffer; the Android emulator's gfxstream and Mesa's virtio-gpu Venus forward GLES/Vulkan the same way. It means an encoder the isolated plugin's engine loads as its graphics driver and a validating decoder in droidtop for the whole API subset Flutter and an engine use. | Not done: a graphics-stack project far larger than one permission, with a per-frame cost on a handheld. |
| `AHardwareBuffer`/`SurfaceControl` handed from an isolated process to a droidtop renderer | An isolated process can be handed buffers and write them with the CPU (that is what contained Flutter does today), but drawing into them with the GPU needs the GPU device it may not open; and it may not reach the window manager for a `SurfaceControlViewHost` of its own (servicemanager `find` only `activity`, `display`, `webviewupdate`). | Gives nothing beyond the software path that exists. |
| `isolated_compute_app` | Selected by `isIsolatedComputeApp=true` in `seapp_contexts`, which the system sets for its own services; AOSP warns it "should not be used unless it is intended to provide isolated processes with relaxed security restrictions". Its policy (`private/isolated_compute_app.te`) is exempted from the `gpu_device` neverallow but grants no `gpu_device` access itself. | Not available to an app, and would not give the GPU anyway. |
| Landlock (unprivileged path rules, Linux 5.13+) | Would close the file path without a broker, but needs a 5.13 kernel with Landlock enabled; a Snapdragon 865 handheld such as the RP5 runs a 4.19 kernel. | Not usable on the target device. |

- **Full trust, by grant.** droidtop's UID, but **one process per
  plugin** from eight declared slots (`:pluginhost`, `:pluginhost1` to
  `:pluginhost7`; beyond eight in use at once the least recently used slot
  is shared), so no plugin can reach another in-process (T8). It is needed
  for:
  - the Shizuku and root providers (their privilege is granted per UID;
    the Shizuku binder arrives in droidtop's main process, whose provider
    hands it on, and the slots ask for it through Shizuku's multi-process
    support; the provider is not in a slot, so droidtop's start never waits
    on another process to publish it, Droidtop/tracker#775);
  - plugins that bind other apps' services (`apps.bind`: an isolated
    process cannot bind);
  - contract 1 plugins, which keep running full-trust with the badge
    "Full access (older plugin)".
  - It needs `host.full_trust` (critical). A contract 2 plugin that cannot
    run contained must declare it or is refused at install; until it is
    granted the plugin is not called (during a call the person started the
    first-use sheet asks), and its badge reads "Full access" once it is.
- **What this buys.** For contained plugins, the permission table in §4
  becomes an actual boundary, and the audit log becomes complete.
- **What it costs.**
  - A broker round trip for network and files.
  - One process per plugin in use. Mitigated by lazy loading and the
    60 s idle unload (§1.5); a contained plugin starts afresh after it.
  - A python plugin's first start in a new process compiles the standard
    library modules it imports from the zip (no bytecode is written).
- **What it cannot fix.** Full-trust plugins. That is why they are
  limited to official origins by default, and why each one is its own
  process.

### 5.6 Every path but the API (audit, 2026-10-16, Droidtop/tracker#53)

The audit the rule "Everything goes over the API" (top of this document)
asks for. Its scope, from the coordinator's package of 2026-10-16:

- **Into droidtop, every kind and every tier (full access included):**
  class loading (the parent class loader, reflection into droidtop's
  classes, its singletons); the Android context plugin code holds
  (`getApplicationContext`, droidtop's resources, preferences, databases
  and files); droidtop's components (activities, services, receivers,
  providers), intents and PendingIntents; binder services, a shared UID
  and process memory; files outside the plugin's own folder and the
  broker's passthrough claims; environment variables and inherited
  descriptors.
- **Into the OS, contained and open tiers:** direct use of Android's APIs
  and system services is blocked except for the hole a granted permission
  opens. A full-access plugin's direct OS use (the Shizuku bridge) is
  allowed and is not listed as a finding.

Each path is listed with its status, the commit that closed it and the
test that proves it closed; the table is posted on Droidtop/tracker#53.
Kinds: `native_bundle`, `python`, `flutter_embed` (there is no
application-interface kind). Tiers: contained (an isolated process),
open (`:plugin_open*`, droidtop's UID), full access (`:pluginhost*`,
droidtop's UID).

| Path | Tier | Status | Closed by | Test |
| --- | --- | --- | --- | --- |
| Driving another plugin process (`IPluginRuntime`: `loadPlugin` of a folder the plugin wrote, so unsigned code in a full-access slot; `invoke`/`handle` on another plugin) by binding droidtop's non-exported plugin services as droidtop's UID | open, full | closed: every call is refused unless it comes from droidtop's own main process, by the system's record of that process (`AppCaller`, `DroidtopCallers`); an isolated process keeps the pid of its first caller of droidtop's UID, which is droidtop binding it. A one-way call (a screen's `screenTouch`, `screenKey`) has no caller pid, so it carries a secret the process made, which droidtop fetches once over the pid-checked `inputToken` after `attachScreen`, and is refused without it or from another UID (Droidtop/tracker#511: the pid check refused every touch and key, seen on the emulator rig) | the audit closings commit; #511 | `DroidtopCallersTest` |
| Forged reports into droidtop (`IPluginRuntimeCallback`: a crash report that disables another plugin, frames into another plugin's screen) | all | closed: each process has its own callback, which believes a report only about a plugin loaded in that process | the audit closings commit | `DroidtopCallersTest` (ownership) |
| droidtop's files through `DroidtopFileProvider` (its root is the whole file system; non-exported, but droidtop's UID passes) | open, full | closed: a plugin process (or an isolated UID) is refused | the audit closings commit | `DroidtopCallersTest` (process names) |
| Shizuku's binder through its provider (OS rule) | open | closed: only droidtop and `:pluginhost*` get it; the Shizuku server still delivers it | the audit closings commit | `DroidtopCallersTest` (`mayUseShizuku`) |
| A contained slot below API 29 reused for a second plugin (its filter and broker stay the first plugin's) | contained, API 26-28 | closed: a slot is one plugin's and its process ends with it | the audit closings commit | `PluginSlotsTest` |
| Files outside the plugin's own folder | contained | closed: the isolated UID and SELinux; droidtop's private names are the plugin's own folder | 2026-10-14 to 10-16 | `ContainedFilesRigTest` (rig), sandbox `sbx_test` |
| `/proc` of other processes | open | closed: the broker refuses another pid | sandbox library | `sbx_test` |
| Environment and inherited descriptors | all | closed: only the python variables are set; the broker socket is close-on-exec, load descriptors are closed after the load, exec is refused | earlier | `sbx_test` (exec) |
| droidtop's other non-exported components (services started by intent, receivers, activities, `LauncherProvider` and the shell's providers) as droidtop's UID | open, full | open tier: closed from Android 11 (an isolated UID cannot reach droidtop's non-exported components); **open** below 11 and for full access: a start, a broadcast or an activity launch carries no caller Android lets droidtop check before API 34 | the separate-UID commit; full access waits on the owner | none |
| droidtop's Android permissions through binder (the OS rule) | open | closed from Android 11: the plugin runs isolated under a UID of its own with no permission, its sockets the broker's (`SBX_LOCK_NET_BROKERED`); **open** below Android 11 until the byte relay | the separate-UID commit | sandbox `sbx_test` (net checks), `PluginTiersTest` |
| droidtop's classes, Application and files in-process | full | **open by design today** (droidtop's UID, the whole Application started, droidtop's class loader as parent) | the separate-UID decision below | none |
| droidtop's classes in-process (no filtering class loader) | contained, open | partial: loadable, but droidtop's Application returns early there, so its state never starts | to do | none |
| Several full-access plugins in one slot past eight | full | partial: they share droidtop's UID anyway | the separate-UID decision below | none |

**A UID of its own, as a sandbox feature (design, 2026-10-16).** The owner,
on a companion app, verbatim: "companion app? This should be a sandbox
functionality". So every plugin process runs under an isolated UID, and
what an open or full-access plugin may do is granted by the sandbox
library's broker, never by sharing droidtop's UID. What Android allows an
isolated process, with AOSP sources, is in the sandbox's `docs/design.md`,
"A UID of its own for every confined process". In short:

- **Network (`net.sockets`): yes, through the broker.** An isolated process
  can never create an internet socket, but from Android 11 (API 30) it may
  use one droidtop created and handed over (no `ioctl`, no `listen` or
  `accept`). The broker opens and connects sockets for the plugin, accepts
  on a listening port the plugin was granted and hands each connection
  over, and answers DNS. Below API 30 the plugin gets a Unix stream the
  broker splices to the network socket. Its traffic is droidtop's on
  Android's per-app network rules.
- **The GPU: not inside an isolated process, on any Android version**
  (sepolicy `neverallow` on `gpu_device` for `isolated_app_all`). The
  sandbox's `own_opens` (a device the process opens itself before the
  lockdown) is how a confined process keeps the GPU, and it works only in
  its app's own SELinux domain, so it stays the GPU mechanism for confined
  processes that keep it (windowcast, Enginehost) and cannot serve an
  isolated plugin. **Decided by the owner (2026-10-16), verbatim:** "We
  already handled the GPU stuff with the 'create abd collapse' design in
  our sandbox library we use for windowcast, right? Plugins shouldn't be
  drawing stuff themselves anyway, but why is it an issue?" So plugins
  never get the GPU: they render through droidtop's declarative views, and
  a contained Flutter screen keeps sending CPU frames droidtop composites.
  `gpu.render` is retired (no permission, no hole; a manifest declaring it
  gets "not supported", never a refusal). No official plugin declared it.
- **Direct OS use for full access: not under an identity of its own.** An
  isolated UID holds no Android permission and can never be granted
  Shizuku (Shizuku authorizes by the caller's package).

**Every plugin is isolated, full access included (decided by the owner,
2026-10-16).** Verbatim: "every plugin is isolated, regardless of access.
We have to assume that plugins are untrusted third party code from our
perspective, so full access does NOT mean they get to fuck with stuff not
prescribed by the API". So:

- **No plugin runs under droidtop's UID, at any tier.** Contained, open and
  full access are all isolated processes under UIDs of their own, loaded
  from descriptors through droidtop's broker. The full-access process
  slots under droidtop's UID (`PluginRuntimeService`, `:pluginhost*`) go,
  and so does the open process type once the broker's byte relay covers
  the network below Android 11.
- **Full access reaches the OS and Shizuku only through the binder relay,
  and the relay is allowlist-only.** A full-access API point (the dedicated
  restricted points of "Everything goes over the API", above) declares the
  system services, and within each the transactions, that it prescribes;
  the relay forwards exactly those, under droidtop's identity, and refuses
  the rest. It is never a generic pass-through: a binder with no
  allowlist entry is never handed to a plugin, and a transaction code not
  listed for its service is refused. Anything addressed to droidtop's own
  components (its package in an intent, a component name, a content
  authority) is refused whatever the allowlist says. Each forwarded call is
  logged with the plugin and the point it came under, like every broker
  call.
- **Shizuku moves onto the relay.** Shizuku authorizes droidtop's package;
  droidtop holds Shizuku's binder and hands the Shizuku plugin a relay to
  it whose allowlist is the plugin's declared `priv.*` points (a shell
  command for `priv.shell`, the package calls for `priv.packages`), so the
  plugin's Shizuku calls are made as droidtop and only those calls.
  Confirmed through the coordinator with the owner's approval, 2026-10-16:
  "Every plugin is isolated", full access only through what the API
  prescribes, and "Shizuku reaches Android only through the relay's
  allowlist". The earlier "Shizuku does need direct access to android
  APIs" means Android's APIs reached through the relay, not a plugin
  process with droidtop's identity.

Built (2026-10-16): an open plugin's `net.sockets` runs isolated on
Android 11 and later (`PluginTiers.runsIsolated`; the sandbox's
`SBX_LOCK_NET_BROKERED`; droidtop's broker grants out, in and name lookups,
and connects netd's `dnsproxyd` and `fwmarkd` for it). Below Android 11 it
stays in an open process of droidtop's UID until the broker's byte relay.
Built (2026-10-16), behind a switch: full access isolated and the Shizuku
relay.

- **The switch.** `adb shell setprop debug.droidtop.isolate_full 1`
  (`PluginTiers.fullAccessIsolated`), off by default. With it on, a
  contract 2 plugin allowed full access on Android 11 or later loads like a
  contained one, in an isolated process with the broker's network
  (`net.sockets`) and a contained process's files; everything else is the
  API's. A contract 1 plugin stays under droidtop's UID, an open row in
  the table above. The default changes only after every official and
  gamegrab plugin has passed on CI and on the rig, with the results on
  #53, as the owner asked.
- **The relay** (`BinderRelay`). A `Binder` in droidtop's process that a
  plugin's process holds in place of a service's. A transaction code
  outside the call range is refused whatever the allowlist says (a dump, a
  shell command, an interface query answered by the relay itself). Every
  other code goes to the service's policy, and every decision goes to the
  plugin's activity log. A refusal reaches the plugin as a
  `SecurityException` naming why, never a crash of droidtop.
- **Shizuku.** The new full-access permission `os.shizuku` ("Use system access
  through droidtop", critical). droidtop hands it to the plugin with
  `IPluginRuntime.attachRelay`, and again whenever Shizuku sends droidtop a
  new binder. `ShizukuRelayPolicy` forwards the state reads, droidtop's
  permission check and request, and attaching the client. It forwards
  `newProcess` only for a plugin that exports a command point (`priv.shell`,
  `priv.packages`, `priv.settings`), and never with an argument naming
  droidtop's package. It never forwards the raw `transactRemote`, user
  services, system properties, or stopping Shizuku.

Needs a rig check: whether an isolated process may use the pipes of a
process Shizuku started in the shell's domain. Until the per-plugin runs
pass, the full-access rows above stay open.

---

## 6. Mapping today's plugin API onto this model

Everything below keeps working unchanged for contract 1 manifests. One
translation function (`LegacyManifest.toV2`, P0-2) turns a v1 manifest
into the v2 shape at parse time, so the host has one model internally.

| Today (code) | v2 | Implicit v2 permissions for a v1 plugin | Callers today |
| --- | --- | --- | --- |
| `acquire_content` (`PluginCapability.ACQUIRE_CONTENT`) | EP `library.sources@1` (A2); `invoke{action:search}` → `search`, `startJob{action:download}` → `acquire` | `provide:library.sources`, `library.folders.write` (the destination only), `net.any` | `AcquireContentSources` → Get games (system settings, gamelist Options) |
| `metadata_source` | EP `library.metadata@1` (A3) | `provide:library.metadata`, `net.any` | the scrape job (`PluginMetadataSources`) |
| `library_action` | EP `ui.context_action@1` (C4) with `targets: [game]` | `provide:ui.context_action`, `library.read` | **none**: declared, never called |
| `status_tile` | EP `ui.status_tile@1` (C3); also rendered as a read-only C2 quick tile | — | `PluginStatusWidgetProvider`, the Plugins screen's debug row |
| `settings_rows` | EP `ui.settings@1` (C1), `target=global` → `plugin`, `system:<id>` unchanged | — | `PluginSettingsRows` |
| `app_status` | EP `apps.bridge@1` (F1); `action:status` → `status`, `action:launch` → `action{id:launch}`, the text-entry job hint → C14 `text` + job | `provide:apps.bridge`, `apps.check`, `apps.launch` | `PluginAppStatus` (Plugins screen, Player choice screen) |
| `startJob` / `cancelJob` / `PluginJobsCenter` | J1, unchanged | — | Get games, app_status jobs, event reactions |
| `PluginEvent.DEFAULT_PLAYER_CHANGED` | event `library.default_player_changed@2`; `default_player_changed` remains a legacy alias translated to this id | `library.read` | `PluginEventBus` |
| `onEvent` → `{startJob, job, ...}` reaction | unchanged; an event reply may return `{job: {...}}` in v2 | — | `PluginEventBus` |
| `requestsRoot` + `PluginRecord.rootApproved` + `PluginContext.hasRootApproval()` | an optional `requires priv.shell (minLevel root)` + `priv.shell.root` | `host.full_trust`, `priv.shell.root` (the existing tick, carried over as the grant) | the approval screen's root tick |
| `PluginContext.hasShizukuAccess()` | `plugins.available{api:"priv.shell"}` with `level=adb`, served by the Shizuku provider | — (the check itself is harmless) | plugins |
| `boundServiceTargets` | permission `apps.bind` with those packages | `apps.bind(<targets>)` | shown on the approval screen |
| `PluginContext.isAppInstalled` | F4 `apps.check` | `apps.check(*)`: for v1 not scoped, because v1 declared no list | plugins |
| `PluginContext.launchApp` / `launchAppWithExtras` | F2 `apps.launch` / `apps.intent` | `apps.launch`, `apps.intents.out` (any; v1 declared no list) | plugins |
| `PluginContext.libraryFolderPath` | the `destination` arg of A2 `acquire` | — | acquire plugins |
| `PluginContext.privateDataDir` | H1 | — | all |
| `PLUGIN_CONTRACT_VERSION = 1` | contract 2 = this envelope + manifest fields; the host serves 1 and 2 | — | — |
| `PluginRunner.CALL_TIMEOUT_MS` 15 s, `MAX_RESULT_BYTES` 256 KiB | unchanged defaults; per-EP budgets (§8) tighter where UI waits | — | — |

**The legacy shim for root and Shizuku.**

- **While no root provider is installed,** `hasRootApproval()` is false:
  droidtop never probes with `su` (removed 2026-10-09, owner rule: root only
  through a provider plugin).
- **Once a `priv.shell@root` provider is installed and resolved,** the
  shim answers from the provider's status instead. v1 code keeps calling
  `su` itself (it is full-trust), and the audit log marks it "direct
  (older plugin)".
- **The shim is deleted** together with contract 1 support. A later
  contract bump is announced in advance (§7).

**As built (P0-2, #54).** `PLUGIN_CONTRACT_VERSION` is 2 and means "the
highest manifest contract this build reads"; the v2 call envelope is not
served yet, so a v2 plugin is reached through the v1 capability its
`provides` maps to (`LegacyManifest.CAPABILITY_POINTS`), and a v2 plugin
whose points map to none installs and is simply not called. Every v1
plugin is derived `host.full_trust`, `apps.check(*)`, `apps.launch` and
`apps.intents.out(any)` on top of the table's rows, because
`PluginContext` and its own sockets give it all of that today, and that is
what makes the approval screen say "Full access (older plugin)". A v1
record re-derives its v2 set on every read; a v2 record stores the five
arrays under the manifest's own keys (`V2Declarations`).

**As built (P1-1, P1-2, P1-3, P1-5 in part, P1-6, P1-7, P1-9; #57, #58,
#59, #62, #63, #65).**

- **The broker.**
  - `IPluginHostBroker` has one method, `call(requestJson)`, and `:app`
    hands `:pluginhost` one `PluginHostBroker` per plugin in
    `IPluginRuntime.loadPlugin`. The object wraps a `BrokerCore` fixed to
    that plugin's id, so a request cannot name its caller.
    `BrokerCoreTest` sends the same request to two brokers and gets two
    identities.
  - `BrokerCore` runs the checks of §1.4 in order: runnable and not
    Waiting; the API and version; the permission declared and granted;
    the parameters within what was declared; the quota; then the host
    executes or forwards; then the audit entry. `BrokerEnvironment` is
    droidtop's side of it, and `AppBrokerEnvironment` its production
    implementation.
  - Host ops built: `host.info`, `plugins.available`,
    `plugins.job_status`, `apps.check`, `apps.launch` and `apps.intent`.
    The last three are the old `isAppInstalled`, `launchApp` and
    `launchAppWithExtras`, which are now broker calls, so a contract 1
    plugin is served by the same code and holds the grants it was
    derived (no new prompts).
  - Not moved onto the broker: `privateDataDir`, `libraryFolderPath`,
    `hasRootApproval` and `hasShizukuAccess` are local answers with no host
    effect. `hasShizukuAccess` asks Shizuku's own client (its binder has
    arrived and droidtop is allowed), and falls back to the permission
    check. It used to test a permission name Shizuku never defines
    (`moe.shizuku.privileged.api.permission.API_V23` instead of
    `moe.shizuku.manager.permission.API_V23`), so it always answered false.
  - `PluginContext.call(api, version, op, argsJson)` is `host.call` for a
    `native_bundle` plugin. Python exposes it as `host.call`; Flutter
    exposes it as `hostCall` on its existing channel and runs the broker
    call on the adapter's single background executor.
  - Quota: 50 calls burst, 10 per second sustained, per plugin (`TokenBucket`).
- **Grants and the sheet.**
  - `PluginGrants` keeps `plugin-grants/<id>.json`: an explicit state per
    permission id, plus `provide:<point>` for a high-risk point and
    `export:<api>` for an export. Approval writes it from the ticked
    subset (a ticked item is granted; an unticked dangerous permission the
    plugin declares `required` is `ask`, any other unticked item `denied`); a contract 1 plugin keeps the
    permissions it could already do, its points follow the ticks, and its
    root tick is the `priv.shell.root` grant. A file that cannot be read is `ask` for
    everything, never `granted`. The approval screen's tick boxes are
    `ConsentView` lines with a grant key; the box state is kept until
    Approve writes it through `PluginStore.setApproval`.
  - A call in `ask` state during a user-initiated host to plugin call
    shows the sheet (`PluginGrantPrompts`, drawn in every mode); a call
    from an event or a job fails with `PERMISSION_DENIED` and records that
    the plugin "wants" it. A surface that cannot draw the sheet answers Not
    now. Only `invoke` and `handle` calls count as user-initiated: a job
    does not.
- **The v2 envelope.**
  - `DroidtopPlugin.handle(PluginCall): PluginReply` and the AIDL method
    `IPluginRuntime.handle` carry the envelope of §1.3. A plugin that only
    implements `invoke` is served by `LegacyHandle`: the point maps to its
    contract 1 capability (`LegacyManifest.capabilityForPoint`), the op
    travels as an `op` argument. A plugin compiled before `handle` existed
    has no such method on its class and the JVM throws
    `AbstractMethodError`; `LegacyHandle.dispatch` treats that as "use the
    translation", never as a crash.
  - `PluginCrashPolicy.handle` is the host's one way in. A miss of the
    default 15 s budget is a crash; a shorter budget passed by a caller is
    the UI declining to wait (`crashOnTimeout = false`). The wait runs on
    the IO dispatcher, so a hung plugin does not hold the timeout up.
  - A point the plugin provides at high risk needs its `provide:<point>`
    grant, or the call is refused. Set to Ask first (or not answered yet),
    a call the person started shows the first-use sheet for the point
    (`PluginGrants.askPoint`) and keeps the answer; from the background it
    is refused with a "wanted" note (Droidtop/tracker#270).
- **Resolution and calls between plugins.**
  - `PluginApiResolver` recomputes the graph on a change of `PluginEpoch`
    (every record write, grant write and uninstall), never per call, to a
    fixed point: a plugin that lost its provider stops providing, which
    can make another one Waiting. A Waiting plugin is not called through
    `invoke`, `handle`, jobs or events, and its row says what it needs.
    "Provided by" is `PluginProviderChoices`.
  - A call to a provider: the caller must have declared `requires` and the
    op's permission and hold its own grant; the provider must be runnable,
    with its export switched on (an update's new export waits at `ask`);
    a privileged API from a source the user added also needs the
    provider's `plugins.export_privileged`. The call reaches the
    provider's `handle` with `point: "api:<id>"`, a `caller` block
    (id, origin, trust, only the grant that applies, `via`), and a
    deadline of the caller's remaining time minus 1 s, at most 15 s. Depth
    is 3 plugins and a repeat is refused (`INVALID_ARGS`). A provider that
    times out or dies gives `TIMEOUT` or `PROVIDER_CRASHED`. Both sides are
    audited when the permission is dangerous or critical.
  - A provider op declared `job: true` becomes a job in
    `PluginJobsCenter` owned by the caller and shown "via" the provider;
    the caller reads it with `plugins.job_status`.
  - Replies of standard interfaces are checked for size and JSON shape only;
    the per-interface reply schemas are not written yet.
- **Updates.** A same-key update keeps the grants it effectively had
  (written out first for a plugin that had no file), and
  `PermissionDiff` puts each new permission, point and export at `ask`
  (for a contract 1 plugin only the dangerous and high-risk ones),
  remembered as "fresh" until answered: that is "Wants new access", and
  `PluginGrants.answerNew` records the answer from the same list.
- **Activity.** `PluginAudit` is the ring of §4.6 (2,000 entries, 30 days,
  kept 7 days after uninstall), written for dangerous and critical calls
  only; normal-permission calls are not counted yet. The Activity screen
  is not built; the Permissions screen reads "last used" from it.
- **Screens** (#59, #60, #62, #65).
  - The first-use sheet is `PluginGrantSheet`, the one sheet for every
    mode (Droidtop/tracker#128): `PluginGrantPrompts.ask` publishes the
    question and opens `PluginGrantSheetActivity`, a translucent activity
    of droidtop's own that draws the sheet over whatever is on screen
    (Gaming's shell, the Android home screen, the Desktop), in the Gaming
    theme when Gaming was the last mode, the way the mode switcher is
    drawn. Allow / Not now / Never allow, Not now holding the cursor at
    the start, Up and Down, A chooses, B is Not now, a hint row of its own
    that is also the touch route, and the plugin's own reason. Leaving the
    window without a choice (Home, Recents) is Not now, and so is a window
    that could not be opened; nobody answering is Not now after 60 s.
  - Accounts and sources > Plugins > a plugin > Permissions lists every
    declared permission, each high-risk point it may provide and each
    export, with Allowed / Ask first / Blocked, its reason, "tried to use
    this while you were not being asked" and when it was last used (a
    logged call's time; an everyday permission, only counted per day
    (4.6), the day of its newest count, Droidtop/tracker#60). Opening a
    plugin's own screen (`ui.main`) set to Ask first is its first use and
    asks on the sheet, as any point does (#59). New
    items from an update sit under "Wants new access" and the plugin's row
    says so. The state is a chip with a tone (Droidtop/tracker#519, built
    2026-10-16): Allowed neutral, Ask first caution, Blocked danger, from
    the notice palette, so what a state costs shows without reading it.
    The tone is the choice's own (`ChoiceOption.tone`); any settings choice
    whose options carry tones is drawn as chips, in Gaming (`MenuRow`'s
    value chip), on the companion and, as coloured bold text, in Standard
    and Desktop. In Gaming a row that steps with Left/Right adds "◄ ►
    Change" to the hint row.
  - A Waiting plugin's row says "Waiting" and its page says what it
    needs. An interface with more than one running provider has a "Provided
    by" choice on the Plugins screen. There is no "Get <provider>" row yet:
    the catalog index carries no exports.
  - The approval list ticks per item (see "At approval"); a dangerous
    permission left unticked is asked on first use or granted on the
    Permissions screen.
- **Extension points wired** (#67, #73). All three are asked from the host's
  own code through `PluginCrashPolicy.handle`, so a plugin that only
  implements `invoke` answers them through `LegacyHandle`; the wire shapes
  are read in one place (`ExtensionProtocols.kt`).
  - **Context actions (C4).** `PluginContextActions` reads the static
    filter of each `ui.context_action` entry (`targets`, default `game`;
    `systems`; `packages`) from manifests, so no plugin is loaded to decide
    visibility. `enabled` is asked once when a game's or app's detail screen
    opens (500 ms, not a crash), and a disabled action is not offered; a
    miss or failure keeps it offered. `run` is a quick call, or a job when
    the entry says `"job": true` (the contract 1 `startJob` path). A game
    target carries its id, title and system only when the plugin holds
    `library.read`. Built on the detail screen of console ROMs and apps and
    the PC game page; on Standard's long-press app menu and Desktop's Start
    menu row menu (Droidtop/tracker#67, 2026-10-16) as a "Plugin actions"
    row, shown when a manifest's static filter matches (Standard answers
    from the hook's memory, the menu being built on the main thread), that
    opens one page of the actions (`PluginContextActions.screen`) in
    droidtop's plugin window, where `enabled` is asked and each action runs
    with its outcome on its row. The call's `surface.place` names where the
    person is (`game.detail`, `gaming.game_menu`, `gaming.app_menu`, `standard.app_menu`, `desktop.start_menu`).
    Every host builds its target with `PluginContextActions.targetOf` (an
    Android app is an `app` with its package, else a `game`). In Gaming the
    PC game menu (Y) and an app's Select menu have the same "Plugin actions"
    row and page, in the menu's own sheet (`gaming.game_menu`,
    `gaming.app_menu`; Droidtop/tracker#67, 2026-10-16). The gamelist's own
    options act on the list and name no game, so they have none.
  - **Metadata sources (A3).** `PluginMetadataSources` opens one session per
    console scrape pass and, for each game, asks every running source
    `match` (facts: title, file name, system) then `fetch` (the ids of the
    best candidate at or above 0.5 confidence). What comes back is offered
    after every built-in source: it fills a field none of them found and
    never replaces one, and each field it supplied is recorded in
    `fieldSources` under the plugin's name. `rating` is 0..1 and release
    dates are normalised to ES-DE's `YYYYMMDDT000000` or dropped. The PC scrape
    asks it the same way. **A plugin as the PC source (2026-10-28,
    Droidtop/tracker#67):** Settings > Accounts and sources > PC & engine game
    source lists every running `library.metadata` plugin beside the built-in
    sources ("<label> (plugin)"). Chosen, it is the one source a PC pass asks:
    no built-in search and no other plugin, only the game's own store id first.
    Its match fills the game, a tie leaves it for the person's pick, and a pass
    the person started over everything replaces what the game had (not their
    edits). "Rescrape with..." on a PC or engine game's menu lists the same
    sources, built-in and plugin, and redoes that one game from the one picked
    as a pass over everything; the chosen source is left as it is. Not built:
    a plugin as the console scrape's source.
  - **Quick Menu tiles (C2, C3).** `PluginTiles` reads `ui.status_tile` and
    `ui.quick_tile` entries whose `surfaces` include `gaming.quick_menu`
    (or list none) from manifests when the sheet opens; the Plugins tab
    exists only when there is one. The state is asked once per opening (5 s,
    a miss keeps the last value and is not a crash), a status tile is
    read-only, and a quick tile's A is `toggle` when its state has `on` and
    `action` otherwise. A contract 1 `status_tile` appears as a read-only
    tile through the same path. The Standard and Desktop surfaces of C2 and
    C3 are not built.

**As built (P1-16 in part, #72, and #82's real fix).**

- **Two standard interfaces are specified** for the first provider:
  - `priv.packages@1`, op `force_stop {package} -> {stopped: true}`,
    permission `priv.packages` (critical). The op runs
    `am force-stop <package>` as the ADB-level user.
  - `priv.shell@1` (attribute `level`: `adb`), op
    `exec {argv: [string], timeoutMs?} -> {exit, stdout, stderr}`,
    permission `priv.shell.adb` (critical). `argv` is executed directly, not
    through a shell, and each stream is capped at 64 KiB.
  Install, the last `priv.packages` op of §2.2, is specified below
  (Droidtop/tracker#130). Two more ops are specified
  and built (2026-10-09, Droidtop/tracker#248), for the
  Emulator setup helper, both under the `priv.packages` permission:
  - `set_appop {package, op, mode} -> {}`: `appops set <package> <op> <mode>`.
    `op` is an upper-case AppOps name (`^[A-Z][A-Z0-9_]*$`, the helper uses
    `MANAGE_EXTERNAL_STORAGE`, which is "All files access"); `mode` is `allow`,
    `ignore`, `deny` or `default`. The provider checks both shapes before it runs
    anything and reports an `Error` or `Unknown operation` line as a failure.
  - `grant_permission {package, permission} -> {}`: `pm grant <package>
    <permission>`; Android itself refuses a permission the app does not declare
    or that is not a runtime permission.
  droidtop calls them only for the person's own action: Settings > Risky actions
  > "Give another app access" is on and that one use was confirmed with the app
  and the exact permission or appop named (docs/SPEC.md "Risky actions"), and it
  reads the result back from Android rather than trusting the reply. It offers
  the rows only while the running provider exports the op (`HostApiCaller.hasOp`).
  Three more (2026-10-16, Droidtop/tracker#130; Shizuku plugin 1.5.0), also
  under `priv.packages`. Decided by the coordinator, 2026-10-16: "build a, b
  and c as you proposed" (clear_data, uninstall, revoke_permission); "uninstall
  refuses droidtop itself and Shizuku's own package. Also refuse any package
  that the active mode currently uses as the home launcher."
  - `revoke_permission {package, permission} -> {}`: `pm revoke`, the pair to
    `grant_permission`, with the same checks.
  - `clear_data {package} -> {cleared: true}`: `pm clear <package>`.
  - `uninstall {package} -> {uninstalled: true}`: `pm uninstall --user 0
    <package>`.
  Neither of the last two ever touches droidtop itself (any package of
  droidtop's uid), the provider's own app (Shizuku), the app Android opens on
  Home at that moment (the launcher the active mode uses), or a system app: the
  provider reads all four on the device before it runs anything and answers
  `PERMISSION_DENIED` with the reason, and `NOT_FOUND` for an app that is not
  installed. Each succeeds only on the command's final `Success` line. A
  provider checks every request's shape (a package name, an AppOps name and
  mode, a permission name) before it runs anything, and each value is one
  argument of the command, never shell text. No droidtop screen calls these
  three yet; they are for plugins that manage apps.
  And install (2026-10-16, Droidtop/tracker#130), a **job** under
  `priv.packages`. Decided by the coordinator, 2026-10-16: "The spec lists
  install, and the apps are meant to be feature-complete, so don't defer it
  indefinitely"; the design on the issue was accepted with "Streaming the APK
  from droidtop's private cache into `pm install -S` over stdin: accepted.
  It's better than my outline. Record why in §2.7. Providers without full
  access get UNSUPPORTED." and "A confirm sheet naming the app on every
  install, run as a job: yes."
  - The caller asks `install {name}`, `name` being an APK in its own `data`
    folder (H1); a plugin holding the APK elsewhere copies it there first.
  - droidtop's broker acts after the caller's grant and before the provider
    sees anything (`PrivInstall`). Only a call the person started installs.
    Android reads the APK (`getPackageArchiveInfo`); anything else is
    `INVALID_ARGS`. droidtop itself, the app Android opens on Home now and an
    installed system app are refused (`PERMISSION_DENIED`). The person
    confirms each install on droidtop's confirm card, "Install <label>
    <version>?", which names the app, its package, the calling plugin and the
    provider (No is `CANCELLED`). The file is copied to droidtop's private
    `cache/priv-install/` under a random name, and the provider is called
    with `{staged: {path, size, sha256, package}}` only, written by droidtop:
    a caller's own `staged` or `name` never reaches it. Staged copies older
    than an hour are removed on the next install.
  - The provider streams the staged file into `pm install -S <size>` on
    stdin, after checking the size and digest and refusing its own app, and
    deletes the staged copy when it ends. It replies `{installed: true,
    package}`; Android's `Failure [INSTALL_FAILED_...]` is
    `FAILED` with a plain sentence and the raw line in `detail`. Downgrades
    and a different signer stay Android's own refusals.
  - **Why stdin and not a staged path the shell user reads:** droidtop cannot
    write anywhere the shell user may read that other apps may not (shared
    storage is readable by any app with storage access, and `/data/local/tmp`
    belongs to the shell user, not droidtop). Streaming from droidtop's
    private cache means no copy of the APK is ever readable outside droidtop.
    It also means the provider must run in droidtop's process to read that
    cache: install is served only by a provider with full access, and the
    broker answers `UNSUPPORTED` for any other.
  - **The rig check is an instrumentation test** (coordinator, 2026-10-16:
    "don't build a test plugin or a test origin. That would add a trust
    anchor to the production console ... Instead, write an androidTest
    (instrumentation) in droidtop that drives the broker's priv.packages calls
    against the installed Shizuku plugin, on a throwaway test package that the
    test installs and cleans up itself"; and "these actions are destructive on
    a production console, so the rig uses only a throwaway test package that
    it installs itself, never one of the owner's apps"). It is
    `PrivPackagesRigTest` in `app/src/androidTest`, on
    `dev.droidtop.rigtest.throwaway` (`:rigtest-throwaway`), run by hand with
    `am instrument` from CI's `droidtop-android-test` artifact. "When a real
    droidtop screen or plugin first uses these calls, that screen gets the
    user-way rig check too."
- **`priv.settings@1`** (2026-10-28, Droidtop/tracker#72; Shizuku plugin,
  level `adb`), every op under the `priv.settings` permission (critical):
  - `get {namespace, key} -> {value}`: `settings get`; `value` is null for a
    setting that is not set.
  - `put {namespace, key, value} -> {}`: `settings put`.
  - `delete {namespace, key} -> {deleted}`: `settings delete`; `deleted` is
    false when the setting was not set.
  `namespace` is `secure` or `global`. `system` is left out: an app changes
  it with Android's own WRITE_SETTINGS, so it is no privileged call. `key` is
  a setting's name (`^[A-Za-z0-9_.:-]{1,128}$`); `value` is one line of at
  most 4096 characters. The provider checks all three before it runs
  anything, and each is one argument of the command, never shell text. A
  provider started over adb refuses to change or delete the settings it
  stands on (`global adb_enabled`, `adb_wifi_enabled`,
  `development_settings_enabled`) with `PERMISSION_DENIED`, since turning
  them off stops it mid-call; reading them is allowed. No droidtop screen
  calls it yet; it is for plugins that tune the device.
- **Ops for the emulator setup helper** (specified 2026-10-08,
  Droidtop/tracker#130, #248; droidtop's side is built, the Shizuku provider
  plugin is to add them). Both are `priv.shell@1` ops, permission
  `priv.shell.adb`, and the provider refuses any `path` that is not a file in
  shared storage: absolute, under `/storage/` or `/sdcard/`, no empty, `.` or
  `..` part, not ending in `/` (`ElevatedFiles.allowed`, which droidtop
  checks before it asks). Their purpose is other apps' `Android/data`
  folders, which the shell user reaches and droidtop cannot since Android 11
  (an emulator's config file and BIOS folder).
  - `read_file {path, offset, length} -> {dataBase64, eof}`: `length` at most
    262144; `eof` true when the file ends within this piece. A missing file is
    an error, not an empty reply.
  - `write_file {path, offset, dataBase64, last} -> {}`: pieces arrive in
    order from offset 0 (offset 0 starts a new file). The provider writes them
    to `<path>.droidtop-part`, creating the folder, and on `last` renames it
    over `path`, so the target is never half-written; any error removes the
    part file. A file over 64 MiB is refused.
  droidtop sends at most 192 KiB of raw data per call
  (`PluginPrivilegedOps.FILE_CHUNK`) and offers the file actions only when the
  running provider exports both ops (`HostApiCaller.hasOp`). The Shizuku app
  backend (`SystemShizukuOps`) does the same with `cat`, `dd` and `mv` in
  Shizuku's server.
- **The transport.** Shizuku's server pushes its binder to a content
  provider named `<applicationId>.shizuku` in each app the user allowed.
  `:plugin-host` declares Shizuku's own `ShizukuProvider` under that name in
  `:pluginhost`, the first full-trust slot (the other slots and droidtop's
  main process ask for the binder through Shizuku's multi-process support),
  and depends on Shizuku's client
  library (`dev.rikka.shizuku:api` and `:provider` 13.1.5, Apache-2.0). The
  plugin's class loader delegates to the host's first, so the provider
  plugin references `rikka.shizuku.Shizuku` without bundling it and shares
  the binder. This is transport only: no other plugin is offered Shizuku,
  privilege reaches a plugin only through `priv.*` with its own grant, and
  `hasShizukuAccess` is the one read-only probe. (A full-trust plugin could
  still call the class directly, as it can anything droidtop's UID can;
  that is the tier's honest limit, §5.)
- **The host as a caller.** `HostApiCaller` lets droidtop's own code call a
  provider for a user's action inside droidtop, with `caller: {kind: "host"}`
  and no caller grant. It still requires the provider to be running, its
  export switched on, and `plugins.export_privileged` for a privileged API
  from a source droidtop has not checked, and it audits the provider's side.
  Quit to Library uses it through `ForceStop`: a `priv.packages` provider
  ends the emulator's package; with none installed the row keeps the honest
  answer of §12a (Android 13 gives droidtop no way to end another app's game)
  and now says the Shizuku plugin can. A provider that fails says why, and
  droidtop's running-game state clears only on `Ended` as before.
  Ending a game this way (Quit to Library, the task manager's End) is the
  person's own action, so a provider that has not yet been allowed full
  access (§5.3) asks for it on the first-use sheet then, where a surface can
  draw it (decided 2026-10-09, Droidtop/tracker#378). droidtop's other calls
  to a provider (reading the task list, background checks) never show a
  sheet; the Permissions screen allows it too.
- The provider plugin itself is `Droidtop/droidtop-plugin-shizuku`
  (`droidtop-plugin/`): a contract 2 manifest exporting both interfaces,
  full-trust, with the existing status tile and app_status surfaces.

**As built (the contained tier, #378, 2026-10-08).**

- **Processes.** `PluginProcessService` is the one binder every plugin
  process runs; `PluginRuntimeService` (and `FullTrustSlot1..7`) loads a
  full-trust plugin from its folder, `PluginSandboxService` (isolated, and
  `PluginSandboxSlot0..7` below API 29) loads a contained one from
  descriptors. `PluginProcesses` hands each plugin its process, lets it go
  after 60 s idle, and reports a death once to
  `PluginCrashPolicy.processDied` with the plugins that process held.
  droidtop's application object starts nothing in an isolated process.
- **Tiers.** `PluginTiers` decides the tier per load from the grants, says
  what cannot run contained and why, and gives the badge.
  `PluginManifest.structuralProblems` refuses at install a contract 2
  plugin that cannot run contained and does not declare `host.full_trust`.
- **Loading.** `ContainedFiles` opens what a kind needs in `:app`; the
  sandbox reads dex from `classes.jar` into `InMemoryDexClassLoader`, and
  starts python from descriptors (§5.3, "Spike results").
  `PythonRuntimeManager` builds `python-stdlib.zip` once per runtime.
- **The broker.** `IPluginHostBroker.open` for ops that hand over a file;
  `HostOp` can choose its permission from the arguments (`net.http`), be
  user-only (`files.pick`), be logged whatever its tier, and name an
  Android permission droidtop must hold. Host ops built: `net` `state`,
  `http`, `download`; `data` `write`, `read`, `list`, `delete`, `usage`,
  `open`; `files` `pick`, `open`, `forget`; `files.shared` `list`, `read`,
  `write`, `mkdir`, `delete`, `open`; `storage` `volumes`; `notify` `post`.
  `BrokerAskActivity` shows Android's document picker and permission prompt
  for the broker. Picked-file tokens are deleted with the plugin.
- **Screens.** The plugin row, page and Permissions screen show the tier;
  the approval list says whether the plugin runs contained or asks for full
  access; Activity lists the log; Advanced → Containment check loads the
  plugin as a call would and reports what its process can reach.
- **Kind parity.** `openFile` is served to `native_bundle`
  (`PluginContext.openFile`) and `python` (`droidtop.host.open`), not yet to
  `flutter_embed`, which runs contained too (§5.3) and reaches its own
  folder by path through the file broker, and the rest through `data` over
  `hostCall`.

**Compatibility promises:**

1. Every v1 bundle that installs and runs today installs and runs after
   contract 2 lands.
2. Its grants are derived from what it could already do. There are no
   new prompts for existing behaviour, and the root tick carries over.
3. It is shown as "Full access (older plugin)".
4. The `PluginRecord` JSON round trip carries every new field, with a
   round-trip test (the `subscribedEvents` loss in 12a is the bug this
   prevents).

---

## 7. Versioning and deprecation

**Three layers, versioned independently:**

- **The contract** (`contractVersion`): the envelope and the manifest
  schema. It changes rarely, and each change is a major.
- **Each extension point, host API, event and exported API** has its own
  version.
  - Host points and APIs are `@major`.
  - Plugin-provided APIs are `major.minor` (§2.2).
  - The event contract integer (`PLUGIN_EVENT_CONTRACT_VERSION`) becomes
    a per-event version.
- **The host advertises** the supported range per id in `host.info()`,
  and the manifest declares the version it implements or needs.

**The additive rule.** Adding an optional argument, a reply field, an
op, an error code or an event is **not** a version change. Unknown
fields are ignored in both directions. Removing or retyping anything, or
changing a meaning, is a new major. The `core` change in
`default_player_changed` is the precedent: meaning changed, so the
version was bumped.

**Deprecation:**

- A superseded major keeps being served for at least **two droidtop
  minor releases and 90 days**, whichever is longer.
- The whole time it shows "Uses an older API that will stop working in
  <version>" on the plugin's row and in the Developer screen. The host
  logs every use.
- It is removed only in a release whose notes say so.
- The catalog can refuse to *offer* (not uninstall) a plugin whose only
  implemented major is gone.

**Contract 2.2: the old op names go (owner, 2026-10-29, Droidtop/tracker#766).** The names contract 2.1 renamed
(`HostOpNames.DEPRECATED`, 21 of them, §3 "Naming") are served as aliases until contract 2.2, and 2.2 comes once no
official plugin calls one: the bundle workflow warns on every old name it finds, so its logs are the check (the
retroarch and fdroid plugins were the last callers, Droidtop/tracker#762). The bump deletes the map, `HostOpNames.current`,
the broker's two helpers that apply it and `host.get_info`'s `deprecatedOps`; a plugin still calling an old name then
gets `UNSUPPORTED` like any op droidtop does not serve.

**Refusals.**

- A manifest whose `contractVersion` is newer than the host supports is
  refused, as today.
- A `provides` entry for a major the host no longer serves is listed as
  "Not supported by this version" and never called, but the rest of the
  plugin still works.

**Contract 3 (proposed 2026-10-22, approved 2026-10-23, Droidtop/tracker#602, built 2026-10-23).**
A plugin whose manifest says `"contractVersion": 3` needs facets: a droidtop built before them refuses to install it
("contractVersion 3 is newer than this build of droidtop understands (2)") and keeps the version it has, so a plugin
that moved its settings or panel to facets is never installed where they cannot be drawn. droidtop installs contract
1 to 3 (`PLUGIN_MANIFEST_CONTRACT_VERSION`). The call envelope does not change in 3: a call's `contract` stays 2, so
a plugin's check for it holds whichever contract its manifest says. `minDroidtop` is documentation only; no droidtop
reads it.
Facets (§1.10): small, typed pieces of UI and information that any
plugin gives droidtop for its targets (a game page, the Quick Menu, Home,
the companion, Settings, notifications, the status area, widgets), which
droidtop draws, caches and attaches to items. They replace the view
schema, panels, tiles and one-off reply fields (A3 `fetch`, A6 `record`
and `facts`). Contract 2 plugins keep working: droidtop maps their views
and replies onto facets, and contract 2 is retired under the rules above.

**Changelog (additive changes; no version moved).**

- 2026-10-17 (update management): `library.updates` gains `record` (the
  record's page, drawn by droidtop) and the `download` job (an `acquire`
  reply, placed as a new version folder), and `check` answers may carry
  `facts`. The unbuilt `apply` is gone. droidtop's own download-site
  sessions (G3) reach a page opened in its web view; no op exposes them.

- 2026-10-16 (Droidtop/tracker#355 G5): a download whose name is already in
  the game folder is kept beside it as "Name (2).ext" (an unpacked game as
  "Name (2)"), never replacing it and never failing after the download; a
  set of several files whose names are taken still stops, since its files
  name each other.

- 2026-10-16 (Droidtop/tracker#520): an `apps.bridge` entry may carry
  `app`, `packages` and `get`; `needs: "app"` on a node or a tile. droidtop
  draws the missing-app notice itself.

- 2026-10-16 (Droidtop/tracker#553): `power.get_state` (D7), under
  `power.state`. An open process reaches the shared-storage folders its
  granted `files.shared` entries declare by path (5.3, "Shared folders by
  path").

- 2026-10-16 (Droidtop/tracker#563): widgets (C10) are built for Android
  app widgets: `ui.widget@1` `snapshot` and `action` with the value, list,
  gauge, media and image templates, and C2 and C3 tiles on a widget.
  `context.surface` `android.widget` is new; a tile's `surfaces` decides
  whether it may be on one.

- 2026-10-16 (Droidtop/tracker#559): theme tokens (G7): `ui.get_theme`,
  `theme` in `host.get_info`, and the event `host.theme_changed`, for UI a
  plugin draws itself.

- 2026-10-16 (Droidtop/tracker#556, #553): paired devices (D21):
  `devices.list_paired`, `devices.announce` and the event
  `devices.changed`, under the new `devices.paired`. A plugin sees, on each
  paired computer or handheld, only what the same plugin id announced there;
  never an address, a key or another plugin's value.

- 2026-10-15 (Droidtop/tracker#553): `net.get_links` (D12), under
  `net.sockets`.
- 2026-10-15 (Droidtop/tracker#553): the `net.sockets` permission, the
  network hole of the open process type that `gpu.render` now shares
  (§5.3, "Open processes: one hole per permission").

- 2026-10-15 (Droidtop/tracker#521 item 1): the `danger` tone for a
  view's `notice` and for `ui.prompt confirm`.
- 2026-10-15 (Droidtop/tracker#521 items 2 and 8): manifest presentation
  fields `summary`, `publisher`, `homepage`, `category`, `icon`,
  `screenshots` (1.2), drawn by droidtop in an "About" group, with
  `summary` and `publisher` also on a catalog index's plugin entry; every
  plugin panel ends with droidtop's own Permissions row.
- 2026-10-15 (Droidtop/tracker#521 items 5 and 7): typed `info` and
  `row` values (`bool`, `number` with `unit`, `enum`) written by droidtop;
  `needs` on any view node, drawn as an inert "Off" row while that
  permission is turned off.
- 2026-10-15 (Droidtop/tracker#521 items 3, 6, 9, 10): a `provides`
  entry's `label` follows the point's name ("Status tiles and widgets:
  VPN"); a view's optional top-level `empty` sentence under droidtop's own
  "Nothing here yet" notice with Refresh; a job's completion `notice`, and
  droidtop's "Finished" notice when there is none; a catalog's
  `disclaimer` is drawn under droidtop's caution card "Notice from the
  list" (reserved, with "The list's notice changed").

- 2026-10-15 (Droidtop/tracker#544): the error code `BROWSER_CHECK_REQUIRED`
  and the error field `detail`; a failure `message` is one plain sentence
  (§1.3); droidtop draws Sign in / Open the site for `SIGN_IN_REQUIRED` and
  `BROWSER_CHECK_REQUIRED`, and a `web.session` plugin's account row, from
  the new optional `signIn` on its `web.session` entry (G3); `apps.view`
  copies the link and says why when it cannot open it (F2); `data.read`
  answers at most 48 KiB per call (H1; a longer `length` is answered
  shorter with `eof: false`); `apps.bridge` `status` fields `status` and
  `version` and droidtop reading the app from Android (F1); tile entries'
  `whileGame` and `gamePackages` (C2).

- 2026-10-14 (Droidtop/tracker#485): the `notice` view node and a reply's
  `data.notice`; `host.viewFeatures` in every call envelope; error replies
  may carry `title`, `subtitle`, `permission` and `action`, and the new code
  `SIGN_IN_REQUIRED`; `ui.prompt@1 confirm`; `ui.toast` is drawn as
  droidtop's flash; the view limits moved into
  `docs/view-limits/plugin-view-limits.json`; permission titles reworded in
  plain words (§4.1).

**Where the version facts live.** The host's registry of points, APIs,
events and permissions is **code** in `plugin-host` (one Kotlin table:
id, versions served, deprecated-since, risk, label), so the table and
the behaviour cannot drift apart. This document describes that table; it
does not duplicate it once built.

---

## 8. Process, threading, timeouts and quotas

**Threads.**

- droidtop never calls a plugin from the main thread. Every call site
  uses `PluginCrashPolicy` from a coroutine on an IO dispatcher. This is
  a standing performance rule.
- A plugin's `handle` runs on a binder thread in its process. A plugin
  may be called concurrently, **up to 4 in-flight calls per plugin**;
  beyond that the host queues calls. Jobs run on the runtime's job
  executor (as today) and count separately.

**Rendering never waits on a plugin.**

- A list or menu renders from the manifest's static data and the last
  cached value, then updates when a call returns.
- There are no per-item plugin calls in list rendering. Context actions
  (C4) decide visibility from static filters, and `enabled` is asked
  only when the menu opens.

**Deadlines**, carried in the envelope as `deadlineMs`:

| Call | Budget | On timeout |
| --- | --- | --- |
| default `handle` | 15 s (`CALL_TIMEOUT_MS`) | counts as a crash → the plugin is disabled (today's rule) |
| `enabled` for a context action (C4) | 500 ms | shown enabled; not a crash |
| search `query` (C5) | 1.5 s | results omitted; not a crash |
| source `search` (`library.sources`, the one search and a source page) | 10 s, one in flight per source | "didn't answer in time" under the source; not a crash |
| `pre_launch` hook (B3) | 2 s | launch proceeds; not a crash |
| status or quick tile `state` (C2, C3) | 5 s (the widget's existing value) | last value kept; not a crash |
| widget `snapshot` (C10) | 5 s, at most once every 30 s | last snapshot kept; not a crash |
| provider call (§2.4) | the caller's remaining time minus 1 s, at most 15 s | caller gets `TIMEOUT`; **provider** treated as crashed |
| job | no per-call bound; progress at least every 60 s or the job shows "not responding" (not killed) | the user cancels |

Budgets below 15 s that are marked "not a crash" exist because the UI
chose not to wait. A plugin that misses them was slow, not broken.

The first default-budget call a process answers after it started (the
runtime's own first lines: the Dart isolate, the Python interpreter) gets
`COLD_START_GRACE_MS` (30 s) on top of the 15 s, and a miss of that call is
"still starting, try again", never a crash. The load has its own 15 s budget.
Later calls get 15 s and the crash rule above. A call with a shorter budget
keeps it.

**Quotas** (per plugin, host-enforced, reply `RATE_LIMITED`):

| Resource | Quota |
| --- | --- |
| broker calls | 50/s burst, 10/s sustained |
| reply size | 256 KiB (`MAX_RESULT_BYTES`); argument size 256 KiB |
| `net.http` body | 128 KiB each way (bigger goes through `net.download` as a job) |
| concurrent jobs | 2, beyond which jobs queue in the Jobs screen |
| notifications | 5 per hour, 1 ongoing |
| overlay toasts | 1 per 10 s |
| scheduled jobs | at most every 15 min (the WorkManager floor) |
| `power.keep_awake` | 10 min per request, only with a running job |
| data directory | 512 MiB: a `data` write or download that would pass it is refused (`RATE_LIMITED`); droidtop never deletes a plugin's data by itself |
| audit log | 2,000 entries / 30 days |
| event delivery | an event whose handlers are not done in 15 s is dropped for that plugin (as today's watchdog) |

**Processes.**

- Built 2026-10-08 (§5.3): contained plugins get one isolated process each
  (one `bindIsolatedService` instance from API 29, one of eight isolated
  slots below it); full-trust plugins get one of eight slots under
  droidtop's UID each.
- The idle unload (60 s) frees memory, and Android's low-memory killer may
  kill any plugin process. That counts as process death: in-flight calls
  fail, but **it does not disable the plugin** when the system (not the
  plugin) killed it (see P1-12).

When a plugin process dies, the host disables exactly the plugins it held
that had calls in flight at the time of death. A death while idle disables
none, so the next call can reconnect after a low-memory kill.
An ordinary `PluginResult.failure` is a plugin-reported result, not a crash,
and does not disable the plugin.

---

## 9. Roadmap

Every item is small and implementable on its own, and each is a tracker
issue labelled `app:droidtop` + `plugin-api` + its priority. Items marked
**oc** also have an worker task file (`/repos/ws/pluginapi/tasks/<id>.md`).
P0-1 is this document itself, tracked by the umbrella #53.

### P0: needed for the feature-complete deadline (~2026-09-30)

The deadline freezes the *shape*, so that everything added after it
fits. P0 therefore lands the schema, the record and the consent view.
Enforcement comes later.

| Id | Item | Acceptance |
| --- | --- | --- |
| P0-1 (#53) | This document, the SPEC §12a summary, the stale 12a text corrected, `docs/plugin-catalog.md` folded in | committed; CI green |
| P0-2 **oc** (#54) | Manifest v2 fields: parse `provides`, `permissions`, `subscribes`, `exports`, `requires` in `PluginManifest`; `LegacyManifest.toV2` for v1; carry every field through `PluginRecord` JSON both ways | unit tests: a v2 manifest round-trips through `PluginRecord`; a v1 manifest yields the §6 implicit permissions; unknown ids are kept as "unsupported", not dropped |
| P0-3 **oc** (#55) | The permission and extension-point registry as code (`PluginPermissions.kt`: id, tier, risk, label, official-only; `ExtensionPoints.kt`: id, versions, risk); install-time validation of declared ids; `requires` on `priv.*`/`root.*` must be optional | unit tests for validation; the labels match §4.1 |
| P0-4 (#56) | The approval screen and the plugin's own row show Adds / Can / Asks for / Uses from other plugins from the registry (read-only: no grant storage yet; the existing root tick unchanged) | a rig check: approve a sample with a v2 manifest and see each section |

### P1: next

| Id | Item | Acceptance |
| --- | --- | --- |
| P1-1 (#57) | `IPluginHostBroker` AIDL + one broker binder per loaded plugin; `PluginContext` methods reimplemented on it; `host.info` and `plugins.available` | the existing samples still pass their rig check; unit test that the caller's identity comes from the binder object |
| P1-2 **oc** (#58) | Grant store (`PluginGrants`: plugin × permission → granted/denied/ask, persisted, a round-trip test); approval writes the grants | unit tests |
| P1-3 (#59) | Broker permission check on every call + `PERMISSION_DENIED`; the first-use sheet (user-initiated calls only) | unit tests on the broker; a rig check of the sheet with the gamepad |
| P1-4 (#60) | Permissions screen per plugin (state, last used, revoke) under Accounts and sources → Plugins | a rig check |
| P1-5 **oc** (#61) | Audit log store (a ring buffer per plugin, 2,000 / 30 d, kept 7 d after uninstall) + an Activity screen that reads it | unit tests on the ring; a rig check of the screen |
| P1-6 (#62) | `PluginApiResolver`: the exports/requires graph, the "Waiting" state, "Provided by" per interface | unit tests: resolve, a provider removed → Waiting, a returning provider → runnable |
| P1-7 (#63) | Plugin → plugin calls through the broker: the caller's own grant, the `caller`/`via` block, depth ≤ 3, cycle refusal, provider timeout = provider crash, job ownership | unit tests on each rule; a test-fixture provider/caller pair |
| P1-8 **oc** (#64) | `startJob`/`cancelJob` for the `python` kind (the kind-parity gap) | a python test fixture gains a job; a rig check |
| P1-9 (#65) | Permission diff on update: new dangerous items → `ask`, "Wants new access" on the row | unit test on the diff |
| P1-10 **oc** (#66) | Event namespacing + the events `game.launching`, `game.exited`, `library.scan_finished`, `mode.changed`; `default_player_changed` kept as an alias | unit tests on the args builders (like `DefaultPlayerChangedArgsTest`) |
| P1-11 (#67) | Wire the two dead capabilities: `library_action` → a game's detail screen (C4), `metadata_source` → the scrape pass (A3) | a rig check with a test fixture |
| P1-12 **oc** (#68) | The crash policy tells a system kill (LMK/process death with no plugin in a call) from a plugin crash: only the latter disables; a whole-process death disables the plugins that had calls in flight (built: `PluginCrashPolicy.processDied`) | unit tests with the fake runner |
| P1-13 **oc** (#69) | Vault API (G1): per-plugin Keystore-wrapped storage, excluded from backups, deleted on uninstall | unit tests (JVM with a fake key provider) |
| P1-14 **oc** (#70) | Manifest JSON Schema (`docs/plugin-manifest.schema.json`) + CI validating the test-fixture manifests | CI job green |
| P1-15 **oc** (#71) | Fake host (`plugin-host-testing`) + the conformance script, run against the native and python test fixtures in CI | CI job green |
| P1-16 (#72) | The official Shizuku provider plugin (`droidtop-plugin-shizuku`: exports `priv.shell@1 level=adb`, `priv.packages@1`), replacing `hasShizukuAccess` for v2 | **owner-action**: create the repo; then build it; a rig check with Shizuku |
| P1-17 (#73) | Quick Menu surface for `ui.status_tile` / `ui.quick_tile` in Gaming (C2/C3) | a rig check |

### P2: later

Each is one issue when its time comes. They are not filed now, to keep
the tracker honest.

- **Containment:**
  - P2-1: the contained-tier spike, per kind, on both rigs (built
    2026-10-08, #378; the device results are §5.3's to record);
  - P2-2: the contained tier as the default for contract 2 (built, #378);
  - P2-3: one process per full-trust plugin (built, #378).
- **Network:**
  - P2-4: `net.http`/`net.download` broker with domain allowlists (built,
    #378);
  - P2-5: a web-session API (G3) for #9 (built 2026-10-09, Droidtop/tracker#380);
  - P2-6: an OAuth helper (G2);
  - P2-7: GitHub request API (G4) folded into #16's token store.
- **Library:**
  - P2-8: A1 library read;
  - P2-9: artwork providers (A4);
  - P2-10: update checks (A6);
  - P2-11: saves sync (A7);
  - P2-12: achievements (A8, with #14);
  - P2-13: launch providers (B1);
  - P2-14: per-game settings (B2);
  - P2-15: launch hooks (B3).
- **UI:**
  - P2-16: overlays (B4);
  - P2-17: controller mapping + the Key Mapper bridge (B5, F1, #15);
  - P2-18: perf read and profiles (B6, B7);
  - P2-19: search providers (C5, with #12);
  - P2-20: notifications (C6; `post` built, #378);
  - P2-21: theme packs (C7);
  - P2-22: desktop tray through SNI (C8);
  - P2-23: file handlers and associations (C9, E4);
  - P2-24: widgets and companion panels (C10, C15);
  - P2-25: gaming rows (C11);
  - P2-26: launcher actions + the `launcher3` popup hook (C12);
  - P2-27: onboarding steps (C13);
  - P2-28: prompts (C14).
- **System and desktop:**
  - P2-29: the system group D1–D17 as host APIs, in order of demand (D1
    to D5 built, #378);
  - P2-30: containers read/manage/exec (E1, E2);
  - P2-31: terminal (E3);
  - P2-32: windows through windowcast (E5);
  - P2-33: printing (E6);
  - P2-34: the container package bridge (E7);
  - P2-35: background services (E8);
  - P2-36: scheduled jobs (E9).
- **Integration:**
  - P2-37: intents in (F3);
  - P2-38: app install (F5);
  - P2-39: bound connections through the broker (F6);
  - P2-40: the root provider `droidtop-plugin-mmrl` (`priv.shell@1
    level=root`, `root.modules@1`);
  - P2-41: accounts registry (G5).
- **Data:**
  - P2-42: import/export (H3);
  - P2-43: backup participation (H4, after #46);
  - P2-44: plugin logs + scrubbing in Share diagnostics (H5, with #48);
  - P2-45: opt-in telemetry (H6).
- **Developer:**
  - P2-46: the Developer screen (I1);
  - P2-47: a provider/caller test-fixture pair for each kind (I4).

**Counts:** 4 P0, 17 P1, 47 P2.

---

## 10. Questions for the owner

These are recorded, not blocking. The design above works with either
answer.

- **Q1. May a plugin contribute library entries that are not files?**
  12a says no, and A10 keeps that. Cloud-gaming or streaming catalogues,
  and web games that are only a URL, cannot be expressed as files in a
  folder. Reopening it would be a new EP, `library.entries@1`, whose
  entries droidtop stores and marks with their source.
- **Q2. Should privileged providers run under their own UID?** The
  Shizuku and root providers must hold privilege, which today means
  droidtop's UID, so every full-trust plugin can also reach it (T11). A
  separate small companion APK for providers (its own UID holding the
  Shizuku grant) would close that. It is a second installable, which is
  a product decision.
- **Q3. Should the accessibility bridge (D18) exist at all?** It is
  listed so the catalogue is exhaustive. It is restricted to official
  origins, and it should not be built without a real plugin that needs
  it.
- **Q4. Should the contained tier be the default for official plugins
  too,** once the spike proves it, with full trust only where a plugin
  needs it? The recommendation is yes. **Decided 2026-10-08
  (Droidtop/tracker#378): contained is the default for every contract 2
  plugin, official ones included.**
