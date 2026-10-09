"""droidtop's python-kind sample plugin (docs/SPEC.md 12a): a harmless
status_tile, the Python analogue of samples/plugin-sample-statustile's
Kotlin StatusTilePlugin.kt, exercising the exact same path -- install,
approve, invoke() through PythonBridge and back, and a deliberate forced
crash for the disable path -- but running inside the embedded CPython
interpreter instead of a DexClassLoader.

Contract with the native bridge (plugin-host/native/src/droidtoppy_jni.c
and PythonDroidtopPlugin.kt): this file is loaded as a single module, and
these top-level functions are called directly:

  on_load(data_dir: str) -> None        (optional)
  invoke(payload_json: str) -> str      (required for status_tile, v1)
  on_event(payload_json: str) -> str    (optional, called when a subscribed
                                       event fires; payload has "event" and
                                       "args")
  handle(call_json: str) -> str         (contract 2)
  on_unload() -> None                   (optional)

invoke's payload is {"capability": "<id>", "args": {...}} as a JSON
string; its return must be a JSON string shaped like PluginResult:
{"ok": bool, "values": {...}, "error": str|null}. Nothing here imports
anything beyond the standard library (json), which is all any python-kind
plugin can rely on being present -- see PythonRuntimeManager for exactly
what the downloaded runtime carries.

handle can also call droidtop: the panel's "hello" action shows a toast through
droidtop.host.call("ui.toast", "show", ...), which needs "overlay.toast" in the
manifest's permissions. A refusal comes back as an ordinary error reply, never
an exception.

The plugin runs contained (docs/plugin-api.md 5.3): an isolated process with no
network and no files of its own, so on_load's data_dir is empty and nothing can
be opened by path. Its settings live in droidtop's data API instead
(droidtop.host.call("data", "read" / "write", {"name": ...})), and the panel's
network line asks droidtop (net.state) rather than opening a socket.

handle receives the contract 2 envelope (JSON text in, JSON text out)
and must reply with {"ok": true, "data": {...}} or
{"ok": false, "error": {"code": "UNSUPPORTED", "message": "..."}}.
"""
import json
import threading
import time

# The module droidtop injects into the interpreter before this file is imported
# (docs/plugin-api.md 1.3): host.call(api, op, args=None, version=1) makes one
# call to droidtop's broker and returns its reply, {"ok": true, "data": {...}}
# or {"ok": false, "error": {"code": ..., "message": ...}}. It is the same call
# a native plugin makes, so a permission the manifest does not declare is
# refused here too.
import droidtop.host

_load_count = 0

# The plugin's settings, by name in its own data kept by droidtop (docs/plugin-api.md 3 H1).
_SETTINGS = "settings.json"
_DEFAULTS = {"show_count": True, "greeting": "hello"}


def _load_settings():
    reply = droidtop.host.call("data", "read", {"name": _SETTINGS})
    if not reply.get("ok"):
        return dict(_DEFAULTS)
    try:
        settings = json.loads((reply.get("data") or {}).get("text") or "{}")
    except ValueError:
        return dict(_DEFAULTS)
    for key, value in _DEFAULTS.items():
        settings.setdefault(key, value)
    return settings


def _save_settings(settings):
    droidtop.host.call("data", "write", {"name": _SETTINGS, "text": json.dumps(settings)})


def _network_line():
    """Whether the device is online, as droidtop sees it: the plugin itself has no network to look at."""
    reply = droidtop.host.call("net", "state")
    if not reply.get("ok"):
        error = reply.get("error") or {}
        return "Not allowed: %s" % (error.get("message") or "no reason given")
    data = reply.get("data") or {}
    if not data.get("online"):
        return "Offline"
    return "Online (%s%s)" % (data.get("type", "unknown"), ", VPN" if data.get("vpn") else "")


def _say_hello():
    """Shows a toast through the broker and says in words what happened."""
    greeting = _load_settings().get("greeting", "hello")
    reply = droidtop.host.call("ui.toast", "show", {"text": "%s, from the Python sample panel" % greeting})
    if reply.get("ok") and (reply.get("data") or {}).get("shown"):
        return "Said hello"
    error = reply.get("error") or {}
    return "droidtop did not show the toast: %s" % (error.get("message") or "no reason given")


def on_load(data_dir):
    # data_dir is empty for a contained plugin: everything of its own goes through the data API.
    global _load_count
    _load_count += 1


def invoke(payload_json):
    payload = json.loads(payload_json)
    capability = payload.get("capability")
    args = payload.get("args") or {}

    if capability != "status_tile":
        return json.dumps({"ok": False, "values": {}, "error": "plugin.py only implements status_tile"})

    # The one deliberate way to test crash containment on the rig
    # (dq-plugins-01's python leg): never present in a real droidtop call
    # site, only in the rig's own manual test step.
    if args.get("query") == "force-crash":
        raise RuntimeError("forced crash for dq-plugins-01 (python)")

    return json.dumps(
        {
            "ok": True,
            "values": {
                "label": "Sample tile (python)",
                "value": "loaded %d time(s), called OK" % _load_count,
            },
            "error": None,
        },
    )


