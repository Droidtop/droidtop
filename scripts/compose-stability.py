#!/usr/bin/env python3
"""Totals the Compose compiler reports a build made with -PcomposeReports (Droidtop/tracker#941).

For each module: how many restartable composables can skip when their inputs are unchanged, and how many classes
Compose counts as unstable. A composable that cannot skip redraws whenever its parent does, which is how one changing
value redraws a whole screen. Prints a Markdown table (to $GITHUB_STEP_SUMMARY as well when it is set) and, with
--list, every composable that cannot skip.

    python3 scripts/compose-stability.py [--variant debug] [--list]
"""
import argparse
import json
import os
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent


def is_variant(path, suffix, variant):
    """The file of the variant's main compilation ("app_debug-module.json"), not of its tests ("app_debugUnitTest-...")."""
    return path.name.lower().endswith("_" + variant + suffix)


def modules(variant, root):
    for metrics in sorted(root.glob("*/build/compose-metrics")):
        module = metrics.parent.parent.name
        found = [p for p in metrics.rglob("*-module.json") if is_variant(p, "-module.json", variant)]
        if not found:
            continue
        data = json.loads(found[0].read_text(encoding="utf-8"))
        reports = metrics.parent / "compose-reports"
        unstable = 0
        stuck = []
        for classes in reports.glob("*-classes.txt"):
            if not is_variant(classes, "-classes.txt", variant):
                continue
            unstable += sum(1 for line in classes.read_text(encoding="utf-8").splitlines() if line.startswith("unstable class "))
        for composables in reports.glob("*-composables.txt"):
            if not is_variant(composables, "-composables.txt", variant):
                continue
            # With strong skipping (Kotlin 2) nearly every composable is "skippable"; what decides whether it really
            # skips is its parameters: an unstable one is compared by identity, so a new object each time (a list
            # rebuilt on every change) re-runs it. Each composable is "fun a.b.Name(" then one parameter per line.
            current = None
            for line in composables.read_text(encoding="utf-8").splitlines():
                name = re.search(r"fun ([\w.]+)\(", line)
                if name and line.startswith("restartable"):
                    current = name.group(1).rsplit(".", 1)[-1]
                elif line.startswith(")"):
                    current = None
                elif current and line.strip().startswith("unstable "):
                    stuck.append(current)
                    current = None
        yield module, data, unstable, stuck


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--variant", default="debug")
    parser.add_argument("--list", action="store_true")
    parser.add_argument("--root", default=str(ROOT), help="the tree holding <module>/build/compose-*, the repository by default")
    args = parser.parse_args()

    rows = list(modules(args.variant.lower(), pathlib.Path(args.root)))
    if not rows:
        # A warning, not a failure: the numbers are a report, and a renamed report file must not block a build.
        print("::warning::No Compose metrics found: build with -PcomposeReports first.")
        return 0
    out = ["### Compose stability (%s)" % args.variant, "",
           "| Module | Restartable | Skippable | Can't skip | Unstable params | Unstable classes |", "| --- | ---: | ---: | ---: | ---: | ---: |"]
    total = [0, 0, 0, 0]
    for module, data, unstable, stuck in rows:
        restartable = int(data.get("restartableComposables", 0))
        skippable = int(data.get("skippableComposables", 0))
        total[0] += restartable
        total[1] += skippable
        total[2] += unstable
        total[3] += len(set(stuck))
        out.append("| %s | %d | %d | %d | %d | %d |" % (module, restartable, skippable, restartable - skippable, len(set(stuck)), unstable))
    out.append("| **all** | %d | %d | %d | %d | %d |" % (total[0], total[1], total[0] - total[1], total[3], total[2]))
    if args.list:
        for module, _, _, stuck in rows:
            if stuck:
                out += ["", "**%s**, composables with an unstable parameter: %s" % (module, ", ".join(sorted(set(stuck))))]
    text = "\n".join(out) + "\n"
    print(text)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as handle:
            handle.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
