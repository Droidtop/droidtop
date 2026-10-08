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
- Unknown top-level fields are ignored, so a newer manifest still parses
  on an older build. Unknown ids *inside* `provides` and `permissions`
  are also ignored. The approval screen lists them as "Not supported by
  this version of droidtop", so the user sees what the plugin wanted and
  did not get.

### 1.3 Three kinds, one contract: one adapter per kind

`PluginKind` (`native_bundle`, `python`, `flutter_embed`) says how a
plugin's code is *loaded*. It never decides what that code may *do*.
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
`PROVIDER_UNAVAILABLE`, `PROVIDER_CRASHED`, `CANCELLED`, `FAILED`. A
plugin's own ordinary failure ("no network", "nothing found") is an
error reply, not a crash. This keeps today's distinction in
`PluginCrashPolicy`.

**Plugin → host (the broker):**

```json
{ "api": "notify", "version": 1, "op": "post",
  "args": { "title": "...", "text": "..." } }
```

The reply uses the same `{ok, data|error}` shape.

| Kind | Host → plugin | Plugin → host | Jobs |
| --- | --- | --- | --- |
| `native_bundle` | `DroidtopPlugin.handle(call: PluginCall): PluginReply` (v2); v1 `invoke(capability, args)` still served via the legacy translation | `PluginHost.call(api, version, op, args)` handed to `onLoad(host)` | `startJob(jobId, call, progress)` / `cancelJob` (as today) |
| `python` | module-level `handle(call_json) -> reply_json` (JSON text both ways, like `invoke`; called for a contract 2 manifest, built 2026-10-01); v1 `invoke(payload_json)` still served | `droidtop.host.call(api, op, args=None, version=1) -> dict`: the modules `droidtop` and `droidtop.host`, which the bootstrap puts in `sys.modules` before `plugin.py` is imported (built 2026-10-07); the dict is the parsed broker reply, `{"ok": true, "data": ...}` or `{"ok": false, "error": {...}}`, and a call never raises for a refusal | `start_job(job_id, call, progress)` / `cancel_job(job_id)`: the bridge runs jobs on Python worker threads, forwards progress live, and calls `cancel_job(job_id)` cooperatively |
| `flutter_embed` | the plugin's `MethodChannel("dev.droidtop.pluginhost/<plugin id>")`, method `handle`, envelope and reply as JSON strings (called for a contract 2 manifest, built 2026-10-01) | the same channel's `hostCall`, with `{api, version, op, args}` as JSON text and the broker reply as JSON text | the same channel's `startJob`/`cancelJob` plus progress messages (the job support built 2026-09-26) |

The rules that make this "no kind-specific features":

1. **An adapter serialises the envelope and does nothing else.** It
   never adds an op, a field or a shortcut that the envelope does not
   carry. Kind-internal plumbing (the Flutter readiness handshake, the
   Python module namespacing) is invisible above the adapter.
2. **Parity is tested, not claimed.** One conformance script (the fake
   host, §3 I2) drives the same calls against each kind's sample, and a
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
- **`PluginContext` becomes a thin client.** The existing methods
  (`hasShizukuAccess`, `isAppInstalled`, `launchApp`,
  `launchAppWithExtras`, `libraryFolderPath`, `privateDataDir`,
  `hasRootApproval`) are reimplemented as broker calls. They are kept as
  deprecated wrappers for contract 1 plugins (§6).

### 1.5 Lifecycle

| Stage | What happens | Existing code |
| --- | --- | --- |
| **Install** | Picker or catalog download → `PluginBundleInstaller` checks: signature against the origin key, every payload hash, the manifest schema, the namespace, the ABIs, the contract version. **v2 adds:** every `provides`/`permissions`/`exports`/`requires` entry is checked against the host registries, and a `requires` on `priv.*` must be `optional` (§2.7). The record lands PENDING. No plugin code runs before approval, not even to describe itself. | `PluginBundleInstaller`, `PluginStore` |
| **Approve** | The approval screen shows, in plain language: what the plugin adds and where (grouped by mode), what it can access (normal permissions as a short list; each dangerous permission and high-risk extension point as its own line), what it needs from other plugins, and its trust badge (Official / Added by you). Every item is a tick box and **the plugin runs with the ticked subset** (decided 2026-10-01, §4.3 "Approval is a list"). Unticked dangerous permissions stay at `ask` (§4.3). | the approval screen in `AppSettingsCatalogs.pluginsScreen` |
| **Enable** | An approved plugin is enabled by default. Disabling stops every call to it and hides its contributions everywhere, because `PluginStore.runnableFor` is the only iterator (checklist point 6). | `PluginStore.setEnabled` |
| **Resolve** | On every install, approve, enable, disable, uninstall or crash, the `requires` graph is recomputed (§2.3). A plugin whose *required* API has no runnable provider is **Waiting**. It is not disabled, and it resumes by itself when a provider appears. | new: `PluginApiResolver` |
| **Run** | Loading is lazy: a plugin is loaded on its first call, and after 60 s idle (proposed) it is unloaded unless it holds a job or a declared background service (§3 E7). A crash, an uncaught exception, a native crash, a timeout or process death disables that plugin with a reason, and the user re-enables it by hand (12a checklist point 6). **Kept as is:** crash containment is the one rule `PluginCrashPolicy` exists for. | `PluginCrashPolicy`, `NativePluginRunner` |
| **Update** | Same key: approval carries over (12a "Trust over updates"). **v2 adds a permission diff.** An update that adds any permission, extension point or `exports` entry keeps running with its *old* grants; the new items wait at `ask`, and the plugin's page asks about those items only, on the same list as at approval ("Wants new access", §4.3). An update never gains dangerous access silently. A different key, or a previously DENIED plugin, goes back to PENDING (unchanged). | `PluginStore`, `PluginRecord.approvedKeySha256` |
| **Uninstall** | `onUnload` runs, and the payload, data directory, vault entries, grants and scheduled work are deleted. The audit log for the plugin is kept for 7 days (labelled "removed plugin") so that "what did it do" can still be answered. Dependents are re-resolved (§2.5). | `PluginStore.uninstall` |

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
| `progress` | `title`, `value` (0 to 100, or -1 for unknown), `subtitle?` | a read-only status row ("45%") |

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
completion's `values` may carry `message`.

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
crash under the usual crash policy) renders as one `info` row with the
reason, plus a way out where the host knows one (a source's "Open <plugin>
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

**`library.sources` with views (the Get games flow).** Every call on this
point carries `context: {system: {id, name}, destination}`; `system.id` is
droidtop's system id (the ES-DE short name, `snes`), `destination` the
system's own games folder, resolved by droidtop (absent when a search has
no destination, such as the PC library). Ops:

