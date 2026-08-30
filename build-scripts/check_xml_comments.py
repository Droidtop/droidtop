#!/usr/bin/env python3
"""Fails when any droidtop-owned XML file carries a comment ManifestMerger2
(or any strict XML parser) will reject: "--" is illegal inside an XML
comment, and em/en dashes in comments are one stray edit away from
becoming one. This exact bug has broken CI repeatedly (AndroidManifest.xml
was the latest, 2026-08-30) -- so it's enforced mechanically now, as an
early CI step, instead of relying on anyone remembering.

Scans every .xml under the repo except vendor/ (not ours), build outputs,
and .git.
"""
import re
import sys
from pathlib import Path

COMMENT_RE = re.compile(r"<!--(.*?)-->", re.S)
BAD = ["--", "—", "–"]  # double dash, em dash, en dash
SKIP_PARTS = {"vendor", "build", ".git", "upstream-unused-reference"}

failures = []
for path in Path(".").rglob("*.xml"):
    if SKIP_PARTS & set(path.parts):
        continue
    try:
        text = path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        continue
    for match in COMMENT_RE.finditer(text):
        body = match.group(1)
        for bad in BAD:
            if bad in body:
                line = text.count("\n", 0, match.start()) + 1
                failures.append(f"{path}:{line}: {bad!r} inside an XML comment")
                break

if failures:
    print("XML comments that will break ManifestMerger2/strict parsers:")
    print("\n".join(failures))
    print(f"\n{len(failures)} bad comment(s). Replace '--' with a single dash or rephrase.")
    sys.exit(1)
print("XML comments clean.")
