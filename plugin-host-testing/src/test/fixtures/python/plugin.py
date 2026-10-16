"""The python kind's conformance fixture (conformance.json's "fixture"), written as a plugin author writes one.
Never packaged: droidtop ships no sample plugins; this only runs under droidtop_fake_host.py."""

import json

import droidtop.host


def ok(data):
    return json.dumps({"ok": True, "data": data})


def error(code, message):
    return json.dumps({"ok": False, "error": {"code": code, "message": message}})


def handle(call_json):
    call = json.loads(call_json)
    op = call.get("op")
    if op == "state":
        return ok({"label": "Fixture", "value": "ready"})
    if op == "action":
        action = call.get("args", {}).get("id")
        if action == "notify":
            return ok({"reply": droidtop.host.call("notify", "post", {"title": "Conformance", "text": "Hello"})})
        if action == "fail":
            return error("FAILED", "The fixture could not do that.")
        return error("UNSUPPORTED", "The fixture has no such action.")
    return error("UNSUPPORTED", "The fixture has no such op.")


def start_job(job_id, call, report):
    report(50, "Halfway")
    return json.dumps({"ok": True, "values": {"done": "yes"}})


def cancel_job(job_id):
    return "{}"
