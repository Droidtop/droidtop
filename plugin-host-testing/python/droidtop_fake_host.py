#!/usr/bin/env python3
"""A python plugin's host without a device (docs/plugin-api.md 3 I2, Droidtop/tracker#71).

Loads a plugin's plugin.py the way droidtop's bootstrap does (the modules droidtop and droidtop.host are in
sys.modules first), answers droidtop.host.call from memory with grants the case sets, and runs the conformance
cases of conformance.json, the same file the JVM fake host (FakeHost, Conformance) reads.

    droidtop_fake_host.py <plugin dir holding manifest.json and plugin.py> [conformance.json]

Exits 0 when every case passes. A host op answers only when its permission is declared in the manifest and
granted by the case; there is no person here to ask, so anything else is PERMISSION_DENIED, as droidtop's broker
answers a call nobody started.
"""

import importlib.util
import json
import os
import re
import sys
import types

HERE = os.path.dirname(os.path.abspath(__file__))
CASES = os.path.join(HERE, "..", "src", "main", "resources", "dev", "droidtop", "pluginhost", "conformance.json")


class FakeHost:
    def __init__(self, plugin_dir, suite, grants):
        with open(os.path.join(plugin_dir, "manifest.json"), encoding="utf-8") as f:
            self.manifest = json.load(f)
        self.declared = {p.get("id") for p in self.manifest.get("permissions", []) if isinstance(p, dict)}
        self.ops = suite.get("hostOps", {})
        self.grants = grants
        self.calls = []
        host = types.ModuleType("droidtop.host")
        host.call = self.call
        host.open = lambda api, op, args=None, version=1: self.refuse("UNSUPPORTED", "The fake host hands over no files.")
        pkg = types.ModuleType("droidtop")
        pkg.__path__ = []
        pkg.host = host
        sys.modules["droidtop"] = pkg
        sys.modules["droidtop.host"] = host
        name = "fixture_" + re.sub(r"\W", "_", self.manifest.get("id", "plugin"))
        spec = importlib.util.spec_from_file_location(name, os.path.join(plugin_dir, "plugin.py"))
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)
        if hasattr(self.module, "on_load"):
            self.module.on_load("")

    @staticmethod
    def refuse(code, message):
        return {"ok": False, "error": {"code": code, "message": message}}

    def call(self, api, op, args=None, version=1):
        self.calls.append((api, op, args))
        entry = self.ops.get(api + "." + op)
        if entry is None or version != 1:
            return self.refuse("UNSUPPORTED", "The fake host does not serve %s.%s." % (api, op))
        permission = entry.get("permission")
        if permission and permission not in self.declared:
            return self.refuse("PERMISSION_DENIED", "The plugin did not declare %s." % permission)
        if permission and self.grants.get(permission) != "granted":
            return self.refuse("PERMISSION_DENIED", "%s is not allowed." % permission)
        return {"ok": True, "data": entry.get("data", {})}

    def handle(self, call):
        return json.loads(self.module.handle(json.dumps(call)))

    def run_job(self, call, job_id="job-1"):
        progress = []

        def report(percent=-1, status=""):
            progress.append({"percent": int(percent), "status": str(status)})

        try:
            result = json.loads(self.module.start_job(job_id, call, report) or "{}")
        except Exception as error:  # the bootstrap's own rule: a raise is a failed job
            result = {"ok": False, "error": str(error)}
        outcome = {"progress": progress, "ok": bool(result.get("ok"))}
        if outcome["ok"]:
            outcome["values"] = {k: str(v) for k, v in (result.get("values") or {}).items()}
        else:
            outcome["error"] = str(result.get("error", ""))
        return outcome


def envelope(json_call):
    return {
        "contract": 2, "callId": "conformance", "deadlineMs": 15000,
        "point": json_call["point"], "version": json_call.get("version", 1), "op": json_call["op"],
        "caller": {"kind": "host"}, "surface": {}, "args": json_call.get("args", {}), "host": {},
    }


def at(node, path):
    for key in path.split("."):
        if isinstance(node, dict):
            node = node.get(key)
        elif isinstance(node, list) and key.isdigit() and int(key) < len(node):
            node = node[int(key)]
        else:
            return None
    return node


def check(outcome, expect, plain):
    failures = []
    for e in expect:
        path = e["path"]
        value = at(outcome, path)
        if "equals" in e:
            want = e["equals"]
            same = value == want and type(value) is type(want) if isinstance(want, bool) else value == want
            if not same:
                failures.append("%s is %s, wanted %s" % (path, "missing" if value is None else json.dumps(value), json.dumps(want)))
        elif e.get("present"):
            if value is None:
                failures.append(path + " is missing")
        elif e.get("plain"):
            if not isinstance(value, str) or not value.strip():
                failures.append(path + " is not a sentence")
            elif plain.search(value):
                failures.append('%s is not one plain sentence: "%s"' % (path, value))
        else:
            failures.append("the expectation on %s says nothing to check" % path)
    return failures


def run(plugin_dir, suite):
    plain = re.compile(suite["plainMessage"])
    results = []
    for case in suite["cases"]:
        try:
            host = FakeHost(plugin_dir, suite, case.get("grants", {}))
            if "job" in case:
                outcome = host.run_job(envelope(case["job"]))
            else:
                outcome = host.handle(envelope(case["call"]))
            failures = check(outcome, case["expect"], plain)
        except Exception as error:
            failures = ["crashed: %s: %s" % (type(error).__name__, error)]
        results.append((case["name"], failures))
    return results


def main(argv):
    if len(argv) < 2:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    with open(argv[2] if len(argv) > 2 else CASES, encoding="utf-8") as f:
        suite = json.load(f)
    results = run(argv[1], suite)
    for name, failures in results:
        print(("PASS " if not failures else "FAIL ") + name)
        for failure in failures:
            print("     " + failure)
    return 0 if all(not failures for _, failures in results) else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
