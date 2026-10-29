#!/usr/bin/env bash
# Lists every sentence in docs/SPEC.md that says something is not built, not yet done or still open, with the line
# it is on, so a sweep can check each against the code (Droidtop/tracker#718): a sentence the code has made untrue is
# deleted or rewritten, one that is still true is kept. State comes from code, not from notes.
#
#   scripts/spec-unbuilt.sh            every such sentence, "line: sentence"
#   scripts/spec-unbuilt.sh -c         only the count
#   scripts/spec-unbuilt.sh <regex>    only the sentences that also match <regex> (case-insensitive)
set -euo pipefail
cd "$(dirname "$0")/.."

phrases='not built|not yet built|not yet (done|there|implemented|written|wired|shown|supported|available)|still open|is not implemented|are not implemented|never built|unbuilt|nothing builds|not built yet'

count=false
filter=''
case "${1:-}" in
  -c) count=true ;;
  '') ;;
  *) filter="$1" ;;
esac

# A line of the SPEC is a paragraph; print only the sentences of it that carry a phrase.
sentences() {
  python3 - "$phrases" "$filter" <<'PY'
import re, sys
phrases = re.compile(sys.argv[1], re.I)
extra = re.compile(sys.argv[2], re.I) if sys.argv[2] else None
for number, line in enumerate(open("docs/SPEC.md", encoding="utf-8"), 1):
    for sentence in re.split(r"(?<=[.;!?])\s+", line.strip()):
        if phrases.search(sentence) and (extra is None or extra.search(sentence)):
            print(f"{number}: {sentence[:300]}")
PY
}

if $count; then sentences | wc -l; else sentences; fi
