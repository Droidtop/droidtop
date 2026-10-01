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

handle receives the contract 2 envelope (JSON text in, JSON text out)
and must reply with {"ok": true, "data": {...}} or
{"ok": false, "error": {"code": "UNSUPPORTED", "message": "..."}}.
"""
import json
import os

_load_count = 0
_settings_path = None


def _load_settings():
    defaults = {"show_count": True, "greeting": "hello"}
    if not _settings_path or not os.path.isfile(_settings_path):
        return defaults.copy()
    try:
        with open(_settings_path, "r") as f:
            settings = json.load(f)
        if "show_count" not in settings:
            settings["show_count"] = defaults["show_count"]
        if "greeting" not in settings:
            settings["greeting"] = defaults["greeting"]
        return settings
    except Exception:
        return defaults.copy()


def _save_settings(settings):
    if _settings_path:
        try:
            with open(_settings_path, "w") as f:
                json.dump(settings, f)
        except Exception:
            pass


def on_load(data_dir):
    global _load_count, _settings_path
    _load_count += 1
    if data_dir:
        _settings_path = os.path.join(data_dir, "settings.json")
    else:
        _settings_path = None


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

    return json.dumps(
        {
            "ok": False,
            "error": {
                "code": "UNSUPPORTED",
                "message": "Unsupported point/op: %s/%s" % (point, op),
            },
        }
    )


def on_unload():
    pass