- `form {context}` → a view whose input nodes are the search field and the
  filters (region, format, a system list, anything the source has). Every
  committed input runs `search` again with all `values`. Optional: a source
  that answers UNSUPPORTED (or a contract 1 source) gets droidtop's default
  form, one `text` node `query`.
- `search {query, values, context}` → `{results: [result]}`, where a result
  is `{id, title, subtitle?, columns?[], badges?[], platform?, ref?}`.
  `ref` is any JSON object; droidtop hands it back unread in `detail` and
  `acquire` and never parses it (12a checklist point 5). droidtop renders a
  result as a `row` that opens `detail`. At most 200 results are shown.
- `detail {ref, context}` → a view: the result's own sections (description
  rows, its files), its pickers (`choice` nodes for version, region,
  format, mirror) and its actions, at least one `job` action with op
  `acquire`. Optional: droidtop's default detail shows the result's title
  and a Download job.
- `acquire {ref, values, context}` → a **job**. It writes only into
  `context.destination`. When it succeeds droidtop rescans the library, so
  the new game appears; results become games only through that scan (12a,
  A10). Additive reply: a successful job may return `download` as a JSON
  string containing `{url, headers?, fileName, sha256?, size?}` instead of
  downloading the file itself. `url` is HTTP(S); `fileName` is a bare file
  name; `sha256` is 64 hexadecimal characters and `size` is a positive
  byte count used as a size cap. droidtop queues it through DownloadManager
  into its own downloads area, then places it in `context.destination` and
  rescans. Credential headers (Authorization, cookies, token and key
  headers) remain in memory only. A plugin may omit `download` and continue
  doing its own transfer as before.

This reply extension is part of the additive 1.6 acquire contract; it does
not change the view document's `view: 1` or the manifest's
`contractVersion: 2`. The JSON shape is also recorded in
`docs/plugin-view.schema.json` under `$defs.acquireDownload` for plugin
authors and tooling.

A source that fails `search` with a setup problem answers FAILED with a
plain sentence ("The game index is not downloaded yet"); droidtop shows it
under the source's name and, when the source provides `ui.settings`, an
"Open <plugin> settings" row right there.

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
the host draws "<plugin>: nothing here" in the same place. A tile that
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
- `library.sources` Get games flow: `SourceScreens` opens a plugin `form`
  and adds source results after committed inputs; `PluginGameSource` calls
  contract 2 `search` and opens source `detail` views, with contract 1
  droidtop defaults. `AcquireContentSources` delegates its per-system
  screen to `SourceScreens`; Gaming's shared search opens the same detail
  screen. Successful acquire jobs rescan the library.
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
  shelves), with C6a (toasts) as the first UI host API.
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

---

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
      { "op": "exec_stream", "permission": "priv.shell.adb", "job": true }
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
   Shizuku binder, and the root provider runs `su`. Every other plugin
   reaches them only through `priv.*`/`root.*` calls with its own grant.
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
- **Ops:**
  - `search {query, platform}`;
  - `lookup {title, platform, ids}` (defaults to `search`);
  - `options {result}`;
  - `acquire {result, choice, destination}`, a **job**.
