"""Checks the sample's host-call path with a fake droidtop module (the real one only exists
inside droidtop's embedded interpreter): `python3 test_plugin.py` from this folder."""
import json
import sys
import types
import unittest

calls = []
next_reply = {"ok": True, "data": {"shown": True}}
# The plugin's own data as droidtop keeps it (the data API): the plugin has no files of its own.
store = {}


def fake_call(api, op, args=None, version=1):
    args = args or {}
    if api == "data":
        if op == "read":
            if args["name"] not in store:
                return {"ok": False, "error": {"code": "NOT_FOUND", "message": "no data named %s" % args["name"]}}
            return {"ok": True, "data": {"text": store[args["name"]], "size": len(store[args["name"]]), "eof": True}}
        if op == "write":
            store[args["name"]] = args["text"]
            return {"ok": True, "data": {"size": len(args["text"])}}
    calls.append((api, op, args, version))
    return next_reply


fake_host = types.ModuleType("droidtop.host")
fake_host.call = fake_call
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
        store.clear()
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

    def test_settings_live_in_droidtops_data_api_not_in_files(self):
        plugin.handle(json.dumps({"point": "ui.settings", "op": "greet", "args": {"values": {"greeting": "hi"}}}))
        self.assertEqual("hi", json.loads(store["settings.json"])["greeting"])
        reply = json.loads(plugin.handle(json.dumps({"point": "ui.settings", "op": "greet", "args": {}})))
        self.assertEqual("hi, from the sample plugin", reply["data"]["message"])
        with open("src/plugin.py") as f:
            source = f.read()
        self.assertNotIn("open(", source, "a contained plugin can open no file; it uses the data API")

    def test_the_panel_asks_droidtop_whether_it_is_online(self):
        global next_reply
        next_reply = {"ok": True, "data": {"online": True, "type": "wifi", "vpn": False}}
        items = panel_call("panel")["data"]["sections"][0]["items"]
        network = [i for i in items if i["id"] == "network"][0]
        self.assertEqual("Online (wifi)", network["value"])
        self.assertIn(("net", "state"), [(api, op) for api, op, _, _ in calls])

    def test_recording_tells_droidtop_and_the_tile_follows(self):
        reply = json.loads(plugin.handle(json.dumps({"point": "ui.quick_tile", "op": "toggle", "args": {"tileId": "recording"}})))
        self.assertTrue(reply["ok"])
        self.assertIn(("companion", "recording", {"on": True}), [(api, op, args) for api, op, args, _ in calls])
        state = json.loads(plugin.handle(json.dumps({"point": "ui.quick_tile", "op": "state", "args": {"tileId": "recording"}})))
        self.assertTrue(state["data"]["on"])
        panel_call("record")
        self.assertEqual({"on": False}, calls[-1][2])

    def test_the_game_tab_gets_rows_and_the_load_row_asks_first(self):
        call = {"point": "ui.panel", "op": "panel", "args": {"context": {"surface": "gaming.companion_game", "game": {"title": "Metroid"}}}}
        items = json.loads(plugin.handle(json.dumps(call)))["data"]["sections"][0]["items"]
        self.assertEqual("Metroid", items[0]["value"])
        self.assertIn("confirm", [i for i in items if i["id"] == "sample_load"][0])

    def test_the_manifest_declares_the_companion_abilities(self):
        with open("manifest.template.json") as f:
            manifest = json.load(f)
        panel = [p for p in manifest["provides"] if p["point"] == "ui.panel"][0]
        self.assertEqual(["game", "recording"], panel["companion"])

    def test_the_manifest_declares_the_toast_permission(self):
        with open("manifest.template.json") as f:
            manifest = json.load(f)
        self.assertIn("overlay.toast", [p["id"] for p in manifest["permissions"]])


if __name__ == "__main__":
    unittest.main()
