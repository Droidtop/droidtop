"""Drives the sample social provider through every op of social.provider@1 with a fake droidtop
module (the real one only exists inside droidtop's embedded interpreter):
`python3 test_plugin.py` from this folder. CI runs the same."""
import json
import sys
import types
import unittest

calls = []
next_reply = {"ok": True, "data": {"accepted": True}}

fake_host = types.ModuleType("droidtop.host")
fake_host.call = lambda api, op, args=None, version=1: (calls.append((api, op, args, version)), next_reply)[1]
fake_pkg = types.ModuleType("droidtop")
fake_pkg.__path__ = []
fake_pkg.host = fake_host
sys.modules["droidtop"] = fake_pkg
sys.modules["droidtop.host"] = fake_host

sys.path.insert(0, "src")
import plugin  # noqa: E402

plugin.REPLY_DELAY = 0  # a fake friend answers inline, so the test needs no threads


def social(op, **args):
    args.setdefault("service", "default")
    return json.loads(plugin.handle(json.dumps({"point": "social.provider", "version": 1, "op": op, "args": args})))


def panel(op):
    return json.loads(plugin.handle(json.dumps({"point": "ui.panel", "version": 1, "op": op, "args": {}})))


class SampleSocialProviderTest(unittest.TestCase):
    def setUp(self):
        global next_reply
        calls.clear()
        next_reply = {"ok": True, "data": {"accepted": True}}
        plugin._presence = "online"
        plugin._messages.clear()
        plugin._unread.clear()

    def test_account_says_connected_with_a_name_and_a_standing(self):
        data = social("account")["data"]
        self.assertEqual("online", data["link"])
        self.assertEqual("online", data["presence"])
        self.assertTrue(data["name"])

    def test_friends_carry_the_fields_droidtop_reads(self):
        friends = social("friends")["data"]["friends"]
        self.assertEqual({"ada", "bo", "cy"}, {f["id"] for f in friends})
        ada = [f for f in friends if f["id"] == "ada"][0]
        self.assertEqual("in_game", ada["state"])
        self.assertEqual("Celeste", ada["activity"])
        for f in friends:
            self.assertIn(f["state"], ("online", "away", "busy", "in_game", "offline"))
            self.assertEqual(0, f["unread"])

    def test_sending_joins_the_conversation_and_the_answer_is_announced_to_droidtop(self):
        reply = social("send", friendId="bo", text="hi there")
        self.assertTrue(reply["ok"])
        self.assertTrue(reply["data"]["message"]["mine"])
        # The fake friend answered and the plugin told droidtop through the broker.
        self.assertEqual(1, len(calls))
        api, op, args, version = calls[0]
        self.assertEqual(("social", "changed", 1), (api, op, version))
        self.assertEqual("bo", args["friendId"])
        self.assertEqual("default", args["service"])
        self.assertEqual("Got it: hi there", args["message"]["text"])
        self.assertFalse(args["message"]["mine"])
        bo = [f for f in social("friends")["data"]["friends"] if f["id"] == "bo"][0]
        self.assertEqual(1, bo["unread"])
        self.assertGreater(bo["lastMessageMs"], 0)

    def test_opening_a_conversation_reads_it_and_marks_it_read(self):
        social("send", friendId="ada", text="gg")
        history = social("conversation", friendId="ada", read=True)["data"]["messages"]
        self.assertEqual(["gg", "Got it: gg"], [m["text"] for m in history])
        self.assertEqual(len(history), len({m["key"] for m in history}))
        ada = [f for f in social("friends")["data"]["friends"] if f["id"] == "ada"][0]
        self.assertEqual(0, ada["unread"])

    def test_a_refused_announcement_does_not_break_the_send(self):
        global next_reply
        next_reply = {"ok": False, "error": {"code": "PERMISSION_DENIED", "message": "Friends and chat is turned off"}}
        self.assertTrue(social("send", friendId="cy", text="still here")["ok"])
        message = panel("ping")["data"]["message"]
        self.assertIn("Friends and chat is turned off", message)

    def test_the_standing_can_be_set_and_offline_empties_the_list(self):
        self.assertTrue(social("presence", presence="offline")["ok"])
        self.assertEqual("offline", social("account")["data"]["link"])
        self.assertEqual([], social("friends")["data"]["friends"])
        self.assertFalse(social("send", friendId="bo", text="x")["ok"])
        self.assertEqual("INVALID_ARGS", social("presence", presence="dancing")["error"]["code"])

    def test_unknown_friends_services_and_ops_are_error_replies(self):
        self.assertEqual("NOT_FOUND", social("conversation", friendId="nobody")["error"]["code"])
        self.assertEqual("NOT_FOUND", social("friends", service="other")["error"]["code"])
        self.assertEqual("UNSUPPORTED", social("invite", friendId="bo")["error"]["code"])
        self.assertEqual("INVALID_ARGS", social("send", friendId="bo", text="   ")["error"]["code"])

    def test_the_panel_button_makes_a_friend_write(self):
        items = panel("panel")["data"]["sections"][0]["items"]
        ping = [i for i in items if i["id"] == "ping"][0]
        self.assertEqual({"kind": "call", "op": "ping"}, ping["action"])
        self.assertEqual("Bo wrote to you", panel("ping")["data"]["message"])
        self.assertEqual("bo", calls[-1][2]["friendId"])

    def test_notify_me_asks_droidtop_to_post_and_reports_a_refusal(self):
        global next_reply
        next_reply = {"ok": True, "data": {"posted": True}}
        self.assertEqual("Posted a notification", panel("notify")["data"]["message"])
        api, op, args, version = calls[-1]
        self.assertEqual(("notify", "post"), (api, op))
        self.assertEqual("Sample chat", args["title"])
        next_reply = {"ok": False, "error": {"code": "RATE_LIMITED", "message": "at most 5 notifications an hour"}}
        self.assertIn("at most 5", panel("notify")["data"]["message"])

    def test_the_manifest_provides_the_point_and_declares_notifications(self):
        with open("manifest.template.json") as f:
            manifest = json.load(f)
        self.assertEqual(2, manifest["contractVersion"])
        self.assertIn("social.provider", [p["point"] for p in manifest["provides"]])
        self.assertIn("notify.post", [p["id"] for p in manifest["permissions"]])


if __name__ == "__main__":
    unittest.main()
