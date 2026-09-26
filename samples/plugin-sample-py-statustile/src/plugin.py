"""droidtop's python-kind sample plugin (docs/SPEC.md 12a): a harmless
status_tile, the Python analogue of samples/plugin-sample-statustile's
Kotlin StatusTilePlugin.kt, exercising the exact same path -- install,
approve, invoke() through PythonBridge and back, and a deliberate forced
crash for the disable path -- but running inside the embedded CPython
interpreter instead of a DexClassLoader.

Contract with the native bridge (plugin-host/native/src/droidtoppy_jni.c
and PythonDroidtopPlugin.kt): this file is loaded as a single module, and
these three top-level functions are called directly:

  on_load(data_dir: str) -> None        (optional)
  invoke(payload_json: str) -> str      (required for status_tile)
  on_unload() -> None                   (optional)

invoke's payload is {"capability": "<id>", "args": {...}} as a JSON
string; its return must be a JSON string shaped like PluginResult:
{"ok": bool, "values": {...}, "error": str|null}. Nothing here imports
anything beyond the standard library (json), which is all any python-kind
plugin can rely on being present -- see PythonRuntimeManager for exactly
what the downloaded runtime carries.
"""
import json

_load_count = 0


def on_load(data_dir):
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


def on_unload():
    pass
