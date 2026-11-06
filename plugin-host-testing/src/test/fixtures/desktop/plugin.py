"""The Desktop-mode fixture (docs/plugin-api.md 1.12, Droidtop/tracker#221), written as a plugin author writes one.
It exercises each Desktop hook: tray and window-menu facets, window list, focus and events, the clipboard bridge,
notifications and Open with. Never packaged: droidtop ships no sample plugins. Its facets are replies.json, which the
JVM test (DesktopFixtureTest) reads too, and desktop_fixture_check.py drives this module with a fake host."""

import json
import os

import droidtop.host

HERE = os.path.dirname(os.path.abspath(__file__))
with open(os.path.join(HERE, "replies.json"), encoding="utf-8") as f:
    REPLIES = json.load(f)

# What the fixture was last told, for its own tray page: the events it got, newest first.
SEEN = []


def ok(data):
    return json.dumps({"ok": True, "data": data})


def error(code, message):
    return json.dumps({"ok": False, "error": {"code": code, "message": message}})


def said(reply, fallback):
    """The host reply's message, or the fixture's own sentence."""
    return reply.get("error", {}).get("message") or fallback


def handle(call_json):
    call = json.loads(call_json)
    point, op, args = call.get("point"), call.get("op"), call.get("args", {})
    if point == "ui.facets" and op == "get":
        target = args.get("target")
        if target not in REPLIES:
            return error("UNSUPPORTED", "The fixture gives nothing there.")
        return ok(REPLIES[target])
    if point == "ui.facets":
        return action(op, args)
    if point == "files.handler" and op == "open":
        opened = droidtop.host.open("files", "open", {"token": args.get("token"), "mode": "r"})
        if not opened.get("ok"):
            return error("FAILED", said(opened, "The fixture couldn't read the file."))
        droidtop.host.call("files", "forget", {"token": args.get("token")})
        return ok({"message": "The fixture read %s." % args.get("name", "the file")})
    return error("UNSUPPORTED", "The fixture has no such op.")


def action(op, args):
    if op == "focus_first":
        listed = droidtop.host.call("windows", "list")
        windows = listed.get("data", {}).get("windows", []) if listed.get("ok") else []
        if not windows:
            return error("NOT_FOUND", said(listed, "There is no window to focus."))
        focused = droidtop.host.call("windows", "focus", {"id": windows[0]["id"]})
        return ok({"message": "Focused %s." % windows[0]["title"]}) if focused.get("ok") else error("FAILED", said(focused, "Couldn't focus it."))
    if op == "focus_this":
        subject = args.get("context", {}).get("subject", {})
        focused = droidtop.host.call("windows", "focus", {"id": subject.get("windowId", -1)})
        return ok({"message": "Focused."}) if focused.get("ok") else error("FAILED", said(focused, "Couldn't focus it."))
    if op == "read_clipboard":
        read = droidtop.host.call("clipboard", "get_text")
        if not read.get("ok"):
            return error("FAILED", said(read, "Couldn't read the clipboard."))
        text = read.get("data", {}).get("text")
        return ok({"message": "The clipboard holds %d characters." % len(text) if text else "The clipboard is empty."})
    if op == "copy_test":
        copied = droidtop.host.call("clipboard", "set_text", {"text": "droidtop desktop fixture"})
        return ok({"message": "Copied a test line."}) if copied.get("ok") else error("FAILED", said(copied, "Couldn't copy."))
    if op == "notify":
        posted = droidtop.host.call("notify", "post", {"title": "Desktop fixture", "text": "Seen %d events" % len(SEEN)})
        return ok({"message": "Sent."}) if posted.get("ok") else error("FAILED", said(posted, "Couldn't notify."))
    return error("UNSUPPORTED", "The fixture has no such action.")


def on_event(payload_json):
    payload = json.loads(payload_json)
    SEEN.insert(0, (payload.get("event"), payload.get("args", {}).get("appId", "")))
    del SEEN[20:]
    return json.dumps({"ok": True})
