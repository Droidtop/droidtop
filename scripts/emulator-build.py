#!/usr/bin/env python3
"""Which android-build run the Emulator tests workflow tests (Droidtop/tracker#735).

A push tests the android-build of the SAME commit: it waits for that run, and starts one when there is none (ci/ branches
are not built on push). A nightly or a by-hand run on main tests main's latest successful build. A given run id is
tested as given. Another commit's build is never the answer: a batch that tests main's build tests nothing of its own.

Usage: emulator-build.py --repo OWNER/NAME --event EVENT --ref-name BRANCH --sha SHA [--run-id ID]
Prints `id=<run id>` (for $GITHUB_OUTPUT) or exits 1 with the reason. GH_TOKEN is the token to the API with.
"""

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request

WORKFLOW = "android-build.yml"
WAIT_SECONDS = 70 * 60
POLL_SECONDS = 30


def pick(runs, sha):
    """(state, run) for the run of [sha] among [runs]: 'none', 'running', 'failed' or 'success'. A success wins over an earlier failure of the same commit."""
    mine = [r for r in runs if r.get("head_sha") == sha]
    if not mine:
        return "none", None
    for run in mine:
        if run.get("status") == "completed" and run.get("conclusion") == "success":
            return "success", run
    running = [r for r in mine if r.get("status") != "completed"]
    if running:
        return "running", running[0]
    return "failed", mine[0]


def api(repo, path, token, data=None):
    request = urllib.request.Request(
        "https://api.github.com/repos/" + repo + path,
        data=json.dumps(data).encode() if data is not None else None,
        method="POST" if data is not None else "GET",
        headers={"Authorization": "Bearer " + token, "Accept": "application/vnd.github+json", "Content-Type": "application/json"},
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        body = response.read()
        return json.loads(body) if body else {}


def runs_of(repo, token, query):
    return api(repo, "/actions/workflows/" + WORKFLOW + "/runs?per_page=30&" + query, token)["workflow_runs"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--event", required=True)
    parser.add_argument("--ref-name", required=True)
    parser.add_argument("--sha", required=True)
    parser.add_argument("--run-id", default="")
    args = parser.parse_args()
    token = os.environ["GH_TOKEN"]

    if args.run_id:
        print("id=" + args.run_id)
        return 0

    # The default branch's own build: nightly, or by hand on main without a run id.
    if args.event == "schedule" or (args.event == "workflow_dispatch" and args.ref_name == "main"):
        runs = runs_of(args.repo, token, "branch=main&status=success")
        if not runs:
            print("No successful android-build run on main to test.", file=sys.stderr)
            return 1
        print("id=" + str(runs[0]["id"]))
        return 0

    # Anything else tests the build of this very commit.
    started = False
    deadline = time.time() + WAIT_SECONDS
    while True:
        state, run = pick(runs_of(args.repo, token, "head_sha=" + args.sha), args.sha)
        if state == "success":
            print("id=" + str(run["id"]))
            return 0
        if state == "failed":
            print("android-build run " + str(run["id"]) + " of " + args.sha + " did not succeed (" + str(run.get("conclusion")) + "): there is no build of this commit to test.", file=sys.stderr)
            return 1
        if state == "none" and not started:
            api(args.repo, "/actions/workflows/" + WORKFLOW + "/dispatches", token, {"ref": args.ref_name})
            started = True
            print("No android-build run of " + args.sha + "; started one on " + args.ref_name + ".", file=sys.stderr)
        if time.time() > deadline:
            print("Gave up waiting for the android-build run of " + args.sha + ".", file=sys.stderr)
            return 1
        time.sleep(POLL_SECONDS)


if __name__ == "__main__":
    sys.exit(main())
