"""droidtop's sample social provider (docs/plugin-api.md 3 C19, docs/SPEC.md "Social"):
a python-kind plugin that provides `social.provider@1` for a FAKE chat service, so the
Social place, the companion's Social tab, the Quick Menu's unread count and the message
notifications can be exercised end to end with no account anywhere.

droidtop draws everything; this file only answers data:

  account                    -> {link, name, presence}
  friends                    -> {friends: [{id, name, state, activity?, unread, lastMessageMs}]}
  conversation {friendId, read} -> {messages: [{key, mine, text, timeMs}]}
  send {friendId, text}      -> {message: {...}}
  presence {presence}        -> {}

Every call also carries args["service"], the provides entry's id ("default" here).

A friend answers a sent message a moment later, from a worker thread, and the plugin
says so with droidtop.host.call("social", "changed", {...}): that is how a provider with
a live connection tells droidtop something happened without being polled. The panel's
"Get a message" button does the same on demand. The message only becomes a notification
because the manifest declares notify.post; without it the unread count still moves. The
panel's "Notify me" button posts one through droidtop itself (notify.post), which asks
Android for its own notification permission the first time, during that press.

It runs contained (docs/plugin-api.md 5.3): everything it does beyond its own process is
one of the broker calls above.

Standard library only (json, threading, time), like every python-kind plugin.
"""
import json
import threading
import time

import droidtop.host

SERVICE = "default"

# How long a fake friend takes to answer, in seconds. The test sets it to 0 and runs replies inline.
REPLY_DELAY = 1.5

_lock = threading.Lock()
_presence = "online"
_friends = {
    "ada": {"id": "ada", "name": "Ada", "state": "in_game", "activity": "Celeste"},
    "bo": {"id": "bo", "name": "Bo", "state": "online"},
    "cy": {"id": "cy", "name": "Cy", "state": "offline"},
}
_messages = {}  # friend id -> list of messages, oldest first
_unread = {}  # friend id -> count
_counter = [0]


def _now_ms():
    return int(time.time() * 1000)


def _message(friend_id, mine, text):
    with _lock:
        _counter[0] += 1
        message = {"key": "m%d" % _counter[0], "mine": mine, "text": text, "timeMs": _now_ms()}
        _messages.setdefault(friend_id, []).append(message)
        if not mine:
            _unread[friend_id] = _unread.get(friend_id, 0) + 1
    return message


def _friend_rows():
    with _lock:
        rows = []
        for friend in _friends.values():
            row = dict(friend)
            row["unread"] = _unread.get(friend["id"], 0)
            history = _messages.get(friend["id"]) or []
            row["lastMessageMs"] = history[-1]["timeMs"] if history else 0
            rows.append(row)
    return rows


def _announce(friend_id, message):
    """Tells droidtop a message arrived; a refusal comes back as a reply, never an exception."""
    return droidtop.host.call("social", "changed", {"service": SERVICE, "friendId": friend_id, "message": message})


def _reply_later(friend_id, text):
    def answer():
        if REPLY_DELAY:
            time.sleep(REPLY_DELAY)
        message = _message(friend_id, False, "Got it: %s" % text)
        _announce(friend_id, message)

    if REPLY_DELAY:
        threading.Thread(target=answer, daemon=True).start()
    else:
        answer()


def _ok(data=None):
    return json.dumps({"ok": True, "data": data or {}})


def _error(code, message):
    return json.dumps({"ok": False, "error": {"code": code, "message": message}})


def _social(op, args):
    global _presence
    if args.get("service", SERVICE) != SERVICE:
        return _error("NOT_FOUND", "no such account")
    if op == "account":
        if _presence == "offline":
            return _ok({"link": "offline", "name": "Sample you", "presence": _presence})
        return _ok({"link": "online", "name": "Sample you", "presence": _presence})
    if op == "friends":
        if _presence == "offline":
            return _ok({"friends": []})
        return _ok({"friends": _friend_rows()})
    friend_id = args.get("friendId")
    if op in ("conversation", "send") and friend_id not in _friends:
        return _error("NOT_FOUND", "no such friend")
    if op == "conversation":
        with _lock:
            if args.get("read"):
                _unread[friend_id] = 0
            history = list(_messages.get(friend_id) or [])
        return _ok({"messages": history})
    if op == "send":
        text = (args.get("text") or "").strip()
        if not text:
            return _error("INVALID_ARGS", "nothing to send")
        if _presence == "offline":
            return _error("FAILED", "You are offline")
        message = _message(friend_id, True, text)
        _reply_later(friend_id, text)
        return _ok({"message": message})
    if op == "presence":
        value = args.get("presence")
        if value not in ("online", "invisible", "offline"):
            return _error("INVALID_ARGS", "unknown standing")
        _presence = value
        return _ok()
    return _error("UNSUPPORTED", "social.provider has no op %s" % op)


def _panel(op, args):
    if op == "panel":
        return _ok(
            {
                "view": 1,
                "sections": [
                    {
                        "id": "main",
                        "items": [
                            {"type": "info", "id": "standing", "title": "Standing", "value": _presence.capitalize()},
                            {
                                "type": "button",
                                "id": "ping",
                                "title": "Get a message",
                                "subtitle": "Bo writes to you, as a real service's friend would",
                                "action": {"kind": "call", "op": "ping"},
                            },
                            {
                                "type": "button",
                                "id": "notify",
                                "title": "Notify me",
                                "subtitle": "droidtop posts a notification for the sample service",
                                "action": {"kind": "call", "op": "notify"},
                            },
                        ],
                    }
                ],
            }
        )
    if op == "ping":
        message = _message("bo", False, "Hello from the sample service")
        reply = _announce("bo", message)
        if reply.get("ok"):
            return _ok({"message": "Bo wrote to you"})
        error = reply.get("error") or {}
        return _ok({"message": "droidtop did not take it: %s" % (error.get("message") or "no reason given")})
    if op == "notify":
        reply = droidtop.host.call("notify", "post", {"title": "Sample chat", "text": "A notification from the sample service"})
        if reply.get("ok") and (reply.get("data") or {}).get("posted"):
            return _ok({"message": "Posted a notification"})
        error = reply.get("error") or {}
        return _ok({"message": "droidtop did not post it: %s" % (error.get("message") or "no reason given")})
    return _error("UNSUPPORTED", "ui.panel has no op %s" % op)


def handle(call_json):
    call = json.loads(call_json)
    point = call.get("point")
    op = call.get("op")
    args = call.get("args") or {}
    if point == "social.provider":
        return _social(op, args)
    if point == "ui.panel":
        return _panel(op, args)
    return _error("UNSUPPORTED", "Unsupported point/op: %s/%s" % (point, op))


def on_load(data_dir):
    pass


def on_unload():
    pass
