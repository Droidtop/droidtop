#!/usr/bin/env python3
"""Drives the Desktop-mode fixture's plugin.py (docs/plugin-api.md 1.12, Droidtop/tracker#221) without a device.

    desktop_fixture_check.py [plugin dir, default ../src/test/fixtures/desktop]

The modules droidtop and droidtop.host are put in sys.modules first, as droidtop's bootstrap does, and answer the
host ops each Desktop hook uses from memory. Each of the fixture's ops is called once; exits 0 when every one answers
as a plugin author would expect. The JVM side of the same hooks is DesktopFixtureTest.
"""

import importlib.util
import json
import os
import sys
import types

sys.dont_write_bytecode = True

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_DIR = os.path.join(HERE, "..", "src", "test", "fixtures", "desktop")

WINDOWS = [{"id": 7, "appId": "foot", "title": "~", "output": "HEADLESS-1", "focused": False}]


class Host:
    def __init__(self):
        self.calls = []
        self.clipboard = "copied"

    def call(self, api, op, args=None, version=1):
        args = args or {}
        self.calls.append((api, op))
        if (api, op) == ("windows", "list"):
            return {"ok": True, "data": {"windows": WINDOWS}}
        if (api, op) == ("windows", "focus"):
            known = any(w["id"] == args.get("id") for w in WINDOWS)
            return {"ok": True, "data": {"done": True}} if known else {"ok": False, "error": {"code": "NOT_FOUND", "message": "No such window."}}
        if (api, op) == ("clipboard", "get_text"):
            return {"ok": True, "data": {"text": self.clipboard}}
        if (api, op) == ("clipboard", "set_text"):
            self.clipboard = args.get("text")
            return {"ok": True, "data": {"set": True}}
        if (api, op) == ("notify", "post"):
            return {"ok": True, "data": {"posted": True}}
        if (api, op) == ("files", "forget"):
            return {"ok": True, "data": {"forgotten": True}}
        return {"ok": False, "error": {"code": "UNSUPPORTED", "message": "Not served here."}}

    def open(self, api, op, args=None, version=1):
        self.calls.append((api, op))
        if (api, op) == ("files", "open") and (args or {}).get("token") == "f-1":
            return {"ok": True, "data": {}, "fd": 3}
        return {"ok": False, "error": {"code": "NOT_FOUND", "message": "No such file."}}


def load(plugin_dir, host):
    module = types.ModuleType("droidtop.host")
    module.call = host.call
    module.open = host.open
    pkg = types.ModuleType("droidtop")
    pkg.__path__ = []
    pkg.host = module
    sys.modules["droidtop"] = pkg
    sys.modules["droidtop.host"] = module
    spec = importlib.util.spec_from_file_location("fixture_desktop", os.path.join(plugin_dir, "plugin.py"))
    plugin = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(plugin)
    return plugin


def envelope(point, op, args):
    return json.dumps({"contract": 2, "callId": "check", "deadlineMs": 15000, "point": point, "version": 1, "op": op,
                       "caller": {"kind": "host"}, "surface": {}, "args": args, "host": {}})


def main():
    plugin_dir = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_DIR
    host = Host()
    plugin = load(plugin_dir, host)
    window = {"type": "window", "windowId": 7, "appId": "foot"}
    cases = [
        ("tray facets", "ui.facets", "get", {"target": "desktop.tray"}, lambda d: len(d["facets"]) == 5),
        ("window facets", "ui.facets", "get", {"target": "desktop.window", "subject": window}, lambda d: len(d["facets"]) == 2),
        ("focus the first window", "ui.facets", "focus_first", {}, lambda d: "Focused" in d["message"]),
        ("focus this window", "ui.facets", "focus_this", {"context": {"subject": window}}, lambda d: d["message"] == "Focused."),
        ("read the clipboard", "ui.facets", "read_clipboard", {}, lambda d: "6 characters" in d["message"]),
        ("copy test text", "ui.facets", "copy_test", {}, lambda d: host.clipboard == "droidtop desktop fixture"),
        ("notify", "ui.facets", "notify", {}, lambda d: d["message"] == "Sent."),
        ("open with", "files.handler", "open", {"token": "f-1", "name": "Krita.AppImage", "mime": ""}, lambda d: "Krita" in d["message"]),
    ]
    failures = []
    for name, point, op, args, good in cases:
        reply = json.loads(plugin.handle(envelope(point, op, args)))
        if not reply.get("ok") or not good(reply.get("data", {})):
            failures.append("%s: %s" % (name, json.dumps(reply)))
    event = json.loads(plugin.on_event(json.dumps({"event": "window.opened", "args": {"id": "7", "appId": "foot"}})))
    if not event.get("ok") or plugin.SEEN[0] != ("window.opened", "foot"):
        failures.append("window.opened was not taken: %s" % json.dumps(event))
    if ("files", "forget") not in host.calls:
        failures.append("open with did not forget its token")
    for failure in failures:
        print("FAIL " + failure)
    print("%d of %d Desktop fixture checks passed" % (len(cases) + 2 - len(failures), len(cases) + 2))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
