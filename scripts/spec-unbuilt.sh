#!/usr/bin/env bash
# Lists every sentence in docs/SPEC.md that says something is not built, not yet done or still open, with the line
# it is on, so a sweep can check each against the code (Droidtop/tracker#718): a sentence the code has made untrue is
# deleted or rewritten, one that is still true is kept. State comes from code, not from notes.
#
#   scripts/spec-unbuilt.sh            every such sentence about a feature, "line: sentence"
#   scripts/spec-unbuilt.sh -a         also the sentences that say what an API answers on purpose ("answers not built",
#                                      "501", "not built in droidtop yet"): those describe behaviour, not a gap
#   scripts/spec-unbuilt.sh -c         only the count (with -a first for all)
#   scripts/spec-unbuilt.sh <regex>    only the sentences that also match <regex> (case-insensitive)
set -euo pipefail
cd "$(dirname "$0")/.."

phrases='not built|not yet built|not yet (done|there|implemented|written|wired|shown|supported|available)|still open|is not implemented|are not implemented|never built|unbuilt|nothing builds|not built yet'

count=false
all=0
filter=''
while [ $# -gt 0 ]; do
  case "$1" in
    -c) count=true ;;
    -a) all=1 ;;
    *) filter="$1" ;;
  esac
  shift
done

# A line of the SPEC is a paragraph; print only the sentences of it that carry a phrase.
sentences() {
  python3 - "$phrases" "$filter" "$all" <<'PY'
import re, sys
phrases = re.compile(sys.argv[1], re.I)
extra = re.compile(sys.argv[2], re.I) if sys.argv[2] else None
every = sys.argv[3] == "1"
# A sentence that says what an API answers on purpose describes behaviour, not a gap.
deliberate = re.compile(r"answer(s|ed)?\b[^.;]{0,100}not built|not built in droidtop|\b501\b|answer(s|ed)? (not built|501)", re.I)
for number, line in enumerate(open("docs/SPEC.md", encoding="utf-8"), 1):
    for sentence in re.split(r"(?<=[.;!?])\s+", line.strip()):
        if phrases.search(sentence) and (every or not deliberate.search(sentence)) and (extra is None or extra.search(sentence)):
            print(f"{number}: {sentence[:300]}")
PY
}

if $count; then sentences | wc -l; else sentences; fi