def handle(call_json):
    call = json.loads(call_json)
    point = call.get("point")
    op = call.get("op")
    args = call.get("args") or {}

    # Persist any input values carried by the call.
    if "values" in args:
        settings = _load_settings()
        updated = False
        if "show_count" in args["values"]:
            val = args["values"]["show_count"]
            if isinstance(val, bool):
                settings["show_count"] = val
            elif isinstance(val, str):
                settings["show_count"] = val == "true"
            else:
                settings["show_count"] = bool(val)
            updated = True
        if "greeting" in args["values"]:
            settings["greeting"] = args["values"]["greeting"]
            updated = True
        if updated:
            _save_settings(settings)

    if point == "ui.status_tile" and op == "state":
        if args.get("query") == "force-crash":
            raise RuntimeError("forced crash for dq-plugins-01 (python)")
        return json.dumps(
            {
                "ok": True,
                "data": {
                    "label": "Sample tile (python)",
                    "value": "loaded %d time(s)" % _load_count,
                },
            }
        )

    if point == "ui.settings":
        if op == "view":
            settings = _load_settings()
            return json.dumps(
                {
                    "ok": True,
                    "data": {
                        "view": 1,
                        "sections": [
                            {
                                "id": "main",
                                "items": [
                                    {
                                        "type": "info",
                                        "id": "about",
                                        "title": "This is a sample plugin page",
                                        "subtitle": "Drawn by droidtop from data the plugin returned",
                                    },
                                    {
                                        "type": "toggle",
                                        "id": "show_count",
                                        "title": "Show the call count in the tile",
                                        "value": settings.get("show_count", True),
                                    },
                                    {
                                        "type": "choice",
                                        "id": "greeting",
                                        "title": "Greeting",
                                        "options": [
                                            {"value": "hello", "label": "Hello"},
                                            {"value": "hi", "label": "Hi"},
                                        ],
                                        "value": settings.get("greeting", "hello"),
                                    },
                                    {
                                        "type": "button",
                                        "id": "greet",
                                        "title": "Say it",
                                        "action": {"kind": "call", "op": "greet"},
                                    },
                                    {
                                        "type": "button",
                                        "id": "run_job",
                                        "title": "Run sample job",
                                        "subtitle": "Reports live progress and can be cancelled from Jobs",
                                        "action": {"kind": "job", "op": "sample_job", "title": "Python sample job"},
                                    },
                                ],
                            }
                        ],
                    },
                }
            )
        if op == "greet":
            settings = _load_settings()
            greeting = settings.get("greeting", "hello")
            return json.dumps(
                {
                    "ok": True,
                    "data": {
                        "message": "%s, from the sample plugin" % greeting,
                    },
                }
            )

    # The Quick Menu panel (docs/plugin-api.md 3 C17): the plugin's own control point, a view droidtop draws.
    if point == "ui.panel":
        if op == "panel":
            settings = _load_settings()
            return json.dumps(
                {
                    "ok": True,
                    "data": {
                        "view": 1,
                        "sections": [
                            {
                                "id": "main",
                                "items": [
                                    {
                                        "type": "info",
                                        "id": "loads",
                                        "title": "Loaded",
                                        "value": "%d time(s)" % _load_count,
                                    },
                                    {
                                        "type": "info",
                                        "id": "network",
                                        "title": "Network",
                                        "value": _network_line(),
                                    },
                                    {
                                        "type": "toggle",
                                        "id": "show_count",
                                        "title": "Show the call count in the tile",
                                        "value": settings.get("show_count", True),
                                        "action": {"kind": "call", "op": "save"},
                                    },
                                    {
                                        "type": "button",
                                        "id": "greet",
                                        "title": "Say it",
                                        "action": {"kind": "call", "op": "greet"},
                                    },
                                    {
                                        "type": "button",
                                        "id": "hello",
                                        "title": "Say hello as a toast",
                                        "subtitle": "Calls droidtop from Python (ui.toast)",
                                        "action": {"kind": "call", "op": "hello"},
                                    },
                                ],
                            }
                        ],
                    },
                }
            )
        if op == "hello":
            return json.dumps({"ok": True, "data": {"message": _say_hello()}})
        if op == "save":
            return json.dumps({"ok": True, "data": {"message": "Saved"}})
        if op == "greet":
            greeting = _load_settings().get("greeting", "hello")
            return json.dumps({"ok": True, "data": {"message": "%s, from the sample panel" % greeting}})

    return json.dumps(
        {
            "ok": False,
            "error": {
                "code": "UNSUPPORTED",
                "message": "Unsupported point/op: %s/%s" % (point, op),
            },
        }
    )


_job_cancellations = {}
_cancelled_before_start = set()
_job_cancellations_lock = threading.Lock()


def start_job(job_id, call, progress):
    """Small visible job that reports live progress and checks cancellation."""
    cancelled = threading.Event()
    with _job_cancellations_lock:
        _job_cancellations[job_id] = cancelled
        if job_id in _cancelled_before_start:
            cancelled.set()
            _cancelled_before_start.discard(job_id)
    try:
        for step in range(1, 6):
            if cancelled.is_set():
                return json.dumps({"ok": False, "error": "cancelled"})
            progress(step * 20, "Python sample job: step %d of 5" % step)
            time.sleep(0.4)
        return json.dumps({"ok": True, "values": {"message": "Python sample job complete"}})
    finally:
        with _job_cancellations_lock:
            _job_cancellations.pop(job_id, None)


def cancel_job(job_id):
    with _job_cancellations_lock:
        cancelled = _job_cancellations.get(job_id)
        if cancelled is not None:
            cancelled.set()
        else:
            _cancelled_before_start.add(job_id)


def on_unload():
    pass
