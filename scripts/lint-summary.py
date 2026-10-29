#!/usr/bin/env python3
"""Prints what a lint XML report holds as a few lines a CI log can show: findings per issue id and severity, per
source tree, and the files of the ones in code droidtop writes (not the vendored trees app/lint-baseline.xml covers).

    scripts/lint-summary.py app/build/reports/lint-results-debug.xml [--ids ContentDescription,UnsafeIntentLaunch]

The report itself is an artifact nobody can read from a log; this is what a sweep (Droidtop/tracker#734) reads.
"""
import collections
import sys
import xml.etree.ElementTree as ET

VENDORED = ("shell-default/wm_shared", "shell-default/msdllib", "shell-default/shared", "runtime-windows/src/main/java/com/winlator")
FILES_PER_ID = 25


def tree(path):
    path = path.replace("\\", "/")
    for marker in VENDORED:
        if marker in path:
            return "vendored"
    parts = [p for p in path.split("/") if p not in ("..", ".")]
    return "/".join(parts[:1]) or "?"


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    only = None
    if "--ids" in argv:
        only = set(argv[argv.index("--ids") + 1].split(","))
    try:
        root = ET.parse(argv[1]).getroot()
    except (OSError, ET.ParseError) as e:
        print(f"lint-summary: no readable report at {argv[1]}: {e}")
        return 0
    by_id = collections.Counter()
    by_tree = collections.defaultdict(collections.Counter)
    ours = collections.defaultdict(list)
    for issue in root.iter("issue"):
        ident, severity = issue.get("id"), issue.get("severity")
        if only and ident not in only:
            continue
        location = issue.find("location")
        path = location.get("file", "") if location is not None else ""
        line = location.get("line", "") if location is not None else ""
        by_id[(ident, severity)] += 1
        where = tree(path)
        by_tree[ident][where] += 1
        if where != "vendored":
            ours[ident].append(f"{path}:{line}")
    print("lint findings by id and severity:")
    for (ident, severity), n in sorted(by_id.items(), key=lambda kv: -kv[1]):
        print(f"  {n:5d}  {ident} ({severity})  " + ", ".join(f"{t} {c}" for t, c in by_tree[ident].most_common(6)))
    for ident, files in sorted(ours.items()):
        print(f"{ident} in code droidtop writes ({len(files)}), first {min(len(files), FILES_PER_ID)}:")
        for f in files[:FILES_PER_ID]:
            print("   " + f)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
