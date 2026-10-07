"""Checks the sample's host-call path with a fake droidtop module (the real one only exists
inside droidtop's embedded interpreter): `python3 test_plugin.py` from this folder."""
import json
import sys
import types
import unittest

calls = []
next_reply = {"ok": True, "data": {"shown": True}}

fake_host = types.ModuleType("droidtop.host")
fake_host.call = lambda api, op, args=None, version=1: (calls.append((api, op, args, version)), next_reply)[1]
fake_pkg = types.ModuleType("droidtop")
fake_pkg.__path__ = []
fake_pkg.host = fake_host
sys.modules["droidtop"] = fake_pkg
sys.modules["droidtop.host"] = fake_host

sys.path.insert(0, "src")
import plugin  # noqa: E402


def panel_call(op):
    return json.loads(plugin.handle(json.dumps({"point": "ui.panel", "op": op, "args": {}})))


class SamplePanelTest(unittest.TestCase):
    def setUp(self):
        global next_reply
        calls.clear()
        next_reply = {"ok": True, "data": {"shown": True}}

    def test_panel_has_a_hello_button_that_calls_the_panel_op(self):
        items = panel_call("panel")["data"]["sections"][0]["items"]
        hello = [i for i in items if i["id"] == "hello"][0]
        self.assertEqual({"kind": "call", "op": "hello"}, hello["action"])

    def test_hello_shows_a_toast_through_the_broker(self):
        reply = panel_call("hello")
        self.assertTrue(reply["ok"])
        self.assertEqual("Said hello", reply["data"]["message"])
        self.assertEqual(1, len(calls))
        api, op, args, version = calls[0]
        self.assertEqual(("ui.toast", "show", 1), (api, op, version))
        self.assertIn("Python sample panel", args["text"])

    def test_a_refusal_is_reported_not_raised(self):
        global next_reply
        next_reply = {"ok": False, "error": {"code": "PERMISSION_DENIED", "message": "Show toasts is turned off for this plugin"}}
        reply = panel_call("hello")
        self.assertTrue(reply["ok"])
        self.assertIn("Show toasts is turned off", reply["data"]["message"])

    def test_the_manifest_declares_the_toast_permission(self):
        with open("manifest.template.json") as f:
            manifest = json.load(f)
        self.assertIn("overlay.toast", [p["id"] for p in manifest["permissions"]])


if __name__ == "__main__":
    unittest.main()