- **Surfaces:**
  - G: gamelist Options → Get games, and global search results (#12);
  - A: a system's settings → Get games, and the launcher's Games grid
    search;
  - D: the file manager's "Get games" on a library folder, and desktop
    search.
- **Permission:**
  - `provide:library.sources`;
  - `library.folders.write`, scoped to the destination the host passes;
  - `net.domains` or `net.any`.
- **Status:** built as `acquire_content` (the wire contract in 12a, "The
  wire contract this UI defines"); the interface is being built by
  `pluginsearch`.
- **Rules:**
  - Results become games only when droidtop's own scan finds the files.
    Plugins contribute no library entries (12a).
  - `destination` is a host-resolved folder. A plugin-returned path is
    never used.

**A3 Metadata providers and scrapers.** EP `library.metadata@1`. Risk
medium.
- **For:** candidate metadata (title, description, dates, genre,
  developer, players, rating, cross-store ids) offered next to the
  built-in scrapers (§7h's honesty rules apply).
- **Ops:**
  - `match {entry facts} → candidates[{ids, confidence}]`;
  - `fetch {ids} → fields`.
- **Surfaces:**
  - G/A/D: the Scraper screen lists it as a source; the game's detail
    screen offers "Rescrape with…".
- **Permission:** `provide:library.metadata`, `net.domains`.
- **Status:** declared (`metadata_source`) with **no caller** (§6).
- **Rules:**
  - The host merges, stores and attributes the result, and the plugin
    never writes to the database.
  - It runs in the scrape job, never in list rendering.

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
- **For:** "is there a newer version of this game, app or mod", covering
  a web-sourced game (#9), a GitHub-released homebrew, or an Obtainium
  bridge (F1).
- **Ops:**
  - `check {entry ids} → [{entryId, available, version, notes}]`, run in
    the host's scheduled update pass;
  - `apply`, a **job** that routes to A2 `acquire`.
- **Surfaces:** G: an "Update available" badge and an Updates row;
  A: notification (C6); D: the file manager badge.
- **Permission:** `provide:library.updates`, `net.domains`.
- **Status:** not built.
- **Rules:** the host schedules it (at most daily per plugin), and it is
  never polled from UI.

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

**A8 Achievements.** EP `achievements.provider@1`. Risk medium.
- **For:** achievement sets and progress, for example RetroAchievements
  (#14) or a store's own achievements.
- **Ops:**
  - `set {entry ids/hashes} → [{id, title, desc, points, badgeUrl}]`;
  - `progress {entry} → [{id, unlockedAt}]`;
  - `account`, handled through G5.
- **Events:** `achievements.unlocked` (host → plugins, for an overlay
  plugin).
- **Surfaces:**
  - G: the game detail screen's Achievements, and the Quick Menu in game;
  - A: the detail screen;
  - D: the library object view.
- **Permission:** `provide:achievements.provider`, `net.domains`, and
  `library.history` if the provider reads play sessions.
- **Status:** not built.

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

### B. Launch and runtime

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
- **Ops:** `state → {label, value, on?, icon}` and `toggle` or `action`.
- **Surfaces:**
  - G: the plugin's panel in the Quick Menu (C17), first rows;
  - A: an `android.service.quicksettings.TileService` slot that droidtop
    owns (P2);
  - D: the tray (C8).
- **Permission:** none.
- **Status:** built in the Quick Menu; since 2026-10-07 its rows are in
  the plugin's panel. A `status_tile` counts as a read-only quick tile
  (§6).

**C3 Status tiles and home widgets.** EP `ui.status_tile@1`. Risk low.
- **For:** ambient readings (network or VPN state, a sync status).
- **Ops:** `state → {label, value, severity}`.
- **Refresh:** droidtop decides when (widget update, Quick Menu open) and
  the plugin never runs its own loop.
- **Surfaces:**
  - G: the plugin's panel in the Quick Menu (C17), and its value on the
    plugin's row of the Plugins list; the companion screen;
  - A: `PluginStatusWidgetProvider`, a home-screen widget;
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
- **Status:** declared as `library_action` with **no caller**.

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
- **Permission:** `notify.post` (normal), rate-limited (§8).
- **Status:** not built.
- **Rules:**
  - Every notification is attributed to the plugin.
  - There is no full-screen intent and no heads-up by default.

**C6a Toasts.** API `ui.toast@1`. Risk low.
- **For:** a short message in the moment, Decky's `toaster.toast`: "Synced",
  "Tunnel up".
- **Ops:** `show {text}` returns `{shown}`. Text is cut to 200 characters.
- **Surfaces:** a toast over whatever is in front, in every mode, always
  led by the plugin's name, so it is never mistaken for droidtop's own.
- **Permission:** `overlay.toast` (normal), rate-limited like every
  broker call (§8).
- **Status:** built (2026-10-07).

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

**C10 Desktop widgets.** EP `ui.widget@1`. Risk low.
- **For:** a small live panel on the desktop surface or the companion
  screen: a clock, a system monitor, now playing, a sync status.
- **What the plugin supplies:** data only, from a fixed set of widget
  templates (value, list, gauge, media, image) that droidtop draws.
- **Ops:** `snapshot {size} → template data`, at most one refresh every
  30 s.
- **Surfaces:**
  - G: companion screen panels (§4d);
  - A: home widgets, through the same template renderer as
    `PluginStatusWidgetProvider`;
  - D: the desktop surface.
- **Permission:** none.
- **Status:** not built.

**C11 Gaming-shell sections and rows.** EP `gaming.rows@1`. Risk medium.
- **For:** a row on Gaming's home or a section, such as "New on
  <source>", "Continue on <other device>" or "Friends playing".
- **Ops:** `rows {section} → [{title, items[{title, art, action}]}]`,
  cached by the host and refreshed at most every 15 minutes or on user
  refresh.
- **Surfaces:** G: droidtop's own surfaces only (Quick Menu, a game's
  page, options menus), never inside a themed view, until the
  frame-only render declares a region for host content (§1.6, #179);
  A: —; D: —.
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

**C15 Companion screen panels.** EP `ui.companion@1`. Risk low.
- **For:** content for the second display (§4c, §4d): now playing, a
  map, a manual page, a chat, a performance graph.
- **What the plugin supplies:** the same template data as C10.
- **Surfaces:** G: companion panels; A: `SECONDARY_HOME` panels; D: —
  (the second screen is part of the desktop there).
- **Permission:** none.
- **Status:** not built (§7e has built-in now-playing and Discord).

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
  `gaming.quick_menu` or `settings`; `game` is the running game's
  `target` under the `library.read` rule (C4).
- **Surfaces:** G: the Quick Menu's Plugins section; G, S, D: a "Panel"
  row on the plugin's page under Settings.
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

### D. System and device

Every host API in this group is run by droidtop's own code with
droidtop's own Android permissions, gated per plugin by the broker.

| Id | Group | For | Ops and events | Surfaces | Permission | Risk | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| D1 | Network state | adapt to connectivity; VPN/hotspot tiles | `net.state()` → {type, metered, vpn, ssid?}; event `net.changed` | data | `net.state` (normal; SSID needs `net.wifi_details`, dangerous: location-grade) | low | not built |
| D2 | Network access | talk to the internet | `net.http {method, url, headers, body≤1MiB}` → response (≤ 8 MiB, or streamed into a job); `net.download {url} → job` (host download manager, into a host-chosen folder or the plugin's data dir) | data | `net.domains` (declared list, normal) / `net.any` (dangerous) / `net.local` (LAN, dangerous) | medium to high | not built; plugins open sockets directly today (§5) |
| D3 | Storage and volumes | free space, removable media, where a library folder lives | `storage.volumes()` → [{id, label, removable, freeBytes}]; events `storage.mounted` / `unmounted` | data | `storage.volumes` (normal) | low | not built |
| D4 | File picker (SAF) | let the user choose a file or folder for the plugin | `files.pick {mode, mime}` → a grant token; `files.open {token}` → fd | the system picker | `files.picker` (normal; the user's pick is the consent) | low | not built |
| D5 | Shared files | broad access to shared storage | `files.list/read/write {scope path}` | data | `files.shared.read` / `files.shared.write` (dangerous; droidtop holds MANAGE_EXTERNAL_STORAGE) | high | not built; available *directly* today (§5) |
| D6 | Clipboard | copy a code or link; read for paste-to-search | `clipboard.write {text}`; `clipboard.read()` only during a user-initiated call | data | `clipboard.write` (normal) / `clipboard.read` (dangerous) | low / high | not built |
| D7 | Power and battery | defer work on low battery; power profiles | `power.state()` → {level, charging, saver}; `power.keep_awake {≤10 min}` (only while a job runs); events `power.low`, `power.charging` | data | `power.state` (normal) / `power.keep_awake` (normal, quota) | low | not built |
| D8 | Display | know the displays (internal, companion, external) to place content | `display.list()` → [{id, role, size, density, hdr}]; events `display.added` / `removed`; `display.set {brightness}` | data; C15 renders | `display.info` (normal) / `display.control` (dangerous) | low / medium | not built |
| D9 | Audio routing | route or duck audio per session | `audio.routes()`, `audio.set_route {id}`, `audio.volume {stream, level}`; event `audio.route_changed` | G Quick Menu | `audio.control` (normal); **no microphone API** (P2 decision: `audio.record` would be critical and needs a real use) | medium | not built |
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
| E8 | Background services and daemons | a long-running plugin task (a sync daemon, a server) | manifest `services: [{id, label, restart: never\|on_failure}]`; the host starts and stops them from a user toggle, shows them in Jobs and in the Android foreground notification | A: Jobs, notification; D: tray; G: Quick Menu → Running | `background.service` (dangerous: battery and network while you are away) | high | not built; `startJob` is the only long-lived shape today |
| E9 | Scheduled jobs | periodic work (update checks, a backup) | manifest `schedules: [{id, every: "≥15m", constraints: {charging, unmetered}}]` → a host WorkManager job → a `handle` call with `point: "schedule"` | Jobs screen | `schedule.jobs` (normal, quota) | low | not built |
| E10 | Inter-plugin IPC | plugins calling each other | **§2**: plugin-provided APIs through the broker are the only channel | — | per exported permission | varies | not built |

### F. Integration with other apps

| Id | Group | For | Ops and events | Surfaces | Permission | Risk | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| F1 | App bridges | a plugin that represents another installed app (Syncthing, Key Mapper, Obtainium, Discord, #15): status, actions, and **droidtop's own fallback when the app is absent** | EP `apps.bridge@1` (today's `app_status`): `status {package}` → {installed, version, state, actions[]}; `action {id}` (quick call or **job**) | A/G/D: the app's row wherever droidtop shows that app (the Player screen today), Accounts and sources, C3 tiles | `provide:apps.bridge` + `apps.check` (declared packages) + `apps.bind` (for live connections) | medium | built as `app_status` (`PluginAppStatus`) |
| F2 | Intents out | start another app, share to it, hand it a file | `apps.launch {package}` (exists); `apps.intent {package, action, extras(strings), data?}` → the host builds and fires it; `apps.share {text \| file token}` → the system share sheet | — | `apps.launch` (normal) / `apps.intents.out` (normal for declared packages; dangerous for any package) | medium | partial (`launchApp`, `launchAppWithExtras`) |
| F3 | Intents in | let other apps or links reach a plugin (a deep link `droidtop://plugin/<id>/…`, share *to* droidtop, a custom URL scheme a source site uses) | manifest `intentFilters: [{scheme, host, pathPrefix}]` → a host-owned exported activity routes the intent to `handle` with `point: "intent.in"` (strings only) | a host sheet shows "Open with <plugin>?" the first time | `intents.in` (dangerous: other apps can trigger it) | high | not built |
| F4 | Installed apps | know what is installed (to find emulators, detect a bridge target) | `apps.check {packages[] declared}`; `apps.list()` → all packages | data | `apps.check` (normal, declared list) / `apps.list` (dangerous: the full list is personal) | low / high | partial (`isAppInstalled`) |
| F5 | Install apps | install or update an APK (an Obtainium-style updater) | `apps.install {file token}` → Android's own installer UI | Android's confirmation | `apps.install` (dangerous) | high | not built |
| F6 | Bound connections | a live service or binder connection to another app | manifest `boundServiceTargets` (exists); the host binds, the plugin exchanges data through the broker (`apps.bound.call {package, method, args}` for AIDL surfaces the host knows) | — | `apps.bind` (dangerous, per package) | high | declared only |
| F7 | Privileged operations | Shizuku / root | **plugin-provided** `priv.*`, `root.*` (§2.7) | — | `priv.shell.adb`, `priv.shell.root`, `priv.packages`, `priv.settings`, `root.modules` (all critical) | critical | host shim only |

### G. Identity and accounts

| Id | Group | For | Ops and events | Surfaces | Permission | Risk | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| G1 | Secure vault | per-plugin secrets (API keys, refresh tokens) | `vault.put {key, value}`, `vault.get {key}`, `vault.delete`; values encrypted with an Android Keystore key per plugin; deleted on uninstall; **excluded from backups** (see #46) | — | `vault.own` (normal; own namespace only) | low | not built |
| G2 | OAuth helper | sign in to a service without the plugin handling a password | `auth.oauth {authUrl, tokenUrl, clientId, scopes, pkce: true}` → the host opens a Custom Tab, catches the redirect on its own scheme, exchanges the code and stores the tokens in G1 → returns a vault key | A/G/D: a host sign-in sheet | `auth.oauth` (normal: the user sees and does the sign-in) | medium | not built |
| G3 | Web session | sources that need a real browser session (login cookies, a Cloudflare check, a "click to download" page, #9) | `web.session.open {startUrl, domains[]}` → a host WebView screen the user drives; the host keeps that session's cookies **per plugin**; `web.session.fetch {url}` reuses those cookies for declared domains; `web.session.clear` | the host WebView screen with the plugin's name and the URL bar visible | `web.session` (dangerous: authenticated access to your accounts on those sites) | high | not built (#9) |
| G4 | GitHub token | higher API limits for sources on GitHub (#16) | `github.token()` → the user's token (set once in Accounts and sources), or `github.request {path}` executed by the host with it (**preferred**: the token never leaves the host) | Accounts and sources | `github.api` (normal, through the host request) / `github.token.read` (dangerous: the raw token) | medium / high | #16 in progress |
| G5 | Accounts registry | a plugin's account shown as a row in Accounts and sources (signed in or not, sign out) | EP `accounts.provider@1`: `status → {signedIn, name}`; `sign_in` (runs G2/G3), `sign_out` | A/G/D: Accounts and sources | `provide:accounts.provider` | low | not built |
| G6 | Identity of the device user | "who is this" | not offered: plugins get no Android account list, no email, no device ids. `host.info()` gives a **per-plugin random install id** only | — | — | — | by design |

### H. Data

| Id | Group | For | Ops and events | Surfaces | Permission | Risk | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| H1 | Per-plugin storage | the plugin's own files and preferences | `privateDataDir()` (exists; a path in the full-trust tier, a host-backed fd API in the contained tier); `prefs.get/set` (small key/value, host-stored, shown in the export); quota §8 | — | — (own data) | low | built (`privateDataDir`) |
| H2 | Shared databases | data other plugins or droidtop may read: droidtop's library index (A1), and a plugin's own published dataset | droidtop's own databases: only through A1 APIs, never a handle. A plugin publishing data does it as a plugin-provided API (§2), e.g. `acme.playlog.query` | — | per API | varies | by design |
| H3 | Import and export | move a plugin's settings or data between devices | EP `data.export@1`: `export → job writing into a host-chosen fd`; `import {fd}` | A/G/D: Accounts and sources → Plugins → <plugin> → Export / Import | none (user-initiated) | low | not built |
| H4 | Backup and restore | droidtop's own backup includes plugin data | manifest `backup: {include: ["prefs", "data/<subpath>"]}`; vault entries never included; restore re-verifies the plugin and asks before re-granting dangerous permissions | Settings → Backup | none | medium | not built (#46 is the credentials-in-backup issue) |
| H5 | Logs and diagnostics | debugging a plugin; including it in "Share diagnostics" (#48) | `log.write {level, msg}` → a per-plugin ring buffer (256 KiB); the host scrubs tokens, emails, IPs, paths under the user's home and anything in the vault before any export; `diagnostics.read` would let a plugin read **droidtop's** logs | Accounts and sources → Plugins → <plugin> → Log; Share diagnostics | `log.write` (—) / `diagnostics.read` (dangerous, official only) | low / high | not built |
| H6 | Telemetry | a plugin's own usage or crash reporting to its author | `telemetry.send {endpoint declared, payload ≤ 16 KiB}`: **off until the user turns it on per plugin**; the host shows the exact payload on the first send; nothing leaves the device without the user | Accounts and sources → Plugins → <plugin> → Share usage data (off) | `telemetry.send` (dangerous, opt-in) | high | not built |

### I. Developer

| Id | Group | For | What | Status |
| --- | --- | --- | --- | --- |
| I1 | Debug surface | plugin authors and bug reports | Accounts and sources → Plugins → <plugin> → Developer (shown when developer options are on): the manifest as parsed; its grants; the last 50 calls with durations and error codes; the last 50 broker calls; the log (H5); "Call op…" with a JSON argument box (replaces today's status-tile "Call …" debug row); "Force crash" (today's `force-crash` query) | the debug row exists, status_tile only |
| I2 | Fake host / test harness | test a plugin without a device | a JVM library `plugin-host-testing` (and a Python `droidtop_fake_host.py`) that loads a plugin, serves the host API from in-memory fakes with configurable grants, and runs the **conformance script** (§1.3) that every kind's sample must pass in CI | not built |
| I3 | Manifest schema | validation for authors and CI | `docs/plugin-manifest.schema.json` (JSON Schema for contract 2, with v1 accepted), used by `PluginManifest` tests and by the sample CI jobs | not built |
| I4 | Samples | a working starting point per kind | `samples/plugin-sample-statustile` (native), `-py-statustile`, `-flutter-statustile` exist; add one sample exercising `provides` + a dangerous permission + `requires` (a provider/caller pair) per kind | three built |

### J. Plugin platform (cross-cutting)

| Id | Group | For | What | Status |
| --- | --- | --- | --- | --- |
| J1 | Jobs | long-running work with progress and cancel | `startJob`/`cancelJob`, `PluginJobsCenter` (one registry, one Jobs screen); any EP op may be declared `job: true`; a job belongs to the plugin that started it (or to the caller, for a brokered provider job) | built |
| J2 | Event bus | host → plugin notifications | `PluginEventBus`; events are namespaced `<area>.<name>`, each with its own version (§7); v1 `default_player_changed` is kept as an alias of `library.default_player_changed@2` | partial (registered; only default-player delivery implemented) |
| J3 | Plugin-provided API broker | plugin → plugin | §2 | not built |
| J4 | Host info | what this host supports | `host.info()` → {droidtopVersion, contract, supported EP/API versions, mode, device ABI, installId}; `plugins.available {api}` | not built |

**The count:** ten areas (A to J) and **93 numbered entries**:

- A: 10
- B: 7
- C: 19 (C1 to C18, and C6a)
- D: 20
- E: 10
- F: 7
- G: 6
- H: 6
- I: 4
- J: 4

E10 and J3 are the same broker seen from two areas, so there are 92
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
| `media.read` | dangerous | See your music, videos and pictures | A9 |
| `perf.read` | normal | See performance readings (CPU, temperature, battery) | B6 |
| `perf.profile.set` | dangerous | Change performance and fan settings | B7 |
| `overlay.toast` | normal | Show short messages during games | B4 |
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
| `background.service` | dangerous | Keep running in the background | E8 |
| `schedule.jobs` | normal | Run scheduled tasks | E9 |
| `apps.check` | normal | Check whether *listed apps* are installed | F4, F1 |
| `apps.list` | dangerous | See all apps installed on this device | F4, C12 |
| `apps.launch` | normal | Open other apps | F2 |
| `apps.intents.out` | normal (declared packages) / dangerous (any) | Send information to *listed apps* / to any app | F2, B1 |
| `intents.in` | dangerous | Be opened by other apps and links | F3 |
| `apps.install` | dangerous | Install and update apps (Android asks you each time) | F5 |
| `apps.bind` | dangerous | Stay connected to *listed apps* in the background | F6, F1 |
| `vault.own` | normal | Store its own passwords and keys securely | G1 |
| `auth.oauth` | normal | Ask you to sign in to a service | G2 |
| `web.session` | dangerous | Use your signed-in session on *listed sites* | G3 |
| `github.api` | normal | Use your GitHub token for GitHub requests (the token stays in droidtop) | G4 |
| `github.token.read` | dangerous | Read your GitHub token | G4 |
| `diagnostics.read` † | dangerous | Read droidtop's own logs | H5 |
| `telemetry.send` | dangerous (opt-in) | Send usage data to *listed address* | H6 |
| `plugins.export` | normal | Offer features to other plugins | §2.8 |
| `plugins.export_privileged` | critical | Give other plugins root or system-level access | §2.7 |
| `host.full_trust` | critical | Run with droidtop's full access (not contained) | §5.3 |
| `priv.shell.adb` | critical | Run system commands with ADB-level access (through *provider*) | F7, §2 |
| `priv.shell.root` | critical | Run commands as root (through *provider*) | F7, §2 |
| `priv.packages` | critical | Install, remove and change permissions of apps without asking (through *provider*) | F7 |
| `priv.settings` | critical | Change protected system settings (through *provider*) | F7 |
| `root.modules` | critical | Install and remove root modules (through *provider*) | F7 |
| `input.remap.apply` | dangerous | Change your controller mapping (through *provider*) | B5 |

That is 66 permissions. Those marked † are restricted to official
origins.

**Always available, with no permission:** `host.info`, `plugins.available`,
own `prefs`, own `privateDataDir`, `log.write`, `locale`/`time`, the
`ui.prompt` sheet during a user-initiated call, and job progress.

### 4.2 Providing sensitive extension points

Providing an extension point rated high or critical is itself a consent
item, shown as `provide:<point>`, because providing is what gives the
plugin power there. These points are:

- `library.sources` (writes into game folders);
- `saves.sync`;
- `launch.provider` (decides what runs your game);
- `files.handler` (receives your files);
- `onboarding.step` (official only);
- `intents.in`;
- `containers.packages`;
- every `exports` entry.

Every point, high-risk or not, is listed on the approval screen under
"Adds", grouped by mode, as its own tick box with one plain sentence on
what it lets the plugin do; the high-risk ones are marked and start
unticked, the others start ticked.

### 4.3 Grant states, and when the user is asked

Each (plugin, permission) pair is in one of three states:

- **granted**;
- **denied**, which is what an unticked normal permission, extension
  point or export becomes at approval;
- **ask**, which is what a dangerous or critical permission the user did
  not tick at approval becomes, and what an update's new items start as.

When they are asked:

- **At approval ("Approval is a list").** One screen, and every line
  below with a tick box can be unticked before approving, high-risk ones
  included:
  - "Adds": each extension point, grouped by Gaming / Android / Desktop,
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
- **Revocable.** Accounts and sources → Plugins → <plugin> → Permissions
  lists every declared permission with its state and when it was last
  used (from the audit log). Changing a state takes effect on the next
  call. A revoked `background.service` stops the service.
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

### 4.5 How trust tiers and permissions interact

| | Official origin | User-trusted origin ("Added by you") | Unknown origin |
| --- | --- | --- | --- |
| Install | yes | yes | refused (as today) |
| Normal permissions | granted on approval | granted on approval | — |
| Dangerous | ticked or asked | ticked or asked; the grant sheet adds "from a source droidtop has not checked" | — |
| Critical | ticked, with a warning | ticked, with a warning **plus** a second confirmation; † permissions not grantable at all | — |
| `host.full_trust` | allowed (existing plugins) | allowed only with an explicit critical grant; the default once the contained tier exists (§5.3) is *contained* | — |
| Export privileged APIs | allowed | only with `plugins.export_privileged` | — |
| Onboarding steps | allowed | never | — |

Trust decides *who may ask*. Permissions decide *what is granted*. An
official plugin still gets no dangerous permission without the user's
tick.

### 4.6 Auditing: what a plugin used, and when

- **What is logged.** Every broker call that needs a dangerous or
  critical permission writes an entry: plugin, permission, op, a
  one-line target summary (a domain, a package, a folder name, never
  contents), `via` chain, result code and timestamp. Normal-permission
  calls are counted per day, not logged one by one.
- **Storage.** In droidtop's private storage, per plugin: a ring of 2,000
  entries or 30 days. It is kept 7 days after uninstall.
- **Where it shows.** Activity per plugin ("Used your GitHub token 3 times
  today, last 14:02"), and "last used" on each permission row.
- **Export.** It is included in Share diagnostics only with the user's
  say-so, and scrubbed (H5).
- **What the audit cannot see.** Anything a full-trust plugin does
  directly with droidtop's UID, bypassing the broker (§5). The Activity
  screen says so on full-trust plugins: "This plugin runs with full
  access; only what it asks droidtop to do is listed."

---

## 5. Threat model

### 5.1 What is enforced today, and what is not

The process boundary is **crash containment, not a security sandbox**
(12a, and `docs/security/2026-09-25-droidtop-plugins.md`). Concretely:

**Enforced today:**

- **Install integrity.** Signature, per-origin keys, the trust tiers,
  every payload hash, re-verification before every activation, id
  namespacing and ABI coverage. Official bundles are signed by CI
  (repo secret `PLUGIN_SIGNING_KEY`, optional `PLUGIN_SIGNING_CERT` packaged as
  `origin.cert`); see docs/SPEC.md, plugin trust.
- **Consent.** Nothing runs before approval. Root needs its own tick.
  Approval binds to the digest and key.
- **Routing.** droidtop calls only declared capabilities and delivers
  only subscribed events. A disabled plugin is never called
  (`runnableFor`).
- **What droidtop hands over.** A folder path only for `acquire_content`
  from that system's screen. Intents are built by droidtop from strings.
  No database handle is passed.
- **Replies.** Results are capped at 256 KiB and treated as untrusted
  input (never used as a path, URI or intent target without validation).
- **Stability.** Watchdogs, and a crash, timeout or process death
  disables the plugin.

**Not enforced today.** The plugin runs as droidtop's UID in
`:pluginhost` (`android:isolatedProcess="false"`), so it holds
**everything droidtop holds**. Plugin code can, directly and invisibly:

- open any socket (`INTERNET`);
- read and write all shared storage (`MANAGE_EXTERNAL_STORAGE`);
- list every installed app (`QUERY_ALL_PACKAGES`);
- read droidtop's **own** private files: its databases, preferences,
  store sign-in tokens and scraper credentials. "Never gets a database
  handle" is an API design, not a wall.
- request package installs (`REQUEST_INSTALL_PACKAGES`), read usage
  stats if granted (`PACKAGE_USAGE_STATS`), write secure settings if
  granted by adb (`WRITE_SECURE_SETTINGS`), and read logs if granted
  (`READ_LOGS`);
- use Shizuku, which is granted to droidtop's UID;
- reach another plugin in the same process: its class loader, its
  `privateDataDir` and its files;
- call the broker pretending to be another plugin (same process, so the
  broker binder objects are reachable by reflection);
- run anything `su` gives it, if the device's root manager has already
  granted droidtop's UID.

This is why permissions today are **declarative consent plus host-side
gating of host-mediated calls**. They are honest about what the host does
on a plugin's behalf, and they do not bound what a plugin can do on its
own.

### 5.2 Threats and mitigations

| # | Threat | Today | Mitigation in this design | What remains |
| --- | --- | --- | --- | --- |
| T1 | **Malicious plugin** (bad author, or a good author's key stolen) exfiltrates droidtop's credentials, the library, play history or shared files | only signing, the trust tier and user approval stand in the way | contained tier (§5.3): no direct network, no files, no UID permissions; everything through the broker, with domain allowlists and an audit | full-trust plugins stay able to do anything; limited to official origins by default and those needing Shizuku/root |
| T2 | **Silent escalation on update** (same key, new code wants more) | approval carries over; nothing re-asks | permission diff (§1.5): new dangerous items wait in `ask`; new exports need a grant | a full-trust plugin's new *code* can still misuse existing direct access |
| T3 | **Confused deputy through the host**: a plugin gets droidtop to act with droidtop's power (write outside a folder, send an intent to an arbitrary package, open a FileProvider URI) | ad hoc: the host builds intents, validates results | the broker checks the **caller's** grant, never droidtop's; every path comes from the host (tokens and fds), never from the plugin; intents only to declared packages unless `apps.intents.out` is dangerous-granted | host bugs in validation: each API gets a unit test that a plugin-supplied path, URI or package outside scope is refused |
| T4 | **Confused deputy through a provider plugin**: A reaches root through B without its own grant | not possible yet (no plugin→plugin) | A's own grant is required (§2.6); risk floor; `via` chain audited; depth ≤ 3, no cycles; narrow standard-interface schemas | a buggy provider that exposes more than its label: reviewed for official providers; third-party exporters need `plugins.export_privileged` |
| T5 | **Data exfiltration** by network, intents, clipboard, share, logs, telemetry or crafted result URLs | open (T1) | `net.domains` allowlist, audited; `apps.intents.out` scoped; `clipboard.read` dangerous and user-initiated only; logs scrubbed; telemetry opt-in with payload shown; host renders no plugin-supplied URL as a clickable link without showing the domain | covert channels inside allowed domains; a contained plugin can still send what it can read to an allowed domain, so keep what it can read small |
| T6 | **UI spoofing / phishing**: a plugin renders "Enter your Steam password" or a fake permission sheet | partially: plugins draw no UI | plugin text always carries its name chip; no secret text fields (C14); passwords only through G2/G3 host flows; permission sheets are host-only and visually distinct; onboarding steps official only | a misleading but honest-looking label; mitigated by attribution and review of official plugins |
| T7 | **Denial of service**: hang, crash loop, battery drain, notification spam | watchdog; crash disables | quotas (§8); background only with a grant; per-plugin notification channel and rate limit | a plugin that behaves badly but never crashes: the Activity and Jobs screens make it visible |
| T8 | **Cross-plugin interference** in the shared `:pluginhost` | open | contained tier: one isolated process per plugin; full-trust plugins in their own non-isolated process (not shared) | full-trust plugins share droidtop's UID and can still read each other's files |
| T9 | **Supply chain**: TOFU key fetch, catalog index tampering, runtime download tampering | mitigated (https only, fingerprint shown, changed key stops dead, index untrusted, pinned runtime hashes) | unchanged; add a "key changed" entry to the audit log | a user who confirms a TOFU key for a malicious source |
| T10 | **Privacy of the user's play history and library** | open (T1) | `library.history` and `apps.list` are dangerous; A1 read is paged and index-only; G6: no identity APIs; per-plugin random install id | a plugin granted history can keep it |
| T11 | **Root or Shizuku abuse** | per-plugin root tick, but same-UID code can call `su` or Shizuku without the host knowing | host holds no privilege; providers only; caller grants; `priv.*` requires optional-only (§2.7) | a full-trust plugin still shares droidtop's UID, so it can reach Shizuku or `su` directly if the root manager granted the UID; hence §5.3's "each full-trust plugin its own process" and the plan for a provider-owned UID (§10 Q2) |
| T12 | **Malformed data into droidtop**: theme files, metadata, result JSON, notification text | size caps, the untrusted-input rule | schema check per op reply; theme input goes through the same hardened parser as downloads; text length caps | parser bugs in droidtop itself |

### 5.3 Proposal: a contained execution tier

To make the permission model hold for real, plugins must stop sharing
droidtop's UID. The proposal:

- **Contained, the default for contract 2 plugins.** Each plugin runs in
  its own **isolated process**: `android:isolatedProcess="true"` on the
  runtime service. `Context.bindIsolatedService` (API 29+) gives one
  process per plugin from a single declaration; on API 28 and older a
  fixed pool of declared slots (`PluginSandbox0..7`) is used, the same
  pattern browsers use for renderers.
  - **What an isolated process has.** Its own random UID, **no
    permissions at all**, no network (it is not in the inet group), no
    access to droidtop's files or shared storage, and almost no system
    services.
  - **What it gets instead.** Everything comes through the broker: code
    and payload as file descriptors, network through `net.http`, files
    as fds from `files.pick` or host-resolved paths, and storage through
    a host-backed data API.
  - **How the code loads.**
    - Dex: `InMemoryDexClassLoader`, API 26+, from an fd.
    - Native `.so`: `android_dlopen_ext` with `ANDROID_DLEXT_USE_LIBRARY_FD`.
    - Python: the stdlib as a zip opened from a host-passed fd, through
      `zipimport`; `lib-dynload` modules through fd dlopen.
    - Flutter: `libflutter.so` and `libapp.so` through fd dlopen, with
      `flutter_assets` from a host-served asset loader.
    - **Needs a spike** (P2-1): each kind's loader has to be proven in an
      isolated process on both rigs before this becomes the default.
- **Full trust, by grant.** This is today's same-UID `:pluginhost`,
  but **one process per plugin**, so no plugin can reach another
  in-process (T8). It is needed for:
  - the Shizuku and root providers (their privilege is granted per UID);
  - plugins that bind other apps' services (`apps.bind`: an isolated
    process cannot bind);
  - contract 1 plugins, which keep running full-trust with the badge
    "Full access (older plugin)".
  - It needs `host.full_trust` (critical).
- **What this buys.** For contained plugins, the permission table in §4
  becomes an actual boundary, and the audit log becomes complete.
- **What it costs.**
  - A broker round trip for network and files.
  - One process per plugin in use. Mitigated by lazy loading and the
    60 s idle unload (§1.5).
  - A per-kind loader spike.
- **What it cannot fix.** Full-trust plugins. That is why they are
  limited to official origins by default, and why each one is its own
  process.

---

## 6. Mapping today's plugin API onto this model

Everything below keeps working unchanged for contract 1 manifests. One
translation function (`LegacyManifest.toV2`, P0-2) turns a v1 manifest
into the v2 shape at parse time, so the host has one model internally.

| Today (code) | v2 | Implicit v2 permissions for a v1 plugin | Callers today |
| --- | --- | --- | --- |
| `acquire_content` (`PluginCapability.ACQUIRE_CONTENT`) | EP `library.sources@1` (A2); `invoke{action:search}` → `search`, `startJob{action:download}` → `acquire` | `provide:library.sources`, `library.folders.write` (the destination only), `net.any` | `AcquireContentSources` → Get games (system settings, gamelist Options) |
| `metadata_source` | EP `library.metadata@1` (A3) | `provide:library.metadata`, `net.any` | **none**: declared, never called |
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

- **While no root provider is installed,** a v1 plugin with
  `rootApproved` keeps today's behaviour: `hasRootApproval()` means
  "device has root (`su -c id`) AND the user ticked it".
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
    subset (a ticked item is granted; an unticked dangerous permission is
    `ask`, any other unticked item `denied`); a contract 1 plugin keeps the
    permissions it could already do, its points follow the ticks, and its
    root tick is the `priv.shell.root` grant. A file that cannot be read is `ask` for
    everything, never `granted`. The approval screen's tick boxes are
    `ConsentView` lines with a grant key; the box state is kept until
    Approve writes it through `PluginStore.setApproval`.
  - A call in `ask` state during a user-initiated host to plugin call
    shows the sheet (`PluginGrantPrompts`, drawn by Gaming's shell); a call
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
    grant, or the call is refused.
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
  - The first-use sheet is `PluginGrantSheetHost` in Gaming's shell:
    Allow / Not now / Never allow, Up and Down, A, B is Not now, with the
    plugin's own reason. Standard and Desktop have no such surface yet, so
    a call in `ask` state there answers Not now and the permission is
    granted from the Permissions screen.
  - Accounts and sources > Plugins > a plugin > Permissions lists every
    declared permission, each high-risk point it may provide and each
    export, with Allowed / Ask first / Blocked, its reason, "tried to use
    this while you were not being asked" and when it was last used. New
    items from an update sit under "Wants new access" and the plugin's row
    says so.
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
    `library.read`. Built on the detail screen of console ROMs and apps;
    the PC game menu, the Select/Options menu, the launcher's long-press
    menu and the desktop menus are not.
  - **Metadata sources (A3).** `PluginMetadataSources` opens one session per
    console scrape pass and, for each game, asks every running source
    `match` (facts: title, file name, system) then `fetch` (the ids of the
    best candidate at or above 0.5 confidence). What comes back is offered
    after every built-in source: it fills a field none of them found and
    never replaces one, and each field it supplied is recorded in
    `fieldSources` under the plugin's name. `rating` is 0..1 and release
    dates are normalised to ES-DE's `YYYYMMDDT000000` or dropped. Not built:
    listing a plugin as a choice on the Scraper screen, "Rescrape with...",
    and the PC scrape.
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
  The other `priv.packages` ops of §2.2 (install, uninstall, grant, appop)
  are not specified or built.
- **The transport.** Shizuku's server pushes its binder to a content
  provider named `<applicationId>.shizuku` in each app the user allowed.
  `:plugin-host` declares Shizuku's own `ShizukuProvider` under that name in
  `:pluginhost`, the process plugins run in, and depends on Shizuku's client
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
- The provider plugin itself is `Droidtop/droidtop-plugin-shizuku`
  (`droidtop-plugin/`): a contract 2 manifest exporting both interfaces,
  full-trust, with the existing status tile and app_status surfaces.

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

**Refusals.**

- A manifest whose `contractVersion` is newer than the host supports is
  refused, as today.
- A `provides` entry for a major the host no longer serves is listed as
  "Not supported by this version" and never called, but the rest of the
  plugin still works.

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
| `pre_launch` hook (B3) | 2 s | launch proceeds; not a crash |
| status or quick tile `state` (C2, C3) | 5 s (the widget's existing value) | last value kept; not a crash |
| provider call (§2.4) | the caller's remaining time minus 1 s, at most 15 s | caller gets `TIMEOUT`; **provider** treated as crashed |
| job | no per-call bound; progress at least every 60 s or the job shows "not responding" (not killed) | the user cancels |

Budgets below 15 s that are marked "not a crash" exist because the UI
chose not to wait. A plugin that misses them was slow, not broken.

**Quotas** (per plugin, host-enforced, reply `RATE_LIMITED`):

| Resource | Quota |
| --- | --- |
| broker calls | 50/s burst, 10/s sustained |
| reply size | 256 KiB (`MAX_RESULT_BYTES`); argument size 256 KiB |
| `net.http` response | 8 MiB (bigger goes through `net.download` as a job) |
| concurrent jobs | 2, beyond which jobs queue in the Jobs screen |
| notifications | 5 per hour, 1 ongoing |
| overlay toasts | 1 per 10 s |
| scheduled jobs | at most every 15 min (the WorkManager floor) |
| `power.keep_awake` | 10 min per request, only with a running job |
| data directory | a 512 MiB soft limit, shown on the plugin row; no hard stop (droidtop never deletes a plugin's data by itself) |
| audit log | 2,000 entries / 30 days |
| event delivery | an event whose handlers are not done in 15 s is dropped for that plugin (as today's watchdog) |

**Processes.**

- Today: one shared `:pluginhost`, with `PluginRuntimeService` holding a
  map of loaded plugins.
- Target:
  - contained plugins get one isolated process each;
  - full-trust plugins get one process each.
  In both cases the idle unload (60 s) frees memory, and Android's
  low-memory killer may kill any plugin process. That counts as process
  death: in-flight calls fail, but **it does not disable the plugin**
  when the system (not the plugin) killed it (see P1-12).

When the shared `:pluginhost` process dies, the host disables exactly the
plugins with calls in flight at the time of death. A process death while
idle disables none, so the next call can reconnect after a low-memory kill.
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
| P1-7 (#63) | Plugin → plugin calls through the broker: the caller's own grant, the `caller`/`via` block, depth ≤ 3, cycle refusal, provider timeout = provider crash, job ownership | unit tests on each rule; a sample provider/caller pair |
| P1-8 **oc** (#64) | `startJob`/`cancelJob` for the `python` kind (the kind-parity gap) | the python sample gains a job; `sample-plugin-python` CI green; a rig check |
| P1-9 (#65) | Permission diff on update: new dangerous items → `ask`, "Wants new access" on the row | unit test on the diff |
| P1-10 **oc** (#66) | Event namespacing + the events `game.launching`, `game.exited`, `library.scan_finished`, `mode.changed`; `default_player_changed` kept as an alias | unit tests on the args builders (like `DefaultPlayerChangedArgsTest`) |
| P1-11 (#67) | Wire the two dead capabilities: `library_action` → a game's detail screen (C4), `metadata_source` → the scrape pass (A3) | a rig check with a sample |
| P1-12 **oc** (#68) | The crash policy tells a system kill (LMK/process death with no plugin in a call) from a plugin crash: only the latter disables; a whole-process death disables the plugins that had calls in flight (today it returns without disabling) | unit tests with the fake runner |
| P1-13 **oc** (#69) | Vault API (G1): per-plugin Keystore-wrapped storage, excluded from backups, deleted on uninstall | unit tests (JVM with a fake key provider) |
| P1-14 **oc** (#70) | Manifest JSON Schema (`docs/plugin-manifest.schema.json`) + CI validating every sample's manifest | CI job green |
| P1-15 **oc** (#71) | Fake host (`plugin-host-testing`) + the conformance script, run against the native and python samples in CI | CI job green |
| P1-16 (#72) | The official Shizuku provider plugin (`droidtop-plugin-shizuku`: exports `priv.shell@1 level=adb`, `priv.packages@1`), replacing `hasShizukuAccess` for v2 | **owner-action**: create the repo; then build it; a rig check with Shizuku |
| P1-17 (#73) | Quick Menu surface for `ui.status_tile` / `ui.quick_tile` in Gaming (C2/C3) | a rig check |

### P2: later

Each is one issue when its time comes. They are not filed now, to keep
the tracker honest.

- **Containment:**
  - P2-1: the contained-tier spike, per kind, on both rigs;
  - P2-2: the contained tier as the default for contract 2;
  - P2-3: one process per full-trust plugin.
- **Network:**
  - P2-4: `net.http`/`net.download` broker with domain allowlists;
  - P2-5: a web-session API (G3) for #9;
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
  - P2-20: notifications (C6);
  - P2-21: theme packs (C7);
  - P2-22: desktop tray through SNI (C8);
  - P2-23: file handlers and associations (C9, E4);
  - P2-24: widgets and companion panels (C10, C15);
  - P2-25: gaming rows (C11);
  - P2-26: launcher actions + the `launcher3` popup hook (C12);
  - P2-27: onboarding steps (C13);
  - P2-28: prompts (C14).
- **System and desktop:**
  - P2-29: the system group D1–D17 as host APIs, in order of demand;
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
  - P2-47: a provider/caller sample pair for each kind (I4).

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
  needs it? The recommendation is yes.
